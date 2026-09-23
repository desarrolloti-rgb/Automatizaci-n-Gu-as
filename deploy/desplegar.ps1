<#
.SYNOPSIS
  Despliega Despachos-Guias en la VM "apps": compila, sube y reinicia el servicio.

.DESCRIPTION
  La app vive en https://apps.calimport.cl/gd/, detras del nginx que comparte con el
  Dashboard Logistica. El jar y los estaticos van a /opt/guias/.

  Es el despliegue MANUAL, desde el PC. El objetivo es reemplazarlo por publicacion
  automatica (ver "Despliegue automatico" en CLAUDE.md); mientras tanto, este script es
  el unico camino y por eso comprueba todo lo que puede antes de tocar la VM.

  Tres cosas del entorno que ya mordieron y aca estan resueltas:

  1. Empaquetar con la app local corriendo deja el jar tomado y el repackage produce un
     archivo de 0,1 MB que parece valido hasta que no arranca en la VM.
  2. La tilde de "Chandia" en el perfil de usuario rompe el tunel IAP: gcloud no logra
     escapar el caracter no ASCII al armar el ProxyCommand. Se esquiva exponiendo el SDK
     por un enlace de directorio sin acentos.
  3. Por lo mismo, gcloud deduce mal el usuario remoto (usa el de Windows) y OS Login lo
     rechaza. Se resuelve preguntandole a OS Login cual es.

.EXAMPLE
  .\deploy\desplegar.ps1
  .\deploy\desplegar.ps1 -SoloFrontend
  .\deploy\desplegar.ps1 -SinIap        # si todavia no esta el rol iap.tunnelResourceAccessor
#>
param(
  [string]$Vm = 'apps',
  [string]$Zona = 'us-east1-b',
  [switch]$SoloFrontend,
  [switch]$SinIap
)

$ErrorActionPreference = 'Stop'
$raiz = Split-Path $PSScriptRoot -Parent
$frontend = Join-Path (Split-Path $raiz -Parent) 'Frontend'
$jar = Join-Path $raiz 'target\guias-backend-0.0.1-SNAPSHOT.jar'

function Paso($texto) { Write-Host "`n>>> $texto" -ForegroundColor Cyan }

<#
  Corre un ejecutable externo sin que PowerShell lo mate por escribir en stderr.

  Con ErrorActionPreference = Stop, Windows PowerShell 5.1 convierte CADA linea que un
  .exe manda a stderr en un error terminante, aunque el comando haya terminado bien.
  gcloud escribe avisos y barras de progreso por ahi todo el tiempo. La preferencia se
  relaja solo durante la llamada y el resultado se juzga por $LASTEXITCODE, que es lo
  unico confiable.
#>
function Nativo([scriptblock]$Bloque) {
  $previo = $ErrorActionPreference
  $ErrorActionPreference = 'Continue'
  try { & $Bloque } finally { $ErrorActionPreference = $previo }
}

# --- Entorno de gcloud ------------------------------------------------------------

<#
  Devuelve la ruta de gcloud.cmd garantizando que no tenga caracteres no ASCII.

  gcloud arma el ProxyCommand del tunel IAP concatenando las rutas de su Python y de
  gcloud.py, y no las escapa: con un perfil como "C:\Users\Maximiliano Chandia" falla con
  "Special character '\xed' couldn't be escaped for ProxyCommand". El SDK no se mueve; se
  expone por un enlace de directorio en una ruta que si es ASCII y se invoca desde ahi.
