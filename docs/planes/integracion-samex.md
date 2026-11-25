# Plan: integración con Samex (guías que despacha un transportista externo)

Estado: en curso (aprobado el 2026-11-23; búsqueda por folio aprobada el mismo día)
Fecha: 2026-11-23 · Responsable: TI Calimport

## Objetivo y criterio de éxito

Hoy las guías que salen por Samex (pie con `TRANSPORTE: SAMEX` o "DESPACHAR VÍA SAMEX A") se
**saltan al sincronizar** (`GUIAS_SYNC_TRANSPORTISTAS_EXTERNOS`). Por eso nadie las sigue en la
app y en SAP quedan en `U_EstadoLog = P` para siempre.

El jefe de bodega crea cada envío a mano en el portal de Samex y escribe el folio de la guía entre
sus referencias. Samex no tiene API ni exportación: el estado de cada envío, la fecha y hora de
entrega y un PDF con la prueba de entrega solo están en su portal web.

**Objetivo:**
- En la app, bodega marca las guías que salen por Samex ("Despacho externo: SAMEX"), en el mismo
  selector con que asigna un repartidor. SAP lo ve en `U_Despachador = SAMEX`.
- Bodega ve en qué va cada una según Samex.
- Se cierran solas cuando Samex las entrega, con su PDF como evidencia y la hora real de entrega,
  igual que una entrega de un repartidor de Calimport.

**Criterio de éxito:**
- Al confirmar la marca, la guía queda en SAP con `U_EstadoLog = T` y `U_Despachador = SAMEX`.
- Una guía marcada que Samex da por entregada queda ENTREGADA en Guías y en SAP (`U_EstadoLog = E`,
  `U_Despachador = SAMEX`, `U_UrlFoto` abre el PDF), en menos de 2 horas hábiles y sin que nadie
  haga nada.
- Ninguna guía se cierra con el PDF de otra: 0 asociaciones erróneas en la revisión de la etapa
  sombra (T-23).
- Lo que no se puede resolver solo aparece en bodega para resolverlo a mano. Nada queda solamente
  en un log.
- El servicio cuesta menos de US$5 al mes y **no usa IA ni OCR**.

## Alcance

- Incluye:
  - Dejar de saltar en la sincronización las guías cuyo pie dice SAMEX: entran como cualquier
    otra.
  - Que bodega marque en la app "Despacho externo: SAMEX", una a una o en lote, y que SAP lo vea en
    `U_Despachador`. Las guías Samex quedan fuera de las rutas.
  - Buscar en el portal de Samex cada guía marcada por su folio: su envío, su estado y, si está
    entregada, la hora de entrega y el PDF.
  - Confirmar que el folio está entre las referencias del envío y que el PDF es de ese envío;
    cerrar la guía y avisar a SAP.
  - Bodega: ver las guías Samex juntas con su estado en Samex y su PDF, y las que Samex no
    encuentra o tienen un problema.
  - Cloud Run Job + Scheduler, alerta, runbook y pruebas por etapas.
- No incluye (por ahora):
  - Correo de despacho al cliente para Samex: la guía queda con `correo_estado` null (T-29).
  - Actuar sobre incidencias o devoluciones de Samex: se ven en el estado, pero no cambian la
    guía (T-27).
  - Crear envíos en Samex desde la app: los sigue creando el jefe en el portal.
  - **IA u OCR**: no hacen falta, porque el folio y la fecha salen como texto (ver D-06).
  - Otros transportistas. El diseño los admite (`transportista` es texto y la lista es
    configurable), pero cada uno necesita su propio lector.

## Contexto técnico

**Lo que ya existe** (leído del código):

- **Backend Guías**: Spring Boot 4.1 · Java 21 · Postgres + Flyway, en la VM `apps` (e2-small,
  2 GB que comparten Postgres, el panel y la JVM). Tests: `./mvnw test` (ver `CLAUDE.md` por la
  tilde de la ruta).
- **El salto de hoy**: `FooterDespacho.transportista()` lee `TRANSPORTE:` o "DESPACHAR VÍA X A".
  Si el transportista está en `GUIAS_SYNC_TRANSPORTISTAS_EXTERNOS`, `GuiaSyncService` salta la
  guía y la cuenta en `porTransportistaExterno`. Vaciar esa variable hace que entren todas (está
  documentado así). Las guías importadas antes del 2026-11-20 siguen en la base como guías
  comunes.
- **La sincronización la dispara bodega a mano** (`POST /api/guias/sincronizar`) y trae solo
  `U_EstadoLog eq 'P'` y `bost_Open`. Una guía en T ya no se vuelve a traer.
- **Asignar**: `PATCH /api/guias/{id}/repartidor` (`GuiaService.reasignar`). En el frontend el
  selector anota un borrador y "Confirmar asignación" manda los cambios; también hay asignación
  en lote.
- **UDF de SAP**: `U_Despachador` es "quién la lleva" y se limpia al volver a P. Mide 25 en
  PRODUCTIVO y 10 en TEST, así que "SAMEX" cabe en los dos. `U_UrlFoto` mide 100 en PRODUCTIVO y
  50 en TEST, donde el link llega vacío.
- **Evidencia**: `DepositoFotos` (`gcs` en la VM, bucket `<proyecto>-guias-fotos`).
  `AlmacenamientoFotos.NOMBRE_VALIDO` acepta solo `guia-{id}-{uuid}.(jpg|png|webp)`.
  `/api/evidencia/{nombre}` no pide token y es el link que va a `U_UrlFoto`.
- **Empuje a SAP**: `SincronizacionSapService`. Una guía con `sincronizada = false` se reintenta
  cada 2 min (`@Scheduled`). `EstadoLogistico` traduce el estado de la app a P/T/E/R.
- **Frontend**: Angular 22 (`../Frontend`), `pnpm test`.
- **GCP**: proyecto `charming-hearth-503417-i0`, región `us-east1`. Ahí ya viven
  `credenciales-portal` y `siemens-stock-sync` con este mismo patrón: Cloud Run Job, Scheduler,
  alerta y Secret Manager. `desarrollo.ti@` es Editor; los pasos marcados **[Owner]** (IAM y
  secretos) los hace `redes@` o `soporte.ti@`.

**El portal de Samex** (comprobado en T-01; todo el detalle en `Guias/samex/docs/portal.md`):

- **Tecnología**: `https://mysamex.samex.cl/gts/`, Alertran sobre JBoss Seam + RichFaces 3.3.3.
  Usa `ViewState` y Ajax de A4J, así que se navega con Playwright. El login es usuario y clave,
  sin captcha ni segundo factor.
- **Credenciales**: el usuario y la clave están en `Guias/backend/.env` (`URL_SAMEX`,
  `USUARIO_SAMEX`, `PASS_SAMEX`).
- **El folio va en "OTRAS REFERENCIAS"** del detalle del envío, a veces junto a otro número
  (¿la OC del cliente?). La columna REFERENCIA de las grillas muestra otra cosa ("CLIENTE FINAL",
  "TRASLADO SUCURSAL").
- **HOME > Búsqueda, por "Referencia", encuentra el envío por el folio**, y también busca por
  "Número" de envío.
- **Estado**: tooltip de la grilla, por ejemplo "Entregada movilidad 23/11/2026".
- **Hora de entrega**: en la línea "ENVÍO ENTREGADO A:" del tracking del detalle, seguida del
  nombre y el RUT de quien recibió, que no se guardan.
- **El PDF** ("albarán digitalizado", columna Img) baja como descarga.
  - Su página 1 la genera Samex con texto: N Expedición y "Fecha de Entrega dd/mm/aaaa
    hh:mm:ss".
  - Las siguientes son fotos de los papeles firmados, que pueden incluir guías de otros envíos.
- **Volumen**: 24 envíos en 7 días, en las 7 cuentas de Calimport.

**Componentes nuevos:**
- Repo `Guias/samex` (Python + Playwright). Ya tiene el spike de T-01 y `docs/portal.md`.
- Cloud Run Job `samex-sync`.
- En el backend:
  - `guia.transportista` y `PATCH /api/guias/{id}/transportista`.
  - Tabla `envio_externo`.
  - Endpoints `/api/integraciones/samex/**`.

```
Bodega (app) ── "Despacho externo: SAMEX" ──► backend ──► SAP: U_EstadoLog=T, U_Despachador=SAMEX
Jefe de bodega ── crea el envío en el portal de Samex con el folio en sus referencias

Cloud Scheduler (cada hora, lunes a viernes)
  └─► Cloud Run Job samex-sync (Python + Playwright, sin IA)
        ├─ Secret Manager: usuario y clave de Samex
        ├─ GET  /gd/api/integraciones/samex/pendientes ─────────────► backend Guías (VM apps)
        │      ◄── folios de las guías marcadas SAMEX sin cerrar
        ├─ por cada folio, en el portal:
        │     Búsqueda > Referencia = folio → envío y estado
        │     detalle → ¿el folio está en sus referencias? hora de entrega
        │     si está entregada: baja el PDF y lee su página 1 (¿es ese envío?)
        ├─ POST /gd/api/integraciones/samex/envios (resultados) ──────► envio_externo (Postgres)
        │      ◄── qué PDF necesita                                     estado de Samex a la vista
        └─ PUT  /gd/api/integraciones/samex/envios/{n}/evidencia ─────► PDF al bucket de fotos
                                                                         guía ENTREGADA (modo cerrar)
                                                                         empuje a SAP: E, SAMEX, link
```

## Mapa de integración

