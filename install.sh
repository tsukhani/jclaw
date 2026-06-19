#!/bin/sh
# JClaw one-line installer (macOS + Linux)
#
#   curl -fsSL https://raw.githubusercontent.com/tsukhani/jclaw/main/install.sh | sh
#
# Downloads the self-contained jclaw-bundle.zip from GitHub Releases, ensures a
# Java 25+ runtime (the bundle's only dependency) — auto-installing a self-contained
# Zulu JRE 25 into ~/.jclaw/jre when none is found — extracts the bundle to ~/.jclaw,
# and starts JClaw on http://localhost:9000.
#
# The bundle bakes in the Play framework, app deps, precompiled classes, the
# prebuilt SPA, and a ./play launcher — so a Java 25 JRE is all the host needs.
#
# Configuration (all optional, via environment variables):
#   JCLAW_HOME      install directory          (default: $HOME/.jclaw)
#   JCLAW_VERSION   release tag, or "latest"    (default: latest)
#   JCLAW_PORT      port to report on launch    (default: 9000)
#   JCLAW_BIN_DIR   where the `jclaw` shim goes (default: $HOME/.local/bin)
#   JCLAW_NO_START  set to 1 to install only, not start
#   JCLAW_NO_JRE    set to 1 to never auto-install a JRE (just print instructions)
#   JCLAW_INSTALL_JRE  set to 1 to auto-install the JRE without prompting
#   NO_COLOR        set to disable ANSI color
#
# POSIX sh — no bashisms; runnable under dash/ash via `| sh`.
set -eu

# ─── Configuration ───────────────────────────────────────────────────────────
JCLAW_REPO="tsukhani/jclaw"
JCLAW_HOME="${JCLAW_HOME:-$HOME/.jclaw}"
JCLAW_VERSION="${JCLAW_VERSION:-latest}"
JCLAW_PORT="${JCLAW_PORT:-9000}"
JCLAW_BIN_DIR="${JCLAW_BIN_DIR:-$HOME/.local/bin}"
JCLAW_NO_START="${JCLAW_NO_START:-}"
JCLAW_NO_JRE="${JCLAW_NO_JRE:-}"            # skip the auto JRE download
JCLAW_INSTALL_JRE="${JCLAW_INSTALL_JRE:-}"  # auto-install the JRE without prompting

APP_DIR="$JCLAW_HOME/jclaw"     # bundle zip extracts under a jclaw/ prefix
JRE_DIR="$JCLAW_HOME/jre"       # self-contained Zulu JRE lands here when Java is missing
ASSET="jclaw-bundle.zip"
MIN_JAVA=25
AZUL_API="https://api.azul.com/metadata/v1/zulu/packages/"

# ─── Output helpers (TTY + NO_COLOR aware) ───────────────────────────────────
if [ -t 1 ] && [ -z "${NO_COLOR:-}" ]; then
    EMERALD=$(printf '\033[38;2;52;211;153m')
    CYAN=$(printf '\033[1;36m')
    YELLOW=$(printf '\033[1;33m')
    RED=$(printf '\033[1;31m')
    DIM=$(printf '\033[2m')
    BOLD=$(printf '\033[1m')
    RESET=$(printf '\033[0m')
else
    EMERALD=''; CYAN=''; YELLOW=''; RED=''; DIM=''; BOLD=''; RESET=''
fi

step()    { printf '%s==>%s %s\n'    "$EMERALD" "$RESET" "$1"; }
substep() { printf '    %s\n' "$1"; }
warn()    { printf '%swarning:%s %s\n' "$YELLOW" "$RESET" "$1" >&2; }
die()     { printf '%serror:%s %s\n'   "$RED"    "$RESET" "$1" >&2; exit 1; }

banner() {
    printf '\n'
    printf '%s   ▟█▙  ▟█▙     ██╗ ██████╗██╗      █████╗ ██╗    ██╗%s\n' "$EMERALD" "$RESET"
    printf '%s   ███  ███     ██║██╔════╝██║     ██╔══██╗██║    ██║%s\n' "$EMERALD" "$RESET"
    printf '%s    █▜▙▟▛█  ██  ██║██║     ██║     ███████║██║ █╗ ██║%s\n' "$EMERALD" "$RESET"
    printf '%s    █████  ╚████╔╝╚██████╗███████╗██║  ██║╚███╔███╔╝%s\n' "$EMERALD" "$RESET"
    printf '%s     ███    ╚═══╝  ╚═════╝╚══════╝╚═╝  ╚═╝ ╚══╝╚══╝ %s\n' "$EMERALD" "$RESET"
    printf '\n'
    printf '%sJava-first AI automation platform — one-line installer%s\n\n' "$DIM" "$RESET"
}

