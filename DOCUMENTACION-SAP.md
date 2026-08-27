# Anatomía de la sesión SAP

Recorrido línea por línea de `SapSessionManager` y `SapClient`: cómo el backend de
Guías mantiene viva la conexión con SAP Business One Service Layer.

- `src/main/java/com/calimport/guias/sap/SapSessionManager.java`
- `src/main/java/com/calimport/guias/sap/SapClient.java`

---

## 0. El problema que resuelven

SAP Business One expone una API REST llamada **Service Layer**. Para usarla hay una
mecánica obligatoria:

1. Haces `POST /Login` mandando `CompanyDB`, `UserName`, `Password`.
2. SAP responde con una **cookie de sesión** (`B1SESSION=abc123...`) en el header `Set-Cookie`.
3. En **cada** consulta posterior tienes que mandar esa cookie.
4. La cookie **caduca** (~30 min sin uso). Cuando caduca, SAP responde `401 Unauthorized`.
5. Si caducó, hay que loguearse de nuevo y reintentar la consulta.

Además, Service Layer **limita cuántas sesiones simultáneas** puedes tener abiertas. Si
cada hilo de la app hace su propio login, se agotan.

Las dos clases se reparten el trabajo así:

| Clase | Responsabilidad |
|---|---|
| `SapSessionManager` | La mecánica: login, guardar la cookie, renovarla al caducar, reintentar |
| `SapClient` | Las consultas concretas ("búscame el empleado con este email") |

`SapClient` **no sabe nada** de cookies ni de sesiones caducadas. Solo dice "ejecuta
esto" y el manager se encarga.

---

## 1. `SapSessionManager`

### 1.1 Los campos

```java
@Component
public class SapSessionManager {

    private final SapConfig config;
    private volatile String sessionCookie;
    private RestClient restClient;
```

**`@Component`** — le dice a Spring: "crea un objeto de esta clase al arrancar y
guárdalo". Eso es lo que permite que `SapClient` lo reciba sin hacer
`new SapSessionManager(...)` a mano.

**`config`** — los datos de conexión (URL, usuario, password), que salen de
`application.properties`.

**`sessionCookie`** — aquí se guarda la cookie que devolvió SAP. Es el estado que la
clase mantiene vivo entre llamadas.

**`volatile`** — esta palabra importa. Sin ella, si el hilo A actualiza `sessionCookie`,
el hilo B podría **seguir viendo el valor viejo** (cada núcleo del procesador cachea
variables por su cuenta). `volatile` obliga a que todos los hilos lean siempre el valor
real de memoria. Como la app web atiende varias peticiones en paralelo, esto es
necesario.

**`restClient`** — el cliente HTTP de Spring, el que efectivamente hace las llamadas por
red.

### 1.2 El constructor y `@PostConstruct`

```java
public SapSessionManager(SapConfig config) {
    this.config = config;
}

@PostConstruct
public void init() {
    RestClient.Builder builder = RestClient.builder().baseUrl(config.getBaseUrl());
    ...
    this.restClient = builder.build();
}
```

Spring ve que el constructor pide un `SapConfig` y se lo pasa solo (esto se llama
**inyección por constructor**).

**¿Por qué `init()` está aparte, con `@PostConstruct`, en vez de todo en el
constructor?** Porque `SapConfig` se llena con los valores del `.properties` *después*
de que el objeto se construye. Si leyeras `config.getBaseUrl()` dentro del constructor,
te podría llegar `null`. `@PostConstruct` significa: "ejecuta este método cuando Spring
ya terminó de armar y configurar todo". Ahí sí `getBaseUrl()` tiene el valor real.

El `builder` arma el cliente HTTP con la URL base, así después puedes pedir solo
`/Login` en vez de la URL completa cada vez.

### 1.3 El bloque de SSL (`trustSelfSigned`)

```java
if (config.isTrustSelfSigned()) {
    log.warn("trust-self-signed=true: la verificacion de certificados SSL esta deshabilitada...");
    builder = builder.requestFactory(createTrustAllRequestFactory());
} else {
    builder = builder.requestFactory(new HttpComponentsClientHttpRequestFactory());
}
```