| Flujo | Origen → destino | Disparador | Volumen y frecuencia | Latencia aceptable | Fuente de verdad |
|---|---|---|---|---|---|
| Guías | SAP `DeliveryNotes` → Postgres | "Sincronizar" de bodega (manual) | las del día, incluidas las de Samex | la del sync de hoy | SAP |
| Marca Samex | App → SAP (`U_EstadoLog = T`, `U_Despachador = SAMEX`) | bodega confirma la marca | una por guía Samex | minutos (reintento cada 2 min) | la app (lo decide bodega) |
| Folios por buscar | Backend → job | cada corrida | las guías marcadas sin cerrar (decenas) | — | Postgres |
| Estado en Samex | Portal → job → `envio_externo` | Scheduler, cada hora de 8 a 20, lunes a viernes | una búsqueda por guía pendiente y corrida (~10 s cada una) | ≤ 2 h hábiles | Samex |
| Evidencia | Portal (PDF) → job → bucket de fotos | lo pide el backend | 1 PDF por guía entregada | misma corrida | el PDF de Samex, con su hash en Postgres |
| Entrega a SAP | Postgres → UDF de `DeliveryNotes` | cierre de la guía + reintento cada 2 min | 1 PATCH por guía | minutos | SAP, una vez hecho el PATCH |

**Mapeo de identificadores** (un envío es una guía y una guía es un envío):

| Samex | App | SAP |
|---|---|---|
| ENVÍO (p. ej. `999905761651`) | `envio_externo.numero`, único por transportista | opcional: `TrackingNumber` (T-28) |
| Folio en "OTRAS REFERENCIAS" del detalle | `guia.folio` | `FolioNumber` |
| — | `guia.doc_entry` | `DocEntry` |
| Samex como transportista | `guia.transportista = 'SAMEX'` (lo elige bodega) | `U_Despachador = SAMEX` |

**Una vez confirmado el envío de una guía** (folio dentro de sus referencias), las corridas
siguientes lo buscan por **Número**, que es exacto y no depende de cómo se escribió la referencia.

**Fallos parciales**

- **La marca se guardó pero SAP no respondió**: `sincronizada = false` y el reintento de siempre
  la empuja.
- **El job no pudo hablar con el backend**: no se marca nada. La próxima corrida vuelve a pedir
  las pendientes y a buscarlas; el backend es idempotente por número de envío.
- **El estado quedó registrado pero el PDF no llegó**: queda "evidencia pendiente" y el backend
  lo vuelve a pedir en la corrida siguiente.
- **La guía se cerró pero SAP no respondió**: `sincronizada = false`, reintento.
- **Lo que no se resuelve solo** no toca la guía y queda a la vista de bodega con su motivo:
  - **Samex no encuentra el folio.** Es normal recién marcada; pasados 2 días hábiles se muestra.
  - **La búsqueda trae varios envíos.**
  - **El folio no está en las referencias** del envío que devolvió la búsqueda (coincidencia
    parcial).
  - **La página 1 del PDF nombra otra expedición**: el job no lo sube.
- **Portal caído (5xx) o lento**: la corrida termina sin fallar el job y lo dice en el resumen.
  Bodega ve "Última lectura de Samex: hace X h".
- **Errores que no se arreglan solos** (login rechazado, una página con otras columnas): fallan el
  job y disparan la alerta.

**Carga inicial y conciliación**

- **Al vaciar `GUIAS_SYNC_TRANSPORTISTAS_EXTERNOS`** entran las guías del pie SAMEX que sigan en P,
  incluidas las viejas que Samex ya entregó. Entran como guías comunes, sin repartidor.
- **Bodega las marca en lote.** Para eso la tabla muestra la píldora "Pie: SAMEX" y un filtro por
  ella.
- **No hay ventana de días**: cada corrida busca todas las marcadas sin cerrar, sean de cuando
  sean. Una carga grande se reparte en varias corridas con el tope `MAX_FOLIOS_POR_CORRIDA`
  (T-25).
- **Conciliación continua**: las que Samex no encuentra o tienen conflicto quedan a la vista, y
  también las que llevan más de N días en tránsito.

## Decisiones

### D-01 Dónde corre el lector del portal
- **Opciones**:
  1. Cloud Run Job en Python.
  2. Dentro del backend Java en la VM (Playwright para Java).
  3. Un servicio de Cloud Run.
- **Elegida**: Cloud Run Job en Python.
- **Por qué**: Chromium necesita cientos de MB y la e2-small ya comparte 2 GB entre Postgres, el
  panel y la JVM. Un job corre y termina, y el patrón ya está probado y operado
  (`credenciales-portal`, `siemens-stock-sync`), con alerta y runbook.

### D-02 Dónde vive el estado y cómo se evitan duplicados
- **Opciones**:
  1. Postgres de Guías (tabla `envio_externo`, única por transportista y número).
  2. Firestore, como proponía el documento.
- **Elegida**: Postgres.
- **Por qué**: una sola fuente. El backend dice qué folios buscar y qué PDF necesita, así que el
  job no guarda nada y no baja un PDF dos veces.

### D-03 Cómo llega el dato al backend
- **Opciones**:
  1. HTTP directo del job al backend.
  2. Pub/Sub.
- **Elegida**: HTTP directo.
- **Por qué**: con decenas de guías por semana, una cola no aporta. Pub/Sub igual necesitaría un
  endpoint push autenticado, o roles de suscriptor para `guias-vm`, que no tiene roles a propósito.
  Además cada corrida vuelve a buscar las pendientes, así que ya funciona como reintento.

### D-04 Dónde queda la evidencia
- **Opciones**:
  1. El PDF original en el bucket de fotos de Guías, con el link permanente de `/api/evidencia/`
     en `U_UrlFoto`.
  2. Una URL firmada.
  3. Convertir el PDF a JPEG.
- **Elegida**: el PDF original en el bucket de fotos.
- **Por qué**: una URL firmada vence en 7 días como máximo, y el link en SAP dejaría de abrir.
  Convertir a JPEG pierde páginas, y la evidencia es el original, con su SHA-256.

### D-05 Cómo se autentica el job ante el backend
- **Opciones**:
  1. ID token de Google (OIDC) de la cuenta de servicio del job, que el backend verifica (firma,
     `aud` y correo de la cuenta).
  2. Una API key compartida.
- **Elegida**: el ID token de Google.
- **Por qué**: no hay secreto que guardar, copiar al `.env` ni rotar, y el token dura una hora. Va
  en una cadena de seguridad aparte para `/api/integraciones/**`, con un rol propio `INTEGRACION`
  que no abre ningún otro endpoint. Prueba y producción usan audiencias distintas, así un token no
  vale en la otra, igual que el `JWT_SECRET`.

### D-06 Cómo se encuentra el envío de una guía (cambiada tras T-01)
- **Opciones**:
  1. **Partir de la guía**: buscar su folio en el portal y confirmarlo.
  2. Leer la lista de entregadas de los últimos días y adivinar qué referencia es el folio.
  3. Leer el folio de las fotos del PDF con IA u OCR.
- **Elegida**: partir de la guía.
- **Por qué**:
  - El folio lo escribe el jefe como texto en las referencias del envío, y la Búsqueda del
    portal lo encuentra.
  - El cruce se confirma dos veces: el folio tiene que estar tal cual entre las referencias del
    detalle, y la página 1 del PDF (texto que genera Samex) tiene que nombrar el mismo envío.
  - La lista de entregadas no muestra esa referencia y tiene una ventana de 7 días.
  - Las fotos pueden traer guías de otros envíos, así que leerlas sería ambiguo, además de
    costar.
- **El precio**: una búsqueda por guía pendiente en cada corrida, y que un envío de una guía no
  marcada en la app no se ve; la píldora "Pie: SAMEX" ayuda a no olvidar marcarla.
- **Ya confirmado**: después de confirmar el envío, se busca por Número.

### D-07 Encendido por etapas
- **Cómo**: cuatro interruptores.
  - En el backend, `GUIAS_SYNC_TRANSPORTISTAS_EXTERNOS`, que ya existe: vacío deja de saltar las
    guías del pie SAMEX.
  - En el backend, `GUIAS_TRANSPORTISTAS`, nuevo (`SAMEX`): los transportistas que bodega puede
    elegir.
  - En el backend, `GUIAS_SAMEX_MODO`: `registrar` guarda el envío, el estado y la evidencia sin
    tocar la guía; `cerrar` la cierra y avisa a SAP.
  - En el job, `DRY_RUN`: busca en el portal pero no le manda nada al backend. Si se le da
    `FOLIOS=…`, busca esos y ni siquiera le pide la lista al backend, así se prueba contra el
    portal real antes de desplegar nada.
- **Por qué**: una entrega cerrada no se reabre (es evidencia). Antes de cerrar guías reales, el
  cruce se mide con datos reales sin consecuencias.

### D-08 Dónde vive el código
- **Elegida**: un repo nuevo, `Guias/samex` (Python), junto a `backend` y `Frontend`, más los
  cambios en esos dos.
- **Por qué**: el lector es descartable si Samex algún día da una API. El contrato con el backend
  (los tres endpoints) sobrevive a ese cambio.

### D-09 Región
- **Elegida**: `us-east1`, con los otros jobs.
- **Excepción**: si el portal bloquea esas IP (T-02), `southamerica-west1`.

### D-10 Quién decide que una guía va por Samex
- **Opciones**:
  1. Bodega en la app, y SAP lo ve en `U_Despachador`.
  2. El pie de SAP (`TRANSPORTE: SAMEX` del vendedor).
- **Elegida**: bodega en la app (decisión del usuario).
- **Por qué**: el jefe de bodega es quien crea el envío en Samex, así que es quien sabe. El pie
  queda como pista ("Pie: SAMEX"), no decide nada.