# ─── Cleanup / rollback ──────────────────────────────────────────────────────
# Removes the download temp dir on every exit. On a *failed* exit, restores a
# prior install we moved aside so a botched upgrade never leaves you worse off.
TMP_DL=''
TMP_JRE=''
ROLLBACK=''
cleanup() {
    rc=$?
    [ -n "$TMP_DL" ] && rm -rf "$TMP_DL" 2>/dev/null || true
    [ -n "$TMP_JRE" ] && rm -rf "$TMP_JRE" 2>/dev/null || true
    if [ "$rc" -ne 0 ] && [ -n "$ROLLBACK" ] && [ -d "$ROLLBACK" ]; then
        rm -rf "$APP_DIR" 2>/dev/null || true
        mv "$ROLLBACK" "$APP_DIR" 2>/dev/null || true
        warn "install failed — restored the previous install at $APP_DIR"
    elif [ -n "$ROLLBACK" ]; then
        rm -rf "$ROLLBACK" 2>/dev/null || true
    fi
}
trap cleanup EXIT

# ─── Platform guard ──────────────────────────────────────────────────────────
detect_os() {
    case "$(uname -s)" in
        Linux)  OS=linux;  AZUL_OS=linux  ;;
        Darwin) OS=macos;  AZUL_OS=macos  ;;
        MINGW*|MSYS*|CYGWIN*)
            die "this is the Unix installer. On Windows run the PowerShell one:
       irm https://raw.githubusercontent.com/$JCLAW_REPO/main/install.ps1 | iex" ;;
        *) die "unsupported OS: $(uname -s)" ;;
    esac
    # CPU arch for the Zulu JRE download. Empty ⇒ unrecognized ⇒ no auto-install.
    case "$(uname -m)" in
        x86_64|amd64)  ARCH=x64 ;;
        aarch64|arm64) ARCH=aarch64 ;;
        *)             ARCH='' ;;
    esac
}

# ─── Dependency: a Java 25+ runtime ──────────────────────────────────────────
# Use the host's Java when it's 25+. Otherwise (with consent) download a
# self-contained Zulu JRE 25 into $JRE_DIR and put it on PATH for this run; the
# bundle's jclaw.sh finds it there on every later `jclaw` call. No sudo, no system
# change — uninstall is just `rm -rf "$JCLAW_HOME"`.
check_java() {
    if java_ok; then
        substep "Java $JV detected ${DIM}($(command -v java))${RESET}"
        return
    fi
    if command -v java >/dev/null 2>&1; then
        REASON="Found Java ${JV:-?}, but JClaw needs Java $MIN_JAVA or newer."
    else
        REASON="Java was not found on your PATH."
    fi
    if [ -z "$JCLAW_NO_JRE" ] && [ -n "$ARCH" ] && want_jre; then
        provision_jre
        java_ok || die "installed a Zulu JRE but \`java\` still isn't $MIN_JAVA+ — please report this."
        substep "Java $JV ready ${DIM}(managed JRE)${RESET}"
        return
    fi
    java_missing "$REASON"
}

# True when a Java >= MIN_JAVA is on PATH. Sets JV to the major version.
#   openjdk version "25.0.1" 2025-...  →  25
#   java version "1.8.0_412"           →  1  (correctly rejected)
java_ok() {
    command -v java >/dev/null 2>&1 || return 1
    JV=$(java -version 2>&1 | head -n1 | sed -n 's/.*version "\([0-9][0-9]*\).*/\1/p')
    [ -n "$JV" ] && [ "$JV" -ge "$MIN_JAVA" ]
}

# Decide whether to auto-install the JRE. Forced by JCLAW_INSTALL_JRE; else prompt
# on a TTY (default yes); a piped run (the curl|sh case) proceeds — pulling the
# runtime is the point of a one-liner — unless JCLAW_NO_JRE is set.
want_jre() {
    [ -n "$JCLAW_INSTALL_JRE" ] && return 0
    if [ -r /dev/tty ] && [ -w /dev/tty ]; then
        printf '\n%s%s%s\n' "$YELLOW" "$REASON" "$RESET" >/dev/tty
        printf 'Download a self-contained Zulu JRE %s (~50 MB) into %s? [Y/n] ' \
            "$MIN_JAVA" "$JRE_DIR" >/dev/tty
        ans=''
        read ans </dev/tty 2>/dev/null || ans=''
        case "$ans" in [Nn]*) return 1 ;; *) return 0 ;; esac
    fi
    substep "${YELLOW}$REASON${RESET} Auto-installing Zulu JRE $MIN_JAVA ${DIM}(set JCLAW_NO_JRE=1 to skip)${RESET}"
    return 0
}