#>
function ResolverGcloud {
  $cmd = Get-Command gcloud.cmd -ErrorAction SilentlyContinue
  if (-not $cmd) { throw 'No se encontro gcloud en el PATH.' }
  $sdk = Split-Path (Split-Path $cmd.Source -Parent) -Parent   # ...\google-cloud-sdk

  if ($sdk -match '^[\x20-\x7E]+$') {
    return (Join-Path $sdk 'bin\gcloud.cmd')
  }

  $enlace = 'C:\Users\Public\gcloudsdk'
  if (-not (Test-Path (Join-Path $enlace 'bin\gcloud.cmd'))) {
    if (Test-Path $enlace) { cmd /c rmdir "$enlace" | Out-Null }
    cmd /c mklink /J "$enlace" "$sdk" | Out-Null
  }
  if (-not (Test-Path (Join-Path $enlace 'bin\gcloud.cmd'))) {
    throw "No se pudo crear el enlace $enlace hacia el SDK. Alternativa: reinstalar " +
          'gcloud en una ruta sin acentos, o usar -SinIap.'
  }
  Write-Host "    SDK expuesto en $enlace (la ruta real tiene caracteres no ASCII)"
  $python = Join-Path $enlace 'platform\bundledpython\python.exe'
  if (Test-Path $python) { $env:CLOUDSDK_PYTHON = $python }
  return (Join-Path $enlace 'bin\gcloud.cmd')
}

$gcloud = ResolverGcloud

<#
  El usuario remoto segun OS Login. gcloud lo deduce del usuario de Windows y con
  "Maximiliano Chandia" queda en algo que OS Login no reconoce; el sintoma es un
  "Remote side unexpectedly closed network connection" que no dice nada.
#>
function ResolverUsuario {
  $u = Nativo { & $gcloud compute os-login describe-profile --format='value(posixAccounts[0].username)' }
  if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($u)) { return $null }
  return ($u | Select-Object -First 1).Trim()
}

$usuario = ResolverUsuario
$destino = if ($usuario) { "$usuario@$Vm" } else { $Vm }
if ($usuario) { Write-Host "    usuario remoto: $usuario" }

# Se decide UNA vez si hay tunel IAP y todo lo demas reusa esa decision. El rol
# iap.tunnelResourceAccessor no viene con roles/editor y hay que pedirlo a un Owner; sin
# el, se entra por la IP externa (que exige una regla de firewall para esta IP).
Paso 'Probando la conexion con la VM'
$conexion = @('--zone', $Zona)
if (-not $SinIap) {
  Nativo { & $gcloud compute ssh $destino @conexion --tunnel-through-iap --quiet --command 'true' } | Out-Null
  if ($LASTEXITCODE -eq 0) {
    $conexion += '--tunnel-through-iap'
    Write-Host '    por tunel IAP'
  } else {
    Write-Host '    IAP no disponible, probando conexion directa' -ForegroundColor Yellow
  }
}
if ($conexion -notcontains '--tunnel-through-iap') {
  Nativo { & $gcloud compute ssh $destino @conexion --quiet --command 'true' } | Out-Null
  if ($LASTEXITCODE -ne 0) {
    throw @"
No se pudo entrar a la VM ni por IAP ni directo. Revisar:
  - Rol de IAP:  gcloud projects add-iam-policy-binding <proyecto> \
                   --member=user:<cuenta> --role=roles/iap.tunnelResourceAccessor
                 (lo tiene que correr un Owner del proyecto)
  - O una regla de firewall que permita tcp:22 desde tu IP con prioridad < 950.
"@
  }
  Write-Host '    conexion directa por IP externa'
}

function EnLaVm($comando) { Nativo { & $gcloud compute ssh $destino @conexion --quiet --command $comando } }

# --- Compilacion ------------------------------------------------------------------