- **Cómo funciona**:
  - Es el **mismo selector** que el del repartidor: elegir SAMEX quita al repartidor y saca la guía
    de su ruta; elegir un repartidor quita SAMEX. Eso cubre el "cambio de planes".
  - **Marcar es entregársela a Samex**: la guía queda **recibida en ese momento**, con la hora de
    la marca, y en SAP es `U_EstadoLog = T` con `U_Despachador = SAMEX` (flujo confirmado por el
    usuario el 2026-11-23). La `T` sale de "recibida", como con un repartidor; no hay una regla
    aparte.
  - Desmarcar, o pasarla a un repartidor, la devuelve a "por retirar": P en SAP, con el
    despachador limpio.
  - La sincronización no lo pisa, porque solo trae las P.

## Cómo se prueba

| Nivel | Qué prueba | Contra qué | Cuándo |
|---|---|---|---|
| 1. Lectores | Grilla (envío, estado del tooltip), detalle (referencias, hora de entrega), página 1 del PDF | Páginas y PDF de T-01, anonimizados | Cada commit (`pytest`) |
| 2. Navegación | Login, búsqueda por referencia y por número, detalle, descarga | El HAR de T-01 reproducido con `route_from_har`, saneado, anonimizado y con un PDF de prueba inyectado (comprobado en T-01) | Cada commit |
| 3. Reglas del backend | Marca, resultados, idempotencia, modos, seguridad | H2 + MockMvc | Cada commit (`mvnw test`) |
| 4. Pantalla | Selector con SAMEX, textos, filtros, selección | vitest | Cada commit (`pnpm test`) |
| 5. Punta a punta sin portal | Marca → SAP TEST → resultado simulado → pantalla | `/gd-prueba/`, con una guía creada en TEST | T-21 |
| 6. Portal real en seco | Que el lector encuentra bien los envíos de verdad | Portal Samex con `DRY_RUN=true` y `FOLIOS` de guías Samex recientes | T-22, de 3 a 5 días |
| 7. Sombra en producción | El cruce con datos reales, sin cerrar | `/gd/` en modo `registrar` | T-23, 1 a 2 semanas |

**Por qué no se prueba todo junto en TEST**: los envíos reales de Samex traen folios de
producción, que en CALIMPORT_TEST no existen o son otras guías. Por eso las dos mitades se
prueban por separado:
- **Backend con SAP TEST** y un resultado simulado (nivel 5).
- **Lector con el portal real** sin escribir nada (nivel 6).

Recién se juntan en producción, en modo `registrar` (nivel 7).

**Datos personales**: las páginas, el HAR y los PDF del portal traen nombres, direcciones,
teléfonos, fotos de firmas y el RUT de quien recibió.
- Los originales quedan en `spike/salida/`, fuera de git; al repo solo van versiones
  anonimizadas, con el PDF de prueba en lugar del real.
- Los logs no llevan destinatario ni dirección, y el nombre y el RUT de "ENTREGADO A:" no se leen.
- La clave del HAR la borra `spike/sanear.py`.

## Supuestos

- El jefe escribe el folio, tal cual, entre las referencias del envío (comprobado en 6 de 6 envíos
  revisados en T-01).
- Un envío es una guía y una guía es un envío (confirmado por el usuario).
- Sin evidencia no se cierra: un envío entregado sin PDF queda registrado y a la vista, pero la
  guía sigue en tránsito.
- El portal responde a IP de GCP (lo comprueba T-02).
- El volumen es de decenas de guías Samex por semana (24 envíos en 7 días en T-01).

## Preguntas abiertas

Ninguna por ahora. Las respondidas están en el registro de cambios.

## Riesgos

| Riesgo | Probabilidad | Impacto | Mitigación |
|---|---|---|---|
| Samex cambia el HTML o el flujo del portal | Media | Alto: se dejan de cerrar guías | Selectores por texto, clases de RichFaces y final del id, nunca `j_idNNN`; si falta lo esperado, el job falla y dispara la alerta. Bodega ve la hora de la última lectura. Tests con el HAR. |
| Folio mal escrito en Samex | Baja | Medio: la guía no se encuentra; o, si coincide con otra guía marcada, esa búsqueda trae dos envíos | Las dos situaciones quedan a la vista de bodega y no cierran nada; bodega puede fijar el número de envío a mano (T-09). |
| La búsqueda por referencia hace coincidencias parciales | Media | Bajo | Se exige el folio exacto entre las referencias del detalle, y una vez confirmado se busca por Número. |
| Bodega olvida marcar la guía en la app | Media | Bajo | Su envío no se busca; la píldora "Pie: SAMEX" y el filtro la delatan. |
| Captcha, MFA o bloqueo de IP de datacenter | Baja | Alto | T-01: no hay captcha ni MFA. T-02 prueba las IP de GCP; plan B: otra región. |
| Clave del usuario de Samex cambiada o bloqueada | Media | Medio | Un solo login por corrida y sin reintentar un login rechazado; la alerta y el runbook dicen qué hacer. El portal admite dos sesiones a la vez con el mismo usuario (dato del usuario), así que el job no saca a nadie. |
| SAP rechaza el PATCH de una guía ya cerrada (facturada) | Media | Medio | T-03 lo prueba en TEST. Samex demora días, así que es más probable que con el reparto propio. |
| La corrida se alarga con muchas pendientes | Baja | Bajo | Tope `MAX_FOLIOS_POR_CORRIDA`; lo que no alcanza queda para la siguiente, por antigüedad de la última búsqueda. |
| Samex se molesta por el acceso automático | Baja | Medio | Frecuencia baja: 1 vez por hora, solo de lunes a viernes, una búsqueda por guía. |
| Datos personales en fixtures o logs | Media | Medio | Ver "Cómo se prueba". |

**Costo estimado**:
- Cloud Run Job (1 vCPU, ~2 GiB, 13 corridas al día, lunes a viernes, de 1 a 4 min cada una): de
  US$0 a 3 al mes, dentro o cerca del nivel gratuito; se mide en T-02.
- Secret Manager, Artifact Registry y Storage: centavos.
- Sin IA: no hay costo por tokens ni por páginas.

## Tareas

### Fase 0: spikes (descartables)

- [x] **T-01 Recorrer el portal de Samex desde el PC** · M · Depende de: — · Hecha el
  2026-11-23.
  - **Resultado**: `Guias/samex/docs/portal.md`. El recorrido es `spike/explorar.py`: modos
    `menu`, `entregadas`, `referencias` y `folio <n> [--grabar|--reproducir]`.
  - **Respondió**:
    - Dónde está el folio: en OTRAS REFERENCIAS.
    - FECHA es la de admisión; la hora de entrega está en el detalle y en el PDF.
    - Cómo baja el PDF: como descarga.
    - Sin captcha ni MFA.
    - `route_from_har` sirve para los tests si se inyecta el PDF.
  - **Queda**: la sesión compartida (pregunta abierta 2).
  - **Pasan a T-12 y T-13**: los fixtures anonimizados, porque con la búsqueda por folio lo que
    sirve es el HAR.

- [x] **T-02 El portal desde Cloud Run** · S · Depende de: T-01 · Hecha el 2026-11-23.
  - **Qué se hizo**: `spike/t02/` abrió solo la página de login, **sin credenciales** (decisión
    del usuario: la clave no sale del PC antes de Secret Manager).
  - **Dónde corrió**: un Cloud Run Job temporal en `us-east1` (1 vCPU, 2 GiB), con la imagen en
    `cloud-run-source-deploy`. Los dos se borraron al terminar.
  - **Resultados**:
    - [x] Código 0: HTTP 200 y formulario de login, al primer intento y con el identificador
      "HeadlessChrome". Samex no bloquea las IP de GCP ni el navegador sin ventana.
    - [x] Chromium tarda 2,9 s en abrir y el login 2,0 s. La ejecución completa duró 34 s,
      contando el arranque del contenedor. Memoria máxima: 576 MiB.
    - [x] No hace falta otra región.

- [x] **T-03 Comprobar lo que hace falta de SAP (en TEST)** · S · Depende de: — · Hecha el
  2026-11-23.
  - **Cómo**: `Guias/samex/spike/t03_sap_test.py` solo corre contra CALIMPORT_TEST. Escribió
    sobre la guía DocEntry 46277 (folio 212, serie 11, cerrada), releyó cada valor y la dejó como
    estaba.
  - **Resultados**:
    - [x] `U_EstadoLog = T` con `U_Despachador = SAMEX`: queda tal cual.
    - [x] `U_EstadoLog = E` en una guía cerrada (facturada): se acepta.
    - [x] `TrackingNumber` existe y guarda un número de envío de 12 dígitos (T-28 es viable).

### Fase 1: backend

