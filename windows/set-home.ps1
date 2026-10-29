# Faz um launcher alternativo virar a tela inicial (botão Home, boot e saída dos apps).
# O Fire OS protege o launcher da Amazon, então o redirecionamento é feito pelo serviço de
# acessibilidade do próprio launcher escolhido (Projectivy e Glass têm um).
#   .\set-home.ps1 -Launcher projectivy
#   .\set-home.ps1 -Launcher glass
#   .\set-home.ps1 -Launcher com.outro.launcher -ServiceComponent com.outro.launcher/.MeuServico
#   .\set-home.ps1 -Launcher projectivy -DisableAmazonLauncher   (tenta desativar o da Amazon também)
# Só um launcher redirecionando por vez: os serviços dos outros launchers conhecidos são desligados.
param(
    [Parameter(Mandatory = $true)][string]$Launcher,
    [string]$ServiceComponent,
    [switch]$DisableAmazonLauncher
)
. "$PSScriptRoot\_common.ps1"

$pkg = Resolve-LauncherPackage $Launcher
Connect-Tv
Show-TvInfo

$installed = Get-TvPackages
if (-not $installed.Contains($pkg)) {
    Write-Err "$pkg não está instalado na TV. Instale o launcher primeiro."
    exit 1
}

Write-Step 'Launcher padrão (HOME)'
$homeAct = Get-HomeActivity $pkg
if ($homeAct) {
    $r = Invoke-TvShell "cmd package set-home-activity $homeAct"
    Write-Host "set-home-activity $homeAct -> $r"
    Write-Host '(O Fire OS costuma aceitar e ignorar isso; o serviço de acessibilidade abaixo é o que garante.)'
} else {
    Write-Warn "$pkg não declara uma atividade HOME; seguindo só com o serviço de acessibilidade."
}

Write-Step 'Serviço de acessibilidade (redirecionamento do Home)'
if ($ServiceComponent) { $svcs = @($ServiceComponent) } else { $svcs = @(Find-A11yServices $pkg) }

if ($svcs.Count -eq 0) {
    Write-Warn "Não encontrei serviço de acessibilidade em $pkg."
    Write-Warn 'Abra o launcher, entre nas configurações dele e ative a opção de substituir o launcher'
    Write-Warn 'padrão (no Projectivy, algo como "Override current launcher"). Depois rode este script de novo.'
} else {
    if ($svcs.Count -gt 1) {
        Write-Warn "O pacote declara mais de um serviço: $($svcs -join ', '). Usando o primeiro."
        Write-Warn 'Se não funcionar, rode de novo com -ServiceComponent <outro>.'
    }
    $svc = $svcs[0]

    # Pacotes de launcher cujos serviços devem sair (evita dois launchers brigando pela tela)
    $launcherPkgs = @($KnownLaunchers.Values)
    if (Test-Path -LiteralPath $HomeStateFile) { $launcherPkgs += (Get-Content -LiteralPath $HomeStateFile -TotalCount 1).Trim() }

    $cur  = Get-A11yServices
    $keep = @()
    foreach ($c in $cur) {
        $cp = ($c -split '/')[0]
        if ($launcherPkgs -contains $cp -and $cp -ne $pkg) { Write-Host "Desligando serviço de outro launcher: $c"; continue }
        if ((Expand-Component $c) -eq (Expand-Component $svc)) { continue }
        $keep += $c
    }
    $keep += $svc
    Set-A11yServices $keep
    $now = Get-A11yServices
    if (@($now | ForEach-Object { Expand-Component $_ }) -contains (Expand-Component $svc)) {
        Write-Ok "Ativado: $svc"
    } else {
        Write-Err "O Fire OS não aceitou ativar $svc. Ative manualmente em Configurações > Acessibilidade."
    }
    Set-Content -LiteralPath $HomeStateFile -Value $pkg
}

if ($DisableAmazonLauncher) {
    Write-Step 'Launcher da Amazon'
    $r = Invoke-TvShell "pm disable-user --user 0 $AmazonLauncher"
    if ($r -match 'new state:\s*disabled') {
        Write-Ok 'Launcher da Amazon desativado. (.\restore.ps1 reativa.)'
    } else {
        Write-Host "Não desativado ($r). Esperado no Fire OS atual; o serviço de acessibilidade cobre isso."
    }
}

Write-Step 'Abrindo o launcher'
if ($homeAct) {
    $null = Invoke-TvShell "am start -n $homeAct"
} else {
    $null = Invoke-TvShell "monkey -p $pkg -c android.intent.category.LEANBACK_LAUNCHER 1"
}
Write-Host ''
Write-Ok 'Pronto. Para testar na TV:'
Write-Host '  1. Abra um app qualquer e aperte Home no controle.'
Write-Host '  2. Abra um app e saia com Voltar até o fim.'
Write-Host '  3. Reinicie a TV:  .\tv.ps1 reboot'
Write-Host 'Para desfazer: .\restore.ps1 -HomeOnly'