if (-not $SoloFrontend) {
  Paso 'Comprobando que no haya una app local con el jar tomado'
  $tomado = Get-CimInstance Win32_Process -Filter "Name='java.exe'" |
    Where-Object { $_.CommandLine -match 'guias-backend' }
  if ($tomado) {
    throw "Hay una instancia local corriendo (pid $($tomado.ProcessId -join ', ')). " +
          'Cerrala antes de empaquetar o el repackage deja un jar truncado.'
  }

  Paso 'Tests'
  # El wrapper de Maven falla con la tilde en la ruta (parte el classpath): se lanza
  # Maven directo desde la distribucion que el wrapper ya bajo.
  $mh = (Get-ChildItem "$env:USERPROFILE\.m2\wrapper\dists\apache-maven-3.9.16" -Directory)[0].FullName
  $cw = (Get-ChildItem "$mh\boot\plexus-classworlds-*.jar").FullName
  $maven = @("-Dmaven.home=$mh", "-Dclassworlds.conf=$mh\bin\m2.conf",
             "-Dmaven.multiModuleProjectDirectory=$raiz", '-cp', $cw,
             'org.codehaus.plexus.classworlds.launcher.Launcher')
  Push-Location $raiz
  Nativo { & java @maven -q test }
  if ($LASTEXITCODE -ne 0) { Pop-Location; throw 'Los tests fallaron: no se despliega.' }

  Paso 'Empaquetando'
  Nativo { & java @maven -q package -DskipTests }
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
Nativo { & pnpm build }
Pop-Location
if ($LASTEXITCODE -ne 0) { throw 'Fallo el build del frontend.' }
$base = Select-String -Path "$frontend\dist\guias-frontend\browser\index.html" -Pattern '<base href="/gd/">'
if (-not $base) { throw 'El index no quedo con baseHref /gd/: revisar angular.json.' }

# --- Subida -----------------------------------------------------------------------

Paso 'Subiendo'
# A la carpeta del usuario primero: /opt/guias es de root y scp no puede escribir ahi.
#
# El destino va SIN "~/": en Windows gcloud usa pscp (PuTTY), que no expande la virgulilla
# y falla con "unable to create directory ~/...". Una ruta relativa ya cuelga del home.
if (-not $SoloFrontend) {
  Nativo { & $gcloud compute scp $jar "${destino}:guias-backend.jar" @conexion --quiet }
  if ($LASTEXITCODE -ne 0) { throw 'Fallo la subida del jar.' }
}
# El directorio destino se crea antes: pscp con --recurse no lo crea y falla con un
# "unable to create directory" que no dice cual. Queda como subida/browser.
EnLaVm 'rm -rf subida; mkdir -p subida'
if ($LASTEXITCODE -ne 0) { throw 'No se pudo preparar el directorio de subida en la VM.' }
Nativo { & $gcloud compute scp --recurse "$frontend\dist\guias-frontend\browser" "${destino}:subida" @conexion --quiet }
if ($LASTEXITCODE -ne 0) { throw 'Fallo la subida de los estaticos.' }

Paso 'Instalando y reiniciando'
# Los estaticos se reemplazan enteros: un build deja archivos con hash nuevo y los
# viejos solo estorban. El jar se mueve con el servicio detenido.
#
# En UNA linea y sin comillas dobles a proposito: plink no respeta los saltos de linea de
# un --command multilinea (parte el script y ejecuta pedazos sueltos), y las comillas se
# pierden entre PowerShell, cmd y plink antes de llegar a bash.
$instalar = 'set -e; sudo rm -rf /opt/guias/static; sudo mv ~/subida/browser /opt/guias/static; ' +
            'rmdir ~/subida; sudo chown -R root:root /opt/guias/static; ' +
            'if [ -f ~/guias-backend.jar ]; then sudo systemctl stop guias; ' +
            'sudo mv ~/guias-backend.jar /opt/guias/guias-backend.jar; ' +
            'sudo chown root:root /opt/guias/guias-backend.jar; fi; ' +
            'sudo systemctl restart guias; sleep 8; systemctl is-active guias'
EnLaVm $instalar
if ($LASTEXITCODE -ne 0) { throw 'El servicio no quedo activo. Revisar: journalctl -u guias -n 50' }

Paso 'Comprobando'
# Se espera a que responda en vez de comprobar una sola vez: en la e2-small, Flyway mas el
# arranque de Spring tardan ~20 s y una comprobacion inmediata daba un falso negativo
# (000) con el servicio perfectamente sano.
EnLaVm 'for i in $(seq 1 20); do curl -s -o /dev/null http://127.0.0.1:8080/ && break; sleep 3; done; curl -s -o /dev/null -w raiz:%{http_code}\n http://127.0.0.1:8080/; curl -s -o /dev/null -w api-sin-token:%{http_code}\n http://127.0.0.1:8080/api/guias'
Write-Host "`nDesplegado. Se espera raiz:200 api-sin-token:401" -ForegroundColor Green
Write-Host "Desde afuera: https://apps.calimport.cl/gd/" -ForegroundColor Green
