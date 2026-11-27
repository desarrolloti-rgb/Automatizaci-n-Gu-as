# Plan: Historial de rutas y KPIs de rutas

Estado: en curso
Fecha: 2026-11-27 · Responsable: Max (desarrollo TI)

## Objetivo y criterio de éxito

Bodega mide **rutas, no choferes** (ver "La ruta es lo que se mide" en CLAUDE.md): necesita ver
qué rutas están en curso, cuáles terminaron y, de cada ruta ya hecha, su recorrido y lo que marcó
el odómetro. Hoy cada ruta se guarda, pero solo se puede consultar de a una (repartidor + fecha) y
el recorrido de los días anteriores se va perdiendo cuando una guía se vuelve a rutear.

Éxito: en la pestaña Rutas, el jefe de bodega ve los KPIs de rutas del día y una tabla con todas
las rutas de un rango de fechas; abre cualquiera y ve sus paradas en orden con el resultado de
cada una, km planificados, odómetro de salida y de término y km recorridos. Una guía que se
vuelve a rutear otro día aparece en las dos rutas. En `/gd/` y en `/gd-prueba/`.

## Alcance

- Incluye:
  - En la vista Rutas, quitar la tira de KPIs de guías (Sin asignar / Asignadas / Entregadas /
    Rechazadas). En la vista Guías se queda.
  - KPIs de rutas del día elegido.
  - Historial de rutas con filtros y detalle de cada una.
  - Que el recorrido de una ruta pasada no cambie al volver a rutear, reasignar o reabrir una guía.
  - Despliegue en prueba y luego en producción.
- No incluye:
  - Cambios al formulario de asignación ni a `POST /api/rutas`: **quedan exactamente como están**.
  - Cambios a la app del repartidor (el odómetro se sigue anotando igual).
  - Exportar a Excel o gráficos (se puede sumar después).
  - Recuperar las rutas borradas hoy de producción: el historial parte desde ahora.

## Contexto técnico

- Backend: este repo (Spring Boot 4, Flyway, H2 en tests). Tests con el comando de "Comandos" en
  CLAUDE.md (desvío por la tilde).
- Frontend: `../Frontend` (Angular). La vista Rutas es `features/bodega/rutas-bodega.component.ts`;
  la tira de KPIs vive en `features/bodega/bodega.component.ts` (líneas ~85-115) y se ve en las
  dos vistas. El detalle de una ruta ya existe: `ruta-resultado.component.ts`.
- Ojo, estado del Frontend: rama `feat/pwa-sesion-comun-samex`, 3 commits detrás de su remoto,
  con `CLAUDE.md` y `ruta.model.ts` modificados sin commitear. Hay que ordenarlo antes (T-00).
- Despliegue: `deploy/desplegar.ps1` (producción) y `deploy/desplegar.ps1 -Prueba`.

## Lo que ya existe (hechos)

| Dato | Dónde | Desde |
|---|---|---|
| Ruta por repartidor y día (`UNIQUE (repartidor_id, fecha)`) | `ruta` | V3 |
| Paradas en orden, con llegada estimada | `ruta_parada` | V3 |
| km planificados, término estimado, vuelve a bodega | `ruta` | V10 |
| Odómetro de salida y término, con la hora en que se anotó | `ruta` | V11 |
| Regenerar una ruta replanifica la misma fila y conserva el odómetro | `Ruta.planificar` | V10 |

Lo que falta: listar rutas, el estado de cada una, el resultado de cada parada y que el pasado no
se reescriba.

## Por qué hoy se pierde el historial

`ruta_parada` tiene `UNIQUE (guia_id)`: una guía vive en una sola ruta. Por eso:

- `RutaRepository.borrarParadasPrevias` (al generar una ruta) borra las paradas de esas guías **en
  cualquier ruta**, también en la de ayer.
- `RutaRepository.quitarGuiaDeRutas` (al reasignar, marcar transportista o reabrir) también las
  borra de todas.

