# Mostra, em tempo real, o log de diagnóstico do Glass (tag GlassDiag).
# Rode, aperte botões no controle e veja o que aparece. Ctrl+C para sair.
#   .\diag-home.ps1
#
# Como ler:
#   "KEY DPAD_DOWN DOWN" etc.     -> o Fire OS entrega teclas ao serviço de acessibilidade
#   "KEY HOME ..."                -> o Home chega ao serviço: troca instantânea é possível
#   só "Amazon launcher window"   -> o Home NÃO chega; só dá para reagir depois que a tela da Amazon aparece
. "$PSScriptRoot\_common.ps1"
Connect-Tv
$null = Invoke-Adb @('-s', $script:Serial, 'logcat', '-c')
Write-Host 'Aguardando eventos... aperte setas, Voltar e Home no controle. (Ctrl+C para sair)' -ForegroundColor Cyan
& $script:Adb -s $script:Serial logcat -v time -s GlassDiag:V
