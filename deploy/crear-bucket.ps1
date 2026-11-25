<#
.SYNOPSIS
  Crea los buckets de Guias (fotos y respaldos) y la cuenta de servicio de la VM "apps".

.DESCRIPTION
  Se corre UNA VEZ desde el PC. Es idempotente: lo que ya existe no se toca.

  Las fotos de entrega son la evidencia de lo que paso en terreno y en el disco de la VM se
  pierden con ella. Van a un bucket; el dump diario de la base, a otro.

  La VM no tenia cuenta de servicio a proposito: quien entrara a ella no heredaba permisos
  sobre el proyecto. Se le da una que SOLO puede:
    - fotos:     crear y leer objetos (no borrar ni reemplazar: la evidencia no se edita)
    - respaldos: crear y leer objetos (no borrarlos)
  Nada mas del proyecto. No hay claves JSON: la VM la usa por el servidor de metadatos.

  Asignarle la cuenta a la VM exige DETENERLA, y eso apaga tambien el Dashboard un par de
  minutos. Por eso va aparte, con -AsignarAVm, y pide confirmacion.

.EXAMPLE
  .\deploy\crear-bucket.ps1                 # buckets, cuenta y permisos
  .\deploy\crear-bucket.ps1 -AsignarAVm     # ademas, detiene la VM y se la asigna
#>
param(
  [string]$Proyecto,
  [string]$Region = 'us-east1',
  [string]$Vm = 'apps',
  [string]$Zona = 'us-east1-b',
  [switch]$AsignarAVm
)

$ErrorActionPreference = 'Stop'

function Paso($texto) { Write-Host "`n>>> $texto" -ForegroundColor Cyan }

# Igual que en desplegar.ps1: con Stop, PowerShell 5.1 convierte en error cada linea que
# gcloud escribe en stderr, aunque termine bien. Se juzga por $LASTEXITCODE.
function Nativo([scriptblock]$Bloque) {
  $previo = $ErrorActionPreference
  $ErrorActionPreference = 'Continue'
  try { & $Bloque } finally { $ErrorActionPreference = $previo }
}

function Exigir($que) {
  if ($LASTEXITCODE -ne 0) { throw "Fallo: $que" }
}

# Sin tunel IAP aca, la tilde de "Chandia" no molesta: se usa gcloud tal cual.
$gcloud = (Get-Command gcloud.cmd -ErrorAction SilentlyContinue).Source
if (-not $gcloud) { throw 'No se encontro gcloud en el PATH.' }

if (-not $Proyecto) {
  $Proyecto = (Nativo { & $gcloud config get-value project 2>$null } | Select-Object -First 1)
  if ([string]::IsNullOrWhiteSpace($Proyecto)) { throw 'Indicar -Proyecto o configurar gcloud config set project.' }
  $Proyecto = $Proyecto.Trim()
}

$bucketFotos = "$Proyecto-guias-fotos"
$bucketRespaldos = "$Proyecto-guias-respaldos"
$cuenta = "guias-vm@$Proyecto.iam.gserviceaccount.com"
$miembro = "serviceAccount:$cuenta"

Write-Host "Proyecto: $Proyecto   Region: $Region"
Write-Host "Buckets:  gs://$bucketFotos   gs://$bucketRespaldos"
Write-Host "Cuenta:   $cuenta"

