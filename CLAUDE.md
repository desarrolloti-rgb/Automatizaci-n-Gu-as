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

**Fotos**: `POST /api/guias/{id}/foto` (multipart, campo `archivo`) valida que sea
JPG/PNG/WEBP por contenido y devuelve `{urlFoto, hashFoto}` (hash calculado en el servidor);
con eso se llama a `/entrega`. Dónde quedan los bytes lo decide `DepositoFotos` con
`fotos.almacen`: `disco` (defecto, local y tests, en `fotos.directorio`) o `gcs` (la VM,
bucket `fotos.bucket`). En la VM van a Cloud Storage porque son la evidencia y el disco se
pierde con la máquina. Una foto no se reemplaza nunca (`ifGenerationMatch=0`).

La misma foto tiene **dos rutas**: `GET /api/fotos/{nombre}` exige token y es la que queda
en la guía y usa el frontend; `GET /api/evidencia/{nombre}` **no pide token** y es la que
va a `U_UrlFoto`, porque quien la abre desde SAP no tiene sesión en la app. La protege el
UUID del nombre (no se puede adivinar ni listar) y solo acepta nombres con el formato que
genera la app. Es corta a propósito: `U_UrlFoto` mide 100 y la URL más larga suma 96.

Variables de entorno (`application.properties` las exige; sin ellas la app no arranca):
`DB_URL`, `DB_USER`, `DB_PASSWORD`, `SAP_BASE_URL`, `SAP_COMPANY_DB`, `SAP_USERNAME`,
`SAP_PASSWORD`, `SAP_TRUST_SELF_SIGNED`, `JWT_SECRET`, `JWT_TTL_MINUTES` (default 720, una
jornada de 12 h: la app instalada del repartidor guarda la sesión todo el turno),
`GUIAS_SYNC_FILTRO_EXTRA`, `GUIAS_SYNC_TRANSPORTISTAS_EXTERNOS` (`SAMEX`). Opcional: `SESION_COMUN_SECRETO` (sesión común con el panel para
el jefe de bodega; vacío, apagada) y `SESION_COMUN_DIAS` (30).

Transportistas externos (opcionales; ver "Guías de un transportista externo"): `GUIAS_TRANSPORTISTAS`
(`SAMEX`), `GUIAS_INTEGRACIONES_AUDIENCIA` y `GUIAS_INTEGRACIONES_CUENTAS` (vacías,
`/api/integraciones` rechaza todo) y `GUIAS_SAMEX_MODO` (`registrar` | `cerrar`).

Correo de despacho (opcional, apagado por defecto): `CORREO_DESPACHO`, `SMTP_PASSWORD` (API
key de Resend, obligatoria con el flag), `CORREO_DESPACHO_COPIA`, `CORREO_DESPACHO_RESPONDER_A`,
`CORREO_DESPACHO_DESVIO`, `CORREO_DESPACHO_MAX_INTENTOS` (12); el resto del SMTP tiene los
defaults de Resend (`SMTP_HOST`, `SMTP_PORT`, `SMTP_USER`, `CORREO_DESPACHO_REMITENTE`). Ver
"Correo de despacho" en Arquitectura.

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
- **Las guías nuevas (desde noviembre de 2026) traen plantilla** en el pie, siempre igual:
  `DESPACHAR A:⏎Dirección: …, Comuna: …, Ciudad: …, Horario de Atención: 09:00 - 19:00,
  CONTACTO: …, FONO: …[, TRANSPORTE: SAMEX]` (DocEntry 46293 y 46296 de CALIMPORT_TEST). No
  tiene parser aparte: son etiquetas más de `FooterDespacho`, así las viejas se leen igual.
  `Comuna:` y `Ciudad:` se juntan con la dirección en "calle número, comuna, ciudad" (sin
  repetir la que ya venía escrita), que es lo que espera `NominatimClient.comuna`;
  `Horario de Atención` va antes que `Atención` a secas, que es la etiqueta del contacto.
  La factura (`Invoices`) copia el pie de la entrega: la fuente sigue siendo `DeliveryNotes`.
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
- **Las guías que retira el cliente no se importan** (`FooterDespacho.esRetiroEnLocal`): se
  reconocen por la raíz "retir" en el pie ("RETIRA EN SUCURSAL", "SERÁ RETIRADO POR…"),
  porque SAP no marca la forma de envío. **Pero si el pie dice "DESPACHAR A" con una
  dirección ubicable, es despacho y no se busca nada más** (regla de bodega, 2026-11-23): la
  calle puede llamarse "Av. El Retiro", y el folio 233 de prueba se saltaba por eso. Sin una
  dirección, "DESPACHAR A" no alcanza y manda el resto del pie. Se cuentan en
  `Resultado.retirosEnLocal`.
