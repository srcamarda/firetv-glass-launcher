# Funções compartilhadas pelos scripts. Não rode este arquivo direto.
# Compatível com Windows PowerShell 5.1 e PowerShell 7.

$Root           = $PSScriptRoot
$IpFile         = Join-Path $Root '.tv-ip'
$DisabledLog    = Join-Path $Root 'disabled-by-script.txt'
$HomeStateFile  = Join-Path $Root '.home-launcher'
$BackupDir      = Join-Path $Root 'backup'
$AmazonLauncher = 'com.amazon.tv.launcher'
# Package list: a disable-list.txt next to the scripts wins, else the repo's debloat\disable-list.txt
$ListFile = Join-Path $Root 'disable-list.txt'
if (-not (Test-Path -LiteralPath $ListFile)) { $ListFile = Join-Path $Root '..\debloat\disable-list.txt' }

# Atalhos aceitos em -Launcher
$KnownLaunchers = [ordered]@{
    projectivy = 'com.spocky.projengmenu'
    glass      = 'com.ivantrj.glasslauncher'
}
# Serviços de acessibilidade já conhecidos (os outros são descobertos no aparelho)
$KnownServices = @{
    'com.ivantrj.glasslauncher' = 'com.ivantrj.glasslauncher/com.ivantrj.glasslauncher.HomeButtonService'
}

$script:Adb    = $null
$script:Serial = $null

function Write-Step([string]$Text) { Write-Host ''; Write-Host "== $Text" -ForegroundColor Cyan }
function Write-Ok([string]$Text)   { Write-Host $Text -ForegroundColor Green }
function Write-Warn([string]$Text) { Write-Host $Text -ForegroundColor Yellow }
function Write-Err([string]$Text)  { Write-Host $Text -ForegroundColor Red }

