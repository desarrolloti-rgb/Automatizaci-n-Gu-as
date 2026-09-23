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

Tres de las guías de ejemplo traen **pie del documento**, con las tres formas reales de
escribirlo (etiquetas con dos puntos y saltos, etiquetas sueltas sin dos puntos, y todo de
corrido en un párrafo): sin ellas no hay cómo probar en local "Ver pie de SAP", la marca de
dirección por confirmar ni el editor de dirección. El pie se parte con el mismo
`FooterDespacho` de la sincronización, así lo que se ve en local es lo que va a pasar de
verdad.

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
`GUIAS_SYNC_FILTRO_EXTRA`.

Rutas (opcionales para arrancar; sin ellas falla solo `POST /api/rutas`): `RUTAS_ORIGEN_LAT`,
`RUTAS_ORIGEN_LNG`, `RUTAS_MINUTOS_POR_PARADA` (10), `RUTAS_PARADAS_POR_TRAMO_MAPS` (10),
`RUTAS_VELOCIDAD_KMH` (25), `RUTAS_OSM_USER_AGENT`, y los selectores `RUTAS_GEOCODIFICADOR`
(`osm` | `cascada` | `google`), `RUTAS_OPTIMIZADOR` (`local` | `google`) y
`RUTAS_COMENTARIOS` (`reglas` | `gemini`). Solo si algún selector usa Google (`cascada`
incluido): `GCP_PROJECT_ID`, `GOOGLE_MAPS_API_KEY`,
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
- **La dirección y el horario salen del pie del documento**, no de la ficha del cliente. En
  esta instalación el vendedor escribe a dónde va el camión en `ClosingRemarks` (el
  `T0.[Footer]` de una consulta), con etiquetas: `DESPACHAR A`, `CONTACTO`, `CEL`,
  `HORARIO`. **No hay plantilla** —de cuatro pies reales no hay dos iguales—, así que
  `FooterDespacho` **busca las etiquetas en todo el texto y no por línea**: los dos puntos
  son opcionales ("CONTACTO MATIAS CANIU"), la etiqueta puede venir abreviada ("At.",
  "Cel.:") y hay pies enteros escritos de corrido en un párrafo sin un solo salto. El valor
  de cada etiqueta es lo que va hasta la siguiente; lo anterior a la primera —condiciones de
  pago, casi siempre— queda en `otros` y viaja igual, porque "CHEQUE A 30 DÍAS CON
  CONTRAENTREGA" le importa al repartidor. `ClosingRemarks` es campo estándar, no un UDF:
  pedirlo no corre el riesgo del 400.
- **El pie se guarda entero, y lo que se leyó de él también.** `footer` es el
  `ClosingRemarks` crudo; `direccion_footer` y `horario_footer`, lo que el parser sacó, sin
  corregir. Se refrescan en **cada** sincronización, incluso cuando bodega ya corrigió la
  dirección: el pie es contra lo que bodega compara, así que tiene que decir lo que el
  documento dice hoy. Lo que no se pisa es la decisión que bodega tomó a partir de él.
- **La dirección del pie le gana a `Address2` solo si se puede ubicar**, o sea si tiene un
  número: "FRANCISCO DE CAMARGO 14317, SAN BERNARDO" sí, "BODEGA Fruna" no. Cambiar una
  dirección buena de la ficha por un nombre de lugar que el geocodificador no encuentra es
  peor que no tocar nada; cuando pasa, el texto del pie viaja en el comentario igual. Si el
  pie no trae dirección se usa `Address2` y después `Address`, como siempre.
- **`origen_direccion` dice de cuál desconfiar** (`OrigenDireccion`): `FOOTER` la escribió
  el vendedor para este despacho, `LOGISTICA` sale de la ficha del cliente —y bodega la ve
  marcada para confirmarla contra el pie—, y `BODEGA` es la que alguien miró y corrigió con
  `PATCH /api/guias/{id}/direccion`. Una dirección `BODEGA` **no la pisa ninguna
  sincronización** y conserva sus coordenadas, igual que pasa con el horario. A diferencia
  del horario, no se puede dejar vacía: sin horario una guía se entrega igual, sin dirección
  no se puede ni ubicar ni rutear. Null en las guías anteriores a la migración `V7`.
