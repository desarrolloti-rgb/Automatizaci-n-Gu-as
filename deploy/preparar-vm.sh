#!/usr/bin/env bash
# Archivo: deploy/preparar-vm.sh
# Deja la VM lista para recibir Despachos-Guias. Se ejecuta UNA VEZ, dentro de la VM,
# desde la carpeta deploy/ subida por scp:
#
#   gcloud compute scp --recurse deploy apps:~/deploy-guias --zone us-east1-b --tunnel-through-iap
#   gcloud compute ssh apps --zone us-east1-b --tunnel-through-iap
#   cd ~/deploy-guias && sudo bash preparar-vm.sh
#
# Despues de esto, cada despliegue es solo .\deploy\desplegar.ps1 desde Windows: este
# script crea lo que ese otro da por hecho (usuario, /opt/guias, Postgres, el servicio).
#
# Es idempotente: si se corta a medias, se vuelve a correr y sigue donde quedo.
#
# NO clona el repositorio, a diferencia del instalar.sh del panel: Guias se publica
# subiendo el jar y los estaticos por scp, asi que el codigo fuente nunca vive en la VM.
set -euo pipefail

USUARIO="guias"
DESTINO="/opt/guias"
DATOS="/var/lib/guias"
BASE="guias"
DOMINIO="apps.calimport.cl"

DEPLOY_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

if [[ $EUID -ne 0 ]]; then
    echo "Ejecutar con sudo." >&2
    exit 1
fi
for archivo in guias.service env.ejemplo guias-proxy.conf nginx-apps-guias.conf; do
    [[ -f "$DEPLOY_DIR/$archivo" ]] || { echo "Falta $archivo junto a este script." >&2; exit 1; }
done

apt-get update

echo "== Swap de 2 GB =="
# La e2-small tiene 2 GB y ahi conviven la JVM (~750 MB) y Postgres (~250 MB). Sin swap,
# el kernel mata el proceso mas grande —la app— justo cuando mas memoria pide.
if ! /sbin/swapon --show | grep -q .; then
    fallocate -l 2G /swapfile
    chmod 600 /swapfile
    mkswap /swapfile
    swapon /swapfile
    grep -q '^/swapfile' /etc/fstab || echo '/swapfile none swap sw 0 0' >> /etc/fstab
fi