function Find-Adb {
    $cands = @()
    if ($env:ADB) { $cands += $env:ADB }
    $cands += (Join-Path $Root 'platform-tools\adb.exe')
    $cmd = Get-Command adb.exe -ErrorAction SilentlyContinue
    if ($cmd) { $cands += $cmd.Source }
    if ($env:ANDROID_HOME)     { $cands += (Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe') }
    if ($env:ANDROID_SDK_ROOT) { $cands += (Join-Path $env:ANDROID_SDK_ROOT 'platform-tools\adb.exe') }
    if ($env:LOCALAPPDATA)     { $cands += (Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe') }
    foreach ($c in $cands) { if ($c -and (Test-Path -LiteralPath $c)) { return $c } }
    return $null
}

# Roda o adb e devolve a saída (stdout+stderr) como texto, sem quebrar o script em erro.
function Invoke-Adb([string[]]$AdbArgs) {
    $old = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $out = & $script:Adb @AdbArgs 2>&1 | ForEach-Object { "$_" }
    } finally {
        $ErrorActionPreference = $old
    }
    $script:AdbExit = $LASTEXITCODE
    return ((@($out) -join "`n") -replace "`r", '').Trim()
}

# Um comando por chamada. Use aspas simples dentro do comando, nunca aspas duplas.
function Invoke-TvShell([string]$Command) {
    return Invoke-Adb @('-s', $script:Serial, 'shell', $Command)
}

function Get-TvIp {
    $ip = "$env:TV_IP".Trim()
    if (-not $ip -and (Test-Path -LiteralPath $IpFile)) {
        $ip = (Get-Content -LiteralPath $IpFile -TotalCount 1).Trim()
    }
    return $ip
}

function Connect-Tv {
    $script:Adb = Find-Adb
    if (-not $script:Adb) {
        Write-Err 'adb não encontrado. Rode .\setup.ps1 primeiro.'
        exit 1
    }
    $ip = Get-TvIp
    if (-not $ip) {
        Write-Err 'IP da TV ainda não configurado. Rode .\setup.ps1 primeiro.'
        exit 1
    }
    if ($ip -match ':') { $script:Serial = $ip } else { $script:Serial = "${ip}:5555" }

    $null = Invoke-Adb @('connect', $script:Serial)
    $warned = $false
    for ($i = 0; $i -lt 45; $i++) {
        $state = Invoke-Adb @('-s', $script:Serial, 'get-state')
        if ($state -eq 'device') { return }
        if ($state -match 'unauthorized' -and -not $warned) {
            Write-Warn 'Olhe a TV: em "Permitir depuração USB?", marque "Sempre permitir" e escolha OK.'
            $warned = $true
        } elseif ($state -notmatch 'unauthorized') {
            $null = Invoke-Adb @('connect', $script:Serial)
        }
        Start-Sleep -Seconds 2
    }
    Write-Err "Não consegui conectar na TV em $($script:Serial). Confira se:"
    Write-Err '  - a TV está ligada e na mesma rede que este PC'
    Write-Err '  - Depuração ADB está ativada (Configurações > Minha Fire TV > Opções do desenvolvedor)'
    Write-Err '  - o IP está certo (rode .\setup.ps1 de novo se mudou)'
    exit 1
}

function Show-TvInfo {
    $model = Invoke-TvShell 'getprop ro.product.model'
    $fos   = Invoke-TvShell 'getprop ro.build.version.name'
    $sdk   = Invoke-TvShell 'getprop ro.build.version.sdk'
    Write-Host "Aparelho: $model | $fos | Android API $sdk"
}

# Conjunto de pacotes. $Flag: '' = todos, '-d' = desativados
function Get-TvPackages([string]$Flag = '') {
    $out = Invoke-TvShell ("pm list packages $Flag").Trim()
    $set = New-Object 'System.Collections.Generic.HashSet[string]'
    foreach ($l in ($out -split "`n")) {
        if ($l -match '^package:(.+)$') { [void]$set.Add($Matches[1].Trim()) }
    }
    return , $set
}

function Read-PackageList([string]$Path) {
    if (-not (Test-Path -LiteralPath $Path)) { return @() }
    $pkgs = @()
    foreach ($l in (Get-Content -LiteralPath $Path)) {
        $t = ($l -replace '#.*$', '').Trim()
        if ($t) { $pkgs += $t }
    }
    return $pkgs
}

function Resolve-LauncherPackage([string]$Name) {
    $k = $Name.ToLower()
    if ($KnownLaunchers.Contains($k)) { return $KnownLaunchers[$k] }
    return $Name
}

# Atividade HOME de um pacote (ex.: com.spocky.projengmenu/.ui.home.MainActivity), ou $null
function Get-HomeActivity([string]$Package) {
    $out = Invoke-TvShell "cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME $Package"
    $last = ($out -split "`n" | Where-Object { $_.Trim() } | Select-Object -Last 1)
    if ($last -and $last.Trim() -match '^[\w\.]+/[\w\.\$]+$') { return $last.Trim() }
    return $null
}

# "pkg/.Cls" -> "pkg/pkg.Cls", para comparar componentes
function Expand-Component([string]$C) {
    if ($C -match '^([^/]+)/\.(.+)$') { return "$($Matches[1])/$($Matches[1]).$($Matches[2])" }
    return $C
}

function Get-A11yServices {
    $v = Invoke-TvShell 'settings get secure enabled_accessibility_services'
    if (-not $v -or $v -eq 'null') { return @() }
    return @($v -split ':' | Where-Object { $_.Trim() } | ForEach-Object { $_.Trim() })
}

function Set-A11yServices([string[]]$List) {
    $List = @($List | Where-Object { $_ })
    if ($List.Count -eq 0) {
        $null = Invoke-TvShell 'settings delete secure enabled_accessibility_services'
        $null = Invoke-TvShell 'settings put secure accessibility_enabled 0'
    } else {
        $joined = $List -join ':'
        $null = Invoke-TvShell "settings put secure enabled_accessibility_services '$joined'"
        $null = Invoke-TvShell 'settings put secure accessibility_enabled 1'
    }
}

# Procura serviços de acessibilidade declarados por um pacote (lê o dumpsys package)
function Find-A11yServices([string]$Package) {
    if ($KnownServices.ContainsKey($Package)) { return @($KnownServices[$Package]) }
    $dump = Invoke-TvShell "dumpsys package $Package"
    $found = @()
    $inside = $false
    $pat = '(' + [regex]::Escape($Package) + '/[\w\.\$]+)'
    foreach ($l in ($dump -split "`n")) {
        if ($l -match '^\s*android\.accessibilityservice\.AccessibilityService:\s*$') { $inside = $true; continue }
        if ($inside) {
            if ($l -match $pat) { $found += $Matches[1]; continue }
            if ($l -match '^\s*[\w\.]+:\s*$' -or $l -match '^\S') { $inside = $false }
        }
    }
    return @($found | Select-Object -Unique)
}

function New-Backup {
    $dir = Join-Path $BackupDir (Get-Date -Format 'yyyyMMdd-HHmmss')
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    Invoke-TvShell 'pm list packages'    | Set-Content -LiteralPath (Join-Path $dir 'packages-all.txt')
    Invoke-TvShell 'pm list packages -d' | Set-Content -LiteralPath (Join-Path $dir 'packages-disabled.txt')
    Invoke-TvShell 'settings get secure enabled_accessibility_services' | Set-Content -LiteralPath (Join-Path $dir 'accessibility.txt')
    Invoke-TvShell 'cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME' |
        Set-Content -LiteralPath (Join-Path $dir 'home-activity.txt')
    return $dir
}
