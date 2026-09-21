<#
.SYNOPSIS
  Despliega Guias a la VM: compila, sube el jar y los estaticos, y reinicia el servicio.

.DESCRIPTION
  Mismo flujo que Dashboard, pero en un script en vez de a mano. La diferencia importa:
  el paso que mas falla es empaquetar con la app local corriendo, porque el jar queda
  tomado y el repackage deja un archivo de 0,1 MB que parece valido hasta que no arranca
  en la VM. Aca eso se detecta antes de subir nada.

  El frontend NO viaja dentro del jar: se copia aparte a static/, igual que en Dashboard,
  para poder actualizar la pantalla sin recompilar el backend.

.EXAMPLE
  .\deploy\desplegar.ps1 -Servidor 34.x.x.x -Usuario usuario
  .\deploy\desplegar.ps1 -Servidor 34.x.x.x -Usuario usuario -SoloFrontend

.NOTES
  El parametro es -Servidor y no -Host a proposito: $Host es una variable automatica de
  PowerShell (la consola) y declararla como parametro revienta el script.
#>
param(
  [Parameter(Mandatory = $true)][string]$Servidor,
  [string]$Usuario = 'usuario',
  [string]$Llave,
  [switch]$SoloFrontend
)

$ErrorActionPreference = 'Stop'
$raiz = Split-Path $PSScriptRoot -Parent
$frontend = Join-Path (Split-Path $raiz -Parent) 'Frontend'
$jar = Join-Path $raiz 'target\guias-backend-0.0.1-SNAPSHOT.jar'
$destino = "${Usuario}@${Servidor}"
$scp = if ($Llave) { @('-i', $Llave) } else { @() }

function Paso($texto) { Write-Host "`n>>> $texto" -ForegroundColor Cyan }

if (-not $SoloFrontend) {
  Paso 'Comprobando que no haya una app local con el jar tomado'
  $tomado = Get-CimInstance Win32_Process -Filter "Name='java.exe'" |
    Where-Object { $_.CommandLine -match 'guias-backend' }
  if ($tomado) {
    throw "Hay una instancia local corriendo (pid $($tomado.ProcessId -join ', ')). " +
          "Cerrala antes de empaquetar o el repackage deja un jar truncado."
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

  # El jar completo ronda los 68 MB. Si sale de ~0,1 MB el repackage fallo y la VM
  # recibiria un archivo que no arranca.
  $mb = [math]::Round((Get-Item $jar).Length / 1MB, 1)
  if ($mb -lt 50) { throw "El jar pesa $mb MB: el repackage fallo. No se sube." }
  Write-Host "    jar: $mb MB"
}

Paso 'Compilando el frontend'
Push-Location $frontend
& pnpm build
Pop-Location
if ($LASTEXITCODE -ne 0) { throw 'Fallo el build del frontend.' }

Paso 'Subiendo'
if (-not $SoloFrontend) {
  & scp @scp $jar "${destino}:/home/$Usuario/app/"
  if ($LASTEXITCODE -ne 0) { throw 'Fallo el scp del jar.' }
}
# El * y no la carpeta: se reemplaza el contenido, no se anida static/browser dentro.
& scp @scp -r "$frontend\dist\guias-frontend\browser\*" "${destino}:/home/$Usuario/app/static/"
if ($LASTEXITCODE -ne 0) { throw 'Fallo el scp de los estaticos.' }

if ($SoloFrontend) {
  Write-Host "`nListo. Los estaticos se leen del disco en cada request: no hace falta reiniciar." -ForegroundColor Green
  return
}

Paso 'Reiniciando el servicio'
& ssh @scp $destino 'sudo systemctl restart guias && sleep 8 && systemctl is-active guias'
if ($LASTEXITCODE -ne 0) { throw 'El servicio no quedo activo. Revisar: journalctl -u guias -n 50' }

Paso 'Comprobando'
& ssh @scp $destino 'curl -s -o /dev/null -w "raiz:%{http_code} " http://localhost:8080/ && curl -s -o /dev/null -w "api-sin-token:%{http_code}\n" http://localhost:8080/api/guias'
Write-Host "`nDesplegado. Se espera raiz:200 api-sin-token:401" -ForegroundColor Green