Una guía que ayer no se pudo entregar y hoy sale de nuevo desaparece del recorrido de ayer.

## Decisiones

### D-01 El estado de una ruta se calcula, no se guarda
Opciones: columna `estado` actualizada por eventos · calcularlo al leer. **Elegida: calcularlo**,
igual que `P`/`T` se derivan de `recibidaPorRepartidor`: una segunda copia del mismo hecho se
desincroniza. Sale de la fecha, el odómetro y el resultado de las paradas:

| Estado | Regla |
|---|---|
| `PLANIFICADA` | Sin odómetro de salida y sin paradas resueltas |
| `EN_CURSO` | Con odómetro de salida o alguna parada resuelta, y le falta algo para completarse |
| `COMPLETADA` | Todas las paradas resueltas (entregada o rechazada) y, **si vuelve a bodega**, odómetro de término anotado |
| `CON_PENDIENTES` | Fecha ya pasada (zona `rutas.zona-horaria`) y no completada |

### D-02 Cada parada guarda su propio resultado
Opciones: mostrar el estado actual de la guía · guardar el resultado en la parada.
**Elegida: en la parada** (`resultado`, `resuelta_en`). Si una guía rechazada ayer se reabre y hoy
se entrega, la ruta de ayer tiene que seguir diciendo "rechazada" y la de hoy "entregada". Se
escribe al entregar o rechazar, en la parada de la ruta más reciente de esa guía.

### D-03 El pasado no se toca
Se reemplaza `UNIQUE (guia_id)` por `UNIQUE (ruta_id, guia_id)`. `borrarParadasPrevias` y
`quitarGuiaDeRutas` solo borran de rutas de **hoy en adelante** que no tengan esa parada resuelta.
La regla que protegían (que el repartidor anterior no vea la guía en su recorrido de hoy) se
mantiene: la app del repartidor solo muestra la ruta de hoy.

### D-04 Un endpoint de historial, el detalle se reusa
`GET /api/rutas/historial?desde=&hasta=&repartidorId=` (solo `JEFE_BODEGA`, rango máximo 93 días)
devuelve un resumen por ruta: repartidor, fecha, estado, paradas (total, entregadas, rechazadas,
pendientes), km planificados, odómetro salida/término y km recorridos. El detalle se abre con el
`GET /api/rutas?repartidorId=&fecha=` que ya existe, sumando `resultado` a cada parada. Los KPIs
del día se cuentan en el frontend con ese mismo historial (desde = hasta = el día elegido).

## Supuestos (valores por defecto, a confirmar)

- S-1 Completada = todas las paradas resueltas, más el odómetro de término solo si vuelve a bodega.
- S-2 Los KPIs son del día elegido (hoy por defecto); la tabla del historial usa un rango (por
  defecto, los últimos 7 días).
- S-3 La tira de KPIs de guías desaparece solo en la vista Rutas; en Guías se queda.
- S-4 Una ruta regenerada el mismo día sigue siendo una sola ruta (la misma fila, como hoy).

## Preguntas abiertas

Ver S-1 a S-4: si todas valen, no hay preguntas.

## Riesgos

| Riesgo | Prob. | Impacto | Mitigación |
|---|---|---|---|
| La migración V15 corre en producción y prueba al desplegar | Media | Alto | Solo cambia una restricción y suma columnas nulas; respaldo antes; probarla primero en `/gd-prueba/` |
| Al dejar de borrar en rutas pasadas, una guía aparece dos veces en algún listado que asumía una | Media | Medio | Revisar los usos de `ruta_parada` por guía (`findBy…Guia…`) en T-01 y cubrirlos con test |
| Frontend con cambios sin commitear en otra rama | Alta | Medio | T-00 antes de tocarlo |
| Rutas viejas de prueba sin resultado por parada | Alta | Bajo | V15 lo rellena desde el estado actual de la guía; las de producción se borraron hoy |

## Tareas

