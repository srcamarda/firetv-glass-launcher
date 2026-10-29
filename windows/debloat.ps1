# Desativa os pacotes de disable-list.txt com "pm disable-user" (nada é desinstalado).
# Antes de mexer, salva um backup do estado atual em .\backup\<data>.
# Cada pacote que ESTE script desativar é anotado em disabled-by-script.txt,
# e o .\restore.ps1 usa esse registro para desfazer exatamente o que foi feito.
#   .\debloat.ps1 -DryRun                   mostra o que aconteceria, sem mudar nada
#   .\debloat.ps1                           desativa tudo da lista (seguro rodar de novo)
#   .\debloat.ps1 -List .\minha-lista.txt   usa outra lista (padrão: ..\debloat\disable-list.txt)
param(
    [string]$List,
    [switch]$DryRun
)
. "$PSScriptRoot\_common.ps1"
if (-not $List) { $List = $ListFile }

$pkgs = Read-PackageList $List
if ($pkgs.Count -eq 0) { Write-Err "Lista vazia ou não encontrada: $List"; exit 1 }

Connect-Tv
Show-TvInfo
$installed = Get-TvPackages
$disabled  = Get-TvPackages '-d'

if (-not $DryRun) {
    $bk = New-Backup
    Write-Host "Backup do estado atual: $bk"
}

$logged = New-Object 'System.Collections.Generic.HashSet[string]'
foreach ($p in (Read-PackageList $DisabledLog)) { [void]$logged.Add($p) }

$n = @{ ok = 0; already = 0; missing = 0; protected = 0; failed = 0; would = 0 }
if ($DryRun) { Write-Step "Simulação ($($pkgs.Count) pacotes na lista)" } else { Write-Step "Desativando ($($pkgs.Count) pacotes na lista)" }

foreach ($p in $pkgs) {
    $label = $p.PadRight(50)
    if (-not $installed.Contains($p)) { Write-Host "$label não existe neste aparelho" -ForegroundColor DarkGray; $n.missing++; continue }
    if ($disabled.Contains($p))       { Write-Host "$label já desativado" -ForegroundColor DarkGray; $n.already++; continue }
    if ($DryRun)                      { Write-Host "$label seria desativado"; $n.would++; continue }

    # Um comando pm por chamada (encadear pm pode parar no primeiro em silêncio)
    $r = Invoke-TvShell "pm disable-user --user 0 $p"
    if ($r -match 'new state:\s*disabled') {
        Write-Ok "$label desativado"
        $n.ok++
        if ($logged.Add($p)) { Add-Content -LiteralPath $DisabledLog -Value $p }
    } elseif ($r -match 'protected') {
        Write-Warn "$label protegido pelo Fire OS (não dá para desativar)"
        $n.protected++
    } else {
        Write-Err "$label falhou: $r"
        $n.failed++
    }
}

Write-Host ''
if ($DryRun) {
    Write-Host "Seriam desativados: $($n.would) | já desativados: $($n.already) | não existem aqui: $($n.missing)"
    Write-Host "Nada foi alterado. Edite $List se quiser manter algo e rode sem -DryRun."
} else {
    Write-Host "Desativados: $($n.ok) | já estavam: $($n.already) | não existem aqui: $($n.missing) | protegidos: $($n.protected) | falhas: $($n.failed)"
    Write-Host 'Para desfazer: .\restore.ps1   (ou .\restore.ps1 -Package <nome> para um só)'
    Write-Host 'Uma atualização do Fire OS pode reativar pacotes; se acontecer, rode este script de novo.'
}