Los servidores SAP internos suelen tener un **certificado HTTPS autofirmado** (no
comprado a una autoridad certificadora). Java, por defecto, se niega a conectarse a esos
servidores.

- Si `trustSelfSigned=true` → usa una configuración que **acepta cualquier certificado**.
- Si es `false` → validación normal.

El `trustAllCerts` de más abajo es literalmente eso:

```java
new X509TrustManager() {
    public void checkClientTrusted(...) {}   // método vacío = "todo bien"
    public void checkServerTrusted(...) {}   // método vacío = "todo bien"
    ...
}
```

Los métodos **están vacíos a propósito**: su trabajo normal es lanzar una excepción si el
certificado es inválido. Al no hacer nada, aceptan todo.

> **Cuidado:** esto desactiva la protección contra ataques de intermediario
> (man-in-the-middle). Por eso el `log.warn` dice "usar solo en desarrollo".

El resto de `createTrustAllRequestFactory()` son ajustes de red: timeout de 30 segundos,
pool de conexiones reutilizables, y cerrar conexiones ociosas.

### 1.4 `login()` — obtener la cookie

```java
Map<String, String> loginBody = Map.of(
    "CompanyDB", config.getCompanyDb(),
    "UserName", config.getUsername(),
    "Password", config.getPassword()
);
```

Arma el JSON que SAP espera:
`{"CompanyDB": "...", "UserName": "...", "Password": "..."}`.

```java
var response = restClient.post()
        .uri("/Login")
        .contentType(MediaType.APPLICATION_JSON)
        .body(loginBody)
        .exchange((req, res) -> {
```

Se lee como una frase: *haz un POST a `/Login`, el contenido es JSON, este es el cuerpo*.

**`.exchange(...)` es la parte clave.** Normalmente usarías `.retrieve()`, que te da solo
el **cuerpo** de la respuesta. Pero aquí la cookie **no viene en el cuerpo, viene en un
header**. `exchange()` te da acceso a la respuesta completa (headers incluidos), y le
pasas una función que dice qué extraer:

```java
if (res.getStatusCode() != HttpStatus.OK
        && res.getStatusCode() != HttpStatus.CREATED
        && res.getStatusCode() != HttpStatus.NO_CONTENT) {
    throw new RuntimeException("SAP login failed: " + res.getStatusCode());
}
String cookie = res.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
if (cookie == null) {
    throw new RuntimeException("No Set-Cookie header in SAP login response");
}
return cookie;
```

1. Si el status no es 200/201/204 → el login falló, revienta.
2. Saca el header `Set-Cookie`.
3. Si no vino cookie → revienta (sin cookie no puedes hacer nada).
4. Guarda la cookie en el campo y la devuelve.

### 1.5 `currentCookieOrLogin()` — "dame una cookie válida"

```java
public String currentCookieOrLogin() {
    if (sessionCookie == null) {           // (1)
        synchronized (this) {              // (2)
            if (sessionCookie == null) {   // (3)
                return login();
            }
        }
    }
    return sessionCookie;
}
```

Traducción: *"si ya tengo cookie, devuélvela; si no, logueate primero"*.

El patrón de tres pasos se llama **double-checked locking** (bloqueo con doble
verificación) y existe por el paralelismo:

- **(1)** Primera revisión, **sin bloqueo**. Es el caso normal (ya hay cookie) y así es
  rapidísimo: no frena a nadie.
- **(2)** `synchronized (this)` = **solo un hilo a la vez puede entrar aquí**. Los demás
  esperan en la puerta.
- **(3)** Segunda revisión, ya **dentro** del bloqueo. ¿Por qué revisar de nuevo?

Imagina dos hilos que llegan a la vez sin cookie. Ambos pasan (1). El hilo A entra en
(2), hace login, guarda la cookie, sale. Ahora entra el hilo B. **Si no existiera (3), B
haría un segundo login innecesario**, gastando una de las sesiones limitadas de SAP. Con
(3), B ve que la cookie ya existe, no hace nada, y sale a devolver la cookie de A.