### Fase 0: Preparación
- [ ] T-00 Ordenar el Frontend · S · Depende de: —
  - Qué: decidir con el usuario qué hacer con la rama `feat/pwa-sesion-comun-samex` (3 commits
    detrás) y los dos archivos sin commitear; dejar una rama limpia desde lo último publicado.
  - Criterios: `git status` limpio en una rama nueva para este trabajo; `pnpm test` en verde.

### Fase 1: Backend
- [x] T-01 Historial que no se reescribe · M · Depende de: —
  - Qué: migración `V15__historial_de_rutas.sql` (cambia la restricción, agrega `resultado` y
    `resuelta_en` a `ruta_parada` y los rellena desde la guía); `borrarParadasPrevias` y
    `quitarGuiaDeRutas` solo en rutas de hoy en adelante y sin resolver.
  - Archivos: `db/migration`, `model/RutaParada`, `repository/RutaRepository`, `RutaRepositoryTest`.
  - Criterios:
    - [ ] Test: guía en la ruta de ayer, se rutea hoy → está en las dos.
    - [ ] Test: reasignar una guía la saca de la ruta de hoy y no de la de ayer.
    - [ ] La app arranca con `ddl-auto=validate`.
- [x] T-02 Resultado en la parada · S · Depende de: T-01
  - Qué: `GuiaService.entregar` y `rechazar` escriben `resultado` y `resuelta_en` en la parada de
    la ruta más reciente de la guía (con UPDATE parcial, como `guardarCorreo`).
  - Criterios:
    - [ ] Test: entregar marca la parada de hoy; una guía sin ruta se entrega igual.
    - [ ] Test: rechazada ayer, reabierta y entregada hoy → ayer RECHAZADA, hoy ENTREGADA.
- [x] T-03 Estado de la ruta e historial · M · Depende de: T-02
  - Qué: `EstadoRuta` calculado (D-01); `GET /api/rutas/historial` con `@PreAuthorize`; `resultado`
    en `RutaResponse.Parada`.
  - Criterios:
    - [ ] Test unitario de cada fila de la tabla de D-01, con y sin vuelta a bodega.
    - [ ] Test de controller: jefe 200, repartidor 403, rango > 93 días o desde > hasta → 400.
    - [ ] `ArquitecturaTest` sigue en verde.

### Fase 2: Frontend
- [ ] T-04 KPIs de rutas en la vista Rutas · S · Depende de: T-03, T-00
  - Qué: en `bodega.component.ts`, la tira de guías solo con `vista() === 'guias'`; en
    `rutas-bodega` una tira nueva (Planificadas, En curso, Completadas, Con pendientes y km
    recorridos del día). El formulario de asignación no se toca.
  - Criterios: test del cálculo de KPIs; el formulario y "Generar ruta" funcionan igual.
- [ ] T-05 Historial y detalle · M · Depende de: T-04
  - Qué: tabla con filtros (desde, hasta, repartidor): fecha, repartidor, estado, paradas
    resueltas/total, km planificados, odómetro salida → término, km recorridos. Al abrir una fila
    se muestra `app-ruta-resultado` con el resultado de cada parada.
  - Criterios: tests del servicio y del armado de filas; se ve bien en celular.

### Fase 3: Salida
- [ ] T-06 Prueba · S · Depende de: T-05
  - Qué: respaldo de `guias_prueba`, `desplegar.ps1 -Prueba`, recorrer: generar ruta, anotar
    odómetro, entregar y rechazar, ver KPIs e historial.
- [ ] T-07 Producción · S · Depende de: T-06 y visto bueno
  - Qué: respaldo de `guias`, `desplegar.ps1`, verificar que V15 corrió y que la pestaña carga.

## Registro de cambios del plan

- 2026-11-27: borrador.
- 2026-11-27: aprobado. T-01 sin restricción UNIQUE por ruta (no `UNIQUE (ruta_id, guia_id)` como decía D-03): rechazada, reabierta y devuelta al mismo repartidor el mismo día, la guía sale dos veces en la ruta. Se trabaja en el worktree `../backend-historial` porque `backend` tiene cambios sin commitear de otra tarea.
