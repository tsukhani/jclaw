#!/bin/sh
# JClaw one-line installer (macOS + Linux)
#
#   curl -fsSL https://raw.githubusercontent.com/tsukhani/jclaw/main/install.sh | sh
#
# Downloads the self-contained jclaw-bundle.zip from GitHub Releases, verifies
# Java 25+ (the bundle's only runtime dependency), extracts it to ~/.jclaw, and
# starts JClaw on http://localhost:9000.
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

APP_DIR="$JCLAW_HOME/jclaw"     # bundle zip extracts under a jclaw/ prefix
ASSET="jclaw-bundle.zip"
MIN_JAVA=25

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
ROLLBACK=''
cleanup() {
    rc=$?
    [ -n "$TMP_DL" ] && rm -rf "$TMP_DL" 2>/dev/null || true
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
        Linux)  OS=linux  ;;
        Darwin) OS=macos  ;;
        MINGW*|MSYS*|CYGWIN*)
            die "this is the Unix installer. On Windows run the PowerShell one:
       irm https://raw.githubusercontent.com/$JCLAW_REPO/main/install.ps1 | iex" ;;
        *) die "unsupported OS: $(uname -s)" ;;
    esac
}

# ─── Dependency check: Java 25+ (the only one) ───────────────────────────────
check_java() {
    if ! command -v java >/dev/null 2>&1; then
        java_missing "Java was not found on your PATH."
    fi
    # Major version from the first line of `java -version` (stderr):
    #   openjdk version "25.0.1" 2025-...  →  25
    #   java version "1.8.0_412"           →  1   (correctly rejected)
    jv=$(java -version 2>&1 | head -n1 | sed -n 's/.*version "\([0-9][0-9]*\).*/\1/p')
    if [ -z "$jv" ]; then
        java_missing "Could not parse the version from \`java -version\`."
    fi
    if [ "$jv" -lt "$MIN_JAVA" ]; then
        java_missing "Found Java $jv, but JClaw needs Java $MIN_JAVA or newer."
    fi
    substep "Java $jv detected ${DIM}($(command -v java))${RESET}"
}

java_missing() {
    printf '%serror:%s %s\n\n' "$RED" "$RESET" "$1" >&2
    printf 'JClaw needs a Java %s+ runtime (Zulu or Temurin recommended).\n' "$MIN_JAVA" >&2
    case "${OS:-}" in
        macos) printf '  Install with Homebrew:  %sbrew install --cask zulu@%s%s\n' "$CYAN" "$MIN_JAVA" "$RESET" >&2 ;;
        linux) printf '  Debian/Ubuntu:  %ssudo apt install zulu%s-jdk%s  (after adding the Azul repo)\n' "$CYAN" "$MIN_JAVA" "$RESET" >&2
               printf '  Fedora/RHEL:    %ssudo dnf install zulu%s-jdk%s\n' "$CYAN" "$MIN_JAVA" "$RESET" >&2 ;;
    esac
    printf '  Or download:    %shttps://www.azul.com/downloads/?version=java-%s%s\n' "$CYAN" "$MIN_JAVA" "$RESET" >&2
    printf '\nRe-run this installer once Java %s is on your PATH.\n' "$MIN_JAVA" >&2
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
        # The JDK ships `jar`, which is guaranteed present since we require Java.
        ( cd "$dest" && jar xf "$zip" )
    else
        die "need 'unzip' (or the JDK 'jar' tool) to extract the bundle."
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
