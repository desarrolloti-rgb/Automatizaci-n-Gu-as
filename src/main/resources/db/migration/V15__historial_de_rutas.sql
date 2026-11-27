-- Historial de rutas: el recorrido de un dia queda como paso, aunque la guia vuelva a salir.
--
-- Hasta ahora una guia vivia en una sola ruta (UNIQUE (guia_id)): volver a rutearla, reasignarla
-- o reabrirla la borraba de la ruta anterior, aunque fuera de otro dia. Ahora puede estar en la
-- ruta de ayer, donde no se entrego, y en la de hoy. Tampoco se exige una sola vez por ruta:
-- rechazada, reabierta y devuelta al mismo repartidor el mismo dia, la ruta muestra los dos
-- intentos. Que el repartidor anterior no la vea en su recorrido de hoy lo siguen resolviendo
-- los borrados de RutaRepository, ahora solo de hoy en adelante y solo de paradas sin resolver.
ALTER TABLE ruta_parada DROP CONSTRAINT uq_ruta_parada_guia;
-- La restriccion era tambien el indice de la llave foranea.
CREATE INDEX ix_ruta_parada_guia_id ON ruta_parada (guia_id);

-- Lo que paso en esa parada (ENTREGADA o RECHAZADA) y cuando. Es de la parada y no de la guia:
-- si se rechaza ayer y se entrega hoy, cada ruta dice lo suyo. NULL = todavia sin resolver.
ALTER TABLE ruta_parada ADD COLUMN resultado VARCHAR(20);
ALTER TABLE ruta_parada ADD COLUMN resuelta_en TIMESTAMP WITH TIME ZONE;

-- Las rutas que ya existen: hasta hoy una guia estaba en una sola parada, asi que su estado
-- actual es el de esa parada.
UPDATE ruta_parada
   SET resultado   = (SELECT g.estado FROM guia g WHERE g.id = ruta_parada.guia_id),
       resuelta_en = (SELECT g.fecha_entrega FROM guia g WHERE g.id = ruta_parada.guia_id)
 WHERE guia_id IN (SELECT g.id FROM guia g WHERE g.estado IN ('ENTREGADA', 'RECHAZADA'));