### 1.6 `executeWithSession()` — el corazón de la clase

```java
public <T> T executeWithSession(SapRequest<T> call) {
    String cookie = currentCookieOrLogin();
    try {
        return call.execute(cookie);
    } catch (HttpClientErrorException.Unauthorized | SapUnauthorizedException e) {
        log.warn("Sesion SAP expirada, renovando...");
        return call.execute(renovarSesion(cookie));
    }
}
```

Esto es lo que hace que `SapClient` no tenga que preocuparse por nada.

**`<T>`** — un *genérico*. Significa "este método devuelve el mismo tipo que devuelva la
operación que me pasaste". Si le pasas algo que devuelve `JsonNode`, obtienes `JsonNode`;
si devuelve `String`, obtienes `String`.

**`SapRequest<T> call`** — el parámetro **no es un dato, es una operación**: "lo que
quieres hacer con la cookie".

La lógica:

1. Consigue una cookie (existente o recién logueada).
2. Intenta ejecutar la operación con esa cookie.
3. **Si sale 401** (cookie caducada) → renueva la sesión y **reintenta una vez**.

Fíjate que captura **dos** excepciones con el `|`:

```java
catch (HttpClientErrorException.Unauthorized | SapUnauthorizedException e)
```

La primera es la que lanza Spring cuando recibe un 401. La segunda es la propia, definida
al final del archivo. El comentario original en Dashboard explicaba que capturar solo la
propia dejaba el reintento inalcanzable — o sea, fue un bug real que corrigieron.

### 1.7 `renovarSesion()`

```java
private synchronized String renovarSesion(String cookieUsada) {
    if (sessionCookie != null && !sessionCookie.equals(cookieUsada)) {
        return sessionCookie;
    }
    return login();
}
```

Mismo problema del paralelismo, otra vez. Si 5 hilos fallan a la vez porque la cookie
caducó, **no queremos 5 logins**.

- `synchronized` en el método = un hilo a la vez.
- `cookieUsada` es la cookie con la que **ese hilo falló**.
- La condición dice: *"¿la cookie guardada ahora es distinta de la que yo usé?"*. Si es
  distinta, significa que **otro hilo ya renovó** mientras yo esperaba → uso la suya y me
  ahorro el login.
- Si es igual (nadie renovó todavía) → me toca a mí hacer el login.

### 1.8 Las dos declaraciones del final

```java
@FunctionalInterface
public interface SapRequest<T> {
    T execute(String sessionCookie);
}
```

Una interfaz con **un solo método** puede escribirse como una **lambda** (una función
suelta). Eso es lo que permite que `SapClient` escriba `cookie -> ...` en vez de crear
una clase entera. `@FunctionalInterface` es una marca que le pide al compilador que
verifique que efectivamente tenga un solo método.

```java
public static class SapUnauthorizedException extends RuntimeException {
    public SapUnauthorizedException(String message) {
        super(message);
    }
}
```

Una excepción propia, para poder señalar "SAP dijo que no estoy autorizado" de forma
distinguible. Es la que capturas en `AuthController` para devolver `502`.

---

## 2. `SapClient`

Esta clase es corta porque toda la complejidad quedó en el manager.

```java
@Component
public class SapClient {

    private final SapSessionManager sessionManager;

    public SapClient(SapSessionManager sessionManager) {
        this.sessionManager = sessionManager;
    }
```

Igual que antes: Spring crea el `SapClient` y le entrega el `SapSessionManager` que ya
había creado.

### 2.1 El filtro OData

```java
String escapedEmail = email == null ? "" : email.replace("'", "''");
String filter = "eMail eq '" + escapedEmail + "' and Active eq 'tYES'";
```

Service Layer usa **OData** para filtrar, que es una sintaxis tipo SQL pero para URLs. El
filtro dice: *"el campo `eMail` es igual a X **y** el campo `Active` es igual a `tYES`"*.

Tres detalles:

**`'tYES'`** — SAP B1 no usa `true`/`false` para booleanos, usa los literales `tYES` y
`tNO`. Así que esto filtra solo empleados activos.