- **`ClosingRemarks` y `Comments` son dos campos distintos de SAP y no se mezclan.**
  `comentario` es y sigue siendo `Comments`; el pie vive en `footer`. Juntarlos daba un
  texto que no era ninguno de los dos y que después no había forma de volver a separar.
- **El horario se interpreta de `horario_footer`, y solo si no hay, de `comentario`**
  (`RutaService.textoConElHorario`). El pie gana porque es de este despacho y porque llega
  limpio: solo la frase del "HORARIO:", sin teléfonos ni direcciones que puedan leerse como
  una hora. El horario interpretado se descarta cuando cambia cualquiera de los dos textos.
- **El pie entero viaja al repartidor** (`RutaResponse.Parada.footer` y `Guia.footer`): ahí
  están el contacto y el teléfono de quien recibe, que en la calle con el portón cerrado es
  lo único que sirve. Va crudo, no resumido.
- **Una guía que dice "DESPACHAR VÍA SAMEX" no la reparte Calimport** y su dirección puede
  estar en cualquier parte del país (hay una a Calama). `FooterDespacho` ya lo parsea
  (`transportista()`) pero **no se guarda ni se filtra todavía**: hoy solo se ve leyendo el
  pie. `GUIAS_SYNC_FILTRO_EXTRA` es una condición OData y no alcanza a leerlo — la salida
  probable es `TransportationCode` (la forma de envío, tabla `OSHP`), pendiente de
  confirmar qué códigos usa esta instalación.
- **Una guía ya resuelta (ENTREGADA o RECHAZADA) no se toca nunca más**: su contenido es la
  evidencia de lo que pasó, y reescribirlo la borraría.
- Los campos que genera la app —repartidor, estado, foto— no se tocan en la sincronización.
- `EstadoGuia` tiene sólo tres valores: `PENDIENTE` → `ENTREGADA` | `RECHAZADA`.

**SAP es la fuente de verdad del ciclo de última milla; Postgres amortigua la mala señal.**
El estado vive en SAP en cuatro UDF de `DeliveryNotes`, y la app los mantiene al día:

| UDF | Qué lleva |
|---|---|
| `U_EstadoLog` | `P` pendiente · `T` tomada · `E` entregada · `R` rechazada |
| `U_Despachador` | Nombre de quien la lleva. Se limpia al volver a `P` |
| `U_UrlFoto` | La evidencia de la entrega, absoluta (`guias.url-publica` + la ruta) |
| `U_MotivoRech` | Por qué la rechazaron. Se limpia al volver a `P` |

**Los cuatro estados de SAP son tres de la app más un booleano**, no un modelo distinto:
`P` es PENDIENTE sin retirar y `T` es PENDIENTE con `recibidaPorRepartidor`. `EstadoLogistico`
lo traduce al momento de enviar. No se guarda un cuarto valor en `EstadoGuia` a propósito:
sería una segunda representación del mismo hecho, y dos copias de un dato se desincronizan.

**Una guía queda "despachada" cuando la foto llega a SAP**, no cuando el repartidor la
cierra en el celular. `fecha_foto_en_sap` se escribe en `SincronizacionSapService.empujar`,
una sola vez, cuando el PATCH con `U_UrlFoto` resulta; un reintento no cambia cuándo llegó.
Entre `fecha_entrega` y ésta pueden pasar minutos o, si SAP estaba caído, bastante más: es
justamente esa diferencia la que se quería poder ver. **Ojo**: mientras
`guias.sap.udf-estado-logistico` siga en `false`, `empujar` retorna sin llamar a SAP y este
campo **nunca se llena**. Se activa solo el día que existan los UDF.

**El orden importa: primero Postgres, después SAP.** El repartidor trabaja en la calle y el
Service Layer no siempre contesta; si la entrega dependiera de que SAP responda en ese
instante, una caída de red le impediría cerrar una guía que ya entregó. `GuiaService`
guarda, `SincronizacionSapService` empuja, y **si el empuje falla no lanza**: la guía queda
con `sincronizada = false` y un `@Scheduled` reintenta cada dos minutos. El PATCH manda el
estado completo, así que reintentarlo de más es inofensivo.

`sincronizada` **nace en true**: una guía recién importada no tiene nada que contarle a SAP,
que ya la creó como `P`. Lo que está en false es, literalmente, lo que SAP todavía no sabe.

