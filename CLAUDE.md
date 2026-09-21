# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

Backend de automatización de guías de despacho de Calimport: importa las guías desde **SAP
Business One Service Layer**, las asigna a repartidores y registra la entrega o el rechazo
en terreno (con foto como evidencia).

Spring Boot 4.1.1 · Java 21 · PostgreSQL + Flyway + JPA · JWT.

Ojo con la versión: al ser Spring Boot 4, el código usa **Jackson 3**
(`tools.jackson.databind.JsonNode`, no `com.fasterxml.jackson...`).

## Comandos

```bash
./mvnw clean package          # o mvnw.cmd en Windows
./mvnw test
./mvnw test -Dtest=GuiaServiceTest
./mvnw test -Dtest=GuiaServiceTest#nombreDelMetodo
./mvnw spring-boot:run
```

Si el wrapper falla con `ClassNotFoundException`, es la tilde de "Chandía" en la ruta
partiendo el classpath. El wrapper es `only-script` (no hay `maven-wrapper.jar`), así que se
esquiva lanzando Maven directo desde la distribución que ya bajó:

```powershell
$mh = (Get-ChildItem "$env:USERPROFILE\.m2\wrapper\dists\apache-maven-3.9.16" -Directory)[0].FullName
$cw = (Get-ChildItem "$mh\boot\plexus-classworlds-*.jar").FullName
java "-Dmaven.home=$mh" "-Dclassworlds.conf=$mh\bin\m2.conf" "-Dmaven.multiModuleProjectDirectory=$PWD" `
  -cp "$cw" org.codehaus.plexus.classworlds.launcher.Launcher test
```

**Modo local (MVP, sin SAP ni GCP)**: agregar `spring-boot:run -Dspring-boot.run.profiles=local`
al comando de Maven y abrir http://localhost:8080. Login contra `auth.local.usuarios`
(clave `1234`: repartidores `uno@` y `dos@calimport.local`, jefe de bodega
`jefe@calimport.local`), carga datos de ejemplo (`DatosLocales`) y sirve la pantalla de
`src/main/resources/local-ui/`. `LocalAuthController` reemplaza a `AuthController` con
`auth.modo=local`. `PerfilLocalTest` recorre el flujo completo con ese perfil.

**El perfil local corre sobre Postgres**, el mismo motor que producción, para que lo que se
prueba acá valga allá (antes era H2 en archivo; H2 quedó solo para los tests). La **base** se
crea una vez a mano — Flyway crea tablas, no bases:

```powershell
& "C:\Program Files\PostgreSQL\18\bin\createdb.exe" -U postgres guias_local
```

Las **tablas** las crea Flyway en cada arranque si faltan, con las mismas migraciones de
producción. Para empezar de cero: `dropdb guias_local` y volver a crearla. Por defecto apunta
a `localhost:5432/guias_local` con usuario `postgres`; se puede cambiar con `DB_URL`,
`DB_USER` y `DB_PASSWORD` sin tocar el archivo. Las fotos siguen en disco (`./data/fotos`);
`./data/guias-local.mv.db`, si quedó de antes, ya no se usa.

`DatosLocales` siembra los usuarios y **15 guías** en direcciones reales repartidas por todo
Santiago, solo si la tabla `guia` está vacía. Son quince y no cinco para que la ruta
optimizada tenga algo que resolver: con paradas de un solo sector cualquier orden da lo mismo
y no se nota si el optimizador funciona. Es código y no una migración de Flyway a propósito
(una migración quedaría registrada en la base y chocaría con las reales). Para volver a
sembrarlas sin borrar la base: `TRUNCATE ruta_parada, ruta, guia RESTART IDENTITY;`.

Generar rutas **no necesita Google**: por defecto todo es gratuito (ver "Rutas" en
Arquitectura) y el perfil local ya trae la ubicación de la bodega (Padre Orellana 1236). Sin
`rutas.origen-*` la llamada responde 503 diciendo qué falta.

**Fotos**: `POST /api/guias/{id}/foto` (multipart, campo `archivo`) guarda en
`fotos.directorio`, valida que sea JPG/PNG/WEBP por contenido y devuelve `{urlFoto, hashFoto}`
(hash calculado en el servidor); con eso se llama a `/entrega`. `GET /api/fotos/{nombre}`
exige token. Disco local: no sirve en Cloud Run, ahí habrá que ir a Cloud Storage.

Variables de entorno (`application.properties` las exige; sin ellas la app no arranca):
`DB_URL`, `DB_USER`, `DB_PASSWORD`, `SAP_BASE_URL`, `SAP_COMPANY_DB`, `SAP_USERNAME`,
`SAP_PASSWORD`, `SAP_TRUST_SELF_SIGNED`, `JWT_SECRET`, `JWT_TTL_MINUTES` (default 480),
`GUIAS_SYNC_FILTRO_EXTRA`, `AUTH_JEFES_BODEGA` (emails separados por coma; vacío = nadie es
jefe de bodega con login SAP, ver "Roles").

Rutas (opcionales para arrancar; sin ellas falla solo `POST /api/rutas`): `RUTAS_ORIGEN_LAT`,
`RUTAS_ORIGEN_LNG`, `RUTAS_MINUTOS_POR_PARADA` (10), `RUTAS_PARADAS_POR_TRAMO_MAPS` (10),
`RUTAS_VELOCIDAD_KMH` (25), `RUTAS_OSM_USER_AGENT`, y los selectores `RUTAS_GEOCODIFICADOR`
(`osm` | `google`), `RUTAS_OPTIMIZADOR` (`local` | `google`) y `RUTAS_COMENTARIOS` (`reglas` |
`gemini`). Solo si algún selector usa Google: `GCP_PROJECT_ID`, `GOOGLE_MAPS_API_KEY`,
`GEMINI_MODEL`, `GEMINI_LOCATION` (default `global`); Route Optimization y Vertex AI usan
Application Default Credentials, no API key.

## Arquitectura

```
controller/  →  service/  →  repository/ (Postgres)
                    ↑
                  sap/ (SapClient + SapSessionManager)