# Resolve, download, checksum-verify, and unpack the newest GA plain Zulu JRE for
# this os/arch into $JRE_DIR, then put it on PATH for the rest of this run.
provision_jre() {
    step "Installing Zulu JRE $MIN_JAVA ${DIM}($AZUL_OS/$ARCH)${RESET}"
    _libc=''
    [ "$AZUL_OS" = linux ] && _libc='&libc_type=glibc'
    _api="${AZUL_API}?java_version=${MIN_JAVA}&os=${AZUL_OS}&arch=${ARCH}&archive_type=tar.gz&java_package_type=jre&release_status=ga&include_fields=sha256_hash&page_size=20${_libc}"
    _json=$(http_get "$_api") || die "couldn't reach the Azul JRE catalog — install Java $MIN_JAVA yourself and re-run."
    # Collapse to one object per line, then take the first plain JRE — skip the
    # JavaFX-bundled (fx), CRaC, and musl-libc variants.
    _obj=$(printf '%s' "$_json" | tr -d '\n' | sed 's/},[[:space:]]*{/}\
{/g' | grep '"download_url"' | grep -viE 'fx|crac|musl' | head -n1)
    JRE_URL=$(printf '%s' "$_obj" | sed -n 's/.*"download_url":"\([^"]*\)".*/\1/p')
    JRE_SHA=$(printf '%s' "$_obj" | sed -n 's/.*"sha256_hash":"\([0-9a-f]*\)".*/\1/p')
    [ -n "$JRE_URL" ] || die "no Zulu JRE $MIN_JAVA found for $AZUL_OS/$ARCH in the Azul catalog."
    substep "$(basename "$JRE_URL")"

    TMP_JRE=$(mktemp -d "${TMPDIR:-/tmp}/jclaw-jre.XXXXXX")
    _tb="$TMP_JRE/jre.tar.gz"
    download "$JRE_URL" "$_tb"
    verify_sha256 "$_tb" "$JRE_SHA"

    rm -rf "$JRE_DIR"
    mkdir -p "$JRE_DIR"
    tar -xzf "$_tb" -C "$JRE_DIR" || die "failed to unpack the JRE archive."
    rm -rf "$TMP_JRE"; TMP_JRE=''

    # Locate bin/java — handles the macOS zulu-25.jre/Contents/Home nesting and Linux flat.
    _jbin=$(find "$JRE_DIR" -type f -name java -path '*/bin/java' 2>/dev/null | head -n1)
    [ -n "$_jbin" ] || die "unpacked the JRE but found no bin/java under $JRE_DIR."
    JAVA_HOME=$(CDPATH= cd -- "$(dirname "$(dirname "$_jbin")")" && pwd)
    export JAVA_HOME
    PATH="$JAVA_HOME/bin:$PATH"
    export PATH
    substep "installed → ${DIM}$JAVA_HOME${RESET}"
}

# Verify a download against an expected sha256. Fatal on mismatch; warns (but
# proceeds) only when no checksum or no hashing tool is available.
verify_sha256() {
    _f="$1"; _want="$2"
    if [ -z "$_want" ]; then warn "Azul returned no checksum — skipping JRE verification."; return 0; fi
    if command -v shasum >/dev/null 2>&1; then
        _got=$(shasum -a 256 "$_f" 2>/dev/null | awk '{print $1}')
    elif command -v sha256sum >/dev/null 2>&1; then
        _got=$(sha256sum "$_f" 2>/dev/null | awk '{print $1}')
    else
        warn "no shasum/sha256sum tool — skipping JRE checksum verification."; return 0
    fi
    [ "$_got" = "$_want" ] || die "JRE checksum mismatch (wanted $_want, got ${_got:-none}) — aborting."
    substep "checksum verified ${DIM}(sha256)${RESET}"
}

# GET a URL to stdout (for the Azul catalog API).
http_get() {
    if command -v curl >/dev/null 2>&1; then curl -fsSL "$1"
    elif command -v wget >/dev/null 2>&1; then wget -qO- "$1"
    else die "need curl or wget to query the Azul JRE catalog."; fi
}

java_missing() {
    printf '%serror:%s %s\n\n' "$RED" "$RESET" "$1" >&2
    printf 'JClaw needs a Java %s+ runtime.\n' "$MIN_JAVA" >&2
    [ -n "${ARCH:-}" ] && printf '  Auto-install it:  re-run with %sJCLAW_INSTALL_JRE=1%s (downloads a Zulu JRE %s).\n' "$CYAN" "$RESET" "$MIN_JAVA" >&2
    case "${OS:-}" in
        macos) printf '  Or with Homebrew: %sbrew install --cask zulu@%s%s\n' "$CYAN" "$MIN_JAVA" "$RESET" >&2 ;;
        linux) printf '  Or your package manager (after adding the Azul repo): %ssudo apt install zulu%s-jdk%s\n' "$CYAN" "$MIN_JAVA" "$RESET" >&2 ;;
    esac
    printf '  Or download:      %shttps://www.azul.com/downloads/?package=jre%s\n' "$CYAN" "$RESET" >&2
    exit 1
}

# ─── Download helper (curl or wget) ──────────────────────────────────────────
download() {
    url="$1"; out="$2"
    if command -v curl >/dev/null 2>&1; then
        curl -fL --progress-bar -o "$out" "$url"
    elif command -v wget >/dev/null 2>&1; then
        wget --show-progress -qO "$out" "$url"
    else
        die "need curl or wget to download the bundle."
    fi
}

resolve_url() {
    if [ "$JCLAW_VERSION" = "latest" ]; then
        # GitHub redirects this to the newest release's asset.
        echo "https://github.com/$JCLAW_REPO/releases/latest/download/$ASSET"
    else
        tag="$JCLAW_VERSION"
        case "$tag" in v*) ;; *) tag="v$tag" ;; esac   # tolerate "0.13.40" or "v0.13.40"
        echo "https://github.com/$JCLAW_REPO/releases/download/$tag/$ASSET"
    fi
}

extract() {
    zip="$1"; dest="$2"
    if command -v unzip >/dev/null 2>&1; then
        unzip -q -o "$zip" -d "$dest"
    elif command -v jar >/dev/null 2>&1; then
        # `jar` is a JDK tool — present only when the host already had a full JDK
        # (an auto-installed JRE has none), so unzip is the primary path.
        ( cd "$dest" && jar xf "$zip" )
    else
        die "need 'unzip' (or a JDK's 'jar' tool) to extract the bundle."
    fi
}

# ─── Main ────────────────────────────────────────────────────────────────────
banner
detect_os

step "Checking prerequisites"
check_java

step "Resolving release"
URL=$(resolve_url)
substep "$JCLAW_VERSION → ${DIM}$URL${RESET}"

step "Downloading $ASSET ${DIM}(~400 MB, first run only)${RESET}"
TMP_DL=$(mktemp -d "${TMPDIR:-/tmp}/jclaw-install.XXXXXX")
ZIP="$TMP_DL/$ASSET"
download "$URL" "$ZIP"

step "Installing to $APP_DIR"
mkdir -p "$JCLAW_HOME"
if [ -d "$APP_DIR" ]; then
    ROLLBACK="$APP_DIR.rollback.$$"
    mv "$APP_DIR" "$ROLLBACK"
    substep "moved the previous install aside (restored automatically on failure)"
fi
extract "$ZIP" "$JCLAW_HOME"
[ -d "$APP_DIR" ] || die "extract did not produce $APP_DIR — the archive layout may have changed."
chmod +x "$APP_DIR/jclaw.sh" "$APP_DIR/play" "$APP_DIR/gradlew" 2>/dev/null || true
substep "extracted"

# Convenience shim so `jclaw` works from anywhere.
mkdir -p "$JCLAW_BIN_DIR"
cat > "$JCLAW_BIN_DIR/jclaw" <<EOF
#!/bin/sh
exec "$APP_DIR/jclaw.sh" "\$@"
EOF
chmod +x "$JCLAW_BIN_DIR/jclaw"
substep "linked ${CYAN}jclaw${RESET} → $JCLAW_BIN_DIR/jclaw"

# Past this point a failure shouldn't roll back a good extract.
ROLLBACK=''

# ─── Launch ──────────────────────────────────────────────────────────────────
if [ -n "$JCLAW_NO_START" ]; then
    step "Install complete"
    printf '\n  Start it with:  %s%s/jclaw.sh start%s\n' "$CYAN" "$APP_DIR" "$RESET"
else
    step "Starting JClaw"
    "$APP_DIR/jclaw.sh" start || die "JClaw failed to start — see the output above."
fi

# ─── Summary ─────────────────────────────────────────────────────────────────
printf '\n%s✓ JClaw is installed.%s\n\n' "$BOLD$EMERALD" "$RESET"
[ -z "$JCLAW_NO_START" ] && printf '  Open       %shttp://localhost:%s%s\n' "$CYAN" "$JCLAW_PORT" "$RESET"
printf '  Manage     %sjclaw status%s · %sjclaw stop%s · %sjclaw restart%s\n' \
    "$CYAN" "$RESET" "$CYAN" "$RESET" "$CYAN" "$RESET"
printf '  Installed  %s%s\n' "$DIM" "$APP_DIR$RESET"

# Nudge if the shim dir isn't on PATH yet.
case ":$PATH:" in
    *":$JCLAW_BIN_DIR:"*) ;;
    *) printf '\n%snote:%s %s is not on your PATH. Add it:\n       %sexport PATH="%s:$PATH"%s\n' \
            "$YELLOW" "$RESET" "$JCLAW_BIN_DIR" "$CYAN" "$JCLAW_BIN_DIR" "$RESET" ;;
esac
printf '\n'
