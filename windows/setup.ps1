# Configuração única: garante o adb, pergunta o IP da TV e testa a conexão.
# Pode rodar de novo quando a TV mudar de IP.
#   .\setup.ps1
. "$PSScriptRoot\_common.ps1"

Write-Step 'adb (Android Platform Tools)'
$adb = Find-Adb
if ($adb) {
    Write-Ok "Encontrado: $adb"
} else {
    Write-Host 'O adb não foi encontrado neste PC.'
    $ans = Read-Host 'Baixar o Platform Tools oficial do Google (dl.google.com, ~7 MB) para esta pasta? [S/n]'
    if ($ans -and $ans -notmatch '^[sSyY]') {
        Write-Err 'Sem adb não dá para continuar. Instale o Platform Tools e rode .\setup.ps1 de novo.'
        exit 1
    }
    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
    $zip = Join-Path $env:TEMP 'platform-tools-latest-windows.zip'
    $ProgressPreference = 'SilentlyContinue'
    Invoke-WebRequest -UseBasicParsing -Uri 'https://dl.google.com/android/repository/platform-tools-latest-windows.zip' -OutFile $zip
    Expand-Archive -LiteralPath $zip -DestinationPath $Root -Force
    Remove-Item -LiteralPath $zip -Force
    $adb = Find-Adb
    if (-not $adb) { Write-Err 'Download concluído mas adb.exe não apareceu em .\platform-tools.'; exit 1 }
    Write-Ok "Instalado em: $adb"
}

Write-Step 'IP da TV'
Write-Host 'Na TV: Configurações > Minha Fire TV > Sobre > Rede. O IP parece com 192.168.1.23.'
$cur = Get-TvIp
while ($true) {
    if ($cur) { $ip = Read-Host "Digite o IP e Enter [$cur]" } else { $ip = Read-Host 'Digite o IP e Enter' }
    if (-not $ip) { $ip = $cur }
    $ip = ("$ip" -replace ',', '.' -replace '\s', '')
    if ($ip -match '^\d{1,3}(\.\d{1,3}){3}(:\d+)?$') { break }
    Write-Warn 'Isso não parece um IP (quatro números separados por ponto, ex.: 192.168.1.23). Tente de novo.'
}
Set-Content -LiteralPath $IpFile -Value $ip
Write-Ok 'Salvo.'

Write-Step 'Conectando na TV'
Write-Host 'Olhe a TV: se aparecer "Permitir depuração USB?", marque "Sempre permitir" e escolha OK.'
Connect-Tv
Write-Ok "Conectado em $($script:Serial)"
Show-TvInfo

Write-Step 'Launchers instalados'
$installed = Get-TvPackages
foreach ($k in $KnownLaunchers.Keys) {
    $p = $KnownLaunchers[$k]
    if ($installed.Contains($p)) { Write-Ok "  $k ($p): instalado" } else { Write-Host "  $k ($p): não instalado" }
}

Write-Host ''
Write-Ok 'Tudo pronto. Próximos passos:'
Write-Host '  .\debloat.ps1 -DryRun        (só mostra o que seria desativado)'
Write-Host '  .\debloat.ps1'
Write-Host '  .\install.ps1                 (depois de compilar o Glass no Android Studio)'