function CrearBucket($nombre) {
  Nativo { & $gcloud storage buckets describe "gs://$nombre" --project $Proyecto 2>$null } | Out-Null
  if ($LASTEXITCODE -eq 0) {
    Write-Host "    gs://$nombre ya existe"
    return
  }
  # Acceso uniforme (solo IAM, sin ACL por objeto) y acceso publico bloqueado: ni por
  # error se puede dejar una foto abierta desde el bucket. El link publico lo da la app.
  # Borrado recuperable 30 dias: un borrado por error se deshace.
  Nativo { & $gcloud storage buckets create "gs://$nombre" --project $Proyecto --location $Region `
      --uniform-bucket-level-access --public-access-prevention --soft-delete-duration 30d }
  Exigir "crear gs://$nombre"
  Write-Host "    gs://$nombre creado"
}

Paso 'Buckets'
CrearBucket $bucketFotos
CrearBucket $bucketRespaldos

# Los respaldos se renuevan a diario: 90 dias alcanza para volver atras y no crece sin fin.
# Las fotos NO tienen vencimiento: son la evidencia, y pesan poco.
$ciclo = Join-Path $env:TEMP 'guias-ciclo-respaldos.json'
'{"rule":[{"action":{"type":"Delete"},"condition":{"age":90}}]}' | Set-Content -Encoding ascii $ciclo
Nativo { & $gcloud storage buckets update "gs://$bucketRespaldos" --project $Proyecto --lifecycle-file $ciclo }
Exigir 'vencimiento de los respaldos'
Remove-Item $ciclo

Paso 'Cuenta de servicio'
Nativo { & $gcloud iam service-accounts describe $cuenta --project $Proyecto 2>$null } | Out-Null
if ($LASTEXITCODE -eq 0) {
  Write-Host "    ya existe"
} else {
  Nativo { & $gcloud iam service-accounts create guias-vm --project $Proyecto `
      --display-name 'Guias (VM apps)' `
      --description 'Solo escribe y lee las fotos de Guias y sube sus respaldos. Sin permisos de proyecto.' }
  Exigir 'crear la cuenta de servicio'
  Write-Host "    creada"
}

Paso 'Permisos (solo sobre los dos buckets)'
# A nivel de bucket y no de proyecto: fuera de estos dos buckets, la cuenta no puede nada.
# Respaldos tambien lleva objectViewer: "gcloud storage cp" lee el objeto de destino antes
# de subir, y solo con objectCreator falla con 403 storage.objects.get (comprobado en la VM).
# Leer los dumps no agrega riesgo: quien entra a la VM ya tiene la base viva al lado. Borrar
# sigue sin poder.
$permisos = @(
  @($bucketFotos, 'roles/storage.objectCreator'),
  @($bucketFotos, 'roles/storage.objectViewer'),
  @($bucketRespaldos, 'roles/storage.objectCreator'),
  @($bucketRespaldos, 'roles/storage.objectViewer')
)
foreach ($p in $permisos) {
  # Una cuenta recien creada tarda en propagarse: durante unos segundos (a veces un minuto)
  # Storage responde "Service account ... does not exist". Se reintenta en vez de fallar.
  for ($intento = 1; $intento -le 8; $intento++) {
    Nativo { & $gcloud storage buckets add-iam-policy-binding "gs://$($p[0])" --project $Proyecto `
        --member $miembro --role $p[1] } | Out-Null
    if ($LASTEXITCODE -eq 0) { break }
    Write-Host "    la cuenta todavia no se propaga; reintento en 15 s ($intento/8)"
    Start-Sleep -Seconds 15
  }
  Exigir "$($p[1]) en gs://$($p[0])"
  Write-Host "    $($p[1]) en gs://$($p[0])"
}

if ($AsignarAVm) {
  Paso "Asignar la cuenta a la VM $Vm"
  $actual = Nativo { & $gcloud compute instances describe $Vm --zone $Zona --project $Proyecto `
      --format 'value(serviceAccounts[0].email)' }
  if ("$actual".Trim() -eq $cuenta) {
    Write-Host "    ya la tiene"
  } else {
    Write-Host "    Hay que DETENER ${Vm}: Guias y el Dashboard quedan fuera de linea 1-2 minutos." -ForegroundColor Yellow
    if ((Read-Host '    Escribir SI para seguir') -ne 'SI') { throw 'Cancelado: la VM no se toco.' }
    Nativo { & $gcloud compute instances stop $Vm --zone $Zona --project $Proyecto }
    Exigir 'detener la VM'
    # scope cloud-platform: el limite real lo ponen los permisos de arriba, no el scope.
    Nativo { & $gcloud compute instances set-service-account $Vm --zone $Zona --project $Proyecto `
        --service-account $cuenta --scopes cloud-platform }
    $asignada = $LASTEXITCODE
    # Se enciende pase lo que pase: una VM apagada es peor que una sin cuenta.
    Nativo { & $gcloud compute instances start $Vm --zone $Zona --project $Proyecto }
    if ($asignada -ne 0) { throw 'No se pudo asignar la cuenta (la VM se volvio a encender).' }
    Exigir 'encender la VM'
    Write-Host "    asignada; VM encendida" -ForegroundColor Green
  }
}

Paso 'Listo. Falta, dentro de la VM:'
Write-Host @"
  1. En /opt/guias/.env:
       FOTOS_ALMACEN=gcs
       FOTOS_BUCKET=$bucketFotos
       RESPALDO_BUCKET=$bucketRespaldos
  2. Copiar las fotos que ya estaban en disco (una vez; no pisa nada):
       sudo gcloud storage cp --no-clobber '/var/lib/guias/fotos/*' gs://$bucketFotos/
  3. Reinstalar el respaldo diario con la version nueva de preparar-vm.sh (es idempotente):
       subir deploy/ y correr  sudo bash preparar-vm.sh
  4. Desplegar la version con Cloud Storage (desplegar.ps1) y probar una entrega.
  5. Probar el respaldo sin esperar a las 03:30:
       sudo systemctl start respaldo-guias
     y verificarlo:
       gcloud storage ls gs://$bucketRespaldos
"@
