-- La fecha de la guia en SAP (DocDate): cuando se creo el documento. Bodega ordena la tabla
-- por ella para ver primero las mas viejas, que son las que llevan mas tiempo esperando.
--
-- DATE y no TIMESTAMP: SAP no guarda hora en DocDate (siempre medianoche).
--
-- NULL en las guias importadas antes de esta migracion. La proxima sincronizacion se la
-- pone a todas las que SAP siga devolviendo.
ALTER TABLE guia ADD COLUMN fecha_documento DATE;