**La sincronización desde SAP solo trae `U_EstadoLog eq 'P'` y `DocumentStatus eq 'bost_Open'`**:
las que ya están en T, E o R salieron de esta app y volver a importarlas arriesga pisar lo
que el repartidor hizo en terreno.

**La reapertura (`PATCH /api/guias/{id}/reapertura`, flujo R → P)** es la única excepción a
que una guía resuelta no se toca, y es del jefe de bodega. No reescribe lo que pasó: decide
volver a intentarlo, limpiando despachador y motivo. Una ENTREGADA no se reabre — ahí el
cliente firmó y hay una foto que lo prueba. **Se pierde el motivo del rechazo anterior**,
porque el flujo de SAP lo deja en null; si hace falta ese historial, es una tabla aparte.

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
| ↳ mezcla | `GeocodificadorEnCascada` (`rutas.geocodificador=cascada`): OSM y, **solo si no encuentra nada**, Google | |
| `InterpreteComentarios` | `InterpreteReglas`: patrones ("de 9 a 13", "hasta las 12", "solo en la mañana"…); no arma nota | `InterpreteGemini` |
| `OptimizadorRutas` | `OptimizadorLocal`: línea recta × 1,35 a `velocidad-promedio-kmh`, mismos costos que Google, vecino más conveniente + búsqueda local | `OptimizadorGoogle` |

Límites de lo gratuito, a tener presentes:
- **Nominatim** exige ≤ 1 consulta por segundo y un User-Agent propio, o bloquea la IP:
  `NominatimClient` las espacia (15 guías nuevas ≈ 15 s; las coordenadas quedan guardadas).
  Con las guías de ejemplo ubica todas, pero ~2 de cada 3 solo a nivel de calle
  (`ubicacionAproximada`), así que el orden puede no ser el óptimo real.
- **Nominatim busca por coincidencia exacta y las direcciones de SAP no vienen exactas.**
  Medido contra cinco que fallaron en producción: dos eran **errores de tipeo**
  ("AV. VICUÑA MACKENA" por Mackenna, "DOMINGO ARTEGA" por Arteaga) — escritas bien las
  ubica exacto, con el typo no las ubica nunca; las otras tres existen pero escritas como
  las escribe un vendedor ("Bodega Planta, SITE CPP (Promedio) KM. 63 LONGITUDINAL SUR")
  y solo aparecen si se les limpia el texto a mano. Limpiar con reglas tapa algunos casos y
  **ningún typo**: para eso está `cascada`. Una dirección que no se ubica **bloquea la ruta
  completa** con un 422 que nombra los folios; bodega la arregla en SAP y vuelve a
  sincronizar, o la corrige a mano con `PATCH /api/guias/{id}/direccion`.
- **`OptimizadorLocal`** no conoce calles ni tráfico: las horas de llegada son estimadas.
- **`InterpreteReglas`** solo lee lo que calza con sus patrones; lo demás lo corrige bodega
  con `PATCH /api/guias/{id}/horario`. Descarta a propósito las horas precedidas por "no
  reciben", "colación", "almuerzo" o "cerrado": "horario colación 13 a 15hrs" es cuando la
  bodega del cliente **está cerrada**, y leerlo como ventana manda al repartidor justo a esa
  hora. Sin ventana la guía se ordena por cercanía y el horario igual se lee en el comentario.

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
`PATCH /api/guias/{id}/horario` · `PATCH /api/guias/{id}/direccion` (bodega corrige la
dirección leyendo el pie) · `PATCH /api/guias/{id}/reapertura` (R → P, bodega) ·
`POST /api/rutas` · `GET /api/rutas?repartidorId=&fecha=` · `GET /api/rutas/mia?fecha=`.
Cada método declara su `@PreAuthorize` (ver "Roles"); un endpoint nuevo sin anotación
queda abierto a cualquier usuario logueado.

## Roles

Dos roles (`model/Rol`), que viajan en el claim `role` del JWT con estos nombres literales.
El frontend (`../Frontend/CLAUDE.md`) los lee igual: si cambia un nombre o un código de
respuesta, cambiarlo en los dos lados.

- **`JEFE_BODEGA`**: sincroniza, crea y asigna guías, define horarios, genera rutas y ve
  **todas** las guías.
- **`Despachador`**: ve **sólo sus guías** y sólo sobre ésas actúa.

