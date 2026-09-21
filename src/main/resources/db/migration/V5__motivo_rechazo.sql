-- Por que el cliente rechazo la guia, escrito por el repartidor en terreno.
--
-- Admite NULL: solo lo tienen las guias RECHAZADA, y las que ya se rechazaron antes de
-- esta migracion se quedan sin el (no hay forma de reconstruir lo que dijo el cliente).
-- Es evidencia igual que la foto, asi que una vez escrito no se toca.
ALTER TABLE guia ADD COLUMN motivo_rechazo VARCHAR(500);
