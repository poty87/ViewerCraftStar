# Crea la chiave di firma delle release di ViewerCraftStar e il file keystore.properties letto da Gradle.
# Entrambi restano fuori da git (.gitignore). La password la scegli tu: conservane una copia al sicuro insieme al file
# della chiave, perché senza di loro gli aggiornamenti non si installano sopra la versione già presente sul telefono.
# Uso:  powershell -ExecutionPolicy Bypass -File tools\make-keystore.ps1
$ErrorActionPreference = 'Stop'
Set-Location (Split-Path $PSScriptRoot)

if (Test-Path viewercraftstar.keystore) {
    Write-Host "viewercraftstar.keystore esiste già: non lo sovrascrivo." -ForegroundColor Yellow
    exit 1
}
$keytool = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin\keytool.exe' } else { 'keytool' }

function Plain($secure) {
    $b = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
    try { [Runtime.InteropServices.Marshal]::PtrToStringBSTR($b) } finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($b) }
}

while ($true) {
    $p1 = Plain (Read-Host 'Scegli la password della chiave (almeno 6 caratteri)' -AsSecureString)
    $p2 = Plain (Read-Host 'Ripeti la password' -AsSecureString)
    if ($p1.Length -lt 6) { Write-Host 'Troppo corta.' -ForegroundColor Yellow; continue }
    if ($p1 -ne $p2) { Write-Host 'Le due password non coincidono.' -ForegroundColor Yellow; continue }
    break
}

& $keytool -genkeypair -keystore viewercraftstar.keystore -alias viewercraftstar -keyalg RSA -keysize 4096 `
    -validity 36500 -storepass $p1 -keypass $p1 -dname 'CN=poty87, O=poty87' | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'keytool non è riuscito a creare la chiave.' }

# nei file .properties la barra rovesciata va raddoppiata
$escaped = $p1.Replace('\', '\\')
@(
    'storeFile=viewercraftstar.keystore'
    "storePassword=$escaped"
    'keyAlias=viewercraftstar'
    "keyPassword=$escaped"
) | Set-Content keystore.properties -Encoding ascii

Write-Host ''
Write-Host 'Chiave creata: viewercraftstar.keystore e keystore.properties (esclusi da git).' -ForegroundColor Green
Write-Host 'Conserva una copia della chiave e della password fuori da questo PC (per esempio in un password manager).'