**De dónde sale.** El claim `role` es la única fuente para autorizar; `UsuarioActual.de()`
lo lee junto al `employeeId` (no volver a sacar `Claims` a mano en los controllers). Un
claim ausente o desconocido se lee como `Despachador` (`Rol.desdeClaim`), así un token viejo
nunca gana permisos. `JwtAuthenticationFilter` arma la authority `ROLE_<rol>`.
`Repartidor.rol` (migración `V4__rol_usuario.sql`) se refresca en cada login;
`listarActivos()` y `RutaService` sólo aceptan `Despachador`, porque el jefe está en la
misma tabla pero no reparte.

- `auth.modo=local`: campo `rol` de `auth.local.usuarios[n]`, `Despachador` si no se declara.
- `auth.modo=sap`: sale del campo `JobTitle` de `EmployeesInfo`. Si (normalizado a
  minúsculas) **empieza con "jefe"** → `JEFE_BODEGA` — el prefijo tolera el typo real de
  esta instalación ("Jefe bogeda") y variantes como "Jefe de bodega". Cualquier otro cargo,
  vacío o ausente → `Despachador`, el de menos permisos.

| Endpoint | Jefe de bodega | Repartidor |
|---|---|---|
| `POST /api/guias/sincronizar`, `POST /api/guias` | sí | 403 |
| `PATCH /api/guias/{id}/repartidor`, `PATCH .../horario`, `PATCH .../direccion` | sí | 403 |
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

## Despliegue (VM en GCP)

Mismo patrón que Dashboard —un jar y los estáticos en `/home/usuario/app/`, variables en un
`.env`— con dos diferencias: **el servicio lo maneja systemd** en vez de `nohup` (sobrevive
al reinicio de la VM y revive si el proceso se cae) y **esta app sí tiene base de datos**.
Todo lo necesario está en `deploy/`:

La app **no tiene VM propia**: vive en `apps`, la máquina de las aplicaciones internas,
junto al Dashboard Logística. Esa VM la crean e instalan los scripts del repositorio
**Panel-Facturadores** (`deploy/crear-vm.ps1`, `deploy/instalar.sh`), que además instalan el
nginx compartido. Acá solo está lo propio de Guías:

| Archivo | Cuándo |
|---|---|
| `preparar-vm.sh` | **Una vez**, dentro de la VM: Java 21, Postgres, usuario, `/opt/guias`, `.env`, el servicio y el respaldo diario |
| `env.ejemplo` | Plantilla de `/opt/guias/.env`. La copia `preparar-vm.sh` y le genera la clave de Postgres |
| `guias.service` | Lo instala `preparar-vm.sh` en `/etc/systemd/system/` |
| `desplegar.ps1` | Cada despliegue, desde el PC |

**`preparar-vm.sh` no clona el repositorio**, a diferencia del `instalar.sh` del panel:
Guías se publica subiendo el jar y los estáticos por scp, así que el código fuente nunca
vive en la VM. Se sube la carpeta `deploy/` entera y se corre desde ahí, porque el script
lee `guias.service` y `env.ejemplo` de al lado.

```
https://apps.calimport.cl/gd/  →  nginx (quita el prefijo)  →  127.0.0.1:8080
```

**El backend no sabe que está montado en `/gd`**: nginx quita el prefijo y Spring sigue
viendo `/api/...`. Quien lo pone es el navegador, y por eso vive en el frontend —
`baseHref` en `angular.json` y `apiBaseUrl` en `environment.prod.ts`, que tienen que
coincidir entre sí y con el nginx. `urlApi()` deriva de ahí la raíz de las fotos.

Como consecuencia, **`pnpm build` ya no sirve para la prueba local**: produce la versión
para `/gd/`. Para servir desde la raíz en el PC o el celular:

```powershell
pnpm exec ng build --configuration development
```

**El 8080 solo escucha en loopback** (`--server.address=127.0.0.1`): a internet se sale
por nginx, con HTTPS de certbot. Nunca se expone el puerto directo.

Dos dependencias externas que no controlamos y conviene pedir temprano: el registro DNS
`apps` y **agregar la IP de la VM a la lista blanca del firewall de SAP** (puerto 50000),
sin la cual ninguna de las dos aplicaciones puede sincronizar.

```powershell
.\deploy\desplegar.ps1 -Servidor <ip> -Usuario usuario
.\deploy\desplegar.ps1 -Servidor <ip> -Usuario usuario -SoloFrontend   # sin recompilar el jar
```

El script corre los tests, empaquetta, sube y reinicia, y **aborta si algo falla antes de
subir nada**. Comprueba dos cosas que ya mordieron: que no haya una app local corriendo (el
jar queda tomado y el repackage deja un archivo de 0,1 MB que parece válido) y que el jar
pese lo que debe.

**Commitear no despliega**, igual que en Dashboard: un cambio de frontend necesita `pnpm
build` y subida, aunque el commit ya esté hecho.

**Dos cosas del PC de desarrollo que rompen el despliegue** y `desplegar.ps1` ya resuelve
solas, pero conviene conocer porque el síntoma no se parece a la causa:

- La tilde de "Chandía" en el perfil de usuario **rompe el túnel IAP**: gcloud arma el
  `ProxyCommand` concatenando las rutas de su Python y de `gcloud.py` sin escaparlas, y
  falla con `Special character '\xed' couldn't be escaped`. Es la misma tilde que parte el
  classpath del wrapper de Maven. Se esquiva exponiendo el SDK por un enlace de directorio
  en `C:\Users\Public\gcloudsdk` e invocándolo desde ahí.
- Por lo mismo, gcloud **deduce mal el usuario remoto** (usa el de Windows) y OS Login lo
  rechaza con un `Remote side unexpectedly closed network connection` que no explica nada.
  El bueno lo dice `gcloud compute os-login describe-profile`.

## Despliegue automático (pendiente)

Hoy se publica a mano con `desplegar.ps1`, desde un PC. Eso no escala: depende de una
máquina, de un sistema operativo y de que alguien se acuerde de correr los tests. El
destino es el mismo patrón que ya usa Panel-Facturadores, **la VM tira los cambios**:

```
git push a main
   └─> GitHub Actions: tests de backend y frontend, y build del jar y del dist
         └─ si pasa: publica el artefacto y mueve la rama "produccion" a ese commit
              └─> la VM (timer cada pocos minutos)
                    ├─ ¿cambió "produccion"? si no, no hace nada
                    ├─ baja el artefacto, lo instala y reinicia
                    └─ si el servicio no responde: vuelve a la versión anterior
```

**La VM tira en vez de que GitHub entre**: así GitHub no guarda credenciales de Google
Cloud ni acceso SSH, y comprometer el repositorio no da acceso a la VM ni a SAP.

Tres restricciones que condicionan el diseño y hay que respetar:

- **No se compila en la VM.** La e2-small tiene 2 GB compartidos con Postgres, el panel y
  la JVM; un build de Maven más uno de Angular no entran. Por eso el artefacto se construye
  en Actions y la VM solo lo instala — a diferencia del panel, que es Node sin build.
- **La VM no tiene cuenta de servicio**, a propósito: quien entre a ella no hereda permisos
  sobre el proyecto. Así que el artefacto no puede salir de un bucket privado de GCS sin
  antes darle una identidad. La alternativa que no rompe esa decisión es publicarlo como
  release del repositorio privado y bajarlo con la deploy key de solo lectura.
- **Los tests tienen que correr también en la VM** antes de reiniciar, como hace el panel:
  un artefacto que pasó en Actions puede fallar acá por configuración o por la base.

Antes de automatizar nada falta lo básico: los dos repositorios están en la rama `max`, sin
CI y sin protección de ramas. El orden es commitear, llevar a `main`, agregar el workflow de
tests, y recién después el timer de publicación.

**En la VM no se activa el perfil `local`**: se usa el default, que exige todas las
variables del `.env`. Sin `RUTAS_ORIGEN_*` no se pueden generar rutas.

Las **fotos viven en `/var/lib/guias/fotos`**, fuera del directorio de la app para que un
despliegue no las pise. Junto con la base, son la evidencia de lo que pasó en terreno: el
respaldo diario que deja `preparar-vm.sh` (timer `respaldo-guias`, 03:30) guarda 14 días,
pero **queda en la misma VM** —
falta copiarlo a un bucket, porque un respaldo local no sirve si se pierde la máquina.

Para diagnosticar: `systemctl status guias` y `journalctl -u guias -n 50 -f`.

## Tests

Es el repo con mejor cobertura de los tres: hay tests de controller, service, repository
(sobre H2) y seguridad. Al tocar `GuiaService` o `GuiaSyncService`, correr al menos
`GuiaServiceTest` y `GuiaSyncServiceTest`.