- [x] **T-04 Bodega marca una guía como despacho externo** · M · Depende de: — · Hecha el
  2026-11-23, sin commit. Suite completa: 518 tests y 0 fallas; 15 tests nuevos en
  `GuiaServiceTest`, `RutaServiceTest` y `GuiaControllerTest`.
  - **Va junto con T-06**: sin ella, marcar avisa a SAP pero `EstadoLogistico` no conoce el
    transportista y manda `P` sin despachador. T-06 ya está hecha.
  - **Corrección en `reasignar`**: antes no hacía nada si el repartidor pedido era el mismo; con
    una guía Samex (sin repartidor), "sin asignar" tiene que quitarle la marca.
  - **`GUIAS_TRANSPORTISTAS`** trae `SAMEX` por defecto, igual que
    `GUIAS_SYNC_TRANSPORTISTAS_EXTERNOS`.
  - **Qué**:
    - Migración `V12`: `guia.transportista` (null = reparto propio). Lo escribe solo la app; la
      sincronización no lo toca.
    - `PATCH /api/guias/{id}/transportista {transportista: "SAMEX" | null}`, solo para
      `JEFE_BODEGA` y solo sobre guías PENDIENTE. El valor tiene que estar en
      `GUIAS_TRANSPORTISTAS`.
    - Marcar quita al repartidor y saca la guía de su ruta (`quitarGuiaDeRutas`), como
      `reasignar`. Asignar un repartidor a una guía Samex le quita el transportista.
    - `POST /api/rutas` no acepta guías con transportista.
  - **Archivos**: `model/Guia`, `GuiaService`, `GuiaController`, `RutaService`,
    `db/migration/`.
  - **Criterios de aceptación**:
    - [x] Marcar una PENDIENTE asignada la deja con `transportista = 'SAMEX'`, sin repartidor y
      fuera de la ruta.
    - [x] Un transportista fuera de la lista → 400.
    - [x] Una ENTREGADA → 409.
    - [x] Un repartidor → 403.
    - [x] Asignar un repartidor a una Samex limpia el transportista.
    - [x] Sincronizar no cambia el transportista.
    - [x] Marcar SAMEX deja la guía recibida, con la hora de la marca. Desmarcar o asignar un
      repartidor la devuelve a "por retirar".
  - **Cómo verificar**: `mvnw test -Dtest=GuiaServiceTest,GuiaControllerTest,RutaServiceTest`.

- [x] **T-05 Tabla `envio_externo` y reglas para los resultados** · M · Depende de: T-04 · Hecha
  el 2026-11-23, sin commit.
  - **Resultado**: 16 tests en `EnviosExternosServiceTest`, contra H2 con las migraciones
    reales. Suite completa: 539 tests y 0 fallas.
  - **Ajuste de diseño**: **una fila por guía** (`V13`, `guia_id` único). El número de envío vive
    en `envio_externo.numero` (único por transportista; los null no chocan), así que **no hace
    falta `guia.envio_externo_numero`**.
  - **Otros nombres**:
    - La búsqueda se guarda como `primera_busqueda` y `ultima_busqueda`, en vez de
      `visto_primera_vez` y `visto_ultima_vez`.
    - El resultado es el enum `ResultadoEnvio`.
    - El código está en `EnviosExternosService`, con los registros `Pendiente`,
      `EnvioEncontrado` y `ResultadoBusqueda`.
  - **Más reglas**:
    - Un envío que ya es de otra guía → `CONFLICTO`.
    - Un envío sin número → `CONFLICTO`.
    - El folio se compara como número, porque el portal puede guardarlo con ceros adelante.
    - Ante un conflicto se conserva lo que había.
  - **Qué** (lo planificado):
    - Migración con `transportista`, `numero` (únicos juntos), `guia_id`, `folio`, `referencias`
      (texto), `estado_portal`, `fecha_entrega` (instante, hora de Chile), `evidencia_url`,
      `evidencia_hash`, `resultado`, `motivo`, `visto_primera_vez`, `visto_ultima_vez`.
    - ~~Además, `guia.envio_externo_numero`~~: va en `envio_externo.numero` (ver el ajuste).
    - `EnviosExternosService.pendientes()` devuelve los folios a buscar: las guías marcadas, sin
      cerrar, de la más antigua a la más nueva búsqueda, con su número de envío si ya lo tienen.
    - `EnviosExternosService.registrar(resultados)` recibe, por folio, lo que encontró el job.
  - **Reglas**:
    - 0 envíos → `NO_ENCONTRADO`.
    - Más de 1 → `CONFLICTO` ("Samex tiene varios envíos con este folio").
    - El folio no está entre las referencias → `CONFLICTO`.
    - La guía dejó de estar marcada o ya está resuelta → se ignora.
    - Si no → `ASOCIADO`, con el estado del portal a la vista.
    - Si está entregado y no hay evidencia, entra en `necesitanEvidencia`.
  - **Criterios de aceptación**:
    - [x] Un test por regla.
    - [x] Registrar el mismo resultado dos veces no duplica filas.
    - [x] Una guía ENTREGADA o RECHAZADA no cambia nunca.
    - [x] `pendientes()` no devuelve guías cerradas ni sin marcar.
  - **Cómo verificar**: `mvnw test -Dtest=EnviosExternosServiceTest`.

- [x] **T-06 La marca llega a SAP** · S · Depende de: T-04, T-03 · Hecha el 2026-11-23, sin
  commit.
  - **Tests**: 4 nuevos en `EstadoLogisticoTest`, 1 en `SincronizacionSapServiceTest`, y las
    aserciones de "sin sincronizar" en `GuiaServiceTest`. Los 4 que dependían del cambio
    fallaron antes de implementarlo (`P` en vez de `T`, `null` en vez de `SAMEX`).
  - **Suite completa**: 523 tests y 0 fallas.
  - **Regla**: el transportista le gana al nombre del repartidor en `U_Despachador`;
    `SincronizacionSapService` no busca repartidor para una guía Samex.
  - **La `T` sale solo de "recibida"**, que `GuiaService` deja en true al marcar. Un primer
    intento la sacaba también del transportista y se quitó (ver el registro de cambios).
  - **Qué**:
    - `EstadoLogistico`: una PENDIENTE con transportista es `T` con `U_Despachador` igual al
      transportista; una ENTREGADA con transportista es `E` con el transportista.
    - Marcar o desmarcar deja `sincronizada = false`, y el empuje y el reintento de siempre la
      llevan a SAP.
  - **Criterios de aceptación**:
    - [x] Tests de `EstadoLogistico` para marcar (`T`/`SAMEX`), desmarcar (`P`, despachador
      limpio) y entregar (`E`/`SAMEX`).
    - [x] Test de `GuiaService`: marcar deja la guía sin sincronizar.
  - **Cómo verificar**: `mvnw test -Dtest=EstadoLogisticoTest,GuiaServiceTest`.

- [x] **T-07 Endpoints de integración con ID token de Google** · M · Depende de: T-05 · Hecha el
  2026-11-23, sin commit.
  - **Archivos**: `IntegracionesSecurityConfig`, `ValidadorTokenIntegracion`,
    `IntegracionSamexController`.
  - **Dependencias** (aprobadas por el usuario): `spring-security-oauth2-resource-server` y
    `spring-security-oauth2-jose` (7.1.1, del BOM), **sin** la autoconfiguración de resource
    server de Spring Boot.
  - **Variables**: `GUIAS_INTEGRACIONES_AUDIENCIA` y `GUIAS_INTEGRACIONES_CUENTAS`; vacías (el
    valor por defecto), `/api/integraciones` rechaza todo.
  - **Tests**: 9 en `ValidadorTokenIntegracionTest`, 9 en `IntegracionSamexControllerTest` (con
    una llave RSA local y el validador de producción) y 1 más en `EnviosExternosServiceTest`.
    Suite completa: 558 tests y 0 fallas.
  - **Prueba de mutación**: con el validador abierto, "otra cuenta" y "otra audiencia" fallan.
  - **Ajustes**:
    - El `PUT .../evidencia` pasa a T-08, con su lógica.
    - El token del lector en `/api/guias` da **401**, no 403: para la cadena de la app es un token
      que no firmó ella.
  - **Qué** (lo planificado):
    - `GET /api/integraciones/samex/pendientes` responde `[{guiaId, folio, envio?}]`
      (`EnviosExternosService.Pendiente`).
    - `POST /api/integraciones/samex/envios` recibe la lista de resultados por guía,
      `[{guiaId, folio, envios: [{numero, estadoPortal, referencias, fechaEntrega}]}]`
      (`ResultadoBusqueda`), y responde `{necesitanEvidencia: [numeros]}`.
    - `PUT /api/integraciones/samex/envios/{numero}/evidencia` recibe el cuerpo
      `application/pdf`.
    - Una `SecurityFilterChain` propia para `/api/integraciones/**` verifica el ID token: emisor
      de Google, `aud` igual a `GUIAS_INTEGRACIONES_AUDIENCIA` y correo dentro de
      `GUIAS_INTEGRACIONES_CUENTAS`; con eso da el rol `INTEGRACION`.
  - **Criterios de aceptación**:
    - [x] Sin token → 401.
    - [x] Con el JWT de un jefe de bodega → 401.
    - [x] Con un token de Google de otra cuenta o de otra audiencia → 401; también vencido o
      firmado con otra llave.
    - [x] Con el token válido → 200.
    - [x] El token de integración en `/api/guias` → 401 (ver los ajustes).
  - **Cómo verificar**: `mvnw test -Dtest=IntegracionSamexControllerTest,ValidadorTokenIntegracionTest`.

