# Instala o Glass compilado na TV e faz dele a tela inicial.
# Compile antes no Android Studio (Build > Build APK(s)) ou com ..\launcher\gradlew.bat assembleDebug.
#   .\install.ps1                  instala o APK de debug do projeto e ativa o redirecionamento do Home
#   .\install.ps1 -Apk caminho.apk instala outro APK
#   .\install.ps1 -NoHome          só instala, sem mexer na tela inicial
param(
    [string]$Apk = (Join-Path $PSScriptRoot '..\launcher\app\build\outputs\apk\debug\app-debug.apk'),
    [switch]$NoHome
)
. "$PSScriptRoot\_common.ps1"

if (-not (Test-Path -LiteralPath $Apk)) {
    Write-Err "APK não encontrado: $Apk"
    Write-Err 'Compile o projeto launcher\ no Android Studio (Build > Build APK(s)) e rode de novo.'
    exit 1
}
Connect-Tv
Show-TvInfo

Write-Step 'Instalando o Glass'
$r = Invoke-Adb @('-s', $script:Serial, 'install', '-r', '-g', (Resolve-Path -LiteralPath $Apk).Path)
Write-Host $r
if ($r -notmatch 'Success') { Write-Err 'A instalação falhou.'; exit 1 }
# Android só mostra o nome da rede Wi-Fi para apps com permissão de localização
$null = Invoke-TvShell 'pm grant com.ivantrj.glasslauncher android.permission.ACCESS_FINE_LOCATION'

if (-not $NoHome) {
    & "$PSScriptRoot\set-home.ps1" -Launcher glass
} else {
    $null = Invoke-TvShell 'am start -n com.ivantrj.glasslauncher/.HomeActivity'
    Write-Ok 'Instalado. Para virar a tela inicial: .\set-home.ps1 -Launcher glass'
}
