<#
  JClaw one-line installer (Windows)

    irm https://raw.githubusercontent.com/tsukhani/jclaw/main/install.ps1 | iex

  Downloads the self-contained jclaw-bundle.zip from GitHub Releases, verifies
  Java 25+ (the bundle's only runtime dependency), extracts it to %USERPROFILE%\.jclaw,
  and starts JClaw on http://localhost:9000.

  The bundle's launcher is jclaw.sh (a POSIX shell script), so JClaw runs through
  Git Bash or WSL. This installer prefers Git Bash (it reuses the Windows Java we
  verify); if only WSL is present it launches there (and checks WSL's own Java);
  if neither is found it installs and prints how to run it.

  Configuration (all optional, via environment variables):
    JCLAW_HOME      install directory        (default: %USERPROFILE%\.jclaw)
    JCLAW_VERSION   release tag, or "latest"  (default: latest)
    JCLAW_PORT      port to report on launch  (default: 9000)
    JCLAW_NO_START  set to 1 to install only, not start
#>

$ErrorActionPreference = 'Stop'

# ─── Configuration ───────────────────────────────────────────────────────────
$Repo      = 'tsukhani/jclaw'
$JclawHome = if ($env:JCLAW_HOME)    { $env:JCLAW_HOME }    else { Join-Path $env:USERPROFILE '.jclaw' }
$Version   = if ($env:JCLAW_VERSION) { $env:JCLAW_VERSION } else { 'latest' }
$Port      = if ($env:JCLAW_PORT)    { $env:JCLAW_PORT }    else { '9000' }
$NoStart   = [bool]$env:JCLAW_NO_START
$Asset     = 'jclaw-bundle.zip'
$MinJava   = 25
$AppDir    = Join-Path $JclawHome 'jclaw'   # bundle zip extracts under a jclaw\ prefix

# ─── Output helpers ──────────────────────────────────────────────────────────
function Step    ($m) { Write-Host '==> ' -ForegroundColor Green -NoNewline; Write-Host $m }
function Substep ($m) { Write-Host "    $m" -ForegroundColor Gray }
function Warn    ($m) { Write-Host 'warning: ' -ForegroundColor Yellow -NoNewline; Write-Host $m }
function Die     ($m) { Write-Host 'error: ' -ForegroundColor Red -NoNewline; Write-Host $m; exit 1 }

function Banner {
    Write-Host ''
    @(
        '   /\  /\     ##  ###### ##       ####  ##    ##'
        '  ###  ###    ##  ##     ##      ##  ## ##    ##'
        '   #\##/#  ## ##  ##     ##      ###### ## /\ ##'
        '   ####   \###/  \#####  ####### ##  ## ##/##\##'
        '    ##     \#/    \####  ####### ##  ## \##  ##/'
    ) | ForEach-Object { Write-Host $_ -ForegroundColor Green }
    Write-Host ''
    Write-Host 'Java-first AI automation platform - one-line installer' -ForegroundColor DarkGray
    Write-Host ''
}

# ─── Java detection (context-aware) ──────────────────────────────────────────
# Runs an invoker that prints `java -version` (Windows java, or WSL's java) and
# returns the major version as [int], or $null if absent/unparseable.
function Get-JavaMajor([scriptblock]$Invoker) {
    try { $out = (& $Invoker 2>&1 | Out-String) } catch { return $null }
    if ($out -match 'version "(\d+)') { return [int]$Matches[1] }
    return $null
}

function Show-JavaHelp {
    Write-Host ''
    Write-Host "JClaw needs a Java $MinJava+ runtime (Zulu or Temurin recommended)." -ForegroundColor Yellow
    Write-Host '  winget:  ' -NoNewline; Write-Host "winget install Azul.Zulu.$MinJava" -ForegroundColor Cyan
    Write-Host '  scoop:   ' -NoNewline; Write-Host "scoop install zulu$MinJava-jdk"   -ForegroundColor Cyan
    Write-Host '  or get:  ' -NoNewline; Write-Host "https://www.azul.com/downloads/?version=java-$MinJava" -ForegroundColor Cyan
    Write-Host ''
}

# ─── Launcher discovery ──────────────────────────────────────────────────────
function Find-GitBash {
    $cands = @(
        (Join-Path $env:ProgramFiles 'Git\bin\bash.exe'),
        (Join-Path ${env:ProgramFiles(x86)} 'Git\bin\bash.exe'),
        (Join-Path $env:LOCALAPPDATA 'Programs\Git\bin\bash.exe')
    )
    foreach ($c in $cands) { if ($c -and (Test-Path $c)) { return $c } }
    $git = Get-Command git.exe -ErrorAction SilentlyContinue
    if ($git) {
        $b = Join-Path (Split-Path (Split-Path $git.Source)) 'bin\bash.exe'
        if (Test-Path $b) { return $b }
    }
    return $null
}

function Test-Wsl {
    if (-not (Get-Command wsl.exe -ErrorAction SilentlyContinue)) { return $false }
    try { wsl.exe -l -q *> $null; return ($LASTEXITCODE -eq 0) } catch { return $false }
}

function Resolve-Url {
    if ($Version -eq 'latest') {
        return "https://github.com/$Repo/releases/latest/download/$Asset"
    }
    $tag = if ($Version -like 'v*') { $Version } else { "v$Version" }
    return "https://github.com/$Repo/releases/download/$tag/$Asset"
}

# ─── Main ────────────────────────────────────────────────────────────────────
Banner

# Pick how we'll run JClaw, and therefore which Java to validate.
$gitBash = Find-GitBash
$useWsl  = if ($gitBash) { $false } else { Test-Wsl }

Step 'Checking prerequisites'
if ($useWsl) {
    $jv = Get-JavaMajor { wsl.exe bash -lc 'java -version' }
    if ($null -eq $jv) { Substep 'No Git Bash; WSL found.'; Show-JavaHelp; Die "WSL is present but has no Java $MinJava+ (install it inside your WSL distro)." }
    if ($jv -lt $MinJava) { Show-JavaHelp; Die "WSL has Java $jv, but JClaw needs $MinJava or newer." }
    Substep "Java $jv detected (in WSL)"
} else {
    $jv = Get-JavaMajor { java -version }
    if ($null -eq $jv) { Show-JavaHelp; Die "Java was not found on your PATH." }
    if ($jv -lt $MinJava) { Show-JavaHelp; Die "Found Java $jv, but JClaw needs $MinJava or newer." }
    Substep "Java $jv detected"
    if (-not $gitBash) { Substep 'Neither Git Bash nor WSL found - will install, then print run instructions.' }
}

Step 'Resolving release'
$url = Resolve-Url
Substep "$Version -> $url"

Step "Downloading $Asset (~400 MB, first run only)"
$tmp = Join-Path ([System.IO.Path]::GetTempPath()) ("jclaw-install-" + [System.Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $tmp -Force | Out-Null
$zip = Join-Path $tmp $Asset
try {
    Invoke-WebRequest -Uri $url -OutFile $zip
} catch {
    Remove-Item -Recurse -Force $tmp -ErrorAction SilentlyContinue
    Die "download failed: $($_.Exception.Message)"
}

Step "Installing to $AppDir"
New-Item -ItemType Directory -Path $JclawHome -Force | Out-Null
$rollback = $null
if (Test-Path $AppDir) {
    $rollback = "$AppDir.rollback.$PID"
    Move-Item $AppDir $rollback
    Substep 'moved the previous install aside (restored on failure)'
}
try {
    Expand-Archive -Path $zip -DestinationPath $JclawHome -Force
    if (-not (Test-Path $AppDir)) { throw "extract did not produce $AppDir" }
    if ($rollback) { Remove-Item -Recurse -Force $rollback -ErrorAction SilentlyContinue }
    Substep 'extracted'
} catch {
    if ($rollback -and (Test-Path $rollback)) {
        Remove-Item -Recurse -Force $AppDir -ErrorAction SilentlyContinue
        Move-Item $rollback $AppDir
        Warn "install failed - restored the previous install at $AppDir"
    }
    Remove-Item -Recurse -Force $tmp -ErrorAction SilentlyContinue
    Die "$($_.Exception.Message)"
}
Remove-Item -Recurse -Force $tmp -ErrorAction SilentlyContinue

# ─── Launch ──────────────────────────────────────────────────────────────────
function Start-ViaGitBash($bash, $appDir) {
    $u = $appDir -replace '\\','/'
    & $bash -lc "cd '$u' && ./jclaw.sh start"
    if ($LASTEXITCODE -ne 0) { throw "jclaw.sh start exited $LASTEXITCODE" }
}
function Start-ViaWsl($appDir) {
    $wp = (wsl.exe wslpath -a "$appDir").Trim()
    wsl.exe bash -lc "cd '$wp' && ./jclaw.sh start"
    if ($LASTEXITCODE -ne 0) { throw "jclaw.sh start exited $LASTEXITCODE" }
}

$started = $false
if (-not $NoStart -and ($gitBash -or $useWsl)) {
    Step 'Starting JClaw'
    try {
        if ($gitBash) { Start-ViaGitBash $gitBash $AppDir } else { Start-ViaWsl $AppDir }
        $started = $true
    } catch {
        Warn "could not auto-start: $($_.Exception.Message)"
    }
}

# ─── Summary ─────────────────────────────────────────────────────────────────
Write-Host ''
Write-Host 'JClaw is installed.' -ForegroundColor Green
Write-Host ''
if ($started) {
    Write-Host '  Open       ' -NoNewline; Write-Host "http://localhost:$Port" -ForegroundColor Cyan
}
Write-Host "  Installed  $AppDir" -ForegroundColor DarkGray
if (-not $started) {
    Write-Host ''
    if ($gitBash) {
        Write-Host '  Run it with Git Bash:'
        Write-Host "    `"$gitBash`" -lc `"cd '$($AppDir -replace '\\','/')' && ./jclaw.sh start`"" -ForegroundColor Cyan
    } elseif ($useWsl) {
        Write-Host '  Run it from a WSL shell:'
        Write-Host ('    cd "$(wslpath -a ''' + $AppDir + ''')" && ./jclaw.sh start') -ForegroundColor Cyan
    } else {
        Write-Host '  To run JClaw, install Git Bash (https://git-scm.com/download/win) or WSL,' -ForegroundColor Yellow
        Write-Host '  then start it from that shell:'
        Write-Host "    cd '$($AppDir -replace '\\','/')' && ./jclaw.sh start" -ForegroundColor Cyan
    }
}
Write-Host ''