- [x] **T-08 Evidencia en PDF y cierre de la guía** · M · Depende de: T-07, T-06 · Hecha el
  2026-11-23, sin commit.
  - **Código nuevo**:
    - `AlmacenamientoFotos.guardarPdf` (valida `%PDF-`, tope de 15 MB, `guia-{id}-{uuid}.pdf`).
    - `GuiaService.entregarPorTransportista`: ENTREGADA con la hora de Samex, sin correo, y aviso
      a SAP.
    - `EnviosExternosService.guardarEvidencia`.
    - `PUT .../envios/{numero}/evidencia`, que responde `{evidenciaUrl, evidenciaHash, guiaCerrada}`.
  - **`GUIAS_SAMEX_MODO`**: `registrar` por defecto; un valor mal escrito impide arrancar.
  - **Al pasar a `cerrar`** se cierran también, en la corrida siguiente, las guías que ya tenían
    PDF.
  - **Tests**: 18 nuevos. Suite completa: 576 tests y 0 fallas.
  - **Hallazgo de despliegue: nginx corta en 16 KB** todo lo que no sea `/gd/api/guias`, y el PDF
    pesa ~450 KB. Se agregó `location ~ ^/gd/api/integraciones(/|$)` con
    `client_max_body_size 16m` en `deploy/nginx-apps-guias.conf`.
    - **En la VM manda el `nginx-apps.conf` del repo Panel-Facturadores**, y `/gd-prueba/` tiene
      su propio snippet: hay que agregarla en los dos (ver T-16, T-21 y T-23).
  - **Qué** (lo planificado):
    - `PUT /api/integraciones/samex/envios/{numero}/evidencia` (cuerpo `application/pdf`,
      `hasRole('INTEGRACION')`), que pasó de T-07. Guarda en `envio_externo.evidencia_url` y
      `evidencia_hash`.
    - Se acepta un PDF validado por contenido (`%PDF-`), con un tope de 15 MB.
    - Se guarda con `DepositoFotos` como `guia-{id}-{uuid}.pdf`, con SHA-256.
    - `NOMBRE_VALIDO` y el tipo de contenido de `/api/fotos` y `/api/evidencia` admiten `.pdf`.
    - Que el PDF sea de ese envío lo comprueba el job antes de subirlo (página 1), sin sumarle una
      librería de PDF al backend.
  - **Según `GUIAS_SAMEX_MODO`**:
    - `registrar` (el valor por defecto) guarda la evidencia en el envío y no toca la guía.
    - `cerrar` deja la guía ENTREGADA con `url_foto`/`hash_foto`, **`fecha_entrega` igual a la
      hora de entrega de Samex**, `sincronizada = false` y **`correo_estado` null**.
  - **Criterios de aceptación**:
    - [x] Un JPEG con nombre `.pdf` → 400.
    - [x] Con `registrar`, la guía queda igual.
    - [x] Con `cerrar`, la guía queda ENTREGADA con la hora de Samex y `CorreoDespachoService` no
      la toma.
    - [x] El PATCH armado lleva `E`, `SAMEX` y un link de 100 caracteres o menos (95 con un id de 6
      dígitos).
    - [x] Mandar la misma evidencia dos veces no cambia la fecha ni duplica el archivo.
  - **Cómo verificar**: `mvnw test -Dtest=EnviosExternosServiceTest,AlmacenamientoFotosTest`.

- [x] **T-09 Lo que necesita ver bodega** · M · Depende de: T-05 · Hecha el 2026-11-23, sin
  commit. 18 tests nuevos; suite completa: 594 tests y 0 fallas.
  - **Ajustes de diseño**:
    - **No hay `GuiaResponse`**: la API devuelve la entidad `Guia`.
      - En el JSON de la guía ya vienen `transportista` y **`transportistaFooter`**. Este último
        se llama así, y no `transportistaPie`, por `direccionFooter` y `horarioFooter`. Se
        guarda al sincronizar (`V14`), leído con el mismo `FooterDespacho`.
    - **Un solo `GET /api/envios-externos`** reemplaza a `/problemas` y `/estado`: devuelve
      `{ultimaBusqueda, porBuscar, envios: [...]}`, con un indicador `revisar` por guía. La
      tabla lo cruza por `guiaId`.
    - **`fijado_a_mano`** (`V14`). Con el número fijado, el lector no exige el folio entre las
      referencias y lo sigue buscando por número aunque una vez no lo encuentre. Sin número se
      deshace lo fijado.
  - **Regla de `revisar`**: conflicto siempre; no encontrada recién a los 2 días hábiles (sin
    descontar feriados); solo guías que siguen pendientes.
  - **Tropiezo**: la columna `fijado_a_mano` necesitó el nombre explícito, como
    `vuelve_a_bodega`; la validación del esquema lo detectó en los tests.
  - **Qué** (lo planificado):
    - `GuiaResponse` suma:
      - `transportista`;
      - `transportistaPie` (lo que dice el pie, como pista);
      - `envio` (número, estado del portal, fecha de entrega, resultado, motivo).
    - `GET /api/transportistas` devuelve la lista de `GUIAS_TRANSPORTISTAS`.
    - `GET /api/envios-externos/problemas` lista las guías Samex no encontradas hace más de 2
      días hábiles y las que tienen conflicto.
    - `PATCH /api/guias/{id}/envio-externo {numero}` fija a mano el número de envío en
      `envio_externo.numero`, como `ASOCIADO` y con una marca de "fijado a mano" (columna nueva).
      Desde ahí el lector la busca por Número.
    - `GET /api/envios-externos/estado` da la última lectura (`max(visto_ultima_vez)`).
    - Todo para `JEFE_BODEGA`.
  - **Criterios de aceptación**:
    - [x] Tests de controller y roles: un repartidor recibe 403 en todos.
    - [x] Fijar el número a mano hace que `pendientes()` lo devuelva con ese número.
  - **Cómo verificar**: `mvnw test -Dtest=EnvioExternoControllerTest,EnviosExternosServiceTest`.

- [x] **T-10 Documentación del backend** · S · Depende de: T-04 a T-09 · Hecha el 2026-11-23,
  sin commit.
  - **`CLAUDE.md`**:
    - Sección nueva "Guías de un transportista externo (Samex)".
    - Variables, endpoints y tabla de roles, con `/api/integraciones/**`.
    - El salto de la sincronización queda como provisorio hasta encender la integración.
    - El largo de `U_Despachador` en TEST se observó de 13.
  - **`env.ejemplo`**: sección "Transportistas externos (Samex)".
  - Las cuatro variables nuevas están en `application.properties`, `env.ejemplo` y `CLAUDE.md`
    (comprobado con grep).
  - **Qué**:
    - En `CLAUDE.md`: guías Samex, `transportista`, la marca en SAP, `envio_externo`, la búsqueda
      por folio, modos, interruptores, endpoints y la tabla de roles. El salto de la
      sincronización deja de ser "provisorio" y pasa a ser historia.
    - `env.ejemplo` con las variables nuevas.
  - **Criterios de aceptación**:
    - [x] Cada variable nueva está en `env.ejemplo` y en `CLAUDE.md`.

### Fase 2: el job de Samex (repo `Guias/samex`)

- [ ] **T-11 Esqueleto del repo** · S · Depende de: T-01 · Casi hecha el 2026-11-23: falta
  construir la imagen.
  - **Hecho**:
    - `git init -b main` (sin commits).
    - `app/config.py`: Settings, con `FiltroSecretos` y `proteger_logs` copiados de
      `credenciales-portal`; lee `../backend/.env` y después `.env`.
    - `app/job.py` como punto de entrada.
    - `Dockerfile`, `.dockerignore` y `.gcloudignore`, para que `spike/salida`, que tiene datos de
      clientes, no se suba a Cloud Build.
    - `pytest`: 6 tests, 0 fallas. `ruff`: limpio. El job arranca leyendo las credenciales.
  - **Decisión de Python**: 3.14, el mismo del PC, sobre `python:3.14-slim-bookworm` con
    `playwright install --with-deps chromium`. Bookworm, porque es el Debian que soporta Playwright.
  - **El spike queda en el repo** (`spike/`, sin `spike/salida/`) porque sirve para regrabar el
    HAR en T-13; ruff no lo revisa.
  - **Imagen**: se construyó bien en Cloud Build (2026-11-23, ver T-16), así que el Dockerfile es
    válido. **No se verificó que Chromium arranque dentro**; eso se ve en la primera ejecución del job.
    Docker Desktop no estaba corriendo.
  - **Visto al pasar**: `python-dotenv` no puede leer la línea 36 de `backend/.env`. Al lector no le
    afecta.
  - **Qué**: `git init` y el esqueleto de `credenciales-portal`:
    - `pyproject`, ruff, pytest.
    - Configuración con pydantic-settings: `URL_SAMEX`, `USUARIO_SAMEX`, `PASS_SAMEX`,
      `GUIAS_API_URL`, `GUIAS_AUDIENCIA`, `MAX_FOLIOS_POR_CORRIDA=40`, `DRY_RUN=true`, `FOLIOS`
      (opcional).
    - El filtro `proteger_logs()`.
    - Dockerfile sobre la imagen oficial de Playwright para Python, con versión fija.
      - **Esa imagen no trae el paquete `playwright`** (T-02): hay que instalarlo fijo en la misma
        versión que sus navegadores (`pip install --break-system-packages playwright==1.61.0`).
      - Trae **Python 3.12**, y el PC tiene 3.14. Decidir en esta tarea entre instalar 3.12 en el
        PC o partir de `python:3.14-slim` con `playwright install --with-deps chromium`, como
        `credenciales-portal`.
    - El spike queda fuera del repo final, o se borra.
  - **Criterios de aceptación**:
    - [ ] `pytest -q` y `ruff check .` pasan.
    - [ ] `docker build` funciona (o `gcloud builds submit`).
  - **Cómo verificar**: esos comandos.

- [x] **T-12 a T-15** · Hechas el 2026-11-23, sin commit, en `Guias/samex`.
  - **Tests**: `pytest` da 36 tests y 0 fallas; `ruff` limpio. Incluyen la reproducción del HAR de
    T-01, que solo corre donde está el archivo.
  - **Prueba real, en seco**: con `FOLIOS=311994,311969` contra el portal de Samex, encontró los
    dos envíos con estado, hora de entrega y referencias, cambiando de búsqueda entre folios. El
    plan pedía 3 guías; se probaron 2.
  - **Decisiones**:
    - **La extracción del HTML corre en el navegador** (`GRILLA_JS`, `DETALLE_JS`) y la
      interpretación es Python puro. Así no hace falta una librería de HTML.
    - **Fixtures**: HTML escrito a mano con la estructura real; ningún dato de cliente en el repo.
      El HAR real queda solo en el PC.
    - **Varios envíos**: se mandan sin detalle; el backend los da por conflicto igual.
    - **El PDF se baja recién cuando el backend lo pide**, en una segunda búsqueda por número.
    - **El albarán** se reconoce por su tooltip, porque la columna Cubis usa el mismo ícono.
    - **Una búsqueda que se cae** no tumba la corrida: esa guía queda para la hora siguiente.
  - **El simulador** (`scripts/simular_entrega.py`) y el PDF de prueba
    (`tests/fixtures/evidencia.pdf`) quedan listos para T-21.