**`.replace("'", "''")`** — esto es **escapar comillas**. En OData, el texto va entre
comillas simples. Si alguien tiene el email `o'brien@x.cl`, el filtro quedaría roto:

```
eMail eq 'o'brien@x.cl'     <- la comilla del medio corta el texto
```

Duplicando la comilla, OData la interpreta como una comilla literal:

```
eMail eq 'o''brien@x.cl'    <- correcto
```

Esto también evita que alguien **inyecte** condiciones maliciosas en el filtro metiendo
comillas en el email del login.

**`email == null ? "" : ...`** — el operador ternario. Se lee: *"¿email es null? entonces
`""`, si no, `email.replace(...)`"*. Evita un `NullPointerException`.

### 2.2 La consulta

```java
return sessionManager.executeWithSession(cookie ->
        sessionManager.getRestClient().get()
                .uri("/EmployeesInfo?$filter={filter}", filter)
                .header("Cookie", cookie)
                .retrieve()
                .body(JsonNode.class));
```

**Todo lo que está después de `cookie ->` es la lambda** — es decir, la operación que le
entregas al manager para que él decida con qué cookie ejecutarla y si hay que reintentar.

Línea por línea:

- `.get()` — método HTTP GET.
- `.uri("/EmployeesInfo?$filter={filter}", filter)` — `{filter}` es un **marcador de
  posición**. Spring reemplaza `{filter}` por el valor de la variable `filter` y **lo
  codifica para URL** (espacios → `%20`, etc.). Nunca concatenes el valor directo en la
  URL: se rompería con espacios.
- `.header("Cookie", cookie)` — aquí es donde se manda la cookie de sesión. **Este es el
  `cookie` que viene de la lambda**, o sea, el que el manager le pasó.
- `.retrieve()` — ejecuta la llamada (aquí sí basta con el cuerpo, no necesitamos headers).
- `.body(JsonNode.class)` — convierte la respuesta JSON en un `JsonNode`, que es un árbol
  JSON genérico de Jackson. Se usa `JsonNode` en vez de una clase propia porque la
  respuesta de SAP tiene muchísimos campos y solo interesan cuatro.

---

## 3. El flujo completo, de punta a punta

Cuando un repartidor hace login en la app:

```
AuthController.login()
   |
   +--> sapClient.queryEmployeeByEmail("juan@calimport.cl")
          |
          +- arma el filtro OData (escapando comillas)
          |
          +--> sessionManager.executeWithSession( lambda )
                 |
                 +- 1. currentCookieOrLogin()
                 |      hay cookie?  si -> la usa
                 |                   no -> POST /Login y la guarda
                 |
                 +- 2. ejecuta la lambda con esa cookie
                 |      GET /EmployeesInfo?$filter=... + header Cookie
                 |
                 +- 3a. respondio OK?  -> devuelve el JsonNode
                 |
                 +- 3b. respondio 401? -> renovarSesion()
                            |             (si otro hilo ya renovo, usa esa)
                            +--> reintenta la lambda una vez
```

Y `AuthController` recibe el `JsonNode`, lee `U_Password`, `EmployeeID`, `eMail`, y sigue
con lo suyo — **sin haber sabido jamás que existía una cookie**.

---

## Glosario rápido

| Concepto | Qué hace |
|---|---|
| `@Component` | Spring crea el objeto al arrancar y lo inyecta donde haga falta |
| `@PostConstruct` | Método que corre cuando Spring ya terminó de configurar el objeto |
| `volatile` | Obliga a que todos los hilos vean el valor actualizado de la variable |
| `synchronized` | Solo un hilo a la vez puede ejecutar ese bloque o método |
| `<T>` (genérico) | El método devuelve el mismo tipo que devuelva lo que le pasaste |
| `@FunctionalInterface` | Interfaz de un solo método; se puede escribir como lambda |
| `cookie -> ...` | Una lambda: una función suelta que se pasa como parámetro |
| OData | Sintaxis de filtros de SAP Service Layer, tipo SQL pero en la URL |
| `tYES` / `tNO` | Los booleanos de SAP Business One |
