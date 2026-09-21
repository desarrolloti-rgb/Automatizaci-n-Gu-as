#!/usr/bin/env bash
# Preparacion de la VM. Se corre UNA VEZ, con sudo, sobre Debian/Ubuntu recien creada.
# Despues de esto, cada despliegue es solo desplegar.ps1 desde el PC.
#
#   sudo bash preparar-vm.sh
#
# No toca SAP ni la app: deja el sistema listo para recibirlas.

set -euo pipefail

USUARIO="${SUDO_USER:-usuario}"
APP_DIR="/home/${USUARIO}/app"
FOTOS_DIR="/var/lib/guias/fotos"
DB_NOMBRE="guias"
DB_USUARIO="guias"

echo ">>> Java 21 y Postgres"
apt-get update -qq
apt-get install -y openjdk-21-jre-headless postgresql postgresql-contrib

echo ">>> Base de datos"
# La BASE se crea aca; las TABLAS las crea Flyway en cada arranque de la app.
# Idempotente: correr el script dos veces no rompe nada.
sudo -u postgres psql -tc "SELECT 1 FROM pg_roles WHERE rolname='${DB_USUARIO}'" | grep -q 1 || {
  echo "    creando el usuario ${DB_USUARIO} (te va a pedir la contraseña)"
  sudo -u postgres createuser --pwprompt "${DB_USUARIO}"
}
sudo -u postgres psql -tc "SELECT 1 FROM pg_database WHERE datname='${DB_NOMBRE}'" | grep -q 1 || {
  sudo -u postgres createdb -O "${DB_USUARIO}" "${DB_NOMBRE}"
  echo "    base ${DB_NOMBRE} creada"
}

# Postgres solo escucha en localhost: la app vive en esta misma maquina y no hay razon
# para exponer el 5432 a la red.
sudo -u postgres psql -c "ALTER SYSTEM SET listen_addresses = 'localhost'"
systemctl restart postgresql

echo ">>> Carpetas"
mkdir -p "${APP_DIR}/static" "${FOTOS_DIR}"
chown -R "${USUARIO}:${USUARIO}" "${APP_DIR}" "${FOTOS_DIR}"
# Las fotos son evidencia de entrega: fuera del directorio de la app, para que un
# despliegue no las pise nunca.
chmod 750 "${FOTOS_DIR}"

echo ">>> Respaldo diario de la base y de las fotos"
cat > /etc/cron.daily/respaldo-guias <<'CRON'
#!/usr/bin/env bash
# Respaldo local con 14 dias de historia. Es el minimo: las fotos y los rechazos son la
# prueba de lo que paso en terreno y no se pueden reconstruir.
# PENDIENTE: copiar tambien a un bucket de Cloud Storage; un respaldo en la misma VM no
# sirve de nada si se pierde la VM.
set -euo pipefail
DESTINO=/var/backups/guias
mkdir -p "$DESTINO"
FECHA=$(date +%F)
sudo -u postgres pg_dump guias | gzip > "$DESTINO/guias-$FECHA.sql.gz"
tar czf "$DESTINO/fotos-$FECHA.tar.gz" -C /var/lib/guias fotos
find "$DESTINO" -name '*.gz' -mtime +14 -delete
CRON
chmod +x /etc/cron.daily/respaldo-guias

echo
echo "Listo. Falta, en este orden:"
echo "  1. Copiar deploy/env.ejemplo a ${APP_DIR}/.env y completarlo"
echo "     chmod 600 ${APP_DIR}/.env"
echo "  2. Copiar deploy/guias.service a /etc/systemd/system/ y:"
echo "     systemctl daemon-reload && systemctl enable --now guias"
echo "  3. Abrir el puerto 8080 en el firewall de GCP (no en el de la VM)"
echo "  4. Desde el PC: desplegar.ps1"