- [x] **T-12 Lectores de páginas y del PDF** · S · Depende de: T-11 (ver arriba)
  - **Qué**: funciones puras sobre HTML y PDF:
    - La grilla da envío y estado: el tooltip, con su fecha.
    - El detalle da las referencias y la fecha y hora de la línea "ENTREGADO A:"; el nombre y el
      RUT se descartan sin guardarlos.
    - La página 1 del PDF (`pypdf`, que lee su capa de texto) da la expedición y la "Fecha de
      Entrega".
    - Si falta algo de lo esperado, fallan con "el portal cambió".
  - **Fixtures**: páginas de T-01 anonimizadas y un PDF de prueba con la misma página 1.
  - **Criterios de aceptación**:
    - [ ] Con los fixtures dan lo esperado.
    - [ ] Un detalle sin la línea de entrega da "sin entregar".
    - [ ] Un PDF de otra expedición se detecta.
  - **Cómo verificar**: `pytest tests/test_lectores.py`.

- [x] **T-13 Navegación con Playwright** · M · Depende de: T-12 (ver T-12 a T-15)
  - **Qué**:
    - Login, búsqueda por Referencia o por Número, detalle y descarga del PDF, como en el modo
      `folio` del spike.
    - Timeouts y `wait_for_selector` en cada paso.
    - Un 5xx o una página de error → `PortalNoDisponible`.
  - **Criterios de aceptación**:
    - [ ] Test que recorre el flujo completo contra el HAR de T-01, saneado, anonimizado y con el
      PDF de prueba inyectado, sin red.
    - [ ] Test de login rechazado → error de credenciales, sin reintentar el login (evita
      bloquear el usuario).
  - **Cómo verificar**: `pytest tests/test_portal.py`.

- [x] **T-14 Cliente del backend** · S · Depende de: T-11, T-07 (ver T-12 a T-15)
  - **Qué**:
    - Pide el ID token: en Cloud Run al servidor de metadatos; en el PC, impersonando
      `samex-runner`.
      - Con la audiencia de la instancia (`GUIAS_AUDIENCIA`).
      - **Con el correo incluido**: `format=full` en el servidor de metadatos, o `include_email`
        al impersonar. Sin el correo, el backend lo rechaza (T-07).
    - Pide las pendientes, manda los resultados y sube los PDF pedidos.
    - Reintenta 5xx y timeouts con espera creciente; un 4xx no se reintenta y queda en el resumen.
  - **Criterios de aceptación**:
    - [ ] Tests con `httpx.MockTransport` para el token, las pendientes, los resultados, la
      subida, un 503 que se reintenta y un 400 que no.
  - **Cómo verificar**: `pytest tests/test_guias_api.py`.

- [x] **T-15 Orquestación, modo en seco y simulador** · S · Depende de: T-13, T-14 (ver T-12 a T-15)
  - **Qué**:
    - `python -m app.job`:
      1. Pide las pendientes (o usa `FOLIOS`).
      2. Busca cada una, hasta el tope.
      3. Baja y comprueba el PDF de las entregadas que el backend pide, y lo sube.
      4. Termina con `Resumen: X buscadas, Y en tránsito, Z entregadas, W PDF subidos, V con
         problema`.
    - `DRY_RUN` no le manda nada al backend y lista `folio → envío, estado, hora de entrega`.
    - Portal caído → código 0 con aviso; cualquier otro error → código 1.
    - `scripts/simular_entrega.py --folio N --envio E --pdf archivo.pdf --api URL` manda un
      resultado inventado, para T-21.
  - **Criterios de aceptación**:
    - [ ] Tests de los tres caminos de salida.
    - [ ] Corrida local en seco contra el portal real, con `FOLIOS` de 3 guías Samex conocidas.
  - **Cómo verificar**: `python -m app.job` con las variables de `Guias/backend/.env`.

- [ ] **T-16 Despliegue en GCP** · M · Depende de: T-15 · **Scripts listos (2026-11-23); falta
  correrlos.**
  - **Listos** en `Guias/samex/deploy/`:
    - `owner.ps1` ([Owner]): las dos cuentas, los secretos, los permisos y, con `-DarInvoker`, el
      permiso del Scheduler.
    - `desplegar.ps1` (desarrollo.ti@): repositorio, imagen, job, Scheduler y alerta.
    - `politica-alerta.json`: copia de la de `credenciales-portal`, con el mismo canal.
    - El runbook está en el README.
  - **Verificado sin tocar GCP**: la sintaxis de los dos scripts y el JSON. Se comprobó que las
    cuentas y los secretos de Samex no existen todavía y que `desarrollo.ti@` ya creó una política
    de alerta equivalente.
  - **Cambio de reparto con el Owner (2026-11-23)**: se midió con `testIamPermissions` y
    `desarrollo.ti@` SÍ puede crear cuentas de servicio, secretos y sus valores, `actAs`, repositorio,
    imagen, job, Scheduler y alerta; NO puede dar permisos (IAM) ni leer secretos. Entonces el Owner
    solo da **4 permisos** y no ve ninguna credencial: `desplegar.ps1` crea todo lo demás, incluidos los
    secretos (desde `backend/.env`), y `-ConSecretos` los conecta al job después del Owner.
  - **El Scheduler nace pausado**: sin el backend desplegado con las integraciones, cada corrida
    fallaría y la alerta sonaría cada hora.
  - **Un job por instancia**: `desplegar.ps1` crea el de `/gd-prueba`; el de producción se prepara
    en T-23, con su propia audiencia.
  - **Etapa 1 aplicada el 2026-11-23** (`desplegar.ps1`), verificada recurso por recurso:
    - cuentas `samex-runner` y `samex-invoker`;
    - secretos `samex-usuario` y `samex-clave` (1 versión cada uno);
    - repositorio e imagen `samex-sync` construida en Cloud Build;
    - job `samex-sync` (`DRY_RUN=true`, sin reintentos, 900 s, apuntado a `/gd-prueba`), **todavía
      sin secretos**;
    - Scheduler `samex-sync-horario` **PAUSADO** (`0 8-20 * * 1-5`, America/Santiago);
    - alerta "Samex: ejecución fallida".
  - **Tropiezo**: el primer intento falló al preguntar si existía la cuenta, porque PowerShell 5.1
    convierte el stderr de `gcloud` en error fatal; no creó nada y se corrigió en los dos scripts.
  - **Falta**: que el Owner dé los 4 permisos y después `desplegar.ps1 -ConSecretos`. El job no se
    ejecutó todavía, ni siquiera en seco.
  - **Qué** (lo planificado):
  - **Qué**:
    - Repositorio de Artifact Registry `samex-sync`.
    - Job `samex-sync` (1 GiB: T-02 midió 576 MiB con una página; revisar en T-22), sin
      reintentos, con un timeout de 15 min.
    - Secretos `samex-usuario` y `samex-clave` con el usuario que ya existe **[Owner]**.
    - Cuenta `samex-runner` con acceso a esos dos secretos **[Owner]**, y Token Creator sobre ella
      para `desarrollo.ti@`, para el simulador y las pruebas locales **[Owner]**.
    - Scheduler `samex-sync-horario`, `0 8-20 * * 1-5` en `America/Santiago`, con `samex-invoker`
      y `run.invoker` **[Owner]**.
    - Alerta "Samex: ejecución fallida" a `desarrollo.ti@`.
    - En el `.env` de cada backend, `GUIAS_INTEGRACIONES_CUENTAS` con el correo de
      `samex-runner`.
    - En nginx de la VM, `location ~ ^/gd/api/integraciones(/|$)` con `client_max_body_size 16m`:
      - en `nginx-apps.conf` del repo Panel-Facturadores, que es el que manda;
      - y su equivalente para `/gd-prueba/` en `guias-prueba-locations.conf`.

      Sin eso, el PDF rebota con un 413 (T-08).
    - Runbook en el README: pausar, cambiar la clave, fijar un envío a mano, qué hacer ante la
      alerta.
  - **Criterios de aceptación**:
    - [ ] `gcloud run jobs execute samex-sync --wait` termina en 0 en seco, con el resumen en los
      logs.
    - [ ] El Scheduler lo dispara a la hora.
    - [ ] Una falla forzada (clave mala) dispara la alerta.
  - **Cómo verificar**: esos comandos y el correo de la alerta.

### Fase 3: frontend (este repo de Frontend)