echo "== Java 21 =="
# Debian 13 (trixie) trae OpenJDK 21 en main, asi que no hace falta ningun repositorio
# de terceros. En Debian 12 no esta: ahi habria que usar backports o Adoptium.
# El JRE basta: en la VM no se compila nada, solo se ejecuta el jar.
if ! command -v java >/dev/null || [[ "$(java -version 2>&1 | head -1 | grep -oP '"\K[0-9]+')" -lt 21 ]]; then
    apt-get install -y openjdk-21-jre-headless
fi
java -version

echo "== PostgreSQL =="
apt-get install -y postgresql postgresql-client
systemctl enable --now postgresql

echo "== Usuario sin privilegios =="
# Sin shell y sin home propio: si alguien vulnera la app, no consigue una sesion.
id "$USUARIO" >/dev/null 2>&1 || useradd --system --home-dir "$DESTINO" --shell /usr/sbin/nologin "$USUARIO"

echo "== Directorios =="
# El codigo (jar y estaticos) es de root y la app solo lo lee: vulnerarla no permite
# reescribirla. Lo unico que escribe son las fotos, que viven fuera de $DESTINO para
# que un despliegue no las pise nunca.
mkdir -p "$DESTINO"
chown root:root "$DESTINO"
chmod 755 "$DESTINO"
mkdir -p "$DATOS/fotos" "$DATOS/respaldos"
chown -R "$USUARIO:$USUARIO" "$DATOS"
chmod 700 "$DATOS/fotos"

echo "== .env =="
ENV="$DESTINO/.env"
if [[ ! -f "$ENV" ]]; then
    cp "$DEPLOY_DIR/env.ejemplo" "$ENV"
    # La clave de Postgres la genera el script y no una persona: es de maquina a maquina,
    # nadie la escribe nunca, y asi no termina siendo "guias123".
    CLAVE="$(openssl rand -base64 24 | tr -d '/+=' | head -c 24)"
    sed -i "s|^DB_PASSWORD=.*|DB_PASSWORD=$CLAVE|" "$ENV"
    echo "   .env creado. Faltan las credenciales de SAP y JWT_SECRET."
fi
chown "$USUARIO:$USUARIO" "$ENV"
chmod 600 "$ENV"

echo "== Base de datos =="
# La clave sale del .env para que las dos no se desincronicen al volver a correr esto.
CLAVE_BD="$(grep -Po '^DB_PASSWORD=\K.*' "$ENV")"
[[ -n "$CLAVE_BD" ]] || { echo "DB_PASSWORD vacio en $ENV." >&2; exit 1; }
# Postgres escucha solo en localhost (default de Debian) y el rol no puede crear bases
# ni otros roles: lo justo para que Flyway cree las tablas de su propia base.
sudo -u postgres psql -v ON_ERROR_STOP=1 <<SQL
DO \$\$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '$USUARIO') THEN
    CREATE ROLE $USUARIO LOGIN PASSWORD '$CLAVE_BD';
  ELSE
    ALTER ROLE $USUARIO PASSWORD '$CLAVE_BD';
  END IF;
END \$\$;
SQL
sudo -u postgres psql -tAc "SELECT 1 FROM pg_database WHERE datname='$BASE'" | grep -q 1 \
    || sudo -u postgres createdb -O "$USUARIO" "$BASE"

echo "== Respaldo diario =="
# La base y las fotos son la evidencia de lo que paso en terreno: si se pierden, no hay
# forma de reconstruirlas. Catorce dias de historia, que es lo que cabe comodo en el disco.
#
# OJO: el respaldo queda en ESTA misma VM. Sirve contra un borrado accidental, no contra
# perder la maquina. Falta copiarlo a un bucket de Cloud Storage.
cat > /usr/local/bin/respaldo-guias.sh <<'RESPALDO'
#!/usr/bin/env bash
set -euo pipefail
DIA="$(date +%F)"
DESTINO="/var/lib/guias/respaldos"
sudo -u postgres pg_dump guias | gzip > "$DESTINO/guias-$DIA.sql.gz"
tar czf "$DESTINO/fotos-$DIA.tar.gz" -C /var/lib/guias fotos
find "$DESTINO" -name '*.gz' -mtime +14 -delete
RESPALDO
chmod 755 /usr/local/bin/respaldo-guias.sh

cat > /etc/systemd/system/respaldo-guias.service <<'UNIDAD'
[Unit]
Description=Respaldo diario de la base y las fotos de Guias

[Service]
Type=oneshot
ExecStart=/usr/local/bin/respaldo-guias.sh
UNIDAD

cat > /etc/systemd/system/respaldo-guias.timer <<'TIMER'
[Unit]
Description=Respaldo diario de Guias

[Timer]
# De madrugada, cuando nadie reparte. Persistent lo corre al arrancar si la VM
# estuvo apagada a esa hora.
OnCalendar=*-*-* 03:30:00
Persistent=true

[Install]
WantedBy=timers.target
TIMER

echo "== Servicio =="
cp "$DEPLOY_DIR/guias.service" /etc/systemd/system/guias.service
systemctl daemon-reload
# enable pero NO start: todavia no existe el jar. Lo sube desplegar.ps1.
systemctl enable guias
systemctl enable --now respaldo-guias.timer

echo "== nginx =="
apt-get install -y nginx certbot python3-certbot-nginx
mkdir -p /etc/nginx/snippets
cp "$DEPLOY_DIR/guias-proxy.conf" /etc/nginx/snippets/guias-proxy.conf
# El sitio solo se instala si no hay uno: cuando el panel llegue, su instalar.sh pone el
# de dos aplicaciones y este script no debe pisarselo. Tampoco se pisa la configuracion
# que ya haya tocado certbot al agregar el bloque HTTPS.
if [[ ! -f /etc/nginx/sites-available/apps ]]; then
    cp "$DEPLOY_DIR/nginx-apps-guias.conf" /etc/nginx/sites-available/apps
    echo "   sitio 'apps' instalado (solo Guias)"
else
    echo "   ya existe /etc/nginx/sites-available/apps: no se toca"
fi
ln -sf /etc/nginx/sites-available/apps /etc/nginx/sites-enabled/apps
rm -f /etc/nginx/sites-enabled/default
nginx -t
systemctl enable --now nginx
systemctl reload nginx

cat <<EOF

VM preparada. Falta, en este orden:

  1. Completar las credenciales en el .env (la de Postgres ya quedo puesta):
       sudo nano $ENV
     Obligatorias: SAP_BASE_URL, SAP_COMPANY_DB, SAP_USERNAME, SAP_PASSWORD, JWT_SECRET.
     Para el JWT:  openssl rand -base64 48

  2. Confirmar que esta VM puede hablar con SAP (su firewall filtra por IP):
       nc -zv \$(grep -Po 'SAP_BASE_URL=https?://\K[^:/]+' $ENV) 50000

  3. Desde Windows, subir la aplicacion:
       .\\deploy\\desplegar.ps1

  4. Con el DNS de $DOMINIO ya apuntando aca, si certbot no corrio todavia:
       sudo certbot --nginx -d $DOMINIO --redirect

  5. Comprobar desde afuera:
       curl -I https://$DOMINIO/gd/
       curl -o /dev/null -w '%{http_code}\\n' https://$DOMINIO/gd/api/guias   # 401 sin token
EOF
