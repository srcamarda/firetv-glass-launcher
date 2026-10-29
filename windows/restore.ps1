# Desfaz o debloat e devolve a tela inicial para a Amazon.
#   .\restore.ps1                    reativa o que o debloat.ps1 desativou + devolve o Home à Amazon
#   .\restore.ps1 -HomeOnly          só devolve o Home à Amazon (mantém o debloat)
#   .\restore.ps1 -PackagesOnly      só reativa os pacotes (mantém o launcher alternativo)
#   .\restore.ps1 -Package <nome>    reativa um pacote só
#   .\restore.ps1 -All               reativa TODA a disable-list.txt, mesmo o que não foi este script que desativou
param(
    [string]$Package,
    [switch]$HomeOnly,
    [switch]$PackagesOnly,
    [switch]$All
)
. "$PSScriptRoot\_common.ps1"

function Enable-Pkg([string]$P) {
    $r = Invoke-TvShell "pm enable --user 0 $P"
    $ok = $r -match 'new state:\s*enabled'
    if ($ok) { Write-Ok ("{0} reativado" -f $P.PadRight(50)) } else { Write-Err ("{0} {1}" -f $P.PadRight(50), $r) }
    return $ok
}

Connect-Tv
Show-TvInfo

if ($Package) { $null = Enable-Pkg $Package; exit 0 }

if (-not $HomeOnly) {
    Write-Step 'Reativando pacotes'
    if ($All) { $pkgs = Read-PackageList $ListFile } else { $pkgs = Read-PackageList $DisabledLog }
    if ($pkgs.Count -eq 0) {
        Write-Host 'Nenhum pacote registrado em disabled-by-script.txt. (Use -All para reativar a lista inteira.)'
    } else {
        $installed = Get-TvPackages
        $left = @()
        foreach ($p in $pkgs) {
            if (-not $installed.Contains($p)) { continue }
            if (-not (Enable-Pkg $p)) { $left += $p }
        }
        if ($left.Count -gt 0) { Set-Content -LiteralPath $DisabledLog -Value $left }
        elseif (Test-Path -LiteralPath $DisabledLog) { Remove-Item -LiteralPath $DisabledLog }
    }
}

if (-not $PackagesOnly) {
    Write-Step 'Devolvendo o Home para a Amazon'
    $null = Enable-Pkg $AmazonLauncher
    $installed = Get-TvPackages
    if ($installed.Contains('com.amazon.firehomestarter')) { $null = Enable-Pkg 'com.amazon.firehomestarter' }
    $amzHome = Get-HomeActivity $AmazonLauncher
    if ($amzHome) { $null = Invoke-TvShell "cmd package set-home-activity $amzHome" }

    # Desliga só os serviços de launcher, mantendo outros serviços de acessibilidade que você usa
    $launcherPkgs = @($KnownLaunchers.Values)
    if (Test-Path -LiteralPath $HomeStateFile) { $launcherPkgs += (Get-Content -LiteralPath $HomeStateFile -TotalCount 1).Trim() }
    $keep = @()
    foreach ($c in (Get-A11yServices)) {
        if ($launcherPkgs -contains ($c -split '/')[0]) { Write-Host "Desligando: $c" } else { $keep += $c }
    }
    Set-A11yServices $keep
    if (Test-Path -LiteralPath $HomeStateFile) { Remove-Item -LiteralPath $HomeStateFile }
    $null = Invoke-TvShell 'input keyevent KEYCODE_HOME'
    Write-Ok 'Home devolvido ao launcher da Amazon.'
    Write-Host 'Obs.: se o launcher tiver a opção de substituir o padrão ligada nas configurações dele, desligue lá também.'
}

Write-Host ''
Write-Ok 'Concluído.'