- [x] **T-17 Marcar SAMEX desde la tabla de bodega** · M · Depende de: T-04, T-09 · Hecha el
  2026-11-23, sin commit. `pnpm test`: 176 tests y 0 fallas (6 nuevos). El build de desarrollo
  compila las plantillas.
  - **El borrador guarda un `Despacho`**: un número (repartidor), un texto (transportista) o
    `null`. Las opciones del selector valen `""`, `"12"` o `"t:SAMEX"` (`valorDeOpcion` /
    `despachoDeOpcion`).
  - **"Confirmar asignación"** llama a `PATCH .../transportista` para un transportista y a
    `PATCH .../repartidor` para el resto; ese endpoint también le quita SAMEX a una guía.
  - **La lista de transportistas** se carga de forma tolerante: sin el endpoint, la tabla
    funciona igual, solo sin la opción.
  - **Sin cubrir**: "un fallo queda en el borrador" es lógica del componente, que ya existía y no
    cambió; los tests cubren solo la parte pura.
  - **Qué**:
    - El selector de repartidor de cada fila ofrece también "Despacho externo: SAMEX" (de
      `GET /api/transportistas`), y la barra de asignación en lote también.
    - El borrador (`asignacion.ts`) guarda repartidor o transportista. "Confirmar asignación"
      manda `PATCH .../repartidor` o `PATCH .../transportista` según el caso; lo que falla queda en
      el borrador, como hoy.
    - Píldora "Pie: SAMEX" cuando el pie lo nombra y la guía no está marcada.
  - **Criterios de aceptación**:
    - [ ] `asignacion.spec` cubre:
      - marcar SAMEX, una por una y en lote;
      - pasar de SAMEX a un repartidor y al revés;
      - un fallo que queda en el borrador.
    - [ ] Pasar a Rutas con una marca sin confirmar pide confirmarla, como con un repartidor.
  - **Cómo verificar**: `pnpm test`.

- [x] **T-18 Las guías Samex a la vista** · M · Depende de: T-17 · Hecha el 2026-11-23, sin
  commit. `pnpm test`: 187 tests y 0 fallas (11 nuevos). El build compila.
  - **Datos**: el seguimiento sale de `GET /api/envios-externos` (`EnviosExternosService` del
    frontend), cargado de forma tolerante como los transportistas.
  - **`textoTransporte`**: para una guía en conflicto dice "Samex · hay que revisar el envío";
    para una entregada con PDF pero sin cerrar (etapa sombra), "Samex la entregó el … (envío …)".
  - **El filtro** se llama `EXTERNA`, y la celda "Despacho externo" se muestra solo si hay
    transportistas o guías marcadas.
  - **`FotoGuiaComponent`** abre el PDF en otra pestaña (Blob URL) en vez de pintarlo.
  - **El texto de Sincronizar no se tocó**: solo aparece mientras el backend sigue saltando las
    guías Samex, y en ese caso es correcto.
  - **Qué**:
    - `textoTransporte()` en `guia-utils`:
      - "Recibida por Samex el 23/11 a las 10:00": la hora en que bodega la marcó, que es
        `fechaRecepcionRepartidor`;
      - "Samex · aún no encuentra el envío";
      - "Samex · En reparto (envío 999905761651)";
      - "Entregada por Samex el 23/11 a las 10:03";
      - "Samex la dio por entregada; falta la evidencia".
    - Una celda "Por Samex" en el resumen que filtra; las Samex no cuentan en "sin asignar" ni en
      "asignadas".
    - A una guía Samex la app solo le sigue el folio: no muestra la marca "Confirmar" de dirección
      ni el editor de horario, que son para armar rutas.
    - "Ver evidencia" con un PDF lo baja con token y lo abre en una pestaña nueva (Blob URL) en vez
      de pintar un `<img>`; el SHA-256 se ve igual.
    - El texto de Sincronizar deja de decir que se saltaron.
  - **Criterios de aceptación**:
    - [x] `guia-utils.spec` cubre los textos y `esPdf()`.
    - [x] `bodega-filtros.spec` cubre el filtro y los conteos.
  - **Cómo verificar**: `pnpm test` y la prueba manual con el perfil local.

- [x] **T-19 Guías Samex con problema y última lectura** · M · Depende de: T-09 · Hecha el
  2026-11-23, sin commit. `pnpm test`: 195 tests y 0 fallas (8 nuevos en
  `envios-externos.spec`). El build compila.
  - **Lógica pura** en `features/bodega/envios-externos.ts`: `lecturaSamex`, `validarNumeroEnvio`
    y `guiasParaRevisar`.
  - **"Fijar número"** recarga la tabla al terminar; "Quitar número" lo deshace.
  - **El aviso de atraso** vale de lunes a viernes de 8 a 20, el horario del Scheduler. La
    antigüedad se calcula al cargar; "Actualizar" la pone al día.
  - **Sin cubrir por tests**: que "Fijar número" recargue la tabla es del componente, que los
    tests no cubren; queda para la prueba de T-21.
  - **Qué**:
    - En la vista Guías, un bloque "Samex: revisar" con las no encontradas hace más de 2 días
      hábiles y las con conflicto, con el motivo y "Fijar número de envío".
    - En el encabezado, "Última lectura de Samex: hace X". En `--warn` si pasó más de 3 h en
      horario hábil.
  - **Criterios de aceptación**:
    - [ ] Tests de la lógica pura (texto de antigüedad, validación del número de envío).
    - [ ] Fijar el número refresca la tabla.
  - **Cómo verificar**: `pnpm test` y la prueba manual con el perfil local.

- [x] **T-20 Documentación del frontend** · S · Depende de: T-17 a T-19 · Hecha el 2026-11-23,
  sin commit. Sección "Guías Samex (despacho externo)" en `Frontend/CLAUDE.md`, con la
  arquitectura y el párrafo de los filtros actualizados.
  - **Qué**: una sección "Guías Samex" en `CLAUDE.md`, y actualizar "Asignar se confirma".

### Fase 4: pruebas por etapas y salida a producción

- [ ] **T-21 Punta a punta en `/gd-prueba/` (SAP TEST, sin el portal)** · S · Depende de: T-08,
  T-15, T-18
  - **Qué**:
    1. Configurar el `.env` de prueba: `GUIAS_SYNC_TRANSPORTISTAS_EXTERNOS=` (vacío),
       `GUIAS_TRANSPORTISTAS=SAMEX`, `GUIAS_SAMEX_MODO=cerrar`, y la audiencia `.../gd-prueba`
       con su cuenta.
    2. Crear en TEST (serie 11) una guía con el pie de plantilla y `TRANSPORTE: SAMEX`.
    3. Sincronizar.
    4. Marcarla SAMEX en la app.
    5. Correr `simular_entrega.py --folio <ese> --envio 999900000001 --pdf tests/fixtures/evidencia.pdf`.
  - **Criterios de aceptación**:
    - [ ] La guía entra con la píldora "Pie: SAMEX".
    - [ ] `pendientes` la devuelve recién marcada.
    - [ ] Marcada, en SAP TEST queda `U_EstadoLog = T` y `U_Despachador = SAMEX`.
    - [ ] Simulada la entrega, queda `E` en SAP y "Entregada por Samex" en la app, con el PDF y la
      hora simulada. En TEST `U_UrlFoto` llega vacío por su largo: es sabido.
    - [ ] Repetir la simulación no cambia nada.
    - [ ] Un resultado con dos envíos aparece en "Samex: revisar".

- [ ] **T-22 El portal real, en seco** · S · Depende de: T-16
  - **Qué**: el job en Cloud Run con `DRY_RUN=true` y `FOLIOS` con las guías Samex de la semana
    (las da bodega), de 3 a 5 días hábiles. Bodega compara `folio → envío, estado` con el
    portal.
  - **Criterios de aceptación**:
    - [ ] Ninguna corrida falla sin explicación.
    - [ ] Todos los folios que Samex tiene se encuentran.
    - [ ] Los estados y las horas coinciden con el portal.

- [ ] **T-23 Sombra en producción** · S · Depende de: T-22, despliegue de la fase 1 y la fase 3
  - **Qué**:
    1. Desplegar el backend y el frontend antes de tocar el `.env`: sin el selector, las guías del
       pie SAMEX entrarían como asignables a un repartidor.
    2. En `/opt/guias/.env`, `GUIAS_SYNC_TRANSPORTISTAS_EXTERNOS=` (vacío),
       `GUIAS_TRANSPORTISTAS=SAMEX` y `GUIAS_SAMEX_MODO=registrar`.
    3. En el job, `DRY_RUN=false`, sin `FOLIOS`.
    4. El jefe marca SAMEX en la app cada vez que crea un envío en el portal.
    5. Durante 1 a 2 semanas, bodega revisa los envíos encontrados y la lista "Samex: revisar".
  - **Criterios de aceptación**:
    - [ ] 0 asociaciones erróneas en la revisión.
    - [ ] La tasa medida de guías encontradas solas
      (`select resultado, count(*) from envio_externo group by 1`).
    - [ ] Las guías marcadas muestran `T`/`SAMEX` en SAP productivo.

- [ ] **T-24 Encender el cierre** · S · Depende de: T-23
  - **Qué**: `GUIAS_SAMEX_MODO=cerrar` y reiniciar `guias`. La próxima corrida cierra las
    entregadas que ya tienen evidencia.
  - **Rollback**: volver a `registrar` y pausar el Scheduler. Una guía ya cerrada no se reabre
    desde la app (es evidencia): corregirla exige SQL y SAP a mano, y por eso existe T-23.
  - **Criterios de aceptación**:
    - [ ] Las primeras 10 guías cerradas tienen el PDF correcto, la hora de Samex y `E` en SAP
      productivo.

- [ ] **T-25 Carga inicial** · S · Depende de: T-24
  - **Qué**:
    1. Bodega marca en lote las guías viejas con "Pie: SAMEX" que siguen pendientes.
    2. Las corridas siguientes las buscan solas, hasta `MAX_FOLIOS_POR_CORRIDA` por corrida.
    3. Las que Samex no encuentra quedan en "Samex: revisar".
  - **Criterios de aceptación**:
    - [ ] Quedan contadas las guías Samex viejas, cuántas se cerraron y cuántas quedan para
      bodega.

