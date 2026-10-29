# Atalho para mandar comandos adb para a TV configurada, sem digitar o IP.
#   .\tv.ps1 shell 'pm list packages -d'      pacotes desativados
#   .\tv.ps1 shell 'settings get secure enabled_accessibility_services'
#   .\tv.ps1 install -r -g .\app-debug.apk    instala um APK
#   .\tv.ps1 reboot
#   .\tv.ps1 disconnect                       encerra a conexão (lembre de desligar a Depuração ADB na TV depois)
. "$PSScriptRoot\_common.ps1"
if ($args.Count -eq 0) { Get-Content -LiteralPath $PSCommandPath -TotalCount 7 | Select-Object -Skip 1; exit 1 }
if ($args[0] -eq 'disconnect') {
    $script:Adb = Find-Adb
    $ip = Get-TvIp
    if ($ip -notmatch ':') { $ip = "${ip}:5555" }
    Invoke-Adb @('disconnect', $ip)
    exit 0
}
Connect-Tv
Invoke-Adb (@('-s', $script:Serial) + @($args | ForEach-Object { "$_" }))
exit $script:AdbExit
