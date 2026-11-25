-- La ruta pasa a ser lo que se mide: guarda su plan y si incluye la vuelta a bodega, para
-- comparar despues contra el kilometraje real. Las rutas anteriores quedan sin plan (NULL).

-- Con la vuelta, el plan cuenta el tramo del ultimo cliente a la bodega. Sin ella, termina en
-- el ultimo cliente. Hace falta saberlo para no comparar una ruta con vuelta contra una sin.
ALTER TABLE ruta ADD COLUMN vuelve_a_bodega BOOLEAN NOT NULL DEFAULT FALSE;
-- Km del recorrido completo segun el optimizador (con la vuelta, si la hay).
ALTER TABLE ruta ADD COLUMN km_planificados DOUBLE PRECISION;
-- Cuando termina la ruta segun el plan: al salir del ultimo cliente o al llegar a bodega.
ALTER TABLE ruta ADD COLUMN termino_estimado TIMESTAMP WITH TIME ZONE;

-- Lo habitual de cada repartidor: uno vuelve a bodega al terminar y otro no. Es lo que trae
-- marcado el formulario de la ruta; lo que bodega marque al generarla queda como el nuevo valor.
ALTER TABLE repartidor ADD COLUMN vuelve_a_bodega BOOLEAN NOT NULL DEFAULT FALSE;
