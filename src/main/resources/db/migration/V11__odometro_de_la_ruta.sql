-- El kilometraje real de la ruta, contra el que se compara km_planificados: lo que marca el
-- odometro del auto al salir y al terminar, en km enteros. Lo anota el repartidor en la app
-- (no en papel). Junto a cada lectura va cuando se anoto: es su respaldo, el que muestra si
-- se tomo a la hora o se escribio despues. NULL mientras no se anote.
ALTER TABLE ruta ADD COLUMN odometro_salida INTEGER;
ALTER TABLE ruta ADD COLUMN odometro_salida_en TIMESTAMP WITH TIME ZONE;
ALTER TABLE ruta ADD COLUMN odometro_termino INTEGER;
ALTER TABLE ruta ADD COLUMN odometro_termino_en TIMESTAMP WITH TIME ZONE;
