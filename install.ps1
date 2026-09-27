<#
  JClaw one-line installer (Windows)

    irm https://raw.githubusercontent.com/tsukhani/jclaw/main/install.ps1 | iex

  Downloads the self-contained jclaw-bundle.zip from GitHub Releases, ensures a
  Java 25+ runtime (the bundle's only dependency), extracts it to %USERPROFILE%\.jclaw,
  and starts JClaw on http://localhost:9000.

  The bundle's launcher is jclaw.sh (a POSIX shell script), so JClaw runs through
  Git Bash or WSL. This installer prefers Git Bash (it reuses the Windows Java we
  verify); if only WSL is present it launches there (and checks WSL's own Java);
  if neither is found it installs and prints how to run it.

  When the Git Bash / Windows path has no Java 25+, the installer offers to
  download a self-contained Zulu JRE 25 into %USERPROFILE%\.jclaw\jre (no admin,
  no system change); jclaw.sh finds it there on every later start. The WSL path
  uses WSL's own Java, so install that inside your distro.

  Configuration (all optional, via environment variables):
    JCLAW_HOME        install directory        (default: %USERPROFILE%\.jclaw)
    JCLAW_VERSION     release tag, or "latest"  (default: latest)
    JCLAW_PORT        port to report on launch  (default: 9000)
    JCLAW_NO_START    set to 1 to install only, not start
    JCLAW_INSTALL_JRE set to 1 to download the Zulu JRE without prompting
    JCLAW_NO_JRE      set to 1 to never auto-install a JRE (fail if Java is missing)
    JCLAW_NO_RC_EDIT  set to 1 to generate completion scripts but not edit your shell rc
    JCLAW_FORCE_REINSTALL  replace an existing install from scratch, DISCARDING its
                      database, workspace and credentials. Without it, an existing
                      install is upgraded in place instead.

  Shell completion: the bundle's jclaw.sh runs through Git Bash or WSL, so the
  installer wires bash/zsh tab-completion into that shell's rc (jclaw.sh <TAB>).
#>

$ErrorActionPreference = 'Stop'

# ─── Configuration ───────────────────────────────────────────────────────────
$Repo      = 'tsukhani/jclaw'
$JclawHome = if ($env:JCLAW_HOME)    { $env:JCLAW_HOME }    else { Join-Path $env:USERPROFILE '.jclaw' }
$Version   = if ($env:JCLAW_VERSION) { $env:JCLAW_VERSION } else { 'latest' }
$Port      = if ($env:JCLAW_PORT)    { $env:JCLAW_PORT }    else { '9000' }
$NoStart   = [bool]$env:JCLAW_NO_START
$NoJre      = [bool]$env:JCLAW_NO_JRE
$InstallJre = [bool]$env:JCLAW_INSTALL_JRE
$Asset     = 'jclaw-bundle.zip'
$MinJava   = 25
$AppDir    = Join-Path $JclawHome 'jclaw'   # bundle zip extracts under a jclaw\ prefix
$JreDir    = Join-Path $JclawHome 'jre'     # managed Zulu JRE, sibling of jclaw\ (jclaw.sh finds it)
$AzulApi   = 'https://api.azul.com/metadata/v1/zulu/packages/'
# Azul arch token for this host (x64 / aarch64); '' means an arch we don't auto-install for.
$WinArch   = switch ($env:PROCESSOR_ARCHITECTURE) {
    'AMD64' { 'x64' }
    'ARM64' { 'aarch64' }
    default { switch ($env:PROCESSOR_ARCHITEW6432) { 'AMD64' { 'x64' } 'ARM64' { 'aarch64' } default { '' } } }
}

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
# returns its combined output as plain text; '' when the command can't run.
# java prints that banner on stderr, and 2>&1 turns each line into an ErrorRecord:
# Out-String renders those through the error formatter, which prefixes the invoked
# path and wraps at the console width - splitting `version "25` for a path as long
# as the managed JRE's - and 'Stop' escalates the first one to a throw. So stringify
# each record, with the preference relaxed for the duration.
function Get-JavaVersionText([scriptblock]$Invoker) {
    $prev = $script:ErrorActionPreference
    $script:ErrorActionPreference = 'Continue'
    try { return ((& $Invoker 2>&1 | ForEach-Object { "$_" }) -join "`n") }
    catch { return '' }
    finally { $script:ErrorActionPreference = $prev }
}

# Major version out of a `java -version` banner, or $null when it carries none.
function Get-JavaMajorFrom([string]$Text) {
    if ($Text -match 'version "(\d+)') { return [int]$Matches[1] }
    return $null
}

# The major version an invoker reports as [int], or $null if absent/unparseable.
function Get-JavaMajor([scriptblock]$Invoker) { Get-JavaMajorFrom (Get-JavaVersionText $Invoker) }

function Show-JavaHelp([switch]$Auto) {
    Write-Host ''
    Write-Host "JClaw needs a Java $MinJava+ runtime (Zulu or Temurin recommended)." -ForegroundColor Yellow
    if ($Auto) { Write-Host '  auto:    ' -NoNewline; Write-Host "re-run with JCLAW_INSTALL_JRE=1 (downloads a Zulu JRE $MinJava)" -ForegroundColor Cyan }
    Write-Host '  winget:  ' -NoNewline; Write-Host "winget install Azul.Zulu.$MinJava" -ForegroundColor Cyan
    Write-Host '  scoop:   ' -NoNewline; Write-Host "scoop install zulu$MinJava-jdk"   -ForegroundColor Cyan
    Write-Host '  or get:  ' -NoNewline; Write-Host "https://www.azul.com/downloads/?package=jre" -ForegroundColor Cyan
    Write-Host ''
}

# Decide whether to auto-install the JRE. Forced by JCLAW_INSTALL_JRE; else prompt on
# an interactive console (default yes); non-interactive proceeds (pulling the runtime
# is the point of a one-liner) unless JCLAW_NO_JRE opted out (checked by the caller).
function Test-WantJre($reason) {
    if ($InstallJre) { return $true }
    if ([Environment]::UserInteractive -and -not [Console]::IsInputRedirected) {
        Write-Host ''
        Write-Host $reason -ForegroundColor Yellow
        $ans = Read-Host "Download a self-contained Zulu JRE $MinJava (~50 MB) into $JreDir? [Y/n]"
        return ($ans -notmatch '^(?i)n')
    }
    Substep "$reason Auto-installing Zulu JRE $MinJava (set JCLAW_NO_JRE=1 to skip)"
    return $true
}

# Resolve, download, checksum-verify, and unpack the newest GA plain Zulu JRE
# (windows/$WinArch) into $JreDir, then confirm it runs. Throws on any failure.
function Get-ZuluJre {
    Step "Installing Zulu JRE $MinJava (windows/$WinArch)"
    $api = "${AzulApi}?java_version=$MinJava&os=windows&arch=$WinArch&archive_type=zip" +
           "&java_package_type=jre&release_status=ga&include_fields=sha256_hash&page_size=20"
    try { $pkgs = Invoke-RestMethod -Uri $api } catch { throw "couldn't reach the Azul JRE catalog: $($_.Exception.Message)" }
    # Skip the JavaFX-bundled (fx) and CRaC variants — take the first plain JRE.
    $pkg = $pkgs | Where-Object { $_.name -notmatch '(?i)fx|crac' } | Select-Object -First 1
    if (-not $pkg -or -not $pkg.download_url) { throw "no Zulu JRE $MinJava found for windows/$WinArch in the Azul catalog." }
    Substep ([System.IO.Path]::GetFileName($pkg.download_url))

    $tmp = Join-Path ([System.IO.Path]::GetTempPath()) ("jclaw-jre-" + [System.Guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $tmp -Force | Out-Null
    try {
        $zip = Join-Path $tmp 'jre.zip'
        Invoke-WebRequest -Uri $pkg.download_url -OutFile $zip
        if ($pkg.sha256_hash) {
            $got = (Get-FileHash -Algorithm SHA256 -Path $zip).Hash
            if ($got -ne $pkg.sha256_hash.ToUpper()) { throw "JRE checksum mismatch (wanted $($pkg.sha256_hash), got $got)" }
            Substep 'checksum verified (sha256)'
        } else {
            Warn 'Azul returned no checksum - skipping JRE verification.'
        }
        if (Test-Path $JreDir) { Remove-Item -Recurse -Force $JreDir }
        New-Item -ItemType Directory -Path $JreDir -Force | Out-Null
        Expand-Archive -Path $zip -DestinationPath $JreDir -Force
    } finally {
        Remove-Item -Recurse -Force $tmp -ErrorAction SilentlyContinue
    }

    $java = Get-ChildItem -Path $JreDir -Recurse -Filter 'java.exe' -ErrorAction SilentlyContinue |
            Where-Object { $_.FullName -match '\\bin\\java\.exe$' } | Select-Object -First 1
    if (-not $java) { throw "unpacked the JRE but found no bin\java.exe under $JreDir." }
    $out = Get-JavaVersionText { & $java.FullName -version }
    $jv  = Get-JavaMajorFrom $out
    if ($null -eq $jv -or $jv -lt $MinJava) {
        $said = (($out -split "`n" | Where-Object { $_.Trim() }) -join ' | ')
        if (-not $said) { $said = '(nothing)' }
        throw "$($java.FullName) did not report Java $MinJava+ - it printed: $said"
    }
    Substep "installed -> $(Split-Path $java.FullName)"
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

# Record the version installed and the checksum of conf/application.conf *as
# shipped*. `jclaw.sh upgrade` compares the live conf against this to tell an
# operator's edits from an untouched default: matching means it can safely
# install the new release's conf, differing means it must keep theirs. Absent
# (installs predating this) is read as "edited", the side that cannot lose
# someone's work.
function Write-Manifest {
    $conf = Join-Path $AppDir 'conf\application.conf'
    if (-not (Test-Path $conf)) { return }
    try {
        $ver = (Select-String -Path $conf -Pattern '^application\.version=(.*)$').Matches[0].Groups[1].Value
        $sha = (Get-FileHash -Path $conf -Algorithm SHA256).Hash.ToLower()
        $at  = (Get-Date).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ssZ')
        # LF, not CRLF: jclaw.sh parses this with sed, which would otherwise
        # carry a trailing \r into the checksum it compares.
        $text = "version=$ver`nconf_sha256=$sha`ninstalled_at=$at`n"
        [System.IO.File]::WriteAllText((Join-Path $AppDir '.jclaw-manifest'), $text)
    } catch {
        Warn "could not write .jclaw-manifest: $($_.Exception.Message)"
    }
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
    if ($null -ne $jv -and $jv -ge $MinJava) {
        Substep "Java $jv detected"
    } else {
        $reason = if ($null -eq $jv) { 'Java was not found on your PATH.' } else { "Found Java $jv, but JClaw needs $MinJava or newer." }
        if (-not $NoJre -and $WinArch -and (Test-WantJre $reason)) {
            try { Get-ZuluJre; Substep "Java $MinJava ready (managed JRE)" }
            catch { Show-JavaHelp -Auto:([bool]$WinArch); Die $_.Exception.Message }
        } else {
            Show-JavaHelp -Auto:([bool]$WinArch); Die $reason
        }
    }
    if (-not $gitBash) { Substep 'Neither Git Bash nor WSL found - will install, then print run instructions.' }
}

# Re-running the installer over an existing install is the obvious way to
# update, so it has to be the safe one. The block below replaces $AppDir
# wholesale and the release archive ships no data\, workspace\, logs\,
# public\apps\ or certs\.env - so without this handoff, "update" would mean
# "delete the database, the agent workspace and the session-signing secret".
# jclaw.sh upgrade carries all of that across, backs the database up first, and
# rolls back a release that fails to start.
if ((Test-Path (Join-Path $AppDir 'jclaw.sh')) -and -not $env:JCLAW_FORCE_REINSTALL) {
    if (-not ($gitBash -or $useWsl)) {
        Die "an install already exists at $AppDir, but neither Git Bash nor WSL is available to upgrade it. Install one, then re-run."
    }
    Step "Existing install detected at $AppDir - upgrading in place"
    $upgradeArgs = 'upgrade --yes'
    if ($Version -ne 'latest') { $upgradeArgs += " --version '$Version'" }
    if ($gitBash) {
        $u = $AppDir -replace '\\','/'
        & $gitBash -lc "'$u/jclaw.sh' $upgradeArgs"
    } else {
        $wp = (wsl.exe wslpath -a "$AppDir").Trim()
        wsl.exe bash -lc "'$wp/jclaw.sh' $upgradeArgs"
    }
    if ($LASTEXITCODE -ne 0) { Die "upgrade exited $LASTEXITCODE" }
    exit 0
}
if ((Test-Path $AppDir) -and $env:JCLAW_FORCE_REINSTALL) {
    Warn "JCLAW_FORCE_REINSTALL is set - $AppDir will be replaced from scratch."
    Warn 'Its database, workspace, credentials and installed apps will be lost.'
}

Step 'Resolving release'
$url = Resolve-Url
Substep "$Version -> $url"

Step "Downloading $Asset (~190 MB, first run only)"
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
    Write-Manifest
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

# Shell tab-completion for jclaw.sh. Delegated to the bundle's own `completion
# install` (single source of truth for the command list), run through the same
# shell that runs JClaw so it wires that shell's rc. Honors JCLAW_NO_RC_EDIT.
function Install-Completion($appDir) {
    if ($gitBash) {
        $u = $appDir -replace '\\','/'
        & $gitBash -lc "'$u/jclaw.sh' completion install"
    } elseif ($useWsl) {
        $wp = (wsl.exe wslpath -a "$appDir").Trim()
        wsl.exe bash -lc "'$wp/jclaw.sh' completion install"
    }
}

if ($gitBash -or $useWsl) {
    Step 'Enabling shell completion'
    try { Install-Completion $AppDir } catch { Warn "shell completion setup skipped: $($_.Exception.Message)" }
}

# Put `jclaw` on PATH for PowerShell and cmd.exe. jclaw.sh's write_shim drops the
# jclaw.cmd there — the shim itself stays single-sourced in the bundle so
# `upgrade` refreshes it. This adds the one piece bash cannot: a persistent
# Windows PATH entry, which only the registry (User scope) provides.
#
# Idempotent by exact segment match, so re-running never stacks duplicates.
# Returns whether $dir is on the User PATH *after* the attempt. Read back rather
# than trusting the write: a managed machine can block the registry update by
# policy without SetEnvironmentVariable throwing, and "we wrote it" is not "it is
# there" — which is the difference between a working install and one that reports
# success and then cannot find jclaw.
function Add-ToUserPath($dir) {
    $parts = @([Environment]::GetEnvironmentVariable('Path', 'User') -split ';' | Where-Object { $_ -ne '' })
    if ($parts -notcontains $dir) {
        try {
            [Environment]::SetEnvironmentVariable('Path', (($parts + $dir) -join ';'), 'User')
            # Current process too, so anything later in this script can find it.
            $env:Path = "$env:Path;$dir"
        } catch { Warn "could not write your PATH: $($_.Exception.Message)" }
    }
    $now = @([Environment]::GetEnvironmentVariable('Path', 'User') -split ';')
    return ($now -contains $dir)
}

# C:\Users\x\.local\bin -> /c/Users/x/.local/bin. Git Bash needs the POSIX form,
# and it has to be derived from the SAME $BinDir that goes on PATH: a shim written
# to one directory while PATH names another leaves `jclaw` unrecognised with no
# error anywhere to explain it.
function ConvertTo-PosixPath($p) {
    $q = $p -replace '\\','/'
    if ($q -match '^([A-Za-z]):(.*)$') { return "/$($Matches[1].ToLower())$($Matches[2])" }
    return $q
}

# Write the shim, then put its directory on PATH. Both are needed and neither is
# sufficient: install.sh runs `jclaw.sh shim` (line ~455) and this must too —
# write_shim's only caller is that subcommand, so without it the PATH entry points
# at an empty directory (JCLAW-1105 follow-up).
$BinDir      = Join-Path $env:USERPROFILE '.local\bin'
$pathChanged = $false
$pathOk      = $false
if ($gitBash -or $useWsl) {
    Step 'Linking the jclaw command'
    try {
        New-Item -ItemType Directory -Force -Path $BinDir | Out-Null
        if ($gitBash) {
            $u  = $AppDir -replace '\\','/'
            $bp = ConvertTo-PosixPath $BinDir
            & $gitBash -lc "JCLAW_BIN_DIR='$bp' '$u/jclaw.sh' shim"
        } else {
            $wp = (wsl.exe wslpath -a "$AppDir").Trim()
            $wb = (wsl.exe wslpath -a "$BinDir").Trim()
            wsl.exe bash -lc "JCLAW_BIN_DIR='$wb' '$wp/jclaw.sh' shim"
        }
        if (Test-Path (Join-Path $BinDir 'jclaw.cmd')) {
            Substep "wrote $BinDir\jclaw.cmd"
        } else {
            Warn "the jclaw launcher was not created in $BinDir - the jclaw command will not be recognised."
        }
    } catch { Warn "could not link the jclaw command: $($_.Exception.Message)" }

    Step 'Putting jclaw on PATH'
    try {
        $alreadyOnPath = @([Environment]::GetEnvironmentVariable('Path', 'User') -split ';') -contains $BinDir
        $pathOk        = Add-ToUserPath $BinDir
        $pathChanged   = ($pathOk -and -not $alreadyOnPath)
        if ($pathOk)   { Substep $(if ($pathChanged) { "added $BinDir to your PATH" } else { "$BinDir already on your PATH" }) }
        else           { Warn "$BinDir is not on your PATH — the jclaw command will not resolve." }
    } catch { Warn "PATH setup skipped: $($_.Exception.Message)" }
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
if ($pathOk) {
    Write-Host '  Command    ' -NoNewline; Write-Host 'jclaw start | stop | status' -ForegroundColor Cyan
    if ($pathChanged) {
        # A User PATH change reaches new processes only. Without saying so, the very
        # first jclaw looks exactly like the "not recognized" bug this replaces.
        Write-Host '             open a NEW terminal first - this one predates the PATH change' -ForegroundColor Yellow
    }
} elseif ($gitBash -or $useWsl) {
    # PATH did not take. The install root carries the same launcher, so name the
    # one command that works rather than leaving the operator to find it.
    Write-Host '  Command    ' -NoNewline; Write-Host 'PATH could not be set - run it from the folder:' -ForegroundColor Yellow
    Write-Host "               cd '$AppDir'; .\jclaw.cmd start" -ForegroundColor Cyan
}
Write-Host '  Uninstall  ' -NoNewline; Write-Host './jclaw.sh uninstall' -ForegroundColor Cyan -NoNewline
Write-Host "  (run via your shell; removes $JclawHome, undoes completion)" -ForegroundColor DarkGray
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
