<#
.SYNOPSIS
  Despliega Despachos-Guias en la VM "apps": compila, sube y reinicia el servicio.

.DESCRIPTION
  La app vive en https://apps.calimport.cl/gd/, detras del nginx que comparte con el
  Dashboard Logistica. El jar y los estaticos van a /opt/guias/.

  Todo pasa por el tunel IAP de Google: la VM no acepta SSH desde internet, asi que no
  sirven scp ni ssh a secas.

  El paso que mas falla es empaquetar con la app local corriendo: el jar queda tomado y
  el repackage deja un archivo de 0,1 MB que parece valido hasta que no arranca en la VM.
  Aca eso se detecta antes de subir nada.

.EXAMPLE
  .\deploy\desplegar.ps1
  .\deploy\desplegar.ps1 -SoloFrontend
#>
param(
  [string]$Vm = 'apps',
  [string]$Zona = 'us-east1-b',
  [switch]$SoloFrontend
)

$ErrorActionPreference = 'Stop'
$raiz = Split-Path $PSScriptRoot -Parent
$frontend = Join-Path (Split-Path $raiz -Parent) 'Frontend'
$jar = Join-Path $raiz 'target\guias-backend-0.0.1-SNAPSHOT.jar'
$iap = @('--zone', $Zona, '--tunnel-through-iap')

function Paso($texto) { Write-Host "`n>>> $texto" -ForegroundColor Cyan }
function EnLaVm($comando) { & gcloud compute ssh $Vm @iap --command $comando }

if (-not $SoloFrontend) {
  Paso 'Comprobando que no haya una app local con el jar tomado'
  $tomado = Get-CimInstance Win32_Process -Filter "Name='java.exe'" |
    Where-Object { $_.CommandLine -match 'guias-backend' }
  if ($tomado) {
    throw "Hay una instancia local corriendo (pid $($tomado.ProcessId -join ', ')). " +
          'Cerrala antes de empaquetar o el repackage deja un jar truncado.'
  }

  Paso 'Tests'
  $mh = (Get-ChildItem "$env:USERPROFILE\.m2\wrapper\dists\apache-maven-3.9.16" -Directory)[0].FullName
  $cw = (Get-ChildItem "$mh\boot\plexus-classworlds-*.jar").FullName
  $maven = @("-Dmaven.home=$mh", "-Dclassworlds.conf=$mh\bin\m2.conf",
             "-Dmaven.multiModuleProjectDirectory=$raiz", '-cp', $cw,
             'org.codehaus.plexus.classworlds.launcher.Launcher')
  Push-Location $raiz
  & java @maven -q test
  if ($LASTEXITCODE -ne 0) { Pop-Location; throw 'Los tests fallaron: no se despliega.' }

  Paso 'Empaquetando'
  & java @maven -q package -DskipTests
  Pop-Location
  if ($LASTEXITCODE -ne 0) { throw 'Fallo el empaquetado.' }

  $mb = [math]::Round((Get-Item $jar).Length / 1MB, 1)
  if ($mb -lt 50) { throw "El jar pesa $mb MB: el repackage fallo. No se sube." }
  Write-Host "    jar: $mb MB"
}

# Produccion compila con baseHref /gd/ y la API en /gd/api (angular.json y
# environment.prod.ts). Un build de desarrollo serviria la app en la raiz y las
# rutas quedarian rotas detras del prefijo.
Paso 'Compilando el frontend (configuracion de produccion)'
Push-Location $frontend
& pnpm build
Pop-Location
if ($LASTEXITCODE -ne 0) { throw 'Fallo el build del frontend.' }
$base = Select-String -Path "$frontend\dist\guias-frontend\browser\index.html" -Pattern '<base href="/gd/">'
if (-not $base) { throw 'El index no quedo con baseHref /gd/: revisar angular.json.' }

Paso 'Subiendo'
# A la carpeta del usuario primero: /opt/guias es de root y scp no puede escribir ahi.
if (-not $SoloFrontend) {
  & gcloud compute scp $jar "${Vm}:~/guias-backend.jar" @iap
  if ($LASTEXITCODE -ne 0) { throw 'Fallo la subida del jar.' }
}
& gcloud compute scp --recurse "$frontend\dist\guias-frontend\browser" "${Vm}:~/static-nuevo" @iap
if ($LASTEXITCODE -ne 0) { throw 'Fallo la subida de los estaticos.' }

Paso 'Instalando y reiniciando'
# Los estaticos se reemplazan enteros: un build deja archivos con hash nuevo y los
# viejos solo estorban. El jar se mueve con el servicio detenido.
$instalar = @'
set -e
sudo rm -rf /opt/guias/static
sudo mv ~/static-nuevo /opt/guias/static
sudo chown -R root:root /opt/guias/static
if [ -f ~/guias-backend.jar ]; then
  sudo systemctl stop guias
  sudo mv ~/guias-backend.jar /opt/guias/guias-backend.jar
  sudo chown root:root /opt/guias/guias-backend.jar
fi
sudo systemctl restart guias
sleep 8
systemctl is-active guias
'@
EnLaVm $instalar
if ($LASTEXITCODE -ne 0) { throw 'El servicio no quedo activo. Revisar: journalctl -u guias -n 50' }

Paso 'Comprobando'
EnLaVm 'curl -s -o /dev/null -w "raiz:%{http_code} " http://127.0.0.1:8080/ ; curl -s -o /dev/null -w "api-sin-token:%{http_code}\n" http://127.0.0.1:8080/api/guias'
Write-Host "`nDesplegado. Se espera raiz:200 api-sin-token:401" -ForegroundColor Green
Write-Host "Desde afuera: https://apps.calimport.cl/gd/" -ForegroundColor Green
