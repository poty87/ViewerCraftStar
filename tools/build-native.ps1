# Compila il motore di sviluppo (native/vcs-develop, LightCraft in Rust) per i telefoni a 64 bit e lo mette in
# app/src/main/jniLibs/arm64-v8a. Serve: Rust (rustup) con il target aarch64-linux-android, cargo-ndk e l'Android NDK.
#   rustup target add aarch64-linux-android ; cargo install cargo-ndk --locked
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$ndk = Get-ChildItem "$env:LOCALAPPDATA\Android\Sdk\ndk" -Directory | Sort-Object Name | Select-Object -Last 1
if (-not $ndk) { throw "Android NDK non trovato in $env:LOCALAPPDATA\Android\Sdk\ndk" }
$env:ANDROID_NDK_HOME = $ndk.FullName
$env:PATH = "$env:USERPROFILE\.cargo\bin;$env:PATH"
Push-Location (Join-Path $root "native\vcs-develop")
try {
    cargo ndk -t arm64-v8a -P 33 -o (Join-Path $root "app\src\main\jniLibs") build --release
    if ($LASTEXITCODE -ne 0) { throw "compilazione del motore non riuscita" }
} finally { Pop-Location }
Get-Item (Join-Path $root "app\src\main\jniLibs\arm64-v8a\libvcs_develop.so") | Select-Object Name, Length
