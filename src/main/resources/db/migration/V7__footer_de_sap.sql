-- El pie del documento de SAP (ClosingRemarks; T0.[Footer] en una consulta) y lo que se
-- saco de el.
--
-- Ahi el vendedor escribe a donde va de verdad el camion y a que hora reciben. La
-- direccion de la pestana Logistica sale de la ficha del cliente y puede estar vieja, asi
-- que no se le cree sola: se confirma contra esto.
ALTER TABLE guia ADD COLUMN footer VARCHAR(2000);

-- Lo que el parser saco del pie, tal cual, sin corregir. Se guarda aparte de la direccion
-- y el horario que se usan porque es el respaldo de por que quedaron asi: si bodega los
-- corrige, esto sigue diciendo de donde venian.
ALTER TABLE guia ADD COLUMN direccion_footer VARCHAR(255);
ALTER TABLE guia ADD COLUMN horario_footer   VARCHAR(255);

-- De donde salio la direccion con la que se rutea: BODEGA, FOOTER o LOGISTICA. VARCHAR y
-- no un entero, igual que los otros enums, para que reordenarlos no corrompa las filas.
--
-- NULL en las guias importadas antes de esta migracion: no hay forma de saber de donde
-- salio su direccion, y decir "LOGISTICA" seria inventarlo. La proxima sincronizacion se
-- lo pone a las que sigan PENDIENTE.
ALTER TABLE guia ADD COLUMN origen_direccion VARCHAR(20);

-- Cuando la evidencia llego a SAP: el PATCH que escribe U_UrlFoto y cierra el ciclo.
--
-- Es el momento en que la guia queda despachada para el resto de la empresa. Antes de eso
-- la entrega existe solo en esta app: afuera nadie la ve. Se escribe una sola vez, cuando
-- el envio resulta; un reintento no cambia cuando llego.
ALTER TABLE guia ADD COLUMN fecha_foto_en_sap TIMESTAMP WITH TIME ZONE;

-- El comentario ahora junta el pie con Comments y 500 se queda corto. Pasarse de largo no
-- daba un comentario recortado: daba una excepcion que tumbaba la sincronizacion entera.
ALTER TABLE guia ALTER COLUMN comentario SET DATA TYPE VARCHAR(2000);
