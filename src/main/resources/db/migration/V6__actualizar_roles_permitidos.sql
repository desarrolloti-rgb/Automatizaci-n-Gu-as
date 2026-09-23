-- Eliminar la restricción antigua
ALTER TABLE repartidor DROP CONSTRAINT ck_repartidor_rol;

-- Actualizar filas y default al nombre nuevo del rol
UPDATE repartidor SET rol = 'Despachador' WHERE rol = 'REPARTIDOR';
ALTER TABLE repartidor ALTER COLUMN rol SET DEFAULT 'Despachador';

-- Recrearla con los valores literales del enum Rol: Despachador y JEFE_BODEGA
ALTER TABLE repartidor ADD CONSTRAINT ck_repartidor_rol CHECK (rol IN ('Despachador', 'JEFE_BODEGA'));