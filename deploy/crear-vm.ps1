<#
.SYNOPSIS
  Crea la VM de Guias en GCP con gcloud. Se corre UNA VEZ.

.DESCRIPTION
  Los comandos de gcloud en un script versionado, en vez de hacer clics en la consola.
  No es Terraform a proposito: para un recurso creado una vez, Terraform agrega instalacion,
  cuenta de servicio y un archivo de estado que hay que cuidar, a cambio de poco. Esto deja
  el registro de como se creo la maquina, que es el 90% de lo que se pierde con la consola.
  Cuando aparezcan el bucket de respaldos, el DNS y el certificado, ahi vale la pena mirar
  Terraform de nuevo.

  Lo que hay DENTRO de la VM lo arma preparar-vm.sh, que es la parte que de verdad no se
  puede reconstruir de memoria.

.NOTES
  NO abre el puerto 8080: se decidio esperar al subdominio con HTTPS antes de exponer el
  login, que hoy viajaria en texto plano. Mientras tanto, para probar la app desplegada sin
  abrir nada, un tunel SSH desde el PC:

    gcloud compute ssh guias --zone southamerica-west1-a -- -L 8080:localhost:8080

  y abrir http://localhost:8080 en el navegador del PC. El trafico va cifrado por SSH.

.EXAMPLE
  .\deploy\crear-vm.ps1
  .\deploy\crear-vm.ps1 -Zona us-east1-d    # junto a la VM de Dashboard, mas barato
#>
param(
  # El de Calimport. No es un secreto: un id de proyecto no da acceso a nada por si solo.
  [string]$Proyecto = 'charming-hearth-503417-i0',
  [string]$Nombre = 'guias',
  # Santiago: es la region mas cercana, y la latencia la siente el repartidor en la calle.
  [string]$Zona = 'southamerica-west1-a',
  # 2 GB. La app con -Xmx512m usa ~750 MB, Postgres ~250 MB y el sistema ~350 MB: entra,
  # pero sin holgura. Por eso preparar-vm.sh agrega swap. Si el log muestra presion de
  # memoria, el cambio a e2-medium es un stop/start sin perder el disco.
  [string]$Tipo = 'e2-small',
  [int]$DiscoGB = 30
)

$ErrorActionPreference = 'Stop'
$region = $Zona -replace '-[a-z]$', ''

function Paso($texto) { Write-Host "`n>>> $texto" -ForegroundColor Cyan }

Paso "IP estatica ($region)"
# Reservada antes que la VM: una IP efimera cambia en cada stop/start y se llevaria por
# delante el registro DNS y cualquier direccion anotada.
$existe = & gcloud compute addresses list --project $Proyecto --filter="name=$Nombre-ip AND region:$region" --format="value(name)" 2>$null
if (-not $existe) {
  & gcloud compute addresses create "$Nombre-ip" --project $Proyecto --region $region
} else {
  Write-Host "    ya existe"
}
$ip = & gcloud compute addresses describe "$Nombre-ip" --project $Proyecto --region $region --format="value(address)"
Write-Host "    IP: $ip"

Paso "VM $Nombre ($Tipo, $DiscoGB GB)"
$existeVm = & gcloud compute instances list --project $Proyecto --filter="name=$Nombre" --format="value(name)" 2>$null
if ($existeVm) {
  Write-Host "    ya existe: no se toca"
} else {
  & gcloud compute instances create $Nombre `
    --project $Proyecto `
    --zone $Zona `
    --machine-type $Tipo `
    --image-family debian-12 `
    --image-project debian-cloud `
    --boot-disk-size "${DiscoGB}GB" `
    --boot-disk-type pd-balanced `
    --address $ip `
    --tags guias `
    --labels "app=guias,entorno=produccion" `
    --metadata enable-oslogin=TRUE
  if ($LASTEXITCODE -ne 0) { throw 'Fallo la creacion de la VM.' }
}

Write-Host @"

Creada. IP: $ip

Sigue, en este orden:
  1. scp deploy/preparar-vm.sh y correrlo con sudo en la VM
  2. Copiar env.ejemplo a ~/app/.env, completarlo y chmod 600
  3. Copiar guias.service a /etc/systemd/system/ y habilitarlo
  4. Desde el PC: .\deploy\desplegar.ps1 -Servidor $ip

El puerto 8080 NO esta abierto. Para probar antes del HTTPS, tunel SSH:
  gcloud compute ssh $Nombre --zone $Zona -- -L 8080:localhost:8080

Cuando exista guias.calimport.cl con certificado, recien ahi:
  gcloud compute firewall-rules create guias-https ``
    --project $Proyecto --allow tcp:443 --target-tags guias
"@ -ForegroundColor Green
