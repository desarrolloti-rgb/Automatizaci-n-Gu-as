-- El correo que le avisa al cliente que su despacho se entrego, con la foto adjunta
-- (CorreoDespachoService). Lo manda un proceso aparte, no la entrega: el repartidor no espera
-- ni a SAP ni al servidor de correo.

-- PENDIENTE (por enviar o reintentando), ENVIADO, SIN_CORREO (el cliente no tiene correo en
-- su ficha de SAP) o FALLIDO (se agotaron los intentos). VARCHAR como los otros enums.
--
-- NULL = no corresponde: las guias sin entregar y las entregadas antes de esta migracion. A
-- proposito no se les pone PENDIENTE: el primer arranque le escribiria a cada cliente por
-- despachos de hace semanas.
ALTER TABLE guia ADD COLUMN correo_estado VARCHAR(20);

-- A que casillas salio (sin la copia interna), para responderle a bodega "a que correo".
ALTER TABLE guia ADD COLUMN correo_destino VARCHAR(500);

-- Cuantas veces se intento. Con el tope se deja de insistir: la cuota de Resend la comparten
-- otros sistemas de Calimport.
ALTER TABLE guia ADD COLUMN correo_intentos INTEGER NOT NULL DEFAULT 0;

-- El ultimo intento; si salio, cuando salio. De aca se cuenta la espera hasta el siguiente.
ALTER TABLE guia ADD COLUMN correo_fecha TIMESTAMP WITH TIME ZONE;

-- Por que no ha salido o por que no va a salir: lo que bodega lee para corregirlo.
ALTER TABLE guia ADD COLUMN correo_motivo VARCHAR(255);
