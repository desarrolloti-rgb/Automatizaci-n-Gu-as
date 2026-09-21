-- Rol de quien inicia sesion. La tabla repartidor pasa a guardar a todos los usuarios de
-- la app, incluido el jefe de bodega. Los que ya existen son repartidores: es lo unico
-- que emitia el login hasta ahora.
ALTER TABLE repartidor ADD COLUMN rol VARCHAR(20) NOT NULL DEFAULT 'REPARTIDOR';
ALTER TABLE repartidor ADD CONSTRAINT ck_repartidor_rol CHECK (rol IN ('REPARTIDOR', 'JEFE_BODEGA'));