- **Las guías de un despachador externo (SAMEX) no se importan**, como los retiros: no las
  reparte Calimport y su dirección puede estar en cualquier parte del país (hay una a
  Calama). `FooterDespacho.transportista()` sale del `TRANSPORTE:` de la plantilla o, en los
  pies a mano, de "DESPACHAR VÍA X A". Se salta solo si nombra a uno de
  `GUIAS_SYNC_TRANSPORTISTAS_EXTERNOS` (por defecto `SAMEX`, sin tildes ni mayúsculas), no
  cualquier transporte: el campo puede decir Calimport, y una guía propia tomada por externa
  desaparecería sin aviso. Se cuentan en `Resultado.porTransportistaExterno` y bodega ve el
  número al sincronizar. **Es provisorio**: la integración ya está en el código (ver "Guías de
  un transportista externo"); al encenderla la variable va vacía, entran todas y bodega marca
  las de Samex. Lo que dice el pie queda solo como pista en `transportista_footer`. Las que ya
  estaban importadas no se borran. Un `TRANSPORTE:` que
  dice "retira…" cuenta como retiro en local. `GUIAS_SYNC_FILTRO_EXTRA` es una condición
  OData y no alcanza a leer el pie.
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
| `U_Dateguia` · `U_Horaguia` | Fecha y hora de la entrega (`fecha_entrega`, hora de Chile, al minuto). Solo en `E`; null en los demás. Detrás de su propio switch (ver abajo) |

**`U_Dateguia` y `U_Horaguia` existen en TEST y no en PRODUCTIVO** (comprobado el
05-11-2026 contra el Service Layer: en PRODUCTIVO responde `Property 'U_Dateguia' of
'Document' is invalid`). Por eso no van con `GUIAS_SAP_UDF_ESTADO` sino con
`GUIAS_SAP_UDF_FECHA_ENTREGA` (default `false`): nombrar un UDF que no existe hace que SAP
rechace **el PATCH entero** con 400, y con ellos adentro dejarían de llegar también el
estado y la foto de todas las guías. Prueba lo tiene en `true`; producción se activa recién
cuando estén creados allá. Son la hora en que el repartidor cerró la guía, no la de
`fecha_foto_en_sap`. La fecha va como `yyyy-MM-dd` y la hora como `HH:mm:00`, el mismo
formato en que Service Layer la devuelve (un UDF de hora no guarda segundos); los dos se
calculan en `America/Santiago` porque la VM corre en UTC.

**Los cuatro estados de SAP son tres de la app más un booleano**, no un modelo distinto:
`P` es PENDIENTE sin retirar y `T` es PENDIENTE con `recibidaPorRepartidor`. `EstadoLogistico`
lo traduce al momento de enviar. No se guarda un cuarto valor en `EstadoGuia` a propósito:
sería una segunda representación del mismo hecho, y dos copias de un dato se desincronizan.

**Una guía queda "despachada" cuando la foto llega a SAP**, no cuando el repartidor la
cierra en el celular. `fecha_foto_en_sap` se escribe en `SincronizacionSapService.empujar`,
una sola vez, cuando el PATCH con `U_UrlFoto` resulta; un reintento no cambia cuándo llegó.
Entre `fecha_entrega` y ésta pueden pasar minutos o, si SAP estaba caído, bastante más: es
justamente esa diferencia la que se quería poder ver. **Ojo**: con
`guias.sap.udf-estado-logistico` en `false` (el default, y lo que corre en local),
`empujar` retorna sin llamar a SAP y este campo **nunca se llena**. La VM lo activa con
`GUIAS_SAP_UDF_ESTADO=true`.

**Los UDF existen en PRODUCTIVO desde el 28-09-2026 y son chicos**: EstadoLog 25,
Despachador 25, MotivoRech 20, UrlFoto 100 (en TEST, 10 y 50; pero el 2026-11-23 la guía
46277 de TEST tenía `U_Despachador = "Despachador 1"`, de 13: ese largo puede haber cambiado).
**Service Layer no avisa
si un valor no cabe**: responde 204 y deja ese campo en null. Comprobado en TEST: la guía
quedó en `E` sin despachador ni foto y la app la dio por sincronizada, así que no hay
reintento que lo arregle. Por eso `EstadoLogistico` recorta despachador y motivo con
elipsis (lo completo queda en Postgres). La URL no se recorta; la más larga suma 96.
Contra TEST, nombre y URL llegan vacíos: para probar el ciclo completo hay que ir a
PRODUCTIVO.

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

**Correo de despacho** (`correo/CorreoDespachoService`, desde el 2026-11-19): al entregar, el
cliente recibe "Despacho entregado · Guía N° …" con folio, fecha y hora (de Chile),
dirección y **la foto de la entrega adjunta**, con copia oculta a `CORREO_DESPACHO_COPIA`.
- **La entrega no envía**: `GuiaService.entregar` solo deja `correo_estado = PENDIENTE`, y un
  `@Scheduled` (cada `correo.despacho.ciclo-ms`, 60 s) lo manda. El repartidor no espera ni a
  SAP ni a Resend, igual que con el empuje a SAP. Comparte el hilo de tareas programadas con
  ese reintento: por eso el SMTP lleva timeouts.
- **A quién**: el `EmailAddress` de la ficha del cliente en SAP (el mismo que usa la app de HES),
  pedido **al enviar** (`DeliveryNotes` → `CardCode` → `BusinessPartners`), no guardado en la
  sincronización: así sirve para las guías importadas antes y una corrección en SAP llega a los
  reintentos. Varias casillas en el campo salen todas (`DireccionesCorreo`).
- **Estados** (`EstadoCorreo`): `PENDIENTE` → `ENVIADO` | `SIN_CORREO` (el cliente no tiene
  correo, o la guía no está en SAP: reintentar no lo arregla) | `FALLIDO` (se agotaron los
  intentos). **Null = no corresponde**: sin entregar o entregada antes del correo. Apagado no
  marca nada, a propósito: encenderlo después no le escribe a nadie por despachos viejos.
  `correo_motivo` dice por qué no salió; es lo que bodega ve en la tabla.
- **Reintentos con tope**: SAP caído, foto ilegible o Resend rechazando dejan `PENDIENTE` y se
  reintenta a 1, 2, 4, 8, 16 y luego cada 30 min, hasta `max-intentos` (12 ≈ 3,5 h). El tope
  es por la cuota de Resend, que comparten el Dashboard, HES y el panel.
- **Enviar no es idempotente**: el resultado se guarda con `GuiaRepository.guardarCorreo` (un
  UPDATE de sus cinco columnas) y `Guia` lleva `@DynamicUpdate`. Sin eso, una request con la
  guía ya leída (bodega reintentando el envío a SAP) devolvía el correo a PENDIENTE y el cliente
  lo recibía dos veces; `GuiaRepositoryTest` lo prueba y falla sin la anotación.
- **Desvío de prueba** (`CORREO_DESPACHO_DESVIO`): todo va a esa casilla, sin copia, con
  "[PRUEBA]" en el asunto y diciendo a quién habría ido; sale aunque SAP no conteste, así se
  prueba en local. **Con una base de SAP con TEST en el nombre, la app no arranca sin desvío**:
  TEST es copia de producción y tiene los correos reales de los clientes, y el `.env` de la
  instancia de prueba es copia del de producción.
- **Se ve como los correos de HES** (`MensajeDespacho`, copia de `marca.js` y `correo.js` de
  `MCP-HES`): marco blanco de 600 px, logo a color (`resources/mail/logo-calimport.png`, el de
  HES) con la etiqueta "Despacho entregado", franja marino y naranja, tabla con encabezado
  marino, firma "Equipo de Despachos" con la línea naranja y pie con el sitio y el lema. El
  cliente recibe de Calimport los dos correos: si la marca cambia allá, cambia acá. **La foto va
  en el cuerpo (cid) y además adjunta**, como el respaldo de entrega de HES. Sin logo, la marca
  va en texto. Remitente "Calimport · Despachos" <`noreply@calimport.cl`>, verificado en Resend.

**Bodega cambia o quita el repartidor de una guía pendiente** con
`PATCH /api/guias/{id}/repartidor` (`"repartidorId": null` la deja sin asignar;
`GuiaService.reasignar`). Si el anterior ya la había retirado, vuelve a "por retirar" y se
avisa a SAP (T → P, limpia `U_Despachador`), porque si no SAP seguiría nombrando al
anterior. En los dos casos **la guía sale de la ruta donde estaba**
(`RutaRepository.quitarGuiaDeRutas`): si quedara, el repartidor anterior la vería en su
recorrido sin poder tocarla. El orden de esa ruta salta un número; no se reoptimiza.

**Guías de un transportista externo (Samex)** (desde el 2026-11-23; el plan está en
`docs/planes/integracion-samex.md` y el portal de Samex, descrito en `../samex/docs/portal.md`):

- **También se puede marcar desde SAP, al crear la guía** (desde el 2026-11-23): bodega deja
  `U_Despachador = SAMEX` en el documento, y la sincronización (que ahora pide ese campo, solo con
  `GUIAS_SAP_UDF_ESTADO=true`) la deja marcada y recibida, y avisa a SAP `T`. Solo si ningún
  repartidor ni transportista la tomó antes: lo que bodega decidió en la app no lo pisa lo que se
  escribió en SAP al crearla. Un `U_Despachador` que no está en `GUIAS_TRANSPORTISTAS` (el nombre
  de una persona, por ejemplo) no marca nada. Para que llegue, `GUIAS_SYNC_TRANSPORTISTAS_EXTERNOS`
  tiene que ir vacía: si no, el filtro del pie la salta antes.
- **Lo decide bodega, no el pie.** `PATCH /api/guias/{id}/transportista {transportista}`
  (`GuiaService.definirTransportista`) es la otra opción del selector del repartidor. Marcarla:
  - le quita el repartidor y la saca de su ruta;
  - la deja **recibida en ese momento**, porque bodega se la entregó a Samex: en SAP es `T`
    con `U_Despachador = SAMEX`.

  Desmarcarla, o asignarle un repartidor (`reasignar`), la vuelve a "por retirar" (`P`). La
  lista de los que se pueden elegir sale de `GUIAS_TRANSPORTISTAS`. Una guía con transportista
  no entra en `POST /api/rutas`. El pie solo deja una pista, `transportista_footer`, que se
  refresca en cada sincronización.
- **El estado lo trae un lector del portal** (`../samex`, un Cloud Run Job: Samex no tiene API).
  Parte de las guías, no del portal:
  - `GET /api/integraciones/samex/pendientes` le da las marcadas sin cerrar, primero las nunca
    buscadas.
  - Busca cada una por su folio, o por el número de envío si ya está confirmado.
  - Devuelve lo que encontró en `POST .../envios`.

  `EnviosExternosService` guarda una fila de `envio_externo` por guía y decide:
  - **`ASOCIADO`**: un solo envío, con el folio exacto entre sus referencias y que no es de
    otra guía.
  - **`NO_ENCONTRADO`**: ningún envío.
  - **`CONFLICTO`**: cualquier otra cosa, sin tocar la guía.

  Prefiere dejar una guía sin asociar antes que cerrarla con el PDF de otro cliente.
- **El PDF.** Si el envío está entregado y falta la evidencia, el lector sube el albarán con
  `PUT .../envios/{numero}/evidencia` (`application/pdf`). `AlmacenamientoFotos.guardarPdf` lo
  valida por contenido (`%PDF-`, hasta 15 MB) y lo guarda en el mismo depósito y con el mismo
  link sin token que las fotos.
- **El cierre** depende de `GUIAS_SAMEX_MODO`:
  - **`cerrar`**: la guía queda ENTREGADA (`GuiaService.entregarPorTransportista`) con **la hora
    de Samex** y sin correo al cliente (`correo_estado` null). En SAP va como `E`, con SAMEX y el
    link al PDF.
  - **`registrar`** (el valor por defecto): todo queda anotado sin tocar la guía; es la etapa
    sombra. Al pasar a `cerrar`, la corrida siguiente cierra las que ya tenían PDF.
  - Un modo mal escrito no arranca.
- **Bodega ve y corrige** (`EnvioExternoController`):
  - `GET /api/envios-externos` trae la última búsqueda, cuántas guías quedan por buscar y el
    seguimiento de cada una. Lleva `revisar` si hay conflicto, o si la guía no se encuentra tras
    2 días hábiles.
  - `PATCH /api/guias/{id}/envio-externo {numero}` fija el envío a mano cuando el folio quedó
    mal escrito en Samex. El lector lo busca por ese número y deja de exigir el folio en las
    referencias (`fijado_a_mano`, columna con nombre explícito como `vuelve_a_bodega`).
- **Tiene su propia puerta** (`IntegracionesSecurityConfig`). `/api/integraciones/**` es una
  cadena aparte que solo acepta el ID token de Google de la cuenta del lector:
  - firmado con las llaves de Google;
  - con `aud = GUIAS_INTEGRACIONES_AUDIENCIA`, que es distinta en `/gd-prueba/`;
  - con el correo verificado y dentro de `GUIAS_INTEGRACIONES_CUENTAS`
    (`ValidadorTokenIntegracion`).

  Da el rol `INTEGRACION`, que no abre nada más. El JWT de la app ahí da 401, y el token del
  lector en el resto de la API también. Con las dos variables vacías rechaza todo. El lector
  tiene que pedir el token con el correo incluido (`format=full` en el servidor de metadatos).
  Usa `spring-security-oauth2-resource-server` y `-jose` sin la autoconfiguración de Spring Boot.
- **nginx corta en 16 KB** todo lo que no sea `/gd/api/guias`, así que `/gd/api/integraciones`
  necesita su `location` con `client_max_body_size 16m`. Ya está en
  `deploy/nginx-apps-guias.conf`, pero en la VM manda el `nginx-apps.conf` del panel y
  `/gd-prueba/` tiene su propio snippet: hay que agregarla en los dos.

**La reapertura (`PATCH /api/guias/{id}/reapertura`, flujo R → P)** es la única excepción a
que una guía resuelta no se toca, y es del jefe de bodega. En la pantalla es "Volver a
despachar", junto al estado de una rechazada; también la saca de su ruta. No reescribe lo que pasó: decide
volver a intentarlo, limpiando despachador y motivo. Una ENTREGADA no se reabre — ahí el
cliente firmó y hay una foto que lo prueba. **Se pierde el motivo del rechazo anterior**,
porque el flujo de SAP lo deja en null; si hace falta ese historial, es una tabla aparte.

**Rutas (`RutaService`)**: el bodeguero manda las guías del día de un repartidor con la hora
de salida (`POST /api/rutas`). Se geocodifican las que no tienen coordenadas
(`Geocodificador`), se interpreta el `Comments` de SAP en horario (`InterpreteComentarios`)
salvo que el bodeguero haya definido el horario (`OrigenHorario.BODEGA` manda), y se ordenan
las paradas (`OptimizadorRutas`, ventanas **blandas** para que no descarte guías). Todo lo
externo corre antes de asignar: si un servicio falla, no queda nada asignado. Una ruta por
repartidor y día. Waze no admite multiparada, así que cada parada
trae su link y Google Maps va partido en tramos (`LinksNavegacion`); abrir esos links es
gratis, lo que se paga son las APIs.

**La ruta es lo que se mide (rutas, no choferes)**, así que guarda su plan (V10):
`km_planificados` y `termino_estimado` (lo que devuelve `OptimizadorRutas.Plan`) y
`vuelve_a_bodega`. Null en las rutas generadas antes del cambio.
- **Regenerar replanifica la misma fila** (`Ruta.planificar`), no la borra y crea otra: lo
  que se mida de ella (el kilometraje real, que viene después) no se puede perder. Primero
  `borrarParadasPrevias` (consulta, por el orden INSERT/DELETE del flush; limpia el contexto,
  así que la ruta se busca después) y luego se vuelven a agregar las paradas.
- **Vuelta a bodega**: uno de los repartidores vuelve al terminar y el otro no. Con vuelta, el
  regreso entra al costo (local: se suma en `evaluar`; Google: `endLocation` = bodega) y el
  recorrido termina cerca de la bodega; el término es la llegada a bodega. Sin vuelta, termina
  al salir del último cliente. Se guarda en la ruta para no comparar km de una ruta con vuelta
  contra una sin. Con vuelta, **el último tramo de Google Maps termina en la bodega**
  (`aRespuesta`), para que el mapa muestre la misma ruta y los mismos km que el plan; la bodega
  va solo en el link, no como parada. Con un múltiplo de `paradas-por-tramo-maps` paradas, el
  regreso queda como un tramo propio.
- **Lo habitual de cada repartidor** es `repartidor.vuelve_a_bodega`, que precarga el
  formulario. `GenerarRutaRequest.vuelveABodega` es opcional: null usa lo habitual; si viene,
  se usa y queda como el nuevo habitual (`RepartidorService.recordarVuelta`). No viene de SAP:
  el login no lo toca. Las dos columnas llevan `@Column(name = "vuelve_a_bodega")` explícito,
  porque la convención de Spring no separa "AB" y buscaría `vuelveabodega`.
- `RutaResponse` trae `kmPlanificados` (un decimal), `minutosPlanificados` (salida → término)
  y `terminoEstimado`. Con Google salen de `routes[0].metrics.travelDistanceMeters` y
  `vehicleEndTime`; si no vienen, el plan queda sin ellos y la ruta sirve igual.
- **El kilometraje real es el odómetro del auto** (V11), y lo anota el repartidor en la app,
  no en papel: `PATCH /api/rutas/mia/odometro` con `{salida?, termino?}` en km enteros, sobre
  su ruta **de hoy** (zona de `rutas.zona-horaria`; sin ruta para hoy, 404). La lectura que no
  viene no se toca y corregir es volver a mandarla. Cada una guarda **cuándo se anotó**
  (`odometro_salida_en`, `odometro_termino_en`): es el respaldo de que se tomó en el momento,
  y la respuesta la trae como `odometroSalidaHora`/`odometroTerminoHora`. El par se valida
  como quedaría (término ≥ salida, y no más de `MAX_KM_POR_DIA` = 500 km entre los dos: más
  es un dígito de más). `planificar` no toca el odómetro: regenerar la ruta con el repartidor
  en la calle conserva lo anotado. Ojo al comparar: el optimizador gratuito estima los km en
  línea recta × 1,35, no por calle.
- **El historial de rutas no se reescribe** (V15, desde el 2026-11-27; plan en
  `docs/planes/historial-de-rutas.md`). Una guía puede estar en varias rutas: la de ayer, donde
  no se entregó, y la de hoy. `borrarParadasPrevias` y `quitarGuiaDeRutas` solo borran paradas
  **sin resolver** y de **hoy en adelante** (hoy en `rutas.zona-horaria`); antes borraban de
  todas, y una guía que volvía a salir desaparecía del recorrido del día anterior. Cada parada
  guarda su `resultado` (ENTREGADA o RECHAZADA) y `resuelta_en`: los escribe
  `RutaRepository.resolverParada` al entregar o rechazar, en la parada más reciente sin resolver,
  así una guía rechazada ayer y entregada hoy dice lo suyo en cada ruta. Una rechazada que se
  reabre sigue en su ruta, como rechazada.
- **El estado de una ruta se calcula, no se guarda** (`EstadoRuta.de`): `PLANIFICADA` (sin
  odómetro de salida ni paradas resueltas), `EN_CURSO`, `COMPLETADA` (todas resueltas y, si
  vuelve a bodega, el odómetro de término anotado) y `CON_PENDIENTES` (día pasado sin completar).
  Va en `RutaResponse.estado` y en el historial (`GET /api/rutas/historial`, solo jefe, máximo
  93 días), que cuenta las paradas por resultado con una consulta agrupada y trae los km
  recorridos (término − salida del odómetro).

Cada paso es una interfaz con dos implementaciones, elegidas con `@ConditionalOnProperty`.
**Por defecto van las gratuitas**, porque la empresa no quiere pagar por ahora:

| Paso | Gratis (defecto) | Google (pagado) |
|---|---|---|
| `Geocodificador` | `osm/NominatimClient`: OpenStreetMap, limpia la dirección, busca "número calle" + comuna y si no, texto libre; `aproximar` cae a la comuna | `google/GeocodingClient` |
| ↳ mezcla | `GeocodificadorEnCascada` (`rutas.geocodificador=cascada`): OSM y, **solo si no encuentra nada**, Google | |
| `InterpreteComentarios` | `InterpreteReglas`: patrones ("de 9 a 13", "hasta las 12", "solo en la mañana"…); no arma nota | `InterpreteGemini` |
| `OptimizadorRutas` | `OptimizadorLocal`: línea recta × 1,35 a `velocidad-promedio-kmh`, mismos costos que Google, vecino más conveniente + búsqueda local; con vuelta, suma el regreso | `OptimizadorGoogle` |

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
  y solo aparecen si se les limpia el texto. `NominatimClient.limpiar` quita agendamiento,
  horario, paréntesis, `#`/`N°`, lo que sigue al número ("LOTEO…", "BODEGA…") y "Chile";
  eso tapa algunos casos y **ningún typo**. Si ni así aparece, `Geocodificador.aproximar`
  la ubica **en su comuna** (el primer trozo tras la calle sin números que no empiece como
  camino) y la guía entra a la ruta como `ubicacionAproximada`: un typo o un "KM 63" rural
  ya no bloquea la ruta. Va aparte de `geocodificar` para que `cascada` pruebe Google antes
  de conformarse con la comuna. Solo si tampoco hay comuna, **bloquea la ruta completa** con
  un 422 que nombra los folios; bodega la corrige en la columna Dirección
  (`PATCH /api/guias/{id}/direccion`) o en SAP y vuelve a sincronizar. Una guía sin ubicar
  hace hasta 3 consultas (≈ 3,3 s).
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
`POST /api/rutas` · `GET /api/rutas?repartidorId=&fecha=` · `GET /api/rutas/historial?desde=&hasta=&repartidorId=` · `GET /api/rutas/mia?fecha=` ·
`PATCH /api/rutas/mia/odometro` (el repartidor anota el kilometraje en su ruta de hoy) ·
`PATCH /api/guias/{id}/transportista` (bodega marca "Despacho externo: SAMEX") ·
`PATCH /api/guias/{id}/envio-externo` · `GET /api/envios-externos` · `GET /api/transportistas` ·
`GET /api/integraciones/samex/pendientes`, `POST .../envios` y
`PUT .../envios/{numero}/evidencia` (solo el lector de Samex).
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
- `auth.modo=sap`: sale del campo `JobTitle` de `EmployeesInfo` (`AuthController.rolDeCargo`).
  Normalizado (minúsculas, sin tildes), si **empieza con "jefe" y contiene "bodega" o
  "bogeda"** → `JEFE_BODEGA`: tolera el typo real de esta instalación ("Jefe bogeda") y
  variantes como "Jefe de bodega", pero no "Jefe de Ventas". Los cargos de bodega
  ("Bodeguero", "Despachador"…) → `Despachador`. Cualquier otro, vacío o ausente, no entra.
  **El Panel de Facturadores reconoce al jefe con la misma regla** (`PERMISOS_POR_CARGO` en
  su `backend/src/auth.js`): si cambia acá, cambia allá.

| Endpoint | Jefe de bodega | Repartidor |
|---|---|---|
| `POST /api/guias/sincronizar`, `POST /api/guias` | sí | 403 |
| `PATCH /api/guias/{id}/repartidor`, `PATCH .../horario`, `PATCH .../direccion` | sí | 403 |
| `PATCH .../transportista`, `PATCH .../envio-externo`, `GET /api/envios-externos`, `GET /api/transportistas` | sí | 403 |
| `POST /api/rutas`, `GET /api/rutas?repartidorId=`, `GET /api/rutas/historial` | sí | 403 (usa `/mia`) |
| `GET /api/repartidores`, `GET /api/sap/**` | sí | 403 |
| `GET /api/guias` | todas, con cualquier filtro | sólo las suyas; `?repartidorId=` ajeno → 403 |
| `GET /api/guias/{id}` | sí | sólo si es suya, si no 403 |
| `PATCH .../recepcion`, `POST .../foto`, `.../entrega`, `.../rechazo`, `PATCH .../sincronizada` | 403 | sólo sobre las suyas |
| `GET /api/rutas/mia`, `PATCH /api/rutas/mia/odometro` | 403 | sí |
| `GET /api/fotos/{nombre}` | sí | sí (el nombre lleva UUID) |
| `GET /api/evidencia/{nombre}` | sin token | sin token (es el link de `U_UrlFoto`) |
| `POST /api/auth/renovar`, `POST /api/auth/salir` | sin token: los autoriza la cookie de la sesión común | 401 / 204 (no tiene sesión común) |
| `/api/integraciones/**` (solo el ID token de Google del lector de Samex, rol `INTEGRACION`) | 401 | 401 |

### Sesión común con el Panel de Facturadores (solo jefe de bodega)

Desde el 2026-11-09 el jefe de bodega usa Guías **y** el Panel de Facturadores
(`C:\Users\Maximiliano Chandía\Panel-Facturadores`, en `/logistica-comercial/` de la misma
VM) con un solo ingreso, y su sesión dura **30 días sin uso**. El resto de los cargos sigue
entrando a una sola app con la sesión de siempre.

- **Qué es** (`security/SesionComun`): una cookie `calimport_sesion` HttpOnly, `Path=/`,
  `SameSite=Lax`, `Secure`, firmada con HMAC-SHA256 con `SESION_COMUN_SECRETO`, **el mismo
  valor en los dos `.env`**. Lleva correo, EmployeeID, vencimiento y un *sello* (HMAC de la
  `U_Password` guardada en SAP). No lleva permisos. El panel la emite y la lee con
  `backend/src/sesionComun.js`: **el formato es un contrato**, y `SesionComunTest` y el
  `sesionComun.test.js` del panel comparten un vector fijo que falla si un lado cambia.
- **Quién la emite**: el login del panel (por donde entra el jefe desde el ingreso común) y
  `POST /api/auth/login` de acá cuando el rol es `JEFE_BODEGA` (formulario propio: app
  instalada, desarrollo). **Todo otro login correcto la borra**, en las dos apps: si el jefe
  no salió en ese navegador, quien entra después no puede terminar, al vencer su sesión,
  canjeando la cookie del jefe.
- **Canje** (`SesionComunController`, `POST /api/auth/renovar`): sin token. Verifica la firma
  y **vuelve a preguntarle a SAP**: activo (la consulta filtra `Active`), mismo EmployeeID,
  mismo sello y cargo de jefe de bodega. Si todo calza, devuelve el JWT de 12 h de siempre,
  refresca la copia local como el login y **vuelve a emitir la cookie** (los 30 días cuentan
  desde el último uso). Si algo cambió, 401 y la cookie se borra: así se corta una sesión
  "permanente": desactivar al empleado, cambiarle el cargo o cambiarle la clave en SAP. SAP
  caído → 502 y la cookie se conserva.
- **`POST /api/auth/salir`** borra la cookie (204). "Salir" en el frontend lo llama junto al
  `/api/logout` del panel.
- **Sin secreto, apagada**: `renovar` responde 401 y ni `renovar` ni `salir` tocan la cookie.
  Así quedan el perfil local y **la instancia de prueba, cuyo `.env` NO debe llevar
  `SESION_COMUN_SECRETO`**: comparte dominio con producción y recibe su cookie; con el secreto
  de producción, el login de un jefe en prueba emitiría una cookie con el sello de la clave de
  TEST y lo sacaría de producción.
- Lo que queda al alcance de JS sigue siendo solo el JWT de 12 h: un XSS no se lleva la sesión
  de 30 días.
- **Firma inválida** → `warn` en el log de las dos apps y la cookie se borra. Si se repite, los
  secretos no son iguales. **Rotar el secreto** cierra la sesión común de todos: cambiarlo en
  los dos `.env` y reiniciar las dos apps juntas (con uno solo cambiado, cada una borra la
  cookie de la otra).
- **Orden de despliegue**: cualquiera. Sin el secreto nada cambia; y si el canje falla (Guías
  sin desplegar o con otro secreto), el ingreso común prueba la misma clave en el login de
  Guías, así que el jefe nunca queda encerrado en el panel.

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
| `crear-bucket.ps1` | **Una vez**, desde el PC: buckets de fotos y respaldos, y la cuenta de servicio de la VM (`-AsignarAVm` la asigna, deteniendo la VM) |

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

### Instancia de prueba (`/gd-prueba/`, SAP CALIMPORT_TEST)

Para probar con guías generadas en la base de pruebas de SAP **sin cambiar la de
producción**, que no se puede apuntar a TEST ni por un rato: su retry de cada 2 minutos
mandaría los estados de las guías de producción a documentos de TEST con el mismo
`DocEntry`, y al volver, al revés, a documentos reales; además el login pasaría a buscar a
los empleados de TEST. La de prueba es una segunda copia en la misma VM, montada el
2026-11-04 para una semana:

| | Producción | Prueba |
|---|---|---|
| URL | `https://apps.calimport.cl/gd/` | `https://apps.calimport.cl/gd-prueba/` |
| Servicio · puerto | `guias` · 8080 (`-Xmx512m`) | `guias-prueba` · 8082 (`-Xmx256m`) |
| Carpeta · `.env` | `/opt/guias` | `/opt/guias-prueba` |
| Base Postgres | `guias` | `guias_prueba` (mismo rol `guias`) |
| SAP | `CALIMPORT_PRODUCTIVO` | `CALIMPORT_TEST`, mismo usuario de Service Layer |
| Fotos | bucket (`gcs`) | disco, `/var/lib/guias-prueba/fotos` |
| nginx | `guias-proxy.conf` | `guias-prueba-proxy.conf` y `guias-prueba-locations.conf`, con un `include` en el sitio `apps` |

El `.env` de prueba es copia del de producción y solo cambia la base, `SAP_COMPANY_DB`, el
`JWT_SECRET` (propio, para que un token no valga en la otra), las fotos, `GUIAS_URL_PUBLICA`
y `STATIC_DIR`, y suma `GUIAS_SAP_UDF_FECHA_ENTREGA=true` (esos UDF solo existen en TEST).
**No lleva `SESION_COMUN_SECRETO`**: si se vuelve a copiar desde producción, borrarlo (ver
"Sesión común con el Panel de Facturadores"). Y si producción tiene `CORREO_DESPACHO=true`,
prueba necesita `CORREO_DESPACHO_DESVIO` o `CORREO_DESPACHO=false`: sin eso no arranca
(ver "Correo de despacho").
Conserva `GUIAS_SAP_UDF_ESTADO=true` y el filtro `Series eq 11`: las guías
que se generen en TEST tienen que ser de esa serie para que la sincronización las traiga.
En TEST, `U_Despachador` y `U_UrlFoto` miden menos y llegan vacíos (ver `EstadoLogistico`).
Entran los empleados de **TEST** con cargo de bodega y `U_Password` cargada.

El frontend es la configuración `prueba` (ver el CLAUDE.md del frontend). Se despliega con
el mismo script: `-Prueba` cambia carpeta, servicio, puerto y prefijo.

```powershell
.\deploy\desplegar.ps1 -Prueba -SoloFrontend   # solo el frontend
.\deploy\desplegar.ps1 -Prueba                 # también el jar del árbol de trabajo
```

**Quitarla** (dentro de la VM; el `include` se borra antes que los snippets o `nginx -t`
falla):

```bash
sudo systemctl disable --now guias-prueba
sudo rm /etc/systemd/system/guias-prueba.service && sudo systemctl daemon-reload
sudo sed -i '\#guias-prueba-locations.conf#d' /etc/nginx/sites-available/apps
sudo rm /etc/nginx/snippets/guias-prueba-*.conf
sudo nginx -t && sudo systemctl reload nginx
sudo -u postgres dropdb guias_prueba
sudo rm -rf /opt/guias-prueba /var/lib/guias-prueba
```

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
- **La cuenta de servicio de la VM (`guias-vm`) solo alcanza a los buckets de fotos y
  respaldos**, a propósito: quien entre a ella no hereda permisos sobre el proyecto. Así
  que el artefacto no puede salir de otro bucket privado sin ampliarle los permisos. La
  alternativa que no rompe esa decisión es publicarlo como release del repositorio privado
  y bajarlo con la deploy key de solo lectura.
- **Los tests tienen que correr también en la VM** antes de reiniciar, como hace el panel:
  un artefacto que pasó en Actions puede fallar acá por configuración o por la base.

Antes de automatizar nada falta lo básico: los dos repositorios están en la rama `max`, sin
CI y sin protección de ramas. El orden es commitear, llevar a `main`, agregar el workflow de
tests, y recién después el timer de publicación.

**En la VM no se activa el perfil `local`**: se usa el default, que exige todas las
variables del `.env`. Sin `RUTAS_ORIGEN_*` no se pueden generar rutas.

Las **fotos viven en el bucket `<proyecto>-guias-fotos`** (`FOTOS_ALMACEN=gcs`), no en la
VM: son la evidencia de lo que pasó en terreno y sobreviven a perder la máquina. La cuenta
`guias-vm` puede crearlas y leerlas, no borrarlas; el bucket es privado, con acceso
público bloqueado y borrado recuperable por 30 días. `/var/lib/guias/fotos` quedó solo con
las anteriores al cambio, que se copian al bucket una vez.

El respaldo diario de la base que deja `preparar-vm.sh` (timer `respaldo-guias`, 03:30)
guarda 14 días en la VM y, con `RESPALDO_BUCKET` en el `.env`, **sube el dump a
`<proyecto>-guias-respaldos`**, donde vive 90 días. La VM puede escribir y leer ahí
(`gcloud storage cp` lee el destino antes de subir; sin lectura falla con 403), pero no
borrar: quien entre a ella no puede eliminar los respaldos anteriores.

Para diagnosticar: `systemctl status guias` y `journalctl -u guias -n 50 -f`.

## Tests

Es el repo con mejor cobertura de los tres: hay tests de controller, service, repository
(sobre H2) y seguridad. Al tocar `GuiaService` o `GuiaSyncService`, correr al menos
`GuiaServiceTest` y `GuiaSyncServiceTest`.

**`ArquitecturaTest` (ArchUnit) comprueba los límites entre paquetes** en cada `test`:

- Un controller no depende de `repository`, `google`, `osm` ni `correo`.
- Tampoco de `sap`, salvo para atrapar `SapUnauthorizedException`.
- Nadie depende de `controller`, salvo de `controller.dto`.
- `repository` y `model` no conocen las capas de arriba.
- `sap`, `google` y `osm` no tocan ni la web ni la base.

Tres controllers hablan con SAP a sabiendas y están nombrados en el test:

- `DeliveryNoteController`: es la ventana de diagnóstico.
- `AuthController` y `SesionComunController`: consultan `EmployeesInfo`. Es deuda, no un
  modelo a copiar.

Un controller nuevo que haga lo mismo hace fallar el build. Si de verdad corresponde, se
agrega a esa lista con su porqué; no se afloja la regla.

## Cómo trabajar en este repo

- **Antes de editar, un plan breve y esperar el visto bueno** cuando el cambio toca un
  contrato que lee otro lado (DTOs y endpoints que usa el frontend, roles, `@PreAuthorize`),
  una migración de Flyway, lo que se empuja a SAP, la seguridad o la integración de Samex.
  Lo demás se hace directo.
- **Test primero** en lo que tiene reglas: transiciones de `EstadoGuia`, permisos (guía
  ajena → 403), parsers (`FooterDespacho`, `InterpreteReglas`, `NominatimClient.limpiar`) y lo
  que se manda a SAP. Normal, borde y error.
- **Alcance mínimo**: no reordenar ni "arreglar" de pasada código que no se pidió, en especial
  las excepciones conocidas de `ArquitecturaTest`. Los comentarios que explican una decisión
  operativa o de seguridad no se borran: son la razón de que el código sea así.
- **Inyección por constructor, con campos `final`.** `@Autowired` solo para una dependencia
  opcional o condicional, como en `GeocodificadorEnCascada`.
- **Errores esperados con `ApiException`** y el `HttpStatus` que corresponde;
  `GlobalExceptionHandler` arma el `ProblemDetail`. Un `ProblemDetail` a mano solo si la
  respuesta lleva algo más, como la cookie de `SesionComunController`.
- **Entradas externas en una consulta** (OData de SAP, Nominatim, Google) se validan y se
  escapan, igual que no van secretos ni rutas sensibles al código ni al log.
- **Terminar con la suite completa en verde** (el comando de "Comandos", con el desvío de la
  tilde si hace falta), no solo el test del cambio.

Al revisar o auditar un cambio, reportar:

1. Capas cruzadas o acoplamiento indebido.
2. Riesgos de concurrencia o transacción: un `save` de la entidad entera donde había un
   UPDATE parcial, o el orden Postgres → SAP.
3. Nulos, seguridad y manejo de errores.
4. Las pruebas que faltan para cubrir el cambio.