### Fase 5: opcional, después del MVP

- [ ] **T-26 ~~IA de respaldo~~**: **descartada** (2026-11-23). El folio y la fecha salen como texto
  del portal y de la página 1 del PDF, y las fotos pueden traer guías de otros envíos.
- [ ] **T-27 Actuar sobre incidencias y devoluciones de Samex**: avisar a bodega, ¿devolución →
  RECHAZADA?
- [ ] **T-28 El número de envío en SAP** (`TrackingNumber`), si T-03 lo confirma.
- [ ] **T-29 Correo de despacho al cliente** para Samex, con el PDF.

## Registro de cambios del plan

- 2026-11-23: borrador. Decisiones del usuario:
  - Adaptar a lo que ya existe, sin Firestore, Pub/Sub ni URL firmada.
  - REFERENCIA trae el folio.
  - Alcance: Guías y SAP, sin correo.
  - IA solo como respaldo.
- 2026-11-23: respuestas del usuario a las preguntas abiertas.
  - **Quién crea los envíos**: el jefe de bodega, en el portal, con el folio entre las
    referencias.
  - **Dónde se marca**: en la app ("Despacho externo, elegir SAMEX"), y en SAP se ve en
    `U_Despachador = SAMEX`. Cambios en el plan:
    - Se agregan D-10, la marca en el selector (T-04, T-06, T-17) y el empuje `T`/`SAMEX`.
    - El pie pasa a ser solo una pista.
    - Desaparece la importación bajo demanda por folio, porque la guía tiene que estar en la app
      para poder marcarla.
    - El interruptor de importar se reemplaza por vaciar `GUIAS_SYNC_TRANSPORTISTAS_EXTERNOS`.
  - **Usuario del portal**: ya existe.
  - **API o exportación**: Samex no tiene.
  - **Días**: solo de lunes a viernes, y el Scheduler pasa a `0 8-20 * * 1-5`.
  - **Envíos y guías**: un envío es una guía, y al revés.
- 2026-11-23: plan aprobado, sin objeciones al supuesto de que marcar SAMEX deja la guía en `T`.
  Empieza T-01 en `Guias/samex/spike/`, con el Playwright 1.61 que ya está instalado en el PC
  (Python 3.14).
- 2026-11-23: T-01 hecha.
  - **Credenciales**: el spike las lee de `Guias/backend/.env` (`URL_SAMEX`, `USUARIO_SAMEX`,
    `PASS_SAMEX`, nombres del usuario).
  - **Hallazgos** (detalle en `Guias/samex/docs/portal.md`):
    - **El folio no está en la columna REFERENCIA de la grilla**: ahí va "CLIENTE FINAL" o
      "TRASLADO SUCURSAL". Está en "OTRAS REFERENCIAS" del detalle, a veces junto a otro número.
    - **La Búsqueda por "Referencia" encuentra el envío por el folio.**
    - **Sí hay hora de entrega**, en el tracking del detalle y en la página 1 del PDF.
    - **El PDF tiene una página de Samex con texto** (expedición y fecha de entrega) y después
      fotos que pueden incluir guías de otros envíos.
    - **`route_from_har` reproduce todo sin red** si se le inyecta el PDF.
- 2026-11-23: el usuario aprueba **buscar cada guía por su folio** (D-06 nueva). Cambios:
  - **Tareas**: cambian T-05 (resultados por folio), T-07 (se suma `pendientes`), T-09 y T-19
    ("Samex: revisar", fijar el envío a mano), T-12 y T-13 (lectores de grilla, detalle y PDF;
    búsqueda por referencia y por número), T-15, T-22 (`FOLIOS` para el modo en seco) y T-25
    (sin ventana de días).
  - **Hora de entrega**: `fecha_entrega` es la hora de entrega de Samex, con lo que se cierra la
    pregunta abierta de la hora.
  - **IA descartada (T-26)**: el usuario preguntó por el costo en tokens de un OCR; con este
    diseño nada lee las fotos y no se usa ningún modelo.
- 2026-11-23: T-02 hecha. El portal responde desde Cloud Run `us-east1` con el navegador sin
  ventana (HTTP 200, formulario de login, 34 s de ejecución, 576 MiB).
  - **Imagen**: la oficial de Playwright no trae el paquete de Python y usa Python 3.12; se
    anotó en T-11.
  - **Limpieza**: el job y la imagen temporales se borraron. En el bucket de Cloud Build quedan
    los dos `.tgz` de fuente, que son el script y el Dockerfile del spike, como en cualquier
    build.
- 2026-11-23: el usuario confirma que el portal de Samex admite **dos sesiones a la vez** con el
  mismo usuario; se cierra esa pregunta abierta. T-03 hecha (ver la tarea).
  - Dato para T-10: en TEST, la guía 46277 tenía `U_Despachador = "Despachador 1"`, de 13
    caracteres. `CLAUDE.md` dice que en TEST ese campo mide 10; puede estar desactualizado.
  - Empieza T-04 en el árbol de trabajo del backend, sin commit, porque `max` ya tiene muchos
    cambios sin commitear en los mismos archivos.
- 2026-11-23: el usuario describe el flujo.
  - **El flujo**: las guías llegan a la app con su folio; ahí bodega elige qué despachador la
    lleva; si es SAMEX, la guía queda en T y **recibida al instante**, y eso va a SAP.
  - **Ajustes en T-04 y T-06**:
    - Marcar deja `recibidaPorRepartidor = true` con la hora de la marca. Antes la dejaba por
      retirar y la `T` salía del transportista.
    - Desmarcar o pasarla a un repartidor la vuelve a "por retirar".
    - `EstadoLogistico.codigo` vuelve a su regla única: recibida → T.
  - Se cierra la pregunta abierta de la `T`. Suite completa: 523 tests y 0 fallas.
- 2026-11-23: T-05 hecha.
  - **Ajuste**: una fila de `envio_externo` por guía, con el número de envío ahí y sin
    `guia.envio_externo_numero`. Se ajustaron T-07 (forma de `pendientes` y de los resultados) y
    T-09 (fijar el número a mano).
  - **Suite completa**: 539 tests y 0 fallas.
- 2026-11-23: T-07 hecha, con las dependencias de Spring Security que aprobó el usuario.
  - **Ajustes**: el `PUT` del PDF pasa a T-08; el token del lector en el resto de la API da 401.
  - **Requisito nuevo para T-14**: pedir el ID token con el correo (`format=full`).
  - **Suite completa**: 558 tests y 0 fallas.
- 2026-11-23: T-08 hecha. El backend queda completo para el lector: pendientes, resultados, PDF y
  cierre en modo `cerrar`.
  - **Hallazgo**: el límite de 16 KB de nginx para todo lo que no sea `/gd/api/guias`. Se anotó en
    T-16 como paso de despliegue, en el repo Panel-Facturadores y en el snippet de `/gd-prueba/`.
  - **Suite completa**: 576 tests y 0 fallas.
- 2026-11-23: T-09 y T-10 hechas. **La fase 1 (backend) está completa.**
  - **Ajustes en T-09**:
    - `transportistaFooter` en vez de `transportistaPie`.
    - Un solo `GET /api/envios-externos` con `revisar`, en vez de `/problemas` y `/estado`.
    - `fijado_a_mano`: el lector no exige el folio y sigue buscando por número.
  - **Suite completa**: 594 tests y 0 fallas.
- 2026-11-23: T-17 hecha. Bodega ya puede marcar "Despacho externo: SAMEX", una a una o en lote,
  y ve la píldora "Pie: SAMEX". Falta T-18 para que el estado de Samex se vea en la tabla.
- 2026-11-23: T-18 hecha. La tabla muestra en qué va cada guía Samex, la cuenta aparte, la filtra
  y abre su PDF. Quedan T-19 (bloque "Samex: revisar") y T-20 (documentación del frontend).
- 2026-11-23: el usuario descubre que **al crear la GD en SAP ya se puede dejar `U_Despachador =
  SAMEX`** (en TEST: folios 228 y 234, serie 11, `U_EstadoLog = P`). Cambios, que enriquecen D-10
  sin quitar lo anterior:
  - **El sincronizador la deja marcada sola** (`GuiaSap.despachador`, `SapClient` pide
    `U_Despachador` con los UDF de estado): transportista SAMEX, recibida, y `T` a SAP. No pisa una
    guía que ya tiene repartidor o transportista en la app. Backend: 600 tests, 0 fallas.
  - **Filtro por despachador** en la tabla de bodega (todos, sin despachador, repartidor,
    "Despacho externo: SAMEX"). Frontend: 200 tests, 0 fallas.
  - **Samex no tiene envío con el folio 234** (probado en seco con el lector): para probar el
    lector de punta a punta se liga la 234 a un envío real con "Fijar número de envío".
  - **VM de prueba** (con respaldos `*.bak-samex-20261123`): `/opt/guias-prueba/.env` con
    `GUIAS_SYNC_TRANSPORTISTAS_EXTERNOS=` (vacía), `GUIAS_TRANSPORTISTAS=SAMEX`,
    `GUIAS_SAMEX_MODO=cerrar`, `GUIAS_INTEGRACIONES_AUDIENCIA=https://apps.calimport.cl/gd-prueba` y
    `GUIAS_INTEGRACIONES_CUENTAS=` (vacía: rechaza todo hasta decidir la cuenta); y la `location` de
    integraciones con 16 MB en `guias-prueba-locations.conf`.
- 2026-11-23: T-19 y T-20 hechas. **Las fases 1 (backend) y 3 (frontend) están completas.**
  Faltan la fase 2 (el lector, T-11 a T-16) y la fase 4 (pruebas por etapas y salida).