```

**El esquema es de Flyway, no de Hibernate.** `spring.jpa.hibernate.ddl-auto=validate`: si
agregas un campo a una entidad y olvidas la migración en
`src/main/resources/db/migration/`, la app **no arranca** y lo dice — es a propósito,
para no reventar más tarde con un "column does not exist".

**El ciclo de vida de una guía** está en `GuiaService` y es donde vive la regla de negocio:

- `GuiaSyncService` trae las guías de SAP y llama a `sincronizarDesdeSap`, que es
  **idempotente**: la primera corrida da de alta, las siguientes refrescan sólo los campos
  que son de SAP (folio, cliente, dirección, comentario), de modo que llegan las
  correcciones hechas en SAP antes del despacho. Si cambia la dirección se borran las
  coordenadas; si cambia el comentario se borra el horario interpretado (no el del bodeguero).
- **Una guía ya resuelta (ENTREGADA o RECHAZADA) no se toca nunca más**: su contenido es la
  evidencia de lo que pasó, y reescribirlo la borraría.
- Los campos que genera la app —repartidor, estado, foto— no se tocan en la sincronización.
- `EstadoGuia` tiene sólo tres valores: `PENDIENTE` → `ENTREGADA` | `RECHAZADA`.

**Rutas (`RutaService`)**: el bodeguero manda las guías del día de un repartidor con la hora
de salida (`POST /api/rutas`). Se geocodifican las que no tienen coordenadas
(`Geocodificador`), se interpreta el `Comments` de SAP en horario (`InterpreteComentarios`)
salvo que el bodeguero haya definido el horario (`OrigenHorario.BODEGA` manda), y se ordenan
las paradas (`OptimizadorRutas`, ventanas **blandas** para que no descarte guías). Todo lo
externo corre antes de asignar: si un servicio falla, no queda nada asignado. Una ruta por
repartidor y día; regenerarla la reemplaza. Waze no admite multiparada, así que cada parada
trae su link y Google Maps va partido en tramos (`LinksNavegacion`); abrir esos links es
gratis, lo que se paga son las APIs.

Cada paso es una interfaz con dos implementaciones, elegidas con `@ConditionalOnProperty`.
**Por defecto van las gratuitas**, porque la empresa no quiere pagar por ahora:

| Paso | Gratis (defecto) | Google (pagado) |
|---|---|---|
| `Geocodificador` | `osm/NominatimClient`: OpenStreetMap, busca "número calle" + comuna y si no, texto libre | `google/GeocodingClient` |
| `InterpreteComentarios` | `InterpreteReglas`: patrones ("de 9 a 13", "hasta las 12", "solo en la mañana"…); no arma nota | `InterpreteGemini` |
| `OptimizadorRutas` | `OptimizadorLocal`: línea recta × 1,35 a `velocidad-promedio-kmh`, mismos costos que Google, vecino más conveniente + búsqueda local | `OptimizadorGoogle` |

Límites de lo gratuito, a tener presentes:
- **Nominatim** exige ≤ 1 consulta por segundo y un User-Agent propio, o bloquea la IP:
  `NominatimClient` las espacia (15 guías nuevas ≈ 15 s; las coordenadas quedan guardadas).
  Con las guías de ejemplo ubica todas, pero ~2 de cada 3 solo a nivel de calle
  (`ubicacionAproximada`), así que el orden puede no ser el óptimo real.
- **`OptimizadorLocal`** no conoce calles ni tráfico: las horas de llegada son estimadas.
- **`InterpreteReglas`** solo lee lo que calza con sus patrones; lo demás lo corrige bodega
  con `PATCH /api/guias/{id}/horario`.

**`SapSessionManager` / `SapClient`** siguen el mismo patrón que el otro backend de
Calimport: el manager mantiene la cookie `B1SESSION`, renueva ante 401 y reintenta una vez;
el cliente sólo formula consultas y no sabe de sesiones. `DOCUMENTACION-SAP.md` es un
recorrido línea por línea de esas dos clases, escrito como material de aprendizaje: vale la
pena leerlo antes de tocarlas.

**`DeliveryNoteController` (`/api/sap`) no es parte de la app**: es una ventana de sólo
lectura al JSON crudo de Service Layer, para inspeccionar cómo se llaman los campos en
esta instalación antes de fijar el mapeo. Tiene un tope de 20 documentos porque una guía
completa es grande y un `$top` alto cuelga la request y la sesión de SAP.

`GUIAS_SYNC_FILTRO_EXTRA` es una condición OData adicional para la sincronización, pensada
para separar las guías que despacha Calimport de las que retira el cliente. Vacío = trae
todas.

## Endpoints principales

`POST /api/guias/sincronizar` (importa desde SAP) · `GET /api/guias` (filtrable por estado
y repartidor) · `POST /api/guias` · `PATCH /api/guias/{id}/repartidor` ·
`PATCH /api/guias/{id}/recepcion` · `POST /api/guias/{id}/entrega` ·
`POST /api/guias/{id}/rechazo` · `PATCH /api/guias/{id}/sincronizada` ·
`PATCH /api/guias/{id}/horario` · `POST /api/rutas` · `GET /api/rutas?repartidorId=&fecha=` ·
`GET /api/rutas/mia?fecha=`.
Cada método declara su `@PreAuthorize` (ver "Roles"); un endpoint nuevo sin anotación
queda abierto a cualquier usuario logueado.

## Roles

Dos roles (`model/Rol`), que viajan en el claim `role` del JWT con estos nombres literales.
El frontend (`../Frontend/CLAUDE.md`) los lee igual: si cambia un nombre o un código de
respuesta, cambiarlo en los dos lados.

- **`JEFE_BODEGA`**: sincroniza, crea y asigna guías, define horarios, genera rutas y ve
  **todas** las guías.
- **`REPARTIDOR`**: ve **sólo sus guías** y sólo sobre ésas actúa.

**De dónde sale.** El claim `role` es la única fuente para autorizar; `UsuarioActual.de()`
lo lee junto al `employeeId` (no volver a sacar `Claims` a mano en los controllers). Un
claim ausente o desconocido se lee como `REPARTIDOR` (`Rol.desdeClaim`), así un token viejo
nunca gana permisos. `JwtAuthenticationFilter` arma la authority `ROLE_<rol>`.
`Repartidor.rol` (migración `V4__rol_usuario.sql`) se refresca en cada login;
`listarActivos()` y `RutaService` sólo aceptan `REPARTIDOR`, porque el jefe está en la
misma tabla pero no reparte.

- `auth.modo=local`: campo `rol` de `auth.local.usuarios[n]`, `REPARTIDOR` si no se declara.
- `auth.modo=sap`: **provisorio**, `AUTH_JEFES_BODEGA` lista los emails que entran como
  jefe; el resto es `REPARTIDOR`. Lo correcto es un campo de `EmployeesInfo` (`JobTitle` o
  un `U_*`), pero **no está confirmado contra el metadata real**: verificarlo antes de
  reemplazar la lista, y no inventar el campo.

| Endpoint | Jefe de bodega | Repartidor |
|---|---|---|
| `POST /api/guias/sincronizar`, `POST /api/guias` | sí | 403 |
| `PATCH /api/guias/{id}/repartidor`, `PATCH .../horario` | sí | 403 |
| `POST /api/rutas`, `GET /api/rutas?repartidorId=` | sí | 403 (usa `/mia`) |
| `GET /api/repartidores`, `GET /api/sap/**` | sí | 403 |
| `GET /api/guias` | todas, con cualquier filtro | sólo las suyas; `?repartidorId=` ajeno → 403 |
| `GET /api/guias/{id}` | sí | sólo si es suya, si no 403 |
| `PATCH .../recepcion`, `POST .../foto`, `.../entrega`, `.../rechazo`, `PATCH .../sincronizada` | 403 | sólo sobre las suyas |
| `GET /api/rutas/mia` | 403 | sí |
| `GET /api/fotos/{nombre}` | sí | sí (el nombre lleva UUID) |

**La pertenencia se valida en `GuiaService`** (`obtenerPropia`, `obtenerPara`,
`listarPara`), no en el controller. Guía ajena o sin asignar → **403**, no 404, y se revisa
antes que el estado: a un tercero no se le dice en qué quedó una guía que no es suya. Para
un repartidor, `estado` filtra dentro de las suyas.

El 403 de un `@PreAuthorize` lo traduce `GlobalExceptionHandler` (`AccessDeniedException`);
sin ese handler caería en el genérico como 500. El 401 sigue siendo sólo "sin token o
vencido".

`PerfilLocalTest` recorre el flujo de los dos roles: bodega asigna, repartidor entrega, y
otro repartidor no ve ni toca esa guía. La pantalla `local-ui` muestra asignación al jefe
y retiro/entrega/rechazo al repartidor.

## Tests

Es el repo con mejor cobertura de los tres: hay tests de controller, service, repository
(sobre H2) y seguridad. Al tocar `GuiaService` o `GuiaSyncService`, correr al menos
`GuiaServiceTest` y `GuiaSyncServiceTest`.
