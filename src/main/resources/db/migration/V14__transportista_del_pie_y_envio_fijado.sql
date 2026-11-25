-- Lo que el pie de SAP dice del transportista ("TRANSPORTE: SAMEX"), tal cual lo leyo el parser,
-- como direccion_footer y horario_footer. Es solo una pista para bodega ("Pie: SAMEX"): quien
-- decide es la columna transportista, que elige bodega en la app. Se refresca en cada sincronizacion.
ALTER TABLE guia ADD COLUMN transportista_footer VARCHAR(50);

-- Bodega fijo a mano el numero de envio de la guia, porque el folio no quedo bien escrito en el
-- portal del transportista. Con esto el lector busca por ese numero y deja de exigir el folio
-- entre las referencias del envio: lo garantizo una persona mirando el portal.
ALTER TABLE envio_externo ADD COLUMN fijado_a_mano BOOLEAN NOT NULL DEFAULT FALSE;
