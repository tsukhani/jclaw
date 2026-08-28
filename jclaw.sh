#!/usr/bin/env bash
set -euo pipefail

# `upgrade` replaces the install directory, so a shell sitting in it hands us a
# dead CWD — without this, every subshell prints getcwd errors before any output.
pwd -P >/dev/null 2>&1 || cd "${0%/*}" 2>/dev/null || cd / 2>/dev/null

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
FRONTEND_PID_FILE="frontend.pid"

# Git Bash / MSYS2 / Cygwin drive NATIVE Windows binaries (the java.exe the
# Windows installer unpacks) from a POSIX shell. Those cannot resolve a /c/...
# path and split classpaths on ';', so anything handed to them needs translating
# — and where a relative path will do, it is the portable answer (JCLAW-1104).
case "$(uname -s)" in
    MINGW*|MSYS*|CYGWIN*) IS_WINDOWS=1; CP_SEP=';' ;;
    *)                    IS_WINDOWS=0; CP_SEP=':' ;;
esac

# Absolute POSIX path -> native form. No-op off Windows.
native_path() {
    if [[ "$IS_WINDOWS" == 1 ]]; then cygpath -w "$1"; else printf '%s\n' "$1"; fi
}

# One pid per line for sockets on $1; pass "listen" to keep only LISTENing ones.
# lsof is absent from Git Bash, so Windows falls back to netstat. Exit 2 means
# NEITHER tool exists: callers must not read that as "the port is free", which is
# how the pre-start conflict check would quietly stop guarding (JCLAW-1105).
_port_pids() {
    local port="$1" mode="${2:-}"
    if command -v lsof >/dev/null 2>&1; then
        if [[ "$mode" == listen ]]; then
            lsof -ti :"$port" -sTCP:LISTEN 2>/dev/null || true
        else
            lsof -ti :"$port" 2>/dev/null || true
        fi
        return 0
    fi
    if command -v netstat >/dev/null 2>&1; then
        # Windows: "TCP  0.0.0.0:9000  0.0.0.0:0  LISTENING  1234"
        netstat -ano 2>/dev/null | awk -v p="$port" -v m="$mode" '
            $1 == "TCP" && $2 ~ ":" p "$" { if (m != "listen" || $4 == "LISTENING") print $5 }' \
            | sort -u
        return 0
    fi
    return 2
}
port_pids()          { _port_pids "$1"; }
port_listener_pids() { _port_pids "$1" listen; }

# How to refer to this script in help/usage text: the global `jclaw` shim when
# it's on PATH and resolves to THIS install (the shim `exec`s jclaw.sh, so $0
# can't tell a shim call from a direct ./jclaw.sh call — we detect the shim
# instead), otherwise ./jclaw.sh. Computed once; referenced as ${INVOKE} in every
# user-facing command example below.
INVOKE='./jclaw.sh'
_invoke_shim="$(command -v jclaw 2>/dev/null || true)"
if [[ -n "$_invoke_shim" ]] && grep -qF "$SCRIPT_DIR/jclaw.sh" "$_invoke_shim" 2>/dev/null; then
    INVOKE='jclaw'
fi
unset _invoke_shim

# Bundle-mode play resolution. Runs unconditionally on every dispatch
# (including stop, secret, status, logs) so any code path that shells
# out to `play` finds the bundled launcher next to this script before
# the system one. Originally lived inside check_prereqs/check_play, but
# stop didn't call check_prereqs — so `./jclaw.sh stop` from a fresh
# bundle hit `play: command not found` even though ./play sat right
# next to jclaw.sh. Hoisting to top-level decouples binary resolution
# (always needed when any command shells out to play) from prerequisite
# validation (only needed for build/run paths).
if [[ -x "$SCRIPT_DIR/play" ]]; then
    export PATH="$SCRIPT_DIR:$PATH"
fi

# Co-located managed JRE. The one-line installers (install.sh / install.ps1) drop a
# self-contained Zulu JRE 25 at <home>/jre — a sibling of this script's dir — when the
# host has no system Java 25. Prefer it on PATH so `jclaw start/restart/...` run without
# a system-wide Java. No-op in a dev clone (no ../jre) and whenever JCLAW_JRE_SKIP is set.
# Matches both `java` (Unix) and `java.exe` (Windows, run via Git Bash) and handles the
# macOS Contents/Home nesting.
if [[ -z "${JCLAW_JRE_SKIP:-}" && -d "$SCRIPT_DIR/../jre" ]]; then
    _jre_java=$(find "$SCRIPT_DIR/../jre" -type f \( -name java -o -name java.exe \) 2>/dev/null \
        | grep -E '/bin/java(\.exe)?$' | head -1)
    if [[ -n "$_jre_java" ]]; then
        JAVA_HOME="$(cd "$(dirname "$(dirname "$_jre_java")")" && pwd)"
        export JAVA_HOME
        export PATH="$JAVA_HOME/bin:$PATH"
    fi
    unset _jre_java
fi

# Audience detection: developers run from a `git clone`, end users run
# from an unzipped `play dist` tarball with no .git/ in tow. Used by
# both show_intro (bare invocation banner) and usage (--help / unknown
# arg) to render an appropriately-scoped command surface — no point
# offering `setup` to someone who can't run it. Anchored at SCRIPT_DIR
# so the classification follows where jclaw.sh lives, not where the
# user happened to cd.
is_developer_clone() {
    /usr/bin/git -C "$SCRIPT_DIR" rev-parse --is-inside-work-tree >/dev/null 2>&1
}

usage() {
    if is_developer_clone; then
        cat <<EOF
Usage: ${INVOKE} [options] <https|no-https|secret|setup|init-worktree|reset|start|stop|restart|status|logs|loadtest|evals|test|e2e|dist|bundle|completion|shim|uninstall|help>

Commands:
  setup     One-time per-clone bootstrap: wires git hooks (.githooks/),
            installs frontend dependencies (so pre-commit's lint-staged works),
            generates certs/.env (with a fresh application secret) if missing,
            and verifies both 'origin' and 'github' remotes exist. Run once
            after every fresh clone. Idempotent — safe to re-run.
  init-worktree
            Slim per-worktree bootstrap: ensures certs/.env carries a fresh
            application secret (if missing) and a deterministic PLAY_TEST_PORT
            so parallel \`play autotest\` runs across git worktrees don't
            /@kill each other. Invoked automatically by .githooks/post-checkout
            on \`git worktree add\`; run by hand to back-fill pre-hook worktrees.
  secret    Generate (or rotate) the application secret in certs/.env (delegates
            to 'play secret', which writes the variable named in
            application.conf's \${...} placeholder). Run after a suspected
            leak or as routine hygiene. Restart the app to pick up the new
            value.
  https     Generate a TLS PEM cert+key at certs/host.cert and host.key
            (mkcert preferred, openssl fallback). The next 'start' enables
            the 9443 HTTPS listener (HTTP/2 + HTTP/3 via ALPN) when those
            certs pass strict validation. conf/application.conf is never
            modified — the toggle is purely cert presence + validity.
  no-https  Disable HTTPS by deleting certs/host.cert and host.key. The
            next 'start' boots HTTP/1.1 only on port 9000.
  reset     Clear the admin password hash from the Config DB so the next
            launch routes through the in-app /setup-password flow. Use
            when the operator has forgotten the password and is locked
            out of the running instance. Safe to run while the app is
            up (db.url has AUTO_SERVER=TRUE).
  start     Start the Play backend and Nuxt frontend
  stop      Stop the running Play backend and Nuxt frontend
  restart   Stop and start (combines stop + start)
  status    Show whether backend and frontend are running
  logs      Tail the production application log
  loadtest  Drive the in-process load-test harness against /api/chat/stream
  evals     Validate the eval dataset in evals/suites, and score a
            recorded agent run against it. Offline — no backend, no model.
  test      Run backend tests (play autotest), frontend tests (pnpm test),
            and frontend quality gates (stylelint, lint, typecheck), and
            report a consolidated pass/fail summary. Exits non-zero on any
            failure.
  e2e       Run the Playwright end-to-end suite against an already-running
            server. Separate from 'test' by design — needs a live server and
            JCLAW_ADMIN_PASSWORD from certs/.env. Local UAT, not a gate.
  dist      Build the developer-distribution zip at dist/jclaw.zip and exit.
            Runs precompile + frontend build + \`play dist\`; operators
            unzipping the result need Java 25 + Gradle + Play 1 fork on
            their machine to launch it. For a self-contained tarball,
            see \`bundle\` (or the Dockerfile, which ships the same artifact).
  bundle    Build the self-contained bundle zip at dist/jclaw-bundle.zip and
            exit. Like \`dist\` but via \`play bundle\` — bakes in the framework,
            resolved deps, and a \`./play\` launcher, so the unzipped tree runs
            with only a Java 25 JRE (no Gradle/Play install). Same artifact the
            Dockerfile ships inside the container image.
  completion Print a shell completion script (completion <bash|zsh>) so
            \`${INVOKE} <TAB>\` completes subcommands. The installer wires this up;
            run it by hand for dev clones or after an upgrade.
  uninstall Remove an installed JClaw: undo completion wiring, drop the \`jclaw\`
            shim, and delete the install dir (~/.jclaw). Warns first, then
            confirms. Not applicable to a developer git clone.
  help      Print this usage reference and exit. Equivalent to --help / -h.

Options:
  --dev                   Run in development mode (play run + pnpm dev)
  --backend-port <port>   Play backend port (default: 9000)
  --frontend-port <port>  Nuxt dev server port, dev mode only (default: 3000)

Environment:
  JCLAW_JVM_HEAP          Symmetric heap override — sets both -Xms and -Xmx to
                          the same value. Default is asymmetric (Xms 512m, Xmx
                          2g) to avoid committing 2 GB at boot on idle deploys;
                          ZGC handles resize without pauses, so a fixed heap
                          isn't required for latency.
                          Example: JCLAW_JVM_HEAP=4g ${INVOKE} start
  JCLAW_JVM_XMS           Override -Xms only (default: 512m).
  JCLAW_JVM_XMX           Override -Xmx only (default: 2g).
  JCLAW_JVM_SOFTMAX       ZGC soft heap target (default: 1g) — the size ZGC
                          collects toward, exceeding it only up to -Xmx to
                          avoid allocation stalls. Not derived from the heap,
                          so raise it when you raise -Xmx or ZGC keeps
                          targeting 1g. Must stay above the live set.
                          Example: JCLAW_JVM_HEAP=8g JCLAW_JVM_SOFTMAX=4g ${INVOKE} start
  JCLAW_JVM_OPTS          Extra JVM flags appended after the built-in set.
                          Last-wins for value flags (e.g. MaxDirectMemorySize),
                          so this lets you override most hardcoded settings.
                          Example: JCLAW_JVM_OPTS='-XX:MaxDirectMemorySize=512m'
  JCLAW_FORCE_SPA_BUILD   Rebuild the SPA even when public/spa looks up to
                          date. The build is normally skipped unless a
                          frontend file is newer than public/spa; set this
                          when a change leaves no newer file (e.g. a
                          build-time env var) and the SPA must be rebuilt.

Load-test options (only used with the 'loadtest' command):
  --concurrency <n>       Parallel workers (default: 10). Each worker drives
                          a single conversation across <turns> sequential
                          chat requests, so total requests = concurrency * turns.
  --turns <n>             Sequential chat requests per worker, all within the
                          same conversation (default: 5). Turn 1 starts a
                          fresh conversation; turns 2..N reuse the
                          conversationId, so growing-history behavior
                          (system-prompt assembly cost, provider prompt-cache
                          hits, model recall) gets exercised under load. To
                          simulate N independent fresh-conversation starts
                          instead, set --turns 1 and crank --concurrency.
  --ttft-ms <n>           Simulated time-to-first-token in ms (default: 100)
  --tokens-per-second <n> Simulated token throughput (default: 50)
  --response-tokens <n>   Tokens per simulated response (default: 40)
  --clean                 Delete leftover loadtest data (conversations, messages, events,
                          latency samples) without running a test. Every run already
                          cleans up on completion, so this is for recovery — a run the
                          server died partway through, or data from an older build.
  --compress              Send 'Accept-Encoding: br, gzip' on each loadtest request so the
                          server's HttpContentCompressor engages — measures the cost of the
                          encoding path. Default off (Java HttpClient sends no Accept-Encoding,
                          so compression doesn't engage even when wired into the pipeline).
  --provider <name>       Registered provider name to drive (e.g. ollama-local,
                          ollama-cloud, openrouter, openai). Must be configured
                          (apiKey/baseUrl set). Pairs with --model: providing
                          one without the other is an error. Omitting both
                          falls back to the in-process mock harness.
  --model <name>          Model to drive on the chosen --provider. Must be
                          pullable/serveable by it. Pairs with --provider.
  --message <text>        Single user message replayed every turn within a
                          conversation. Defaults to a length-constrained
                          factual prompt so cross-model tokens/sec comparisons
                          are apples-to-apples. The same message every turn
                          surfaces in-context recall behavior (does the model
                          parrot, get terse, reference earlier turns?) and
                          provider prompt-cache hits. Mutually exclusive with
                          --prompts.
  --prompts <path>        Path to a UTF-8 file with one user prompt per line.
                          Activates varied-prompts mode: turn t sends line t
                          instead of replaying --message. The file must have
                          at least --turns non-blank lines. Use to drive a
                          topic flow (different question every turn) inside a
                          growing conversation. Mutually exclusive with
                          --message. The repo ships loadtest/prompts.txt with
                          50 fair-comparison prompts for convenience.

Examples:
  ${INVOKE} setup                                    # One-time setup after fresh clone
  ${INVOKE} --dev start                              # Start in dev mode
  ${INVOKE} --dev --backend-port 8080 start          # Dev mode with custom backend port
  ${INVOKE} start                                    # Start production in current directory
  ${INVOKE} dist                                     # Build dist/jclaw.zip (then unzip wherever)
  ${INVOKE} --dev stop                               # Stop dev mode services
  ${INVOKE} stop                                     # Stop production in current directory
  ${INVOKE} loadtest                                 # Drive default 10 workers x 5-turn conversations against :9000
  ${INVOKE} --concurrency 50 --turns 1 loadtest      # 50 fresh single-turn conversations (cold-start at scale)
  ${INVOKE} --concurrency 5 --turns 50 loadtest      # 5 deep conversations of 50 turns each (history growth)
  ${INVOKE} --turns 10 --prompts loadtest/prompts.txt loadtest             # varied prompt per turn (mock)
  ${INVOKE} --provider openrouter --model amazon/nova-micro-v1 loadtest    # real provider
EOF
    else
        # User-facing reference: trimmed to the runtime commands and
        # operator knobs that actually apply to a dist install. Setup,
        # test, --dev, --frontend-port, and the loadtest
        # options are all developer-only and would either fail or
        # silently no-op against an unzipped distribution, so they're
        # omitted entirely. loadtest itself technically works against a
        # running prod backend, but it's an operator/dev tool and not
        # part of the "I just want to run JClaw" contract.
        cat <<EOF
Usage: ${INVOKE} [options] <https|no-https|reset|start|stop|restart|status|logs|upgrade|completion|shim|uninstall|help>

Commands:
  https     Generate a TLS PEM cert+key at certs/host.cert and host.key.
            The next 'start' enables HTTPS (HTTP/2 + HTTP/3 via ALPN) on
            port 9443 when the cert+key pass strict validation.
  no-https  Disable HTTPS by deleting certs/host.cert and host.key. The
            next 'start' boots HTTP/1.1 only on port 9000.
  reset     Clear the admin password hash from the Config DB so the next
            launch routes through the in-app /setup-password flow. Use
            when you've forgotten the password and are locked out of
            the running instance.
  start     Start JClaw (Play backend serving the bundled SPA)
  stop      Stop the running instance
  restart   Stop and start (combines stop + start)
  status    Show whether the backend is running
  logs      Tail the application log
  upgrade   Download the newest release and install it in place, then restart.
            Your data, workspace, credentials and installed apps are kept, the
            database is backed up first, and a release that fails to come up is
            rolled back automatically. Use --check to look without installing.
  completion Print a shell completion script (completion <bash|zsh>) so
            \`${INVOKE} <TAB>\` completes subcommands. The installer wires this up.
  shim      Rewrite the \`jclaw\` command so it points at this install. Written
            by the installer and refreshed by \`upgrade\`; run it by hand only if
            the command went missing or points somewhere stale.
  uninstall Remove JClaw: undo completion wiring, drop the \`jclaw\` shim, and
            delete the install dir (~/.jclaw). Warns first, then confirms.
  help      Print this usage reference and exit. Equivalent to --help / -h.

Options:
  --backend-port <port>   Backend HTTP port (default: 9000)

Environment:
  JCLAW_JVM_HEAP          Symmetric heap override — sets both -Xms and -Xmx
                          to the same value. Default is asymmetric (Xms 512m,
                          Xmx 2g): JClaw commits ~512 MB at boot and grows
                          to 2 GB on demand. ZGC resizes without pauses.
                          Example: JCLAW_JVM_HEAP=4g ${INVOKE} start
  JCLAW_JVM_XMS           Override -Xms only (default: 512m).
  JCLAW_JVM_XMX           Override -Xmx only (default: 2g).
  JCLAW_JVM_SOFTMAX       ZGC soft heap target (default: 1g) — the size ZGC
                          collects toward, exceeding it only up to -Xmx to
                          avoid allocation stalls. Not derived from the heap,
                          so raise it when you raise -Xmx or ZGC keeps
                          targeting 1g. Must stay above the live set.
                          Example: JCLAW_JVM_HEAP=8g JCLAW_JVM_SOFTMAX=4g ${INVOKE} start
  JCLAW_JVM_OPTS          Extra JVM flags appended after the built-in set.
                          Last-wins for value flags (e.g. MaxDirectMemorySize),
                          so this lets you override most hardcoded settings.
                          Example: JCLAW_JVM_OPTS='-XX:MaxDirectMemorySize=512m'

Examples:
  ${INVOKE} start                              # Start on default port 9000
  ${INVOKE} --backend-port 8080 start          # Start on a custom port
  ${INVOKE} status                             # Check whether it's running
  ${INVOKE} logs                               # Tail the application log
  ${INVOKE} stop                               # Stop the running instance
  JCLAW_JVM_HEAP=4g ${INVOKE} start            # Start with a 4 GB heap
EOF
    fi
}

# True if the argument is a recognized subcommand. Used by the help-routing
# logic so that `./jclaw.sh help <cmd>` falls back to the top-level banner
# when <cmd> is unknown rather than blowing up — and so the parser can
# distinguish the per-command help path from the bare-help path.
is_known_command() {
    case "$1" in
        https|no-https|secret|setup|init-worktree|reset|start|stop|restart|status|logs|upgrade|loadtest|evals|test|e2e|dist|bundle|completion|uninstall)
            return 0
            ;;
        *)
            return 1
            ;;
    esac
}

# Dispatch per-command help. Caller has already verified the argument
# names a real subcommand via is_known_command, so the wildcard arm is
# only reachable through programmer error and hands back the full banner.
usage_for() {
    case "$1" in
        https)    usage_https    ;;
        no-https) usage_no_https ;;
        secret)        usage_secret         ;;
        setup)         usage_setup          ;;
        init-worktree) usage_init_worktree  ;;
        reset)         usage_reset          ;;
        start)    usage_start    ;;
        stop)     usage_stop     ;;
        restart)  usage_restart  ;;
        status)   usage_status   ;;
        logs)     usage_logs     ;;
        upgrade)  usage_upgrade  ;;
        loadtest) usage_loadtest ;;
        scrapetest) usage_scrapetest ;;
        evals)    usage_evals    ;;
        test)     usage_test     ;;
        e2e)      usage_e2e      ;;
        dist)     usage_dist     ;;
        bundle)   usage_bundle   ;;
        completion) usage_completion ;;
        uninstall)  usage_uninstall  ;;
        *)        usage          ;;
    esac
}

usage_uninstall() {
    cat <<EOF
Usage: ${INVOKE} uninstall [--yes]

Remove this installed JClaw: stop it if running, undo the shell-completion
wiring (the generated scripts and the managed block in your shell rc), remove
the \`jclaw\` command shim, and delete the install directory (e.g. ~/.jclaw,
including the managed JRE). The install directory is resolved from where this
script lives, never from your CWD.

Prompts for confirmation after printing exactly what it will remove. Pass --yes
(or -y) to skip the prompt for scripted removal. Not available on a developer
git clone — there's nothing installed to remove (use \`completion uninstall\` to
undo just the completion wiring).
EOF
}

usage_completion() {
    cat <<EOF
Usage: ${INVOKE} completion <bash|zsh|install>

Generate shell completion for \`jclaw\`/\`jclaw.sh\` so \`${INVOKE} <TAB>\` completes
subcommands. The emitted command list reflects this install (a dist exposes
fewer commands than a developer clone).

  install  Write the bash + zsh scripts to your per-user completion dirs and
           wire your shell's rc file (idempotent; honors JCLAW_NO_RC_EDIT=1).
           This is what the one-line installer runs; also handy on a dev clone.
  bash     Print a bash completion script to stdout. Enable for this shell:
             source <(${INVOKE} completion bash)
           or permanently where bash-completion looks:
             ${INVOKE} completion bash > ~/.local/share/bash-completion/completions/jclaw
  zsh      Print a zsh completion script to stdout. Enable by putting it on your
           \$fpath before compinit, e.g.:
             ${INVOKE} completion zsh > "\${fpath[1]}/_jclaw"   # then: compinit
EOF
}

usage_https() {
    cat <<EOF
Usage: ${INVOKE} https [--install-ca]

Generate a TLS PEM cert+key at certs/host.cert and certs/host.key,
overwriting any existing pair. After this completes, the next
'${INVOKE} start' enables the 9443 HTTPS listener (HTTP/2 + HTTP/3
via ALPN) at runtime via -Dhttps.port=9443, gated by a strict
validity check on the cert+key. conf/application.conf is NOT
modified — the toggle is purely a function of cert presence + validity.

Cert source preference:
  1. mkcert if installed. Produces a cert signed by the local CA that
     'mkcert -install' added to the system trust store, so Chrome's
     QUIC stack will negotiate HTTP/3 in the browser without warnings.
     Install once with: brew install mkcert (then 'mkcert -install',
     or pass --install-ca on the next https invocation — see below).
  2. openssl as a fallback. Produces a self-signed cert; serves
     correctly but browsers warn and Chrome refuses to upgrade to
     HTTP/3 (its QUIC stack only honors trusted certs).

Errors out if neither tool is on PATH.

Options:
  --install-ca   Run 'mkcert -install' before issuing the leaf cert,
                 adding the local CA to the system / NSS (Firefox) /
                 Java trust stores. Idempotent (mkcert skips stores
                 that already trust the CA) but may prompt for admin
                 auth / Touch ID on stores that need updating.
                 Requires mkcert; errors out with install instructions
                 when mkcert is absent (no openssl fallback for this
                 flag — only mkcert can install a reusable local CA).

After this command, restart the app to apply: '${INVOKE} restart'.
EOF
}

usage_no_https() {
    cat <<EOF
Usage: ${INVOKE} no-https

Disable HTTPS by deleting certs/host.cert and certs/host.key. The
next '${INVOKE} start' will see no valid pair (certs_valid → false)
and skip the -Dhttps.port=9443 override, so Play boots HTTP-1.1 only
on port 9000. Idempotent — no-op when the files are already absent.

To re-enable, run '${INVOKE} https' (regenerates a fresh cert+key).
conf/application.conf is NOT modified by either command.

After this command, restart the app to apply: '${INVOKE} restart'.
EOF
}

usage_secret() {
    cat <<EOF
Usage: ${INVOKE} secret

Generate or rotate the application secret. Delegates to 'play secret',
which writes the variable named in conf/application.conf's \${...}
placeholder (default: PLAY_SECRET) into a per-clone certs/.env file. The
file is created with mode 600 on first run and preserved across rotations
(only the secret line is rewritten).

Restart the app to pick up the new value.

Examples:
  ${INVOKE} secret              # Generate or rotate
  ${INVOKE} restart             # Pick up the new value
EOF
}

usage_setup() {
    if is_developer_clone; then
        cat <<EOF
Usage: ${INVOKE} setup

One-time per-clone bootstrap (developer flow). Wires git hooks
(.githooks/), installs frontend dependencies so pre-commit's
lint-staged is available, generates certs/.env with a fresh
application secret if missing, and verifies both 'origin' and
'github' remotes are configured. Idempotent — safe to re-run after
every fresh clone.

Example:
  ${INVOKE} setup
EOF
    else
        cat <<EOF
Usage: ${INVOKE} setup

Not available in this distribution. The 'setup' command is part of
the developer flow (run after a fresh git clone — wires git hooks,
installs frontend deps, etc.). End-user 'play dist' installs don't
need it: start the app directly with ${INVOKE} start.

For the full list of commands in this distribution: ${INVOKE} help
EOF
    fi
}

usage_init_worktree() {
    if is_developer_clone; then
        cat <<EOF
Usage: ${INVOKE} init-worktree

Slim per-worktree bootstrap (developer flow). Ensures certs/.env exists
with a fresh application secret (only if missing — never rotates) and
appends a deterministic PLAY_TEST_PORT so parallel \`play autotest\`
runs across git worktrees don't /@kill each other on a shared port.

Invoked automatically by .githooks/post-checkout when \`git worktree add\`
fires, so a fresh worktree is immediately safe for parallel testing.
Run by hand to back-fill a worktree created before the hook landed.

Idempotent — safe to re-run; preserves any existing PLAY_SECRET and
PLAY_TEST_PORT values.

Example:
  ${INVOKE} init-worktree
EOF
    else
        cat <<EOF
Usage: ${INVOKE} init-worktree

Not available in this distribution. The 'init-worktree' command is part
of the developer flow (used by the post-checkout hook to prep fresh git
worktrees for parallel \`play autotest\`).

For the full list of commands in this distribution: ${INVOKE} help
EOF
    fi
}

usage_reset() {
    cat <<EOF
Usage: ${INVOKE} reset

Clear the admin password hash from the Config DB so the next launch
routes through the in-app /setup-password flow. Use when the operator
has forgotten the password and is locked out of the running instance.
Safe to run while the app is up — db.url has AUTO_SERVER=TRUE so a
second JDBC connection joins the running H2 instance without
contention.

Prompts for confirmation before touching the DB. Set JCLAW_RESET_YES=1
to skip the prompt for scripted use.

Environment:
  JCLAW_RESET_YES=1       Skip the y/N confirmation prompt.

Examples:
  ${INVOKE} reset                       # Interactive
  JCLAW_RESET_YES=1 ${INVOKE} reset     # No prompt
EOF
}

usage_start() {
    if is_developer_clone; then
        cat <<EOF
Usage: ${INVOKE} [options] start

Start JClaw in production mode (Play backend serving the bundled SPA),
or with --dev start the Play dev backend plus the Nuxt frontend on
separate ports. First run on a fresh checkout auto-generates certs/.env
with a random application secret if one isn't already present.

Options:
  --dev                   Run in development mode (play run + pnpm dev)
  --backend-port <port>   Play backend port (default: 9000)
  --frontend-port <port>  Nuxt dev server port, dev mode only (default: 3000)

Environment:
  JCLAW_JVM_HEAP          Symmetric heap — sets both -Xms and -Xmx to the
                          same value. Default is asymmetric (Xms 512m, Xmx 2g).
  JCLAW_JVM_XMS / XMX     Override -Xms / -Xmx independently
                          (defaults: 512m / 2g).
  JCLAW_JVM_SOFTMAX       ZGC soft heap target (default: 1g). Not derived from
                          the heap — raise it when you raise -Xmx.
  JCLAW_JVM_OPTS          Extra JVM flags appended last (last-wins for value
                          flags, e.g. -XX:MaxDirectMemorySize=512m).
  JCLAW_FORCE_SPA_BUILD   Rebuild the SPA even when public/spa looks up to
                          date. The build is normally skipped unless a
                          frontend file is newer than public/spa; set this
                          when a change leaves no newer file (e.g. a
                          build-time env var) and the SPA must be rebuilt.

Examples:
  ${INVOKE} start                              # Production in current directory
  ${INVOKE} --dev start                        # Dev mode
  ${INVOKE} --dev --backend-port 8080 start    # Dev with custom backend port
  JCLAW_JVM_HEAP=4g ${INVOKE} start            # 4 GB heap
EOF
    else
        cat <<EOF
Usage: ${INVOKE} [options] start

Start JClaw on the bundled Play backend (which serves the SPA from
this distribution package). First run auto-generates certs/.env with
a random application secret if one isn't already present.

Options:
  --backend-port <port>   Backend HTTP port (default: 9000)

Environment:
  JCLAW_JVM_HEAP          Symmetric heap — sets both -Xms and -Xmx to the
                          same value. Default is asymmetric (Xms 512m, Xmx 2g).
  JCLAW_JVM_XMS / XMX     Override -Xms / -Xmx independently
                          (defaults: 512m / 2g).
  JCLAW_JVM_SOFTMAX       ZGC soft heap target (default: 1g). Not derived from
                          the heap — raise it when you raise -Xmx.
  JCLAW_JVM_OPTS          Extra JVM flags appended last (last-wins).

Examples:
  ${INVOKE} start                              # Default port 9000
  ${INVOKE} --backend-port 8080 start          # Custom port
  JCLAW_JVM_HEAP=4g ${INVOKE} start            # 4 GB heap
EOF
    fi
}

usage_stop() {
    if is_developer_clone; then
        cat <<EOF
Usage: ${INVOKE} [options] stop

Stop the running JClaw instance. Reads the PID file the matching
start path wrote; in --dev mode also stops the Nuxt frontend.

Options:
  --dev                   Stop dev-mode services

Examples:
  ${INVOKE} stop                # Stop production in current directory
  ${INVOKE} --dev stop          # Stop dev mode
EOF
    else
        cat <<EOF
Usage: ${INVOKE} stop

Stop the running JClaw instance. Reads the PID file written by
the matching start path.

Example:
  ${INVOKE} stop
EOF
    fi
}

usage_restart() {
    if is_developer_clone; then
        cat <<EOF
Usage: ${INVOKE} [options] restart

Stop and start as one operation. Accepts the same flags as 'start'.

Options:
  --dev                   Restart in dev mode
  --backend-only          Restart the Play backend only, leaving the Nuxt
                          dev server running (dev mode only; ignored in
                          prod, where one JVM serves both)
  --backend-port <port>   Play backend port (default: 9000)
  --frontend-port <port>  Nuxt dev server port, dev mode only (default: 3000)

Environment:
  JCLAW_JVM_HEAP, JCLAW_JVM_XMS, JCLAW_JVM_XMX, JCLAW_JVM_SOFTMAX,
  JCLAW_JVM_OPTS — see 'start --help'.

Examples:
  ${INVOKE} restart
  ${INVOKE} --dev restart
  ${INVOKE} --dev restart --backend-only
EOF
    else
        cat <<EOF
Usage: ${INVOKE} [options] restart

Stop and start as one operation. Accepts the same flags as 'start'.

Options:
  --backend-port <port>   Backend HTTP port (default: 9000)

Environment:
  JCLAW_JVM_HEAP, JCLAW_JVM_XMS, JCLAW_JVM_XMX, JCLAW_JVM_SOFTMAX,
  JCLAW_JVM_OPTS — see 'start --help'.

Example:
  ${INVOKE} restart
EOF
    fi
}

usage_status() {
    if is_developer_clone; then
        cat <<EOF
Usage: ${INVOKE} [options] status

Show whether the Play backend (and, in --dev, the Nuxt frontend) is
running. Reports PID and port when up; "not running" otherwise.

Options:
  --dev                   Check dev-mode services

Example:
  ${INVOKE} status
EOF
    else
        cat <<EOF
Usage: ${INVOKE} status

Show whether the backend is running. Reports PID and port when up;
"not running" otherwise.

Example:
  ${INVOKE} status
EOF
    fi
}

usage_logs() {
    if is_developer_clone; then
        cat <<EOF
Usage: ${INVOKE} [options] logs

Tail the production application log (logs/application.log) — equivalent
to 'tail -f' on that file. Ctrl+C to exit.

Options:
  --dev                   Tail the dev-mode backend log instead

Example:
  ${INVOKE} logs
EOF
    else
        cat <<EOF
Usage: ${INVOKE} logs

Tail the application log (logs/application.log) — equivalent to
'tail -f' on that file. Ctrl+C to exit.

Example:
  ${INVOKE} logs
EOF
    fi
}

usage_upgrade() {
    cat <<EOF
Usage: ${INVOKE} upgrade [--check] [--version <tag>] [--yes]

Install the newest JClaw release over this one and restart. Only for
installs made by the one-line installer or an unzipped release archive —
a git clone is refused (use 'git pull'), as is a container (upgrade the
image with 'docker compose pull && docker compose up -d').

The release is downloaded, checksum-verified and unpacked while JClaw is
still serving, so a network or disk failure costs no downtime at all.
Only then is the instance stopped and the tree replaced.

Kept across the upgrade:
  data/ (database, attachments, search index), workspace/, logs/,
  certs/ (application secret and TLS pair), public/apps/, sidecar
  virtualenvs, and any other file this release does not ship. A
  conf/application.conf you have edited is kept too, with the release's
  copy left beside it as conf/application.conf.new-<version>.

Safety:
  The database is copied to data/backups/ before the swap, and a release
  that fails to answer /api/status within 4 minutes is rolled back
  automatically — tree and database together.

Options:
  --check                 Report the installed and latest versions, then exit
                          without changing anything.
  --version <tag>         Install a specific release (e.g. --version v0.17.48)
                          instead of the newest. Also permits re-installing or
                          going back to an earlier release.
  --yes, -y               Skip the confirmation prompt (for scripted upgrades).

Examples:
  ${INVOKE} upgrade --check
  ${INVOKE} upgrade
  ${INVOKE} upgrade --version v0.17.48 --yes
EOF
}

usage_loadtest() {
    if is_developer_clone; then
        cat <<EOF
Usage: ${INVOKE} [options] loadtest

Drive the in-process load-test harness against /api/chat/stream. The
harness simulates LLM streaming with controllable TTFT and throughput
so you can measure serving overhead, queueing, and latency percentiles
without spinning up a real upstream.

Options:
  --concurrency <n>       Parallel workers (default: 10). Each worker drives
                          one conversation across <turns> sequential requests.
  --turns <n>             Sequential chat requests per worker, all within the
                          same conversation (default: 5). Turn 1 starts a
                          fresh conversation; turns 2..N reuse the assigned
                          conversationId. Use --turns 1 with high --concurrency
                          to simulate cold-start at scale.
  --ttft-ms <n>           Simulated time-to-first-token in ms (default: 100)
  --tokens-per-second <n> Simulated token throughput (default: 50)
  --response-tokens <n>   Tokens per simulated response (default: 40)
  --backend-port <port>   Target port (default: 9000)
  --clean                 Delete leftover loadtest data — conversations, messages,
                          events and latency samples — without running a test.
                          Every run now cleans up on completion, so reach for
                          this only to recover: a run the server died partway
                          through leaves data behind (stopping a run does not
                          clean either), as does a run on a build older than
                          the automatic sweep.
  --compress              Send 'Accept-Encoding: br, gzip' so the server's
                          HttpContentCompressor engages — measures the cost
                          of the encoding path.
  --provider <name>       Registered provider to drive (e.g. ollama-local,
                          ollama-cloud, openrouter, openai, anthropic-via-
                          openrouter, …). Must be configured (apiKey/baseUrl
                          set in Settings). Pairs with --model: providing one
                          without the other is an error. Omitting both falls
                          back to the in-process mock harness — fine for
                          pipeline checks, but mock latency is stubbed so
                          end-to-end latency comparisons aren't meaningful.
  --model <name>          Model to drive on the chosen --provider. Must be
                          pullable/serveable by it. Pairs with --provider.
  --message <text>        Single user message replayed every turn within a
                          conversation. Default is a length-constrained
                          factual prompt ("Why is the sky blue? Answer in
                          exactly 50 words.") so cross-model tokens/sec
                          comparisons measure speed rather than how verbose
                          each model chose to be. Replaying the same message
                          surfaces in-context recall behavior (parroting,
                          terseness, "as I mentioned above") and provider
                          prompt-cache hits. Mutually exclusive with --prompts.
  --prompts <path>        Path to a UTF-8 file with one user prompt per line
                          (blank lines ignored). Activates varied-prompts
                          mode: turn t sends line t instead of replaying
                          --message. The file must have at least --turns
                          non-blank lines. Use to drive a topic flow rather
                          than a recall test. Mutually exclusive with
                          --message. The repo ships loadtest/prompts.txt
                          with 50 fair-comparison prompts.

Examples:
  ${INVOKE} loadtest                                                                                # 10 workers x 5-turn conversations, mock
  ${INVOKE} --concurrency 50 --turns 1 loadtest                                                     # 50 cold starts in parallel, mock
  ${INVOKE} --concurrency 5 --turns 50 loadtest                                                     # 5 deep conversations, mock
  ${INVOKE} --provider ollama-local --model gemma4:latest loadtest                                  # local real provider
  ${INVOKE} --provider ollama-cloud --model kimi-k2.5 loadtest                                      # cloud
  ${INVOKE} --provider openrouter --model google/gemini-3-flash-preview loadtest                    # alt cloud
  ${INVOKE} --turns 10 --prompts loadtest/prompts.txt loadtest                                      # varied prompts (mock)
  ${INVOKE} --turns 10 --prompts loadtest/prompts.txt --provider openrouter --model amazon/nova-micro-v1 loadtest  # varied prompts (real)
  ${INVOKE} --clean loadtest                                                                        # recover leftovers from an interrupted run
EOF
    else
        cat <<EOF
Usage: ${INVOKE} loadtest

Not available in this distribution. The 'loadtest' command is a
developer/operator tool that exercises /api/chat/stream with a
synthetic LLM stream — it lives in the dev workflow, not the
end-user runtime.

For the full list of commands in this distribution: ${INVOKE} help
EOF
    fi
}

usage_scrapetest() {
    cat <<'USAGE'
Usage: ./jclaw.sh scrapetest [options]

Runs the CF-100 corpus against one rung of the scrape escalation ladder and
reports per-tier access rates (JCLAW-1081). Needs the backend running: the
thing being measured is the shipped fetch stack, so a curl-based harness would
measure curl instead.

Options:
  --rung ID          Rung to measure (default 1). 1 = shared fetch chain;
                     scrape = the web_scrape tool's own path (adds robots + pacing).
  --concurrency N    Outbound fan-out, 1-16 (default 8).
  --out FILE         Write the full JSON report to FILE.

Build or refresh the corpus first:
  python3 evals/scrape/build_corpus.py --sample 40000 --per-stratum 25
USAGE
}

usage_evals() {
    if is_developer_clone; then
        cat <<EOF
Usage: ${INVOKE} evals [--suites <dir>] [--responses <file>] [--baseline <file>] [--out <file>]
       ${INVOKE} evals --capture <file> --agent <name> --suite <id> [--concurrency <n>]

Work with the eval dataset in evals/suites (JCLAW-875, JCLAW-883).

Validating and scoring are entirely offline: they start no backend, call no
model, and touch no database, so running them costs nothing on the serving
path. Capturing is the exception and says so — see below.

With no --responses it validates the dataset — every suite parses, every
check kind is one the scorer implements, every JSON Schema keyword is one it
enforces — and prints the case/check counts. That is the same validation
'play autotest' runs, so a malformed suite fails the build, not the next
eval run.

With --responses it scores a recorded agent run: pass rate, per-case
latency, failed checks by name, and the LLM calls the run spent. Cases the
agent never answered are reported as errored and kept out of the pass rate,
so a provider outage does not read as a quality collapse.

With --capture it drives a live agent through a suite and writes the recorded
run. This one needs the backend running, and it spends real model calls. The
agent is required, never defaulted — a sweep that silently ran against your
working agent is the accident this guards against. Turns leave no
conversation, no message history and no memories behind, and their latency is
not recorded into the Chat Performance histograms.

Tools DO execute for real, so use __evaltest__ — the eval sibling of
__loadtest__, provisioned on first capture, for which every tool is opt-in.
Grant it only what a suite needs in the agent editor; delete the agent to
clean up everything a sweep created.

Options:
  --suites <dir>     Suite directory (default: evals/suites)
  --responses <file> A recorded run: {"suite":…, "fingerprint":…, "responses":
                     {"<caseId>": {"output":…, "toolsCalled":[…], "llmCalls":N}}}
  --baseline <file>  An earlier --out report; exits non-zero if a case that
                     passed there fails now
  --out <file>       Write the report as JSON (feed it back as --baseline)
  --capture <file>   Drive a live agent and write the recorded run here
  --agent <name>     Which agent to drive (required with --capture)
  --suite <id>       Which suite to drive (required with --capture)
  --local            Read the suite from evals/local/suites instead of
                     evals/suites — for benchmark-derived suites, which
                     cannot be committed to a publicly mirrored repository
  --concurrency <n>  Cases in front of the model at once (default: 4, max 16)

Exit codes: 0 clean, 1 invalid dataset / failing case / regression / capture
failure, 2 usage.

Examples:
  ${INVOKE} evals                                              # validate the dataset
  ${INVOKE} evals --responses run.json --out report.json       # score a recorded run
  ${INVOKE} evals --responses run.json --baseline report.json  # catch regressions
  ${INVOKE} evals --capture run.json --agent __evaltest__ --suite tool-selection
EOF
    else
        cat <<EOF
Usage: ${INVOKE} evals

Not available in this distribution. The 'evals' command reads the
evals/ dataset and the compiled app classes from a developer checkout;
neither ships in a 'play dist' tarball.

For the full list of commands in this distribution: ${INVOKE} help
EOF
    fi
}

usage_test() {
    if is_developer_clone; then
        cat <<EOF
Usage: ${INVOKE} test

Run the full pre-push validation suite — backend tests (play autotest),
frontend tests (pnpm test), and frontend quality gates (stylelint, lint,
typecheck) — and print a consolidated pass/fail summary. Logs at
logs/test-*.log per check. Exits non-zero if any check fails, so it's
safe to wire into git hooks or CI.

Example:
  ${INVOKE} test
EOF
    else
        cat <<EOF
Usage: ${INVOKE} test

Not available in this distribution. The 'test' command runs the
backend + frontend suites against a developer checkout (it needs the
test/ directory and the frontend Vitest project, neither of which
ship with 'play dist' tarballs).

For the full list of commands in this distribution: ${INVOKE} help
EOF
    fi
}

usage_e2e() {
    if is_developer_clone; then
        cat <<EOF
Usage: ${INVOKE} e2e

Run the Playwright end-to-end suite (frontend/tests/e2e) against an
already-running JClaw server. Deliberately NOT part of '${INVOKE} test' or
Jenkins CI — it needs a live server and a real admin credential, so it is a
local UAT safety net rather than a merge gate.

Handles the three prerequisites that otherwise fail the suite before any
spec runs:
  * Credential — reads JCLAW_ADMIN_PASSWORD from certs/.env (gitignored,
    alongside PLAY_SECRET). Never put it in conf/application.conf: that
    file is tracked and ships to both remotes, one of which is public.
  * Base URL — follows whichever mode is actually listening (:${FRONTEND_PORT}
    in dev, else :${BACKEND_PORT} in production). Override with
    JCLAW_E2E_BASE_URL.
  * Browser build — Playwright pins a browser per package version, so a
    dependency bump silently outdates the cache and the suite cannot
    launch. Reconciled on every run.

Example:
  ${INVOKE} start
  ${INVOKE} e2e
EOF
    else
        cat <<EOF
Usage: ${INVOKE} e2e

Not available in this distribution. The 'e2e' command runs the Playwright
suite from a developer checkout (frontend/tests/e2e, which doesn't ship
with 'play dist' tarballs).

For the full list of commands in this distribution: ${INVOKE} help
EOF
    fi
}

usage_dist() {
    if is_developer_clone; then
        cat <<EOF
Usage: ${INVOKE} dist

Build the developer-distribution zip at dist/jclaw.zip and exit.
Runs precompile + frontend build + \`play dist\`; the resulting zip
contains the source tree (filtered by .gitignore + .distignore) plus
precompiled/ and public/spa/. Operators unzip it wherever they want
to install JClaw, then run ${INVOKE} start inside the unzipped tree.

The resulting tarball is NOT self-contained: framework jar, framework
lib, and Gradle-resolved app deps are excluded. Operators need a local
Java 25 + Gradle + Play 1 fork install — the bundled \`${INVOKE} start\`
delegates to \`play run\`, which uses the host's Gradle to assemble the
runtime classpath. For a self-contained tarball that runs with only a
JRE, see the Dockerfile (\`play bundle\` instead of \`play dist\`).

Example:
  ${INVOKE} dist
  unzip -o dist/jclaw.zip -d /opt
  cd /opt/jclaw && ${INVOKE} start
EOF
    else
        cat <<EOF
Usage: ${INVOKE} dist

Not available in this distribution. The 'dist' command builds a
distribution artifact from a developer checkout — needs app/ sources
and frontend/ to (re)build, neither of which ship with 'play dist'
tarballs.

For the full list of commands in this distribution: ${INVOKE} help
EOF
    fi
}

usage_bundle() {
    if is_developer_clone; then
        cat <<EOF
Usage: ${INVOKE} bundle

Build the self-contained bundle zip at dist/jclaw-bundle.zip and exit.
Unlike 'dist', the bundle bakes in the framework jar + lib, the
Gradle-resolved app deps, precompiled classes, the prebuilt SPA, and a
\`./play\` launcher — so the unzipped tree runs with only a Java 25 JRE,
no Gradle or Play 1 fork install on the host. Same artifact the
Dockerfile ships inside the container image.

Example:
  ${INVOKE} bundle
EOF
    else
        cat <<EOF
Usage: ${INVOKE} bundle

Not available in this distribution. The 'bundle' command builds a
self-contained artifact from a developer checkout — needs app/ sources
and frontend/ to (re)build, neither of which ship with 'play dist'
tarballs.

For the full list of commands in this distribution: ${INVOKE} help
EOF
    fi
}

# Render the JClaw landing screen on bare invocation: ASCII-art logo in
# emerald, one-line product blurb, and pointers at the two commands every
# new contributor needs (setup for first-time wiring, --help for the full
# reference). Always runs when ./jclaw.sh is invoked with no command —
# the previous design suppressed it after the first setup, but that
# hid the intro from anyone who wanted to see it again.
#
# TTY-aware: ANSI colors only when stdout is an interactive terminal,
# so piping into less or redirecting into a logfile doesn't bury escape
# codes in the output. Modern terminals (iTerm2, macOS Terminal,
# Windows Terminal, VS Code/Cursor integrated, gnome-terminal) render
# 24-bit true color; older 256-color terminals fall back to nearest
# match, still readable.
show_intro() {
    local emerald='' cyan='' yellow='' dim='' bold='' reset=''
    if [[ -t 1 ]]; then
        # Tailwind emerald-400 (#34d399) matches the bg-emerald-* accents
        # used elsewhere in the project (Settings UI toggles, "active"
        # badges) — same color story everywhere reads as one product.
        emerald=$'\033[38;2;52;211;153m'
        cyan=$'\033[1;36m'
        yellow=$'\033[1;33m'
        dim=$'\033[2m'
        bold=$'\033[1m'
        reset=$'\033[0m'
    fi

    cat <<EOF

${emerald}  ▟█▙  ▟█▙        ██╗ ██████╗██╗      █████╗ ██╗    ██╗
  ███  ███        ██║██╔════╝██║     ██╔══██╗██║    ██║
   █▜▙▟▛█         ██║██║     ██║     ███████║██║ █╗ ██║
   ███████   ██   ██║██║     ██║     ██╔══██║██║███╗██║
    █████    ╚█████╔╝╚██████╗███████╗██║  ██║╚███╔███╔╝
     ███      ╚════╝  ╚═════╝╚══════╝╚═╝  ╚═╝ ╚══╝╚══╝ ${reset}

${dim}Java-first AI automation platform — Play 1.x backend, Nuxt 4 SPA,
LLM agents, OCR, web tools.${reset}

EOF

    # Audience split: a developer working from a `git clone` needs setup +
    # the full command surface; a user running an unzipped distribution
    # just needs to start/stop the app. Detection lives in
    # is_developer_clone — see the comment block above usage().
    if is_developer_clone; then
        # Developer view: cloned repo
        cat <<EOF
  ${cyan}${INVOKE} setup${reset}     One-time setup for a fresh clone
                       (validates prereqs, wires git hooks, installs deps,
                        adds github remote)
  ${cyan}${INVOKE} help${reset}      Full command reference

EOF

        # First-run hint footer (developer-only — users have no setup to
        # run, and the hooksPath signal doesn't apply outside a git work
        # tree). When core.hooksPath != .githooks, append a single
        # highlighted line nudging the user at the setup command. Once
        # setup runs, the hint disappears on its own. `|| true` keeps
        # the substitution safe under `set -e` if config lookup fails.
        local hooks_path
        hooks_path=$(/usr/bin/git -C "$SCRIPT_DIR" config --local core.hooksPath 2>/dev/null || true)
        if [[ "$hooks_path" != ".githooks" ]]; then
            echo "${yellow}${bold}→ Setup hasn't run on this clone yet. Run ${cyan}${INVOKE} setup${yellow} to wire things up.${reset}"
            echo ""
        fi
    else
        # User view: installed bundle / unzipped distribution. ${INVOKE} is the
        # command the user can actually type (jclaw or ./jclaw.sh — see the top
        # of this script). Description column aligns on command-name length
        # (longest is 'uninstall', 9) + a 2-space gap, independent of the prefix.
        _intro_cmd() { printf '  %s%s %s%s%*s%s\n' "$cyan" "$INVOKE" "$1" "$reset" "$(( 11 - ${#1} ))" '' "$2"; }
        _intro_cmd start     "Start JClaw (backend on :9000)"
        _intro_cmd stop      "Stop the running instance"
        _intro_cmd status    "Show whether the backend is running"
        _intro_cmd logs      "Tail the application log"
        _intro_cmd upgrade   "Install the newest release in place"
        _intro_cmd uninstall "Remove JClaw (deletes ~/.jclaw, undoes completion)"
        _intro_cmd help      "Full command reference"
        echo ""
    fi
}

# Parse arguments
DEV_MODE=false
# Restart/start/stop the Play backend only, leaving the Nuxt dev server up
# (dev mode only — prod serves the SPA as static files from the same JVM, so
# there is no second process to spare). Exists for the in-app restart button:
# bouncing Nuxt would tear down the very dev server that served the page
# issuing the request, so the browser could never observe the result.
BACKEND_ONLY=false
BACKEND_PORT="9000"
FRONTEND_PORT="3000"
COMMAND=""
COMPLETION_SHELL=""   # shell name for the `completion` subcommand (bash|zsh)
ASSUME_YES=""         # confirmation bypass for uninstall/upgrade (--yes / -y)
UPGRADE_VERSION=""    # `upgrade --version <tag>`; empty = newest release
UPGRADE_CHECK=false   # `upgrade --check`: report only, change nothing
# Set once the upgrade is committed to a target; read by upgrade_status, which
# can run before they are assigned on the resolve path.
UPGRADE_FROM=""
UPGRADE_TO=""
UPGRADE_STARTED=""
# The post-swap critical section: between replacing the tree and confirming the
# new version answers, a failure leaves an install with new code and no state.
# upgrade_abort / upgrade_cleanup read these to put it back.
UPGRADE_SWAPPED=false
UPGRADE_PREV=""
UPGRADE_DB_BACKUP=""
UPGRADE_STAGING=""
LT_CONCURRENCY="10"
LT_TURNS="5"
LT_TTFT_MS="100"
LT_TOKENS_PER_SECOND="50"
LT_RESPONSE_TOKENS="40"
# Track mock-only knobs that were explicitly passed; warn if combined
# with a real-provider run (where they're silently ignored by the harness).
LT_MOCK_FLAGS_SET=()
LT_CLEAN=false
LT_COMPRESS=false
# Real-provider mode is implied by --provider AND --model both being set.
# Defaults are blank so the absence of either flag means mock mode; the
# pair is validated together after argument parsing (one without the
# other is rejected).
LT_PROVIDER=""
LT_MODEL=""
# Empty = let the backend apply LoadTestRunner.DEFAULT_USER_MESSAGE (a length-
# constrained factual prompt that is fair across providers). Operators who
# want to A/B a different prompt shape pass --message.
LT_MESSAGE=""
# Path to a UTF-8 text file with one prompt per line. When set, each turn
# sends a different prompt (turn t uses line t+1) instead of replaying
# --message. Mutually exclusive with --message. Validated below: file must
# exist and contain at least LT_TURNS non-blank lines.
LT_PROMPTS_FILE=""
# JSON-encoded prompts array, populated from LT_PROMPTS_FILE in the
# validation block. Embedded in the loadtest body when non-empty.
LT_PROMPTS_JSON=""
# Opt-in: when true, `./jclaw.sh https` runs `mkcert -install` before
# issuing the leaf cert, adding the local CA to the system / NSS / Java
# trust stores. Default false because the install step touches stores
# outside this repo and can prompt for admin auth — running it should
# be a deliberate operator choice, not a side-effect of cert rotation.
HTTPS_INSTALL_CA=false
# Arguments forwarded verbatim to the eval CLI (services.evals.EvalRunner).
EVAL_ARGS=()

while [[ $# -gt 0 ]]; do
    case "$1" in
        --dev)
            DEV_MODE=true
            shift
            ;;
        --backend-only)
            BACKEND_ONLY=true
            shift
            ;;
        --backend-port)
            BACKEND_PORT="$2"
            shift 2
            ;;
        --frontend-port)
            FRONTEND_PORT="$2"
            shift 2
            ;;
        --concurrency)
            LT_CONCURRENCY="$2"
            shift 2
            ;;
        --turns)
            LT_TURNS="$2"
            shift 2
            ;;
        --ttft-ms)
            LT_TTFT_MS="$2"
            LT_MOCK_FLAGS_SET+=("--ttft-ms")
            shift 2
            ;;
        --tokens-per-second)
            LT_TOKENS_PER_SECOND="$2"
            LT_MOCK_FLAGS_SET+=("--tokens-per-second")
            shift 2
            ;;
        --response-tokens)
            LT_RESPONSE_TOKENS="$2"
            LT_MOCK_FLAGS_SET+=("--response-tokens")
            shift 2
            ;;
        --clean)
            LT_CLEAN=true
            shift
            ;;
        --compress)
            LT_COMPRESS=true
            shift
            ;;
        --provider)
            LT_PROVIDER="$2"
            shift 2
            ;;
        --model)
            LT_MODEL="$2"
            shift 2
            ;;
        --message)
            LT_MESSAGE="$2"
            shift 2
            ;;
        --prompts)
            LT_PROMPTS_FILE="$2"
            shift 2
            ;;
        --install-ca)
            HTTPS_INSTALL_CA=true
            shift
            ;;
        https|no-https|secret|reset|start|stop|restart|status|logs|upgrade|shim|uninstall)
            COMMAND="$1"
            shift
            ;;
        --yes|-y)
            # Skip the uninstall / upgrade confirmation prompt (for scripted use).
            ASSUME_YES=true
            shift
            ;;
        --version)
            UPGRADE_VERSION="$2"
            shift 2
            ;;
        --check)
            UPGRADE_CHECK=true
            shift
            ;;
        completion)
            # Universal (dev + dist): emit a shell completion script. Takes one
            # positional, the shell name (bash|zsh), consumed here so it doesn't
            # fall through to the unknown-argument arm. A leading-dash next token
            # (e.g. `completion --help`) is left for the --help arm to handle.
            COMMAND="$1"
            shift
            if [[ -n "${1:-}" && "${1:-}" != -* ]]; then
                COMPLETION_SHELL="$1"
                shift
            fi
            ;;
        scrapetest)
            # Developer-only, and its flags are forwarded verbatim rather than
            # re-declared here — same reasoning as `evals` below.
            if ! is_developer_clone; then
                echo "Error: 'scrapetest' is a developer-only command, not available in this distribution."
                exit 1
            fi
            COMMAND="$1"
            shift
            if [[ "${1:-}" == "--help" || "${1:-}" == "-h" ]]; then
                usage_scrapetest
                exit 0
            fi
            SCRAPETEST_ARGS=("$@")
            break
            ;;
        evals)
            # Developer-only, like the block below — but its flags are NOT
            # re-declared here. Everything after `evals` is forwarded verbatim
            # to the Java CLI, which owns the option vocabulary; duplicating it
            # in this parser would give the two sides a chance to disagree.
            if ! is_developer_clone; then
                echo "Error: 'evals' is a developer-only command, not available in this distribution."
                exit 1
            fi
            COMMAND="$1"
            shift
            if [[ "${1:-}" == "--help" || "${1:-}" == "-h" ]]; then
                usage_evals
                exit 0
            fi
            EVAL_ARGS=("$@")
            break
            ;;
        setup|init-worktree|loadtest|test|e2e|dist|bundle)
            # Developer-only commands. Available on a `git clone` because
            # they touch repo state (hooks, fixtures, frontend deps); not
            # available on a `play dist` install where there's no .git
            # and the frontend ships pre-built. The end-user banner
            # already advertises a narrower subcommand list — this gate
            # makes the runtime parse match that advertisement, so a
            # mistyped `./jclaw.sh setup` on a dist fails with a clear
            # "developer-only" message instead of silently running into
            # check_prereqs / git mid-execution.
            if ! is_developer_clone; then
                echo "Error: '$1' is a developer-only command, not available in this distribution."
                echo "       This install supports: https, no-https, secret, reset, start, stop, restart, status, logs, help."
                exit 1
            fi
            COMMAND="$1"
            shift
            ;;
        --help|-h)
            # Contextual when a subcommand has been parsed, top-level
            # otherwise — matches `git <cmd> --help` and friends.
            if [[ -n "$COMMAND" ]]; then
                usage_for "$COMMAND"
            else
                usage
            fi
            exit 0
            ;;
        help)
            # Bare `help` → top-level. `help <cmd>` → per-command. The
            # post-COMMAND form (`<cmd> help`) is intentionally NOT a
            # help signal — it falls through to the unknown-argument arm
            # so operators get steered toward the supported
            # `<cmd> --help` shape (per clig.dev / git / kubectl).
            if [[ -n "$COMMAND" ]]; then
                echo "Unknown argument: help"
                usage
                exit 1
            fi
            shift
            if [[ $# -gt 0 ]] && is_known_command "$1"; then
                usage_for "$1"
            else
                usage
            fi
            exit 0
            ;;
        *)
            echo "Unknown argument: $1"
            usage
            exit 1
            ;;
    esac
done

# Bare invocation (no command given) is a deliberate landing screen, not
# an error — render the intro and exit cleanly. The --help flag above
# is the path to the full command reference; the intro just points at
# it. This frees `./jclaw.sh status` and friends from rendering a
# 30-line banner on every invocation.
if [[ -z "$COMMAND" ]]; then
    show_intro
    exit 0
fi

# Validate flag combinations

# Hard-reject --dev on dist installs. A dist tarball ships precompiled
# bytecode + the built SPA only — no app/, no frontend/ — so the dev
# workflow physically can't run (no Java sources to recompile on save,
# no Nuxt source to dev-serve). Without this guard the user would hit
# a confusing failure further down (npx nuxi dev: ENOENT on
# frontend/package.json) rather than a clear "this command isn't
# available in your install" message. is_developer_clone returns true
# inside any git checkout (the source-of-truth for whether we're on a
# developer machine with the full source tree).
if [[ "$DEV_MODE" == true ]] && ! is_developer_clone; then
    echo "Error: --dev is a developer-only flag, not available in this distribution."
    echo "       This install supports the production commands: start, stop, restart, status, logs, https, no-https, secret."
    exit 1
fi

# Loadtest provider/model pairing: real-mode is implied by both being set.
# Reject the half-set state up front so operators don't get a surprising
# fall-through to mock mode (silent) or a server-side 400 (delayed).
if [[ -n "$LT_PROVIDER" && -z "$LT_MODEL" ]]; then
    echo "Error: --provider given without --model. Both are required to drive a real provider; omit both for the mock harness."
    exit 1
fi
if [[ -n "$LT_MODEL" && -z "$LT_PROVIDER" ]]; then
    echo "Error: --model given without --provider. Both are required to drive a real provider; omit both for the mock harness."
    exit 1
fi
# Derived: real-provider mode iff both are set. Used by the loadtest dispatch
# below to choose banner text, request shape, and curl timeout.
LT_REAL=false
if [[ -n "$LT_PROVIDER" && -n "$LT_MODEL" ]]; then
    LT_REAL=true
fi

# --prompts validation. Resolve and read the file once here, so a missing
# file or short file fails fast before do_loadtest does anything (no
# backend round-trip wasted on a bad input). The resulting JSON array is
# stashed in LT_PROMPTS_JSON for the body builder; downstream code
# checks LT_PROMPTS_JSON, not the file path.
if [[ -n "$LT_PROMPTS_FILE" && -n "$LT_MESSAGE" ]]; then
    echo "Error: --prompts and --message are mutually exclusive (both set per-turn message strategy)."
    exit 1
fi
if [[ -n "$LT_PROMPTS_FILE" ]]; then
    if [[ ! -f "$LT_PROMPTS_FILE" ]]; then
        echo "Error: --prompts file not found: $LT_PROMPTS_FILE"
        exit 1
    fi
    # python3 handles JSON escaping (quotes, backslashes, non-ASCII) and
    # blank-line stripping in one pass. Pass the path via env var so spaces
    # / unusual chars in the path don't need shell-side escaping.
    LT_PROMPTS_JSON=$(LT_PROMPTS_FILE="$LT_PROMPTS_FILE" python3 -c "
import json, os
with open(os.environ['LT_PROMPTS_FILE'], encoding='utf-8') as f:
    lines = [line.rstrip('\n').rstrip('\r') for line in f]
    prompts = [line for line in lines if line.strip()]
print(json.dumps(prompts))
") || { echo "Error: failed to parse --prompts file"; exit 1; }
    prompt_count=$(echo "$LT_PROMPTS_JSON" | python3 -c "import json, sys; print(len(json.load(sys.stdin)))")
    if [[ "$prompt_count" -lt "$LT_TURNS" ]]; then
        echo "Error: --prompts file has $prompt_count non-blank line(s) but --turns is $LT_TURNS;" \
             "provide at least one prompt per turn."
        exit 1
    fi
fi

# Route every `pnpm` invocation through corepack so the version pinned
# in frontend/package.json's `packageManager` field is authoritative,
# regardless of what's installed globally. corepack presence is enforced
# upstream by check_prereqs at every dispatch entry point, so this
# function never has to defend against the corepack-missing case.
pnpm() {
    corepack pnpm "$@"
}

# Read the packageManager pin from frontend/package.json. Echoes the raw
# value (e.g. "pnpm@10.33.1+sha512.abc...") on stdout, or empty when the
# field or file is absent. Used by both the setup-time pin migration and
# the start-time validation guard.
read_pnpm_pin() {
    local frontend_dir="$SCRIPT_DIR/frontend"
    [[ -f "$frontend_dir/package.json" ]] || return 0
    sed -n 's/.*"packageManager": *"\([^"]*\)".*/\1/p' \
        "$frontend_dir/package.json" | head -1
}

# Setup-time only: ensure the packageManager pin includes a +sha512-...
# integrity hash. Idempotent — already-hashed pins land in the no-op
# branch. Called from do_setup, never from start paths, so the
# package.json mutation is scoped to an explicit "I'm setting up this
# clone" action rather than appearing as a surprise during start.
setup_corepack_pnpm_pin() {
    local frontend_dir="$SCRIPT_DIR/frontend"
    [[ -d "$frontend_dir" && -f "$frontend_dir/package.json" ]] || return 0

    local current_pin
    current_pin=$(read_pnpm_pin)
    if [[ -z "$current_pin" ]]; then
        echo "    Warning: no packageManager pin in frontend/package.json — nothing to migrate."
        return 0
    fi

    if [[ "$current_pin" == *"+sha"* ]]; then
        echo "    pnpm pin already includes integrity hash — no migration needed."
        return 0
    fi

    # `corepack use pnpm@VERSION` re-pins to the same version string and
    # appends the +sha512-... hash. Rewrites frontend/package.json — the
    # mutation is the whole point of running setup.
    local pin_version="${current_pin#pnpm@}"
    echo "    Adding pnpm integrity hash via corepack use..."
    echo "      Old pin: $current_pin"
    if ! (cd "$frontend_dir" && corepack use "pnpm@$pin_version" >/dev/null 2>&1); then
        echo "Error: corepack use failed; could not add integrity hash."
        echo "       Try manually: cd frontend && corepack use pnpm@$pin_version"
        exit 1
    fi
    local new_pin
    new_pin=$(read_pnpm_pin)
    echo "      New pin: $new_pin"
    echo "      Note: frontend/package.json was modified — review and commit."
}

# Start-time only: validate that the pinned pnpm is present locally and
# verifies against its +sha hash. Read-only — never mutates package.json.
# Hard-fails on missing hash with an actionable error pointing at setup,
# so the security gate doesn't silently degrade to no-op when someone
# hand-edits the pin and drops the hash.
validate_corepack_pnpm() {
    local frontend_dir="$SCRIPT_DIR/frontend"
    [[ -d "$frontend_dir" && -f "$frontend_dir/package.json" ]] || return 0

    local current_pin
    current_pin=$(read_pnpm_pin)
    if [[ -z "$current_pin" ]]; then
        echo "    Warning: no packageManager pin in frontend/package.json — cannot validate pnpm."
        return 0
    fi

    if [[ "$current_pin" != *"+sha"* ]]; then
        echo "Error: pnpm pin lacks integrity hash (frontend/package.json: packageManager=$current_pin)."
        echo "       Without the +sha512-... hash, corepack cannot verify the downloaded"
        echo "       tarball against tampering — refusing to launch."
        echo ""
        echo "       Fix with one of:"
        echo "         ${INVOKE} setup"
        echo "         cd frontend && corepack use ${current_pin}"
        exit 1
    fi

    # `corepack install` reads packageManager, downloads the version if
    # missing, and verifies it against the +sha hash. Output is suppressed
    # on success because corepack is noisy on cached hits.
    local install_log install_status
    install_log=$(cd "$frontend_dir" && corepack install 2>&1)
    install_status=$?
    if (( install_status != 0 )); then
        echo "Error: corepack install failed — pnpm hash validation may have failed."
        echo "       frontend/package.json packageManager pin: $current_pin"
        echo "       corepack output:"
        echo "$install_log" | sed 's/^/         /'
        exit 1
    fi
    echo "==> pnpm validated via corepack ($current_pin)"
}

# Make `pnpm` resolvable on PATH for grandchild processes — specifically
# the Gradle daemon spawned by `play dist`, whose PlayDistTask probes the
# frontend toolchain via execve. The bash pnpm() shadow at the top of this
# script only catches in-script calls; once we hand off to Gradle, only
# env vars and PATH cross the process boundary.
#
# corepack enable --install-directory writes shims to a path we control
# (here tmp/corepack-shims/, already gitignored via tmp/), sidestepping
# the system-write requirement of plain `corepack enable` on installs
# where node lives in a root-owned tool dir (Debian's nodejs package,
# Jenkins agents that re-shim per stage, locked-down CI runners).
# Idempotent — corepack rewrites the same shim on repeat invocations.
ensure_pnpm_on_path_for_gradle() {
    local shim_dir="$SCRIPT_DIR/tmp/corepack-shims"
    mkdir -p "$shim_dir"
    if ! corepack enable --install-directory "$shim_dir" pnpm >/dev/null 2>&1; then
        echo "Error: corepack enable failed to write a pnpm shim to $shim_dir."
        echo "       Gradle's :playDist task probes pnpm directly on PATH; the bash"
        echo "       pnpm() shadow doesn't reach grandchild processes, so we cannot"
        echo "       proceed without a real shim."
        exit 1
    fi
    case ":$PATH:" in
        *":$shim_dir:"*) ;;
        *) export PATH="$shim_dir:$PATH" ;;
    esac
}

# Resolve the env-var name that backs `application.secret` in conf.
# Mirrors framework/pym/play/utils.py:secretVarName so jclaw.sh and
# `play secret` always agree on which variable to read/write — the
# operator can rename `${APP_SECRET}` to anything in conf and this
# helper picks it up automatically. Falls back to the framework's
# DEFAULT_SECRET_VAR ("PLAY_SECRET") when conf is missing or the line
# uses an unparseable form, matching the framework's behaviour.
secret_var_name() {
    local conf="$SCRIPT_DIR/conf/application.conf"
    if [[ ! -f "$conf" ]]; then
        echo "PLAY_SECRET"
        return
    fi
    # Match: application.secret=${VARNAME} (skip lines starting with # or !).
    # The framework's regex anchors on a single ${...} placeholder; anything
    # else (literal, missing braces, : default-value form) falls back below.
    local name
    name=$(grep -vE '^[[:space:]]*[#!]' "$conf" \
        | grep -E '^[[:space:]]*application\.secret[[:space:]]*=[[:space:]]*\$\{[^}:]+\}[[:space:]]*$' \
        | head -n1 \
        | sed -E 's/^[[:space:]]*application\.secret[[:space:]]*=[[:space:]]*\$\{([^}:]+)\}[[:space:]]*$/\1/')
    echo "${name:-PLAY_SECRET}"
}

# Source $SCRIPT_DIR/certs/.env into the current shell with auto-export so
# the JVM started by `play` inherits the variables. Called from start
# paths (dev + prod). Silent no-op when certs/.env is absent — that path
# is reserved for failure handling at the validate step, not here.
load_env_file() {
    local env_file="$SCRIPT_DIR/certs/.env"
    if [[ -f "$env_file" ]]; then
        set -a
        # shellcheck disable=SC1090
        source "$env_file"
        set +a
    fi
}

# First-run helper for the start paths: if certs/.env is absent AND the
# conf-named secret variable is unset in the parent shell, generate one.
# This is what makes `./jclaw.sh start` work on a fresh jclaw.zip install
# with no developer setup step. We DON'T auto-create when the operator
# has already exported the variable externally — that'd overwrite their
# intent with a stored random value they didn't ask for. We DON'T
# auto-create from setup either; setup has its own explicit gate.
ensure_env_for_start() {
    local env_file="$SCRIPT_DIR/certs/.env"
    local var_name
    var_name=$(secret_var_name)
    # Bash indirect expansion: `${!var_name:-}` reads the variable whose
    # NAME is held in $var_name. Equivalent to `${APP_SECRET:-}` when
    # var_name="APP_SECRET", but follows whatever the conf line dictates.
    if [[ ! -f "$env_file" && -z "${!var_name:-}" ]]; then
        echo "==> First run detected — no certs/.env and no $var_name in env."
        do_secret
    fi
}

# Hard-fail if the conf-named secret variable ends up unset by the time
# we're about to launch the JVM. application.conf has no dev fallback
# (intentional — the previous in-repo secret was an admin-session-forgery
# primitive), so an unresolved placeholder would yield an empty signing
# key. We detect early to give a clean diagnostic instead of a Play
# startup stacktrace from CookieSessionStore.
require_application_secret() {
    local var_name
    var_name=$(secret_var_name)
    if [[ -z "${!var_name:-}" ]]; then
        # Reachable only when certs/.env exists but lacks (or empties) the
        # key, OR when the launcher set the env var to an empty string.
        # The ensure_env_for_start guard upstream creates certs/.env on
        # first run, so this is operator-misconfiguration territory.
        echo "Error: $var_name resolved to empty after sourcing certs/.env."
        echo "       Check that certs/.env contains a non-empty value:"
        echo "         $var_name=<some-64-char-string>"
        echo "       Rotate or regenerate with: $0 secret"
        exit 1
    fi
}

# Generate or rotate the application secret in $SCRIPT_DIR/certs/.env.
# Delegates the actual generation + write to `play secret`, which since
# PF-71 defaults to certs/.env and auto-detects the env-var name from
# conf/application.conf's ${VARNAME} placeholder — so renaming the
# variable in conf flows through here without touching this script.
# The framework's writer preserves any other lines in the file;
# rotation rewrites only the secret line.
# Strict TLS cert+key validity check. Returns 0 only if every gate passes:
#   1. both certs/host.cert and certs/host.key exist;
#   2. openssl is on PATH (we fail closed when we can't validate — better
#      to leave HTTPS off than silently enable it without verification);
#   3. cert parses as X.509 and is not expired (`-checkend 0`);
#   4. cert's public key matches the key file's public key — catches a
#      half-rotated pair (cert regenerated, key stale, or vice versa).
# Algorithm-agnostic via `openssl pkey -pubout`: works for both mkcert's
# ECDSA P-256 default and the openssl-fallback RSA pair. Used by start
# to gate the runtime -Dhttps.port=9443 override; conf has https.port
# commented out, so a missing override = HTTPS off (HTTP-1.1 only on 9000).
certs_valid() {
    local cert_file="$SCRIPT_DIR/certs/host.cert"
    local key_file="$SCRIPT_DIR/certs/host.key"

    [[ -f "$cert_file" && -f "$key_file" ]] || return 1
    command -v openssl >/dev/null 2>&1 || return 1

    openssl x509 -in "$cert_file" -checkend 0 -noout >/dev/null 2>&1 || return 1

    local cert_pub key_pub
    cert_pub=$(openssl x509 -in "$cert_file" -pubkey -noout 2>/dev/null) || return 1
    key_pub=$(openssl pkey -in "$key_file" -pubout 2>/dev/null) || return 1
    [[ "$cert_pub" == "$key_pub" ]]
}

# Generate a TLS PEM cert+key at certs/host.{cert,key}. mkcert (when
# installed) produces a cert signed by its locally-trusted CA — Chrome's
# QUIC stack will negotiate HTTP/3 without warnings. openssl is the
# fallback; browsers warn and HTTP/3 won't upgrade against a self-signed
# cert. After this completes, the next `./jclaw.sh start` will pass
# -Dhttps.port=9443 (gated by certs_valid). conf/application.conf is
# never touched.
do_https() {
    local certs_dir="$SCRIPT_DIR/certs"
    local cert_file="$certs_dir/host.cert"
    local key_file="$certs_dir/host.key"

    mkdir -p "$certs_dir"

    # --install-ca: add the mkcert local CA to the system / NSS / Java
    # trust stores before issuing the leaf cert. mkcert -install is
    # itself idempotent (it inspects each store and only modifies the
    # ones missing the CA), so we don't pre-check — let it be the source
    # of truth. Hard-fail if mkcert is absent: the flag's intent is
    # specifically the CA install, which openssl can't do, so silently
    # falling through to the openssl fallback would be wrong.
    if [[ "$HTTPS_INSTALL_CA" == true ]]; then
        if ! command -v mkcert >/dev/null 2>&1; then
            cat >&2 <<EOF
ERROR: --install-ca requires mkcert, which is not on PATH.

Install it once:
  macOS:    brew install mkcert
  Linux:    follow https://github.com/FiloSottile/mkcert#linux
            (typically: apt install libnss3-tools, then install mkcert via
             your package manager or the GitHub release binary)
  Windows:  choco install mkcert  (or scoop install mkcert)
  Other:    https://github.com/FiloSottile/mkcert#installation

Then re-run: ${INVOKE} https --install-ca
EOF
            return 1
        fi
        echo "==> Installing mkcert local CA into system trust stores..."
        echo "    (idempotent — mkcert skips stores that already trust the CA;"
        echo "     may prompt for admin / Touch ID on stores that need updating.)"
        mkcert -install
    fi

    if command -v mkcert >/dev/null 2>&1; then
        mkcert -cert-file "$cert_file" -key-file "$key_file" \
               localhost 127.0.0.1 ::1 >/dev/null
        echo "Generated mkcert-signed PEM cert+key at $certs_dir."
        if [[ "$HTTPS_INSTALL_CA" == true ]]; then
            echo "(Local CA installed in system trust store — Chrome will accept HTTP/3.)"
        else
            echo "(Trusted by the system store after 'mkcert -install' — Chrome will accept HTTP/3.)"
            echo "Tip: run '${INVOKE} https --install-ca' once to install the CA automatically."
        fi
    elif command -v openssl >/dev/null 2>&1; then
        # 10-year lifetime (3650 days) — local-dev cert that's never reachable
        # from the public internet, so rotation hygiene matters less than
        # avoiding mid-development expiry. CN=localhost plus SANs for IPv4 +
        # IPv6 loopback covers what browsers actually validate; modern Chrome
        # rejects certs that lack a SAN even when CN matches, so the SAN is
        # non-optional.
        openssl req -x509 -newkey rsa:2048 -nodes \
            -keyout "$key_file" -out "$cert_file" \
            -days 3650 -subj "/CN=localhost" \
            -addext "subjectAltName=DNS:localhost,IP:127.0.0.1,IP:0:0:0:0:0:0:0:1" \
            >/dev/null 2>&1
        echo "Generated self-signed PEM cert+key at $certs_dir (openssl fallback)."
        echo "Hint: install mkcert (https://github.com/FiloSottile/mkcert) for browser-trusted local-dev TLS."
    else
        echo "ERROR: neither mkcert nor openssl found on PATH. Install one and re-run." >&2
        return 1
    fi

    chmod 600 "$key_file"
    chmod 644 "$cert_file"

    echo "Run '$0 restart' (or '$0 start') to apply."
}

# Disable HTTPS by deleting the cert+key on disk. certs_valid will then
# return false on the next start, so the -Dhttps.port=9443 override
# won't be passed and Play boots HTTP-1.1 only on 9000. Idempotent
# (no-op when the files are already absent). To re-enable, run
# `./jclaw.sh https` — the next cert is regenerated fresh.
do_no_https() {
    local certs_dir="$SCRIPT_DIR/certs"
    local cert_file="$certs_dir/host.cert"
    local key_file="$certs_dir/host.key"

    if [[ ! -f "$cert_file" && ! -f "$key_file" ]]; then
        echo "HTTPS already disabled — no cert+key at $certs_dir."
        return 0
    fi

    rm -f "$cert_file" "$key_file"
    echo "Deleted $cert_file and $key_file. HTTPS disabled."
    echo "Run '$0 restart' (or '$0 start') to apply."
}

do_secret() {
    local env_file="$SCRIPT_DIR/certs/.env"

    mkdir -p "$SCRIPT_DIR/certs"

    # Seed a brand-new certs/.env with our self-documenting header BEFORE
    # invoking `play secret`. The framework writer preserves existing
    # lines and only appends/replaces the secret variable, so dropping a
    # header here keeps those comments intact across future rotations.
    # umask 077 ensures the file is owner-only readable from creation,
    # before the secret lands.
    if [[ ! -f "$env_file" ]]; then
        (
            umask 077
            printf '%s\n' \
                "# Per-clone environment overrides sourced by jclaw.sh." \
                "# Generated by '$0 secret' or '$0 setup' — do not commit." \
                "" \
                "# Admin password used by '$0 e2e' to sign in before the Playwright" \
                "# specs run. Belongs here rather than conf/application.conf, which is" \
                "# tracked and ships to both remotes. Uncomment and set to use it." \
                "#JCLAW_ADMIN_PASSWORD=" \
                > "$env_file"
        )
    fi

    # `play secret` reads ${VARNAME} from application.conf's
    # `application.secret=${VARNAME}` line, generates a fresh 64-char
    # alphanumeric value, and writes <VARNAME>=<value> to certs/.env
    # (PF-71 default). Run from $SCRIPT_DIR so it locates the right conf.
    (cd "$SCRIPT_DIR" && play secret)

    # `play secret` doesn't tighten perms, so re-apply chmod 600 — the
    # secret is the admin-session-forgery primitive; non-owner reads
    # would defeat the point of having it in certs/.env at all.
    chmod 600 "$env_file"

    # DEV_MODE is "true"/"false" string — `${VAR:+...}` would print on
    # both because the string is always non-empty. Compare to "true"
    # explicitly to get the intended boolean behavior.
    local dev_flag=""
    [[ "$DEV_MODE" == true ]] && dev_flag="--dev "
    echo "    Restart the app to pick up the new value:"
    echo "      $0 ${dev_flag}restart"
}

# Per-worktree HTTP port for `play autotest`, derived deterministically
# from this worktree's path. Parallel `play autotest` runs across git
# worktrees would otherwise collide on the conf default (%test.http.port
# = 9100): PlayAutotestTask.runAutotest() HTTP-POSTs /@kill to whatever
# is listening on its target port before spawning its own server, so two
# worktrees sharing 9100 alternately murder each other's test JVMs
# mid-run. The patched plugin reads PLAY_TEST_PORT from certs/.env, so
# pinning a stable port per worktree here makes `play autotest` safe
# under parallelism without any per-invocation flag-juggling.
#
# Idempotent: re-runs preserve any value already in certs/.env so the
# operator can override the derived port by hand.
ensure_play_test_port() {
    local env_file="$SCRIPT_DIR/certs/.env"
    if [[ ! -f "$env_file" ]]; then
        echo "    Skipped: certs/.env missing (do_secret should have created it)."
        return
    fi
    if grep -q "^PLAY_TEST_PORT=" "$env_file"; then
        local existing
        existing=$(grep "^PLAY_TEST_PORT=" "$env_file" | head -1 | cut -d= -f2)
        echo "    PLAY_TEST_PORT already set to $existing — leaving it untouched."
        return
    fi
    # cksum (POSIX) over the worktree's canonical path, modulo 800,
    # offset to 9100-9899. 9000 is the dev server's default; 9100 is the
    # conf default for %test.http.port; >=9900 leaves headroom for other
    # local tooling. Collision probability with N worktrees is
    # ~N(N-1)/1600 — negligible at typical use (under 1% for 4 worktrees).
    # Collisions are recoverable by editing certs/.env manually.
    local worktree_path
    worktree_path=$(/usr/bin/git -C "$SCRIPT_DIR" rev-parse --show-toplevel 2>/dev/null || echo "$SCRIPT_DIR")
    local port
    port=$(printf '%s' "$worktree_path" | cksum | awk '{print 9100 + ($1 % 800)}')
    {
        echo ""
        echo "# Per-worktree HTTP port for \`play autotest\`. Derived from this"
        echo "# worktree's path so parallel test runs across git worktrees bind"
        echo "# distinct ports and don't /@kill each other. Read by the patched"
        echo "# play1 plugin (PlayAutotestTask) and forwarded into the test JVM."
        echo "PLAY_TEST_PORT=$port"
    } >> "$env_file"
    echo "    PLAY_TEST_PORT=$port (derived from $worktree_path)"
}

# Slim worktree-state init: PLAY_SECRET + PLAY_TEST_PORT in certs/.env,
# nothing else. Called by .githooks/post-checkout on `git worktree add`
# so a fresh worktree can immediately run `play autotest` in parallel
# with siblings, AND by do_setup as the first step of the full bootstrap.
# Centralizing here means the secret-generation guard and the test-port
# derivation live in exactly one place — neither caller can drift.
#
# Idempotent: preserves an existing PLAY_SECRET (rotate via `$0 secret`)
# and an existing PLAY_TEST_PORT (override by editing certs/.env).
do_init_worktree() {
    cd "$SCRIPT_DIR"
    if [[ -f "$SCRIPT_DIR/certs/.env" ]]; then
        echo "    certs/.env already exists — leaving secret untouched."
        echo "    Rotate with: $0 secret"
    else
        do_secret
    fi
    ensure_play_test_port
}

# Locate the H2 jar shipped with the Play framework, falling back to a
# bundled copy in the dist's framework/lib/. Used by do_reset to invoke
# the H2 Shell tool standalone — no JVM-side classpath assembly needed.
locate_h2_jar() {
    local jar
    # Dist/bundle layout: PlayBundleTask emits a two-tree lib hierarchy —
    # jclaw/lib/ for app deps (Gradle-resolved) and jclaw/framework/lib/
    # for framework deps (copied wholesale from the play install). H2 is
    # declared by Play (framework/dependencies.yml), not by jclaw, so it
    # lands under framework/lib/ — same path the developer-layout branch
    # below resolves to via the `play` CLI.
    jar=$(ls "$SCRIPT_DIR"/framework/lib/h2-*.jar 2>/dev/null | head -1)
    if [[ -n "$jar" ]]; then
        echo "$jar"
        return 0
    fi
    # Developer layout: framework/lib/ inside the play install. Resolve
    # the play CLI's real path (jenv shims aren't symlinks, but realpath
    # on a non-symlink is a no-op so it's safe to call unconditionally).
    if command -v play >/dev/null 2>&1; then
        local play_real play_home
        play_real=$(python3 -c "import os, shutil; print(os.path.realpath(shutil.which('play')))" 2>/dev/null || true)
        if [[ -n "$play_real" ]]; then
            play_home=$(dirname "$play_real")
            jar=$(ls "$play_home"/framework/lib/h2-*.jar 2>/dev/null | head -1)
            if [[ -n "$jar" ]]; then
                echo "$jar"
                return 0
            fi
        fi
    fi
    return 1
}

# Detect whether the dist's docker-compose.yml has a running `jclaw` service
# in the current $SCRIPT_DIR. Returns 0 when a running container exists, 1
# otherwise (no docker, no compose file, daemon down, parse error — all
# silent). Best-effort dispatcher hint for `do_reset`, not a hard gate; the
# caller falls through to direct-mode behavior on any failure path.
docker_jclaw_running() {
    command -v docker >/dev/null 2>&1 || return 1
    [[ -f "$SCRIPT_DIR/docker-compose.yml" ]] || return 1
    local services
    services=$(cd "$SCRIPT_DIR" && docker compose ps --status running --services 2>/dev/null) || return 1
    grep -q '^jclaw$' <<< "$services"
}

# Confirmation gate for do_reset. The reset wipes the credentials that gate
# access to the running instance — anyone who reaches the post-reset
# /setup-password page can claim the admin role. The operator must
# acknowledge that surface before we touch the DB. JCLAW_RESET_YES=1 in
# the environment skips the prompt for scripted use, and is also how the
# host-side reset propagates the operator's confirmation when it delegates
# into `docker compose exec` — the in-container script sees JCLAW_RESET_YES=1
# and skips its own prompt instead of blocking on a stdin nobody owns.
prompt_reset_confirmation() {
    local target_desc="$1"
    if [[ "${JCLAW_RESET_YES:-}" == "1" ]]; then
        return 0
    fi
    echo "About to clear the admin password hash from $target_desc."
    echo "After this, the next visit to the app will land on the"
    echo "/setup-password page and the first arriver claims the"
    echo "admin role."
    read -r -p "Proceed? [y/N] " reply
    case "$reply" in
        y|Y|yes|YES) return 0 ;;
        *) echo "Aborted."; exit 0 ;;
    esac
}

# Clear the admin password hash from the Config DB so the next launch
# routes through the in-app /setup-password flow. The recovery path for
# an operator who's locked themselves out — the in-app
# POST /api/auth/reset-password endpoint requires being signed in, which
# is impossible when you've forgotten the password.
#
# Two dispatch paths:
#
# 1. Docker mode — when the dist's docker-compose stack has a running
#    `jclaw` service, delegate via `docker compose exec`. The container's
#    H2 holds the database file lock and registers an AUTO_SERVER socket
#    on the container's internal IP (e.g. 172.18.0.2), which Docker
#    Desktop on macOS does not route from host → container; a host-side
#    H2 Shell would time out on connect. The host-side prompt runs first,
#    then JCLAW_RESET_YES=1 propagates into the exec environment so the
#    in-container call doesn't re-prompt.
#
# 2. Direct mode — host-side H2 Shell against data/jclaw.mv.db. Safe to
#    run while a host-side `play run` is up because db.url is configured
#    with AUTO_SERVER=TRUE — H2 promotes the file lock to a TCP server
#    on first connect, so a second process (this one) can join the same
#    database without contention.
#
# Idempotent in both modes: re-running on a fresh install where no
# password has ever been set succeeds with 0 rows deleted.
do_reset() {
    if docker_jclaw_running; then
        prompt_reset_confirmation "the running jclaw container's database"
        echo "==> Detected jclaw container running; running reset inside the container..."
        cd "$SCRIPT_DIR"
        if ! docker compose exec -T -e JCLAW_RESET_YES=1 jclaw ./jclaw.sh reset; then
            echo "Error: in-container reset failed. The DB may or may not have been modified." >&2
            exit 1
        fi
        # The H2 Shell DELETE just modified the DB file directly, behind
        # the JVM's back. ConfigService caches each config key in-process
        # for 60s (Caffeine, expireAfterWrite); until the cache entry
        # TTLs out, /api/auth/status keeps returning passwordSet=true and
        # the frontend's checkPasswordSet() gate refuses to route to
        # /setup-password. Restarting the container drops the cache cold,
        # so the next page load sees the empty config row and surfaces
        # the setup flow as the operator expects from a "reset" command.
        echo "==> Restarting jclaw container so the JVM picks up the deletion..."
        if ! docker compose restart jclaw; then
            echo "Warning: container restart failed. The DB row is gone, but the JVM" >&2
            echo "         cache may still hold the stale hash for up to 60s. Either" >&2
            echo "         wait for the cache TTL or restart manually:" >&2
            echo "             docker compose restart jclaw" >&2
            exit 1
        fi
        echo "==> Done. Open the app — you'll land on /setup-password."
        exit 0
    fi

    local data_file="$SCRIPT_DIR/data/jclaw.mv.db"
    if [[ ! -f "$data_file" ]]; then
        echo "Error: No database found at $data_file."
        echo "       Nothing to reset — the app hasn't been started yet,"
        echo "       so there's no password hash to clear. Just start the"
        echo "       app and use the in-app /setup-password flow."
        exit 1
    fi

    check_java

    local h2_jar
    h2_jar=$(locate_h2_jar) || {
        echo "Error: Could not locate H2 jar. Looked in:"
        echo "  - $SCRIPT_DIR/framework/lib/h2-*.jar  (dist layout)"
        echo "  - <play-home>/framework/lib/h2-*.jar  (developer layout)"
        echo "       Without the H2 driver this script can't talk to the DB."
        exit 1
    }

    prompt_reset_confirmation "$data_file"

    # AUTO_SERVER=TRUE matches application.conf's db.url so a running
    # app's file lock doesn't block us. MODE=MYSQL is irrelevant for a
    # DELETE but kept for parity with the canonical URL — H2 caches
    # connection-time options per file, and a mismatch would cost a
    # short reconnect dance.
    #
    # IFEXISTS=TRUE: H2 otherwise auto-creates the database, parent directories and
    # all, so a URL that resolves anywhere unexpected deletes from an empty decoy
    # instead of failing.
    #
    # No -user / -password flags: the app is configured with neither
    # db.user nor db.pass set in conf, so Play opens the database with
    # null credentials. Subsequent connects MUST use the same null
    # pattern — passing -user sa -password "" lands a 28000 invalid-
    # auth error against the very database we created.
    #
    # Relative and run from $SCRIPT_DIR, exactly as application.conf spells it: under
    # Git Bash an absolute $SCRIPT_DIR is /c/Users/..., which the native java.exe reads
    # as the current drive's root (C:\c\Users\...) — same trap as the -Xlog paths in
    # do_start_prod (JCLAW-1104).
    local jdbc_url="jdbc:h2:file:./data/jclaw;MODE=MYSQL;AUTO_SERVER=TRUE;IFEXISTS=TRUE"
    local sql="DELETE FROM config WHERE config_key='auth.admin.passwordHash';"

    echo "==> Clearing auth.admin.passwordHash..."
    local shell_out
    if ! shell_out=$(cd "$SCRIPT_DIR" && java -cp "$(native_path "$h2_jar")" \
            org.h2.tools.Shell -url "$jdbc_url" -sql "$sql" 2>&1); then
        echo "$shell_out" >&2
        echo "Error: could not open $data_file. Inspect the output above." >&2
        exit 1
    fi
    # org.h2.tools.Shell prints a failing statement's error and still exits 0, so the
    # status above catches a refused connect and nothing else. H2 emits one "Update
    # count" line per statement that ran; its absence is the only signal that the
    # DELETE never reached a table.
    if ! grep -q 'Update count:' <<< "$shell_out"; then
        echo "$shell_out" >&2
        echo "Error: the DELETE did not run — the password hash was NOT cleared." >&2
        exit 1
    fi

    # Suppress the trailing message when reached via host-side docker
    # compose exec (JCLAW_RESET_YES=1 is the unambiguous signal of that
    # path), since the host wraps the call with a container restart and
    # its own "Done" message — emitting both would print contradictory
    # advice ("no restart" then "restarting") in close succession.
    if [[ "${JCLAW_RESET_YES:-}" != "1" ]]; then
        echo "==> Done. Cleared the admin password hash."
        # ConfigService caches each key for 60s, and the DELETE above went in behind
        # the JVM's back, so a live instance keeps answering passwordSet=true until
        # the entry ages out. An unreadable probe advises the restart too — it is
        # harmless on a stopped app, whereas staying quiet strands the operator.
        local listeners probe_rc=0
        listeners=$(port_listener_pids "$BACKEND_PORT") || probe_rc=$?
        if [[ $probe_rc -ne 0 || -n "$listeners" ]]; then
            echo "    Restart so the app stops serving the cached hash:"
            echo "        ${INVOKE} restart"
        else
            echo "    Start the app and it will open on /setup-password."
        fi
    fi
}

# Verify Java 25+ is available. Required for Play backend (compile, run, test).
check_java() {
    local java_version
    java_version=$(java -version 2>&1 | head -1 | grep -oE '"[0-9]+'| tr -d '"')
    if [[ -z "$java_version" ]]; then
        echo "Error: Java not found. JDK 25+ is required."
        exit 1
    fi
    if [[ "$java_version" -lt 25 ]]; then
        echo "Error: Java $java_version found, but JDK 25+ is required."
        echo "       Set JAVA_HOME or use jenv to configure JDK 25."
        exit 1
    fi
}

# Verify Node.js 20+ is available. Required for the Nuxt dev server, the
# prod SPA build (npx nuxi generate), and corepack itself.
check_node() {
    if ! command -v node >/dev/null 2>&1; then
        echo "Error: node not found. Node.js 20+ is required."
        echo "       Install from https://nodejs.org/ (or use nvm/fnm/asdf)."
        exit 1
    fi
    local node_major
    node_major=$(node -v | sed -E 's/^v([0-9]+).*/\1/')
    if [[ -z "$node_major" || "$node_major" -lt 20 ]]; then
        echo "Error: Node $(node -v) found, but Node.js 20+ is required."
        exit 1
    fi
}

# Verify corepack is on PATH. Ships with Node 20+ by default but some
# distros (Debian's `nodejs` package, certain Nix profiles) strip it. We
# use it to validate the pnpm pin's +sha integrity hash on every start —
# without it, the security gate goes inert.
check_corepack() {
    if ! command -v corepack >/dev/null 2>&1; then
        echo "Error: corepack not found. It ships with Node 20+ — your install"
        echo "       may have stripped it. Install with: npm install -g corepack"
        exit 1
    fi
}

# Verify the Play 1.x command is on PATH. Backend builds, dev runs, prod
# starts, dependency syncs, and the test runner all shell out to play.
#
# Verify the play CLI is reachable. Bundle-mode resolution (prepending
# $SCRIPT_DIR to PATH when ./play sits next to this script) happens at
# top-level on every dispatch, so by the time we get here a bundled
# launcher would already be on PATH. This check just enforces "play
# must be findable somewhere" for the build/run paths that need it.
check_play() {
    if ! command -v play >/dev/null 2>&1; then
        echo "Error: play not found in $SCRIPT_DIR or on PATH."
        echo "       Install Abundent's fork: https://github.com/tsukhani/play1"
        echo "       and add play to your PATH, or extract a play bundle into $SCRIPT_DIR."
        exit 1
    fi
}

# Single entry point for prerequisite validation. Called from setup and
# from each runtime entry point (start/restart/test) so an environment
# missing a dependency fails at the dispatch level with a clean
# diagnostic, instead of cryptically halfway through play deps --sync or
# pnpm install. Cheap (4 fork-execs, ~50ms total on warm caches).
#
# Order matters — foundational toolchains first, derived tools after, so
# each successful check is unambiguous. corepack is checked after node
# because it ships inside Node's binary distribution.
#
# Dependency graph:
#   java     — standalone
#   node     — standalone (corepack ships inside it)
#   play     — standalone (the 1.13.x `play` CLI is a /bin/sh Gradle wrapper)
#   corepack — depends on node

check_prereqs() {
    # Foundational — no dependencies on other checks
    check_java

    # Derived — depends on the foundational checks above
    check_play       # the play CLI must be on PATH

    # Node + corepack are only needed when there's frontend source to
    # build, which means we're in a developer clone. A dist install
    # ships the prebuilt SPA in public/spa/ and never invokes
    # node/pnpm at runtime — requiring them there would be a needless
    # regression.
    if [[ -d "$SCRIPT_DIR/frontend" ]]; then
        check_node
        check_corepack
    fi
}

# SCRIPT_DIR is the canonical working directory — set at the top of the
# script via realpath of $0, it points at wherever this jclaw.sh lives,
# which is also where the install (or developer clone) sits. The
# working directory is always this script's own location.

# ─── First-time setup ───

# Idempotent — safe to re-run on a clone that's already configured. The
# things this fixes are all per-clone state that don't survive a fresh
# `git clone` or `rm -rf && git clone` cycle, because they live in
# `.git/config` (which git refuses to track) or under `frontend/node_modules/`
# (gitignored). Running this once after a fresh clone restores the wiring
# the rest of the workflow assumes.
do_setup() {
    cd "$SCRIPT_DIR"

    if [[ ! -f "conf/application.conf" ]]; then
        echo "Error: Not a JClaw directory (conf/application.conf not found)"
        echo "       Run from the jclaw directory."
        exit 1
    fi

    echo "==> Checking prerequisites..."
    check_prereqs
    # Print in the same dependency-graph order as check_prereqs runs them:
    # foundational toolchains first, then the wrappers/tools that ride them.
    echo "    Java:     $(java -version 2>&1 | head -1 | sed -E 's/.*"([^"]+)".*/\1/')"
    echo "    Node:     $(node -v)"
    echo "    Play:     $(command -v play)"
    echo "    Corepack: $(corepack -v 2>/dev/null || echo 'present')"

    echo ""
    echo "==> Wiring git hooks (.githooks/)..."
    /usr/bin/git config --local core.hooksPath .githooks
    echo "    core.hooksPath = $(/usr/bin/git config --local core.hooksPath)"

    echo ""
    echo "==> Initializing worktree state (certs/.env, PLAY_TEST_PORT)..."
    # Shared with .githooks/post-checkout — see do_init_worktree comment.
    do_init_worktree

    echo ""
    echo "==> Pinning pnpm via corepack (with integrity hash)..."
    setup_corepack_pnpm_pin

    echo ""
    echo "==> Installing frontend dependencies (so pre-commit's lint-staged is available)..."
    if [[ ! -d "frontend" ]]; then
        echo "    Skipped: frontend/ directory not found."
    elif ! command -v pnpm &>/dev/null; then
        echo "    Warning: pnpm not on PATH. Install with: npm install -g pnpm"
        echo "             Then re-run: ${INVOKE} setup"
    else
        (cd frontend && (pnpm install --frozen-lockfile 2>/dev/null || pnpm install))
    fi

    echo ""
    echo "==> Installing BMAD (slash commands → .claude/skills/)..."
    # _bmad/ and .claude/skills/ are both gitignored — they hold ~270 BMAD
    # install files and ~42 generated skill manifests respectively. Tracking
    # them caused massive diffs every time BMAD upgraded between minor
    # versions (e.g. 6.2.2 → 6.5.0 deleted/moved hundreds of files), so we
    # let setup regenerate them instead. Quick-update is the lightest action
    # that keeps existing module settings AND re-registers the IDE; -y skips
    # the prompts that would otherwise hang in non-interactive contexts;
    # --directory pins it to this clone (otherwise it asks).
    if ! command -v npx &>/dev/null; then
        echo "    Warning: npx not on PATH. Install Node.js to enable BMAD."
        echo "             Then re-run: ${INVOKE} setup"
    else
        npx bmad-method install \
            --directory "$SCRIPT_DIR" \
            --action quick-update \
            --tools claude-code \
            -y 2>&1 | tail -5
    fi

    echo ""
    echo "==> Registering graphify PreToolUse hook (.claude/settings.json)..."
    # graphify's knowledge-graph hook lives in .claude/settings.json, which is
    # gitignored (.claude/*), so it doesn't survive a fresh clone — the same
    # reason BMAD is reinstalled above. graphify is an optional per-machine
    # tool, so a clone without it still completes setup.
    #
    # `graphify claude install --project` is the only supported way to register
    # the hook, but it also (a) appends an always-on block to the *tracked*
    # CLAUDE.md and (b) installs a project-scoped skill duplicating the global
    # one in ~/.claude/skills/. AGENTS.md is this repo's canonical agent guide
    # and CLAUDE.md only points there, so we snapshot CLAUDE.md, let the
    # installer run, then restore it and drop the duplicate skill. That keeps
    # graphify authoritative for the hook's shape (upgrades land automatically)
    # without letting it edit files git tracks.
    if ! command -v graphify &>/dev/null; then
        echo "    Skipped: graphify not on PATH (optional)."
        echo "             Install with: uv tool install graphifyy"
    else
        local claude_md_snapshot
        claude_md_snapshot="$(mktemp)"
        [[ -f CLAUDE.md ]] && cp CLAUDE.md "$claude_md_snapshot"

        graphify claude install --project >/dev/null 2>&1

        # Restore only on a real difference, so an unchanged CLAUDE.md keeps its mtime.
        if [[ -f "$claude_md_snapshot" ]] && ! cmp -s "$claude_md_snapshot" CLAUDE.md; then
            cp "$claude_md_snapshot" CLAUDE.md
        fi
        rm -f "$claude_md_snapshot"
        rm -rf .claude/skills/graphify .claude/CLAUDE.md

        # Verify by outcome, not exit code — the hook is the only thing we want.
        if grep -q "hook-guard" .claude/settings.json 2>/dev/null; then
            echo "    PreToolUse hooks registered (Bash|Grep + Read|Glob)"
            if [[ -f graphify-out/graph.json ]]; then
                echo "    Graph present at graphify-out/ — hook is active."
            else
                echo "    No graph yet — hook stays inert until you run: /graphify ."
            fi
        else
            echo "    Warning: hook registration did not take."
            echo "             Run manually: graphify claude install --project"
        fi
    fi

    echo ""
    echo "==> Checking git remotes..."
    if /usr/bin/git remote get-url origin >/dev/null 2>&1; then
        echo "    origin: $(/usr/bin/git remote get-url origin)"
    else
        echo "    Warning: 'origin' remote not configured (unusual for a fresh clone)."
    fi
    # JClaw is an internal Abundent project with one canonical GitHub mirror,
    # so we auto-add the remote rather than prompting. /deploy requires both
    # `origin` (Bitbucket) and `github` (GitHub) — without this auto-add, every
    # fresh clone would have to read the help text and re-run a manual command
    # before the first deploy. The default URL matches the badge in README.md
    # and the JCLAW_GITHUB_REMOTE env var is the override hatch for the rare
    # contributor working from a personal fork.
    local github_url="${JCLAW_GITHUB_REMOTE:-https://github.com/tsukhani/jclaw.git}"
    if /usr/bin/git remote get-url github >/dev/null 2>&1; then
        echo "    github: $(/usr/bin/git remote get-url github)"
    else
        /usr/bin/git remote add github "$github_url"
        echo "    github: $github_url (added)"
    fi

    echo ""
    echo "==> Setup complete."
    echo ""
    echo "Next steps:"
    echo "  Start dev:        ${INVOKE} --dev start"
    echo "  Start prod:       ${INVOKE} start"
    echo "  Run tests:        ${INVOKE} test"
}

# ─── Production deploy ───

# Build a self-contained dist artifact that runs on a JDK-only host:
# precompiled bytecode replaces the Java source tree, the built static
# SPA replaces the Nuxt source tree. Per Play 1.x's
# deployment.textile § "Deploying without source code", the runtime only
# needs precompiled/, conf/, lib/, public/ — app/ and frontend/ are
# excluded by .distignore on this side. The matching runtime invocation
# in do_start_prod uses `play start -Dprecompiled=true` which forces
# prod mode and skips both compile passes (and refuses to start if
# precompiled/ is missing).
#
# Pure builder: produces $SCRIPT_DIR/dist/jclaw.zip and exits.
# Operators unzip the resulting tarball wherever they want to install
# JClaw — `unzip -o dist/jclaw.zip -d /opt && cd /opt/jclaw && ./jclaw.sh start`.
do_dist() {
    cd "$SCRIPT_DIR"

    # Add a workspace-local pnpm shim to PATH so the play1 plugin's
    # playDist task (which probes `pnpm --version` via Gradle's
    # ExecOperations.exec) can find pnpm. The bash pnpm() shadow doesn't
    # reach grandchild processes, so the shim is what crosses the boundary.
    ensure_pnpm_on_path_for_gradle

    # Validate the corepack/pnpm pin (hard-fail on a missing or mismatched
    # integrity hash) before Gradle drives pnpm under the hood.
    validate_corepack_pnpm

    # play dist (PlayDistTask) is self-contained: it runs playPrecompile
    # (via dependsOn — Gradle resolves deps natively in 1.13.x, PF-90) and
    # buildFrontendAndCopySpa (pnpm install + nuxi generate + copy to
    # public/spa/), then zips. We deliberately do NOT pre-run the backend
    # precompile or the SPA build here — playDist redoes both, so doing them
    # up front just built the whole thing twice.
    #
    # Disable the Gradle daemon for `play dist` only. The play1 plugin's
    # playDist task probes pnpm via ExecOperations.exec, which defaults to
    # the JVM's frozen-at-startup PATH — i.e. the daemon's environment. A
    # daemon started in an earlier shell (or in another concurrent Jenkins
    # job on the same agent) wouldn't have our shim on PATH, so its pnpm
    # probe fails. -Dorg.gradle.daemon=false makes Gradle run in-process for
    # this single invocation; the in-process JVM inherits the calling shell's
    # PATH directly, so the shim is visible.
    #
    # Why not `gradlew --stop`: --stop is global — it kills every daemon in
    # the user's daemon registry regardless of project or version, which on
    # a multi-executor Jenkins agent murders daemons used by concurrent
    # jobs. --no-daemon is targeted: it only affects this one invocation
    # and leaves other Gradle work untouched. Cost: ~5-10s extra cold-start
    # for play dist (acceptable; play dist isn't a hot-loop command).
    #
    # Why GRADLE_OPTS rather than `--no-daemon`: the play wrapper invokes
    # gradlew without forwarding arbitrary CLI flags, but POSIX env-var
    # inheritance carries GRADLE_OPTS through cleanly. gradlew's launcher
    # appends GRADLE_OPTS to the JVM args (gradlew:242), so a -D system
    # property lands on Gradle's own properties parser.
    echo "==> Packaging application (play dist)..."
    GRADLE_OPTS="${GRADLE_OPTS:-} -Dorg.gradle.daemon=false" play dist

    # play dist (PlayDistTask) writes a developer-distribution zip to
    # dist/<rootProject.name>.zip = dist/jclaw.zip — stable name driven
    # by rootProject.name in settings.gradle.kts, inner prefix "jclaw/",
    # contents are the source tree filtered by .gitignore + .distignore
    # plus precompiled/ and public/spa/ (force-included even though
    # gitignored). Notably absent: framework jar, framework lib, Gradle-
    # resolved app deps, and the runtime `./play` launcher — operators
    # unzipping this artifact need a local Java 25 + Gradle + Play 1
    # fork install to assemble the runtime classpath. For self-contained
    # packaging without those external prereqs see the Dockerfile, which
    # uses `play bundle` (PlayBundleTask) to produce dist/jclaw-bundle.zip.
    local zip_file="$SCRIPT_DIR/dist/jclaw.zip"
    if [[ ! -f "$zip_file" ]]; then
        echo "Error: play dist did not create $zip_file"
        exit 1
    fi

    echo "==> Distribution ready at $zip_file"
}

do_bundle() {
    cd "$SCRIPT_DIR"

    # Same Gradle-needs-pnpm-on-PATH + pin-hash plumbing as do_dist (see there
    # for the rationale). play bundle (PlayBundleTask) is self-contained too —
    # playPrecompile + buildFrontendAndCopySpa + dep/framework resolution + zip
    # in one task — so, like do_dist, we don't pre-run any of it here.
    ensure_pnpm_on_path_for_gradle
    validate_corepack_pnpm

    # play bundle (PlayBundleTask) writes dist/<rootProject.name>-bundle.zip =
    # dist/jclaw-bundle.zip (inner prefix "jclaw/"). Unlike the dist zip it
    # bakes in the framework jar + lib, Gradle-resolved app deps, and a `./play`
    # launcher alongside precompiled/ + public/spa/, so the unzipped tree runs
    # on a Java 25 JRE alone — the same artifact the Dockerfile stages into its
    # image. Daemon disabled for the same pnpm-probe-PATH reason as do_dist.
    echo "==> Building self-contained bundle (play bundle)..."
    GRADLE_OPTS="${GRADLE_OPTS:-} -Dorg.gradle.daemon=false" play bundle

    local zip_file="$SCRIPT_DIR/dist/jclaw-bundle.zip"
    if [[ ! -f "$zip_file" ]]; then
        echo "Error: play bundle did not create $zip_file"
        exit 1
    fi

    echo "==> Bundle ready at $zip_file"
}

# ─── Production start/stop ───

# Probe whether host:port accepts a TCP connection within ~2s. Returns 0
# when reachable, non-zero otherwise. Bash /dev/tcp + a watchdog kill is
# the most portable choice on the platforms we support — `timeout(1)` is
# missing from macOS base, and the `nc` flag set differs between BSD
# (macOS) and GNU (Linux).
probe_tcp_reachable() {
    local ip="$1" port="$2"
    ( exec 3<>"/dev/tcp/$ip/$port" ) 2>/dev/null &
    local pid=$!
    ( sleep 2 && kill -9 "$pid" 2>/dev/null ) >/dev/null 2>&1 &
    local watchdog=$!
    wait "$pid" 2>/dev/null
    local rc=$?
    kill "$watchdog" 2>/dev/null
    wait "$watchdog" 2>/dev/null
    return $rc
}

# Detect a stale H2 lock file from a prior crashed/abandoned run. H2's
# AUTO_SERVER=TRUE mode (set in db.url in conf/application.conf) writes
# the auto-server's IP and port into data/jclaw.lock.db. When the holder
# dies ungracefully (Docker container removed without a clean shutdown,
# host crash, JVM kill -9, OOM), the file persists with an unreachable
# address. Without this check, the next start hangs on the JDBC connect
# until OS-level timeout (often 60s+) before bailing without a useful
# diagnostic.
#
# Behavior:
#   - No lock file                 → no-op (fresh start)
#   - File-mode lock (no server=)  → no-op (no AUTO_SERVER hint to verify)
#   - Server-mode, reachable       → abort: a live holder owns the DB
#   - Server-mode, unreachable     → remove stale lock; continue starting
check_stale_h2_lock_or_exit() {
    local lock_file="$SCRIPT_DIR/data/jclaw.lock.db"
    [[ -f "$lock_file" ]] || return 0

    local method
    method=$(grep -iE "^method=" "$lock_file" 2>/dev/null | head -1 | cut -d= -f2 | tr -d '\r')
    case "$method" in
        Server|SERVER|server) ;;
        *) return 0 ;;
    esac

    local server
    server=$(grep -iE "^server=" "$lock_file" 2>/dev/null | head -1 | cut -d= -f2- | tr -d '\r')
    server="${server#tcp://}"
    if [[ -z "$server" || "$server" != *:* ]]; then
        return 0
    fi
    local ip="${server%:*}"
    local port="${server##*:}"

    if probe_tcp_reachable "$ip" "$port"; then
        echo "Error: another instance is already using this database at $ip:$port."
        echo "       Stop it before starting again ('$0 stop' or 'docker compose down')."
        echo "       (If you're sure no other instance exists, remove $lock_file manually.)"
        exit 1
    fi

    echo "==> Removed stale H2 lock pointing at $ip:$port"
    rm -f "$lock_file"
}

do_start_prod() {
    cd "$SCRIPT_DIR"

    if [[ ! -f "conf/application.conf" ]]; then
        echo "Error: Not a JClaw directory (conf/application.conf not found)"
        echo "       Run from the unzipped dist or developer clone root."
        exit 1
    fi

    # Refuse to start when our own instance is already running. Auto-stop
    # was the original behavior but it surprised users — they expected
    # `start` to be safe to run on a healthy instance and at worst
    # report "already up", not silently kill the JVM, drain its
    # connections, and rebuild on top. Mirror the dev path: error
    # cleanly and point at stop/restart, leaving the choice deliberate.
    if [[ -f "server.pid" ]] && kill -0 "$(cat server.pid)" 2>/dev/null; then
        echo "Error: JClaw is already running (pid: $(cat server.pid))."
        echo "       Run '$0 stop' to stop it,"
        echo "       or '$0 restart' to restart in place."
        exit 1
    fi

    # Refuse to start if the port is held by anything (a foreign process from
    # a different deploy dir, or a prior instance still inside its shutdown
    # hooks). The pid-file check above only catches OUR own server.pid; a
    # JVM running from /tmp/JClaw/ — say, a pre-existing prod deploy — has
    # its own pid file there and is invisible to us. Without this guard,
    # Play tries to bind, fails with "Could not bind on port 9000", aborts
    # startup → ShutdownJob fires → JPA work in the shutdown sequence
    # produces a giant Hibernate trace that buries the real one-line error.
    #
    # -sTCP:LISTEN is load-bearing: a plain `lsof -ti :PORT` matches any
    # socket on the port, including client-side CLOSE_WAITs (e.g. a Chrome
    # tab that was talking to a now-dead JVM). Only a LISTENing socket
    # blocks bind(), so filtering by state avoids false positives.
    local listeners probe_rc=0
    listeners=$(port_listener_pids "$BACKEND_PORT") || probe_rc=$?
    if (( probe_rc == 2 )); then
        echo "Warning: cannot tell whether port $BACKEND_PORT is free (no lsof, no netstat); starting anyway." >&2
    elif [[ -n "$listeners" ]]; then
        local holder
        holder=$(port_pids "$BACKEND_PORT" | tr '\n' ' ')
        echo "Error: Port $BACKEND_PORT is already in use (pid: ${holder% })."
        echo "       Run '$0 stop' first, or kill the holder."
        exit 1
    fi

    # Reap any stale H2 lock from a prior ungraceful shutdown (Docker rm
    # without compose down, JVM kill -9, host crash). Without this, the
    # JDBC connect below hangs ~60s following an AUTO_SERVER hint that
    # points nowhere before bailing.
    check_stale_h2_lock_or_exit

    # First-run guard for jclaw.zip distributions: if no certs/.env and
    # the conf-named secret variable isn't already set in the parent
    # shell, generate one on the fly so end-users who skip the developer-
    # only `setup` command don't get blocked. Then source certs/.env into
    # the JVM env and validate. TLS cert generation is opt-in via
    # `$0 cert` — start happily on plain HTTP/1.1 (port 9000) without it.
    ensure_env_for_start
    load_env_file
    require_application_secret

    # No explicit dep-resolution step here — Gradle handles it natively
    # in 1.13.x. `play precompile` and `play start` below both trigger
    # Gradle's dependency resolution as a transitive step.

    # Wipe tmp/ on every start. It contains things that go stale across
    # restarts but that the selective-precompile check below can't see:
    #   - tmp/bytecode/<MODE>/   enhanced bytecode cache (BytecodeCache.java);
    #                            keyed by class hash, but a wipe also clears
    #                            entries for classes that were renamed/deleted.
    #   - tmp/classes/           dev-mode JIT compile output; orphan .class
    #                            files from refactors live here.
    #   - tmp/uploads/           Apache MultipartParser staging; should be
    #                            empty between requests, but a crashed app
    #                            can leave half-written upload temp files.
    # Cheap (≤10 MB typical) and the prod path doesn't read tmp/ at all
    # (it loads precompiled/), so wiping is purely defensive here. The
    # selective-precompile guard below still skips the compile step when
    # sources are unchanged.
    rm -rf tmp

    # Branch on whether the runtime tree carries sources. A developer-
    # clone start has app/ + frontend/ and rebuilds those on every start
    # to honour code changes. A dist install (the unzipped tarball
    # produced by do_dist + .distignore stripping) has neither — just
    # precompiled/ and public/spa/ — so the rebuild steps are
    # impossible AND unnecessary. The presence of app/ is the source-
    # of-truth signal for which side of that fence we're on.
    if [[ -d app ]]; then
        # Auto-precompile when the existing precompiled/ classes are stale
        # or missing. Play 1.x's `play start --%prod` loads precompiled/
        # as-is and does NOT recompile when sources have changed — without
        # this check, restarts silently boot the prior binary and code
        # changes don't take effect. The -newer test uses the
        # precompiled/java directory's mtime as the staleness threshold
        # (Play refreshes it on each successful precompile), and
        # -print -quit stops the walk at the first match so a clean tree
        # costs milliseconds.
        if [[ ! -d precompiled/java ]] \
            || [[ -n "$(find app -name '*.java' -newer precompiled/java -print -quit 2>/dev/null)" ]]; then
            echo "==> Precompiling backend (source newer than precompiled classes)..."
            play precompile
        else
            echo "==> Skipping precompile (precompiled classes are up to date)"
        fi

        validate_corepack_pnpm

        echo "==> Installing frontend dependencies..."
        cd "$SCRIPT_DIR/frontend"
        pnpm install --frozen-lockfile 2>/dev/null || pnpm install

        # Rebuild the SPA only when it's actually stale, mirroring the
        # precompile guard above (JCLAW-887). Two reasons, the second the
        # important one:
        #
        #   - Cost. A warm `nuxi generate` is ~7 s (measured, twice) and was
        #     being paid on every restart, including backend-only ones.
        #     jclaw.sh never clears .nuxt or the Vite cache, so the restart
        #     case is always this warm figure, never a cold build.
        #   - Update detection. Nuxt's prod buildId is a per-build
        #     randomUUID() (@nuxt/schema 4.5.0), so an unconditional rebuild
        #     minted a fresh id even when every Vite content hash was
        #     byte-identical — verified by two back-to-back builds producing
        #     an identical chunk set under different ids. Nuxt's
        #     outdated-build poll ships live in the bundle, so open tabs read
        #     a backend-only restart as a new frontend version and hard-reload
        #     on next navigation. Gating the rebuild makes "new id" mean
        #     "frontend actually changed", which is the correct semantics.
        #
        # public/spa's mtime is the threshold: the `cp -r` below stamps it at
        # build time (cp doesn't preserve mtimes without -p).
        #
        # The prunes are load-bearing, not tidiness. `pnpm install` directly
        # above touches node_modules on every start, so without pruning it the
        # gate reports stale 100% of the time and silently degrades to the old
        # unconditional behaviour while appearing to work. .nuxt/.output/.vite
        # are outputs of the very build being gated, so they'd do the same.
        #
        # mtime can't see a change that leaves no newer file (a build-time env
        # var, say), and a missed input means shipping a frontend change that
        # never appears — strictly worse than a wasted rebuild. Hence the
        # escape hatch.
        spa_rebuild_reason=""
        if [[ -n "${JCLAW_FORCE_SPA_BUILD:-}" ]]; then
            spa_rebuild_reason="forced by JCLAW_FORCE_SPA_BUILD"
        elif [[ ! -d "$SCRIPT_DIR/public/spa" ]]; then
            spa_rebuild_reason="public/spa is missing"
        elif [[ -n "$(find . \
                \( -name node_modules -o -name .nuxt -o -name .output -o -name .vite \) -prune \
                -o -type f -newer "$SCRIPT_DIR/public/spa" -print -quit 2>/dev/null)" ]]; then
            spa_rebuild_reason="frontend sources are newer than public/spa"
        fi

        if [[ -n "$spa_rebuild_reason" ]]; then
            echo "==> Generating static SPA ($spa_rebuild_reason)..."
            npx nuxi generate

            echo "==> Copying SPA build to public/spa/..."
            rm -rf "$SCRIPT_DIR/public/spa"
            cp -r .output/public "$SCRIPT_DIR/public/spa"
        else
            echo "==> Skipping SPA build (public/spa is up to date)"
        fi

        cd "$SCRIPT_DIR"
    else
        # Dist install: precompiled/ and public/spa/ are baked into the
        # tarball by do_dist. Refuse to start if either is missing —
        # that means the dist was assembled wrong (or someone hand-
        # edited it). The matching `play run -Dprecompiled=true` below
        # would otherwise produce Play's terse "Precompiled classes
        # are missing!!" with no hint at the operator-side cause.
        if [[ ! -d precompiled/java ]]; then
            echo "Error: dist install is missing precompiled/java."
            echo "       The tarball was built without a precompile pass — re-run \`${INVOKE} dist\` from a developer clone and re-unzip the resulting dist/jclaw.zip."
            exit 1
        fi
        if [[ ! -d public/spa ]]; then
            echo "Error: dist install is missing public/spa."
            echo "       The tarball was built without a frontend build — re-run \`${INVOKE} dist\` from a developer clone and re-unzip the resulting dist/jclaw.zip."
            exit 1
        fi
        echo "==> Dist install detected (no app/, no frontend/) — skipping precompile + SPA build"
    fi
    mkdir -p "$SCRIPT_DIR/logs"

    # JVM tuning for production. Rationale for each flag:
    #   - ZGC: sub-millisecond pause collector. Matters because SSE streams
    #     hold connections open for seconds/tens of seconds; a 100 ms G1
    #     pause would stutter token output to the client.
    #   - Asymmetric heap by default (-Xms 512m, -Xmx 2g): the steady-state
    #     working set fits in ~512 MB, so committing the full 2 GB at boot
    #     would waste resident memory on idle deployments. ZGC handles
    #     heap resizing without stop-the-world pauses, so the
    #     resize-under-load argument that motivates fixed heaps in G1/CMS
    #     doesn't apply. To force a fixed heap (the previous default),
    #     set JCLAW_JVM_HEAP=2g — that pins -Xms == -Xmx == 2g. To split
    #     them independently, use JCLAW_JVM_XMS / JCLAW_JVM_XMX.
    #   - SoftMaxHeapSize 1g: ZGC's soft target, which ergonomics pin to -Xmx
    #     when -Xmx is explicit — leaving no cushion, so the collector only
    #     reacts near the ceiling and the heap ran to 92% under a c=50 x 20-turn
    #     loadtest. A 1g target against the ~280 MB live set held peak used to
    #     46% and peak RSS to 1.76 GB (from 2.5 GB) with zero allocation
    #     stalls, costing +75% GC cycles. Raising the heap does not raise this —
    #     set JCLAW_JVM_SOFTMAX too, or ZGC keeps targeting 1g.
    #   - HeapDumpOnOutOfMemoryError + ExitOnOutOfMemoryError: dump for
    #     postmortem, then exit cleanly so a process manager can restart.
    #   - MaxDirectMemorySize: caps Netty off-heap buffer allocation so a
    #     leak here can't exhaust native memory unnoticed.
    #   - io.netty.leakDetection.level=DISABLED: Netty defaults to SIMPLE,
    #     which allocates an Exception (with captured stack) per sampled
    #     ByteBuf (~1/128) to support leak diagnostics. Under sustained SSE
    #     traffic this is ~100+ Exception allocs/sec of pure overhead — dev
    #     mode keeps the default so handler authors catch forgotten
    #     releases, prod turns it off. Must be a -D (JVM property): Netty
    #     reads it once in ResourceLeakDetector's static initializer, so
    #     setting it via application.conf is too late.
    #   - DNS TTLs: LLM providers rotate endpoints via DNS; 30 s positive
    #     TTL keeps us close to current, 0 s negative TTL prevents caching
    #     transient lookup failures indefinitely.
    #   - GC log: rotated, time-stamped, very low overhead; invaluable for
    #     diagnosing GC-related latency spikes.
    # Play 1.x passes unrecognized args straight to the JVM (see
    # framework/pym/play/application.py:java_cmd), so these become the
    # actual java command line — no -J prefix needed.
    #
    # JCLAW_JVM_OPTS is appended last; the JVM uses last-wins for value
    # flags, so a user can override e.g. MaxDirectMemorySize without
    # editing the script. Boolean GC flags (UseZGC vs UseG1GC) conflict
    # rather than override — switching collectors still requires editing
    # the array below.
    # Resolution order (highest priority first):
    #   1. JCLAW_JVM_XMS / JCLAW_JVM_XMX — explicit per-flag override.
    #   2. JCLAW_JVM_HEAP — symmetric override (sets both flags to same value).
    #   3. Asymmetric default — Xms 512m, Xmx 2g.
    # The nested ${var:-${other:-default}} expansion encodes that order in one line.
    local heap="${JCLAW_JVM_HEAP:-}"
    local xms="${JCLAW_JVM_XMS:-${heap:-512m}}"
    local xmx="${JCLAW_JVM_XMX:-${heap:-2g}}"
    # Deliberately not derived from xmx: the soft target has to sit above the
    # live set, which scales with workload rather than with the ceiling, so a
    # fixed fraction of a raised -Xmx would be a guess. Raise it explicitly.
    local softmax="${JCLAW_JVM_SOFTMAX:-1g}"
    local jvm_opts=(
        "-Xms${xms}"
        "-Xmx${xmx}"
        "-XX:+UseZGC"
        "-XX:SoftMaxHeapSize=${softmax}"
        "-XX:+HeapDumpOnOutOfMemoryError"
        # Both file paths below are relative: do_start_prod cd's to $SCRIPT_DIR, so the JVM
        # inherits it. Absolute breaks Windows, where the bundle runs under Git Bash against a
        # native java.exe: /c/Users/... is unresolvable, and -Xlog splits its spec on ':', so a
        # translated C:/... path fails the parser instead of fixing it.
        "-XX:HeapDumpPath=logs/heap-oom.hprof"
        "-XX:+ExitOnOutOfMemoryError"
        "-XX:MaxDirectMemorySize=256m"
        "-Dio.netty.leakDetection.level=DISABLED"
        "-Dnetworkaddress.cache.ttl=30"
        "-Dnetworkaddress.cache.negative.ttl=0"
        "-Xlog:gc*:file=logs/gc.log:time,uptime,level,tags:filecount=5,filesize=10M"
    )

    # User-supplied extras go last so last-wins semantics let them override
    # value flags (e.g. -XX:MaxDirectMemorySize=512m). Word-splitting on the
    # env var is intentional: it lets the operator pass multiple flags.
    if [[ -n "${JCLAW_JVM_OPTS:-}" ]]; then
        # shellcheck disable=SC2206
        local extra_opts=( ${JCLAW_JVM_OPTS} )
        jvm_opts+=( "${extra_opts[@]}" )
    fi

    # HTTPS toggle: enable the 9443 listener (HTTPS + h2 + h3) only when
    # certs/host.{cert,key} pass strict validation (certs_valid). The
    # commented `# https.port=9443` in application.conf means HTTPS is off
    # without this -D — so absence of the flag = HTTP-1.1 only on 9000.
    # Placed after JCLAW_JVM_OPTS so an operator can still override the
    # port (e.g. JCLAW_JVM_OPTS='-Dhttps.port=8443') without losing the
    # cert-gate — last-wins on value flags.
    if certs_valid; then
        jvm_opts+=( "-Dhttps.port=9443" )
        echo "    HTTPS: enabled on 9443 (certs valid)"
    else
        echo "    HTTPS: disabled — run '$0 https' to enable HTTPS/h2/h3"
    fi

    # Dist installs have no sources — pass -Dprecompiled=true so Play
    # short-circuits the Java + template compile passes and loads
    # precompiled/ verbatim. Per Play 1.x's deployment.textile § "Step 3
    # — start in precompiled mode": "The system property forces prod mode
    # and skips both the Java and template compile passes. If precompiled/
    # is missing, the framework logs 'Precompiled classes are missing!!'
    # and refuses to start." We keep --%prod alongside as defense-in-depth
    # in case a future Play release decouples the implication.
    local mode_label="prod"
    if [[ ! -d app ]]; then
        jvm_opts+=( "-Dprecompiled=true" )
        mode_label="prod, precompiled"
    fi

    echo "==> Starting Play backend on port $BACKEND_PORT ($mode_label)..."
    if [[ "$xms" == "$xmx" ]]; then
        echo "    JVM: ${xms} heap (fixed), ZGC softmax ${softmax}, GC log → logs/gc.log"
    else
        echo "    JVM: -Xms${xms} -Xmx${xmx}, ZGC softmax ${softmax}, GC log → logs/gc.log"
    fi
    [[ -n "${JCLAW_JVM_OPTS:-}" ]] && echo "    Extra JVM opts: ${JCLAW_JVM_OPTS}"
    play start --%prod --http.port="$BACKEND_PORT" "${jvm_opts[@]}"

    echo ""
    echo "JClaw is running (production):"
    echo "  App: http://localhost:$BACKEND_PORT  (pid: $(cat "$SCRIPT_DIR/server.pid"))"
    echo ""
    echo "Tail logs with: $0 logs"
    echo "Stop with:      $0 stop"
}

# JCLAW zombie-JVM sweep (2026-07-08 incident): a Play JVM whose server.pid
# was removed (play stop deletes it immediately) but whose shutdown wedged
# becomes invisible to the pidfile path AND to port checks once its HTTP
# listener dies — it survived 11.5h of restarts, leaking a db-scheduler
# generation per dev reload. Sweep by process signature instead: every Play
# JVM carries -Dapplication.path=<project dir> on its command line.
kill_orphan_jvms() {
    # Git Bash ships no pgrep and Windows offers no cheap command-line match, so
    # the signature sweep is skipped there rather than reporting "no orphans".
    command -v pgrep >/dev/null 2>&1 || return 0
    local orphans
    orphans=$(pgrep -f "application.path=${SCRIPT_DIR}" 2>/dev/null || true)
    [[ -z "$orphans" ]] && return 0
    local pid
    for pid in $orphans; do
        echo "Warning: orphan JClaw JVM (pid $pid) found by process signature — terminating."
        kill "$pid" 2>/dev/null || true
    done
    sleep 3
    for pid in $orphans; do
        if kill -0 "$pid" 2>/dev/null; then
            echo "         pid $pid ignored SIGTERM — escalating to kill -9."
            kill -9 "$pid" 2>/dev/null || true
        fi
    done
}

do_stop_prod() {
    cd "$SCRIPT_DIR"

    if [[ ! -f "server.pid" ]]; then
        # No pidfile — but sweep for signature-matched orphans before
        # declaring victory (the pidfile-less wedged-JVM incident).
        kill_orphan_jvms
        echo "Nothing to stop — JClaw does not appear to be running in $SCRIPT_DIR"
        return
    fi

    local pid
    pid=$(cat server.pid 2>/dev/null)
    if [[ -z "$pid" ]]; then
        echo "Warning: server.pid is empty — cannot wait for JVM exit."
        play stop || true
        return
    fi
    echo "==> Stopping Play backend (pid: $pid)..."
    # `play stop` shells out to the gradle :playStop task, which waits a
    # bounded ~10s for the JVM to exit and returns BUILD FAILED (non-zero)
    # if it doesn't — e.g. when the shutdown hook deadlocks (the Play.stop
    # ↔ ApplicationClassloader circular wait, triggered by an in-flight
    # Groovy 404 render holding the classloader monitor onApplicationStop
    # needs). Under `set -e` that non-zero would abort this function before
    # the wait-and-escalate loop below ever runs, leaving a half-dead JVM
    # and surfacing only a bare gradle stack trace. Swallow it: the SIGTERM
    # is delivered regardless, and the kill -0 loop below is our real exit
    # signal.
    play stop || true

    # JCLAW-190: `play stop` (framework/pym/play/commands/daemon.py:84-86)
    # signals the JVM with SIGTERM and immediately removes server.pid —
    # it does NOT wait for the JVM to actually exit. The shutdown hook
    # (ShutdownJob + Play plugins + Hikari close) keeps running for up
    # to Play's 30s scheduler-shutdown budget. If we return now, a
    # follow-up `play start` boots a new JVM that overlaps the old one
    # — both polling Telegram with the same bot token, 409 Conflict on
    # the new JVM's first getUpdates.
    #
    # Polling on `[[ -f server.pid ]]` is therefore wrong (the file is
    # gone within milliseconds of `play stop`). The only reliable
    # liveness signal is `kill -0 $pid` — sends signal 0, which probes
    # the process without delivering a signal.
    #
    # Bound the wait at 60s so a wedged JVM doesn't hang the shell
    # forever; at that point an operator can investigate manually.
    local elapsed_ds=0
    local max_ds=120   # 60 seconds, polling every 0.5s = 120 deciseconds
    while [[ $elapsed_ds -lt $max_ds ]]; do
        if ! kill -0 "$pid" 2>/dev/null; then
            break
        fi
        sleep 0.5
        elapsed_ds=$((elapsed_ds + 1))
    done

    if kill -0 "$pid" 2>/dev/null; then
        # SIGTERM didn't bring it down within the budget — almost always the
        # Play.stop ↔ ApplicationClassloader shutdown deadlock, which no
        # further SIGTERM can clear (the process is already inside its own
        # SIGTERM handler running shutdown hooks). Escalate to SIGKILL so
        # `stop` actually stops and a follow-up `start`/`restart` finds
        # released ports and a reapable H2 lock instead of a wedged zombie.
        echo "Warning: pid $pid still alive after $((max_ds / 2))s (likely the Play"
        echo "         shutdown deadlock) — escalating to kill -9."
        kill -9 "$pid" 2>/dev/null || true
        sleep 1   # let the OS release the ports / H2 file lock the JVM held
    fi
    # Clean up the pid file regardless of graceful vs forced exit.
    [[ -f server.pid ]] && rm -f server.pid
    # Belt-and-braces: reap any signature-matched orphan from a prior
    # generation the pidfile path couldn't see.
    kill_orphan_jvms
    echo ""
    echo "JClaw stopped."
}

# ─── Dev mode start/stop ───

do_start_dev() {
    cd "$SCRIPT_DIR"

    if [[ ! -f "conf/application.conf" ]]; then
        echo "Error: Not a JClaw directory (conf/application.conf not found)"
        exit 1
    fi

    # Check if already running
    if [[ -f "server.pid" ]] && kill -0 "$(cat server.pid)" 2>/dev/null; then
        echo "Error: Play backend is already running (pid: $(cat server.pid))"
        exit 1
    fi

    # Refuse to start if the port is held by anything (a foreign process,
    # or a prior instance still inside its shutdown hooks). Without this,
    # Play silently fails to bind, exits, and the polling loop below would
    # see a stale listener and falsely declare success.
    #
    # -sTCP:LISTEN is load-bearing: a plain `lsof -ti :PORT` matches any
    # socket on the port, including client-side CLOSE_WAITs (e.g. a Chrome
    # tab that was talking to a now-dead JVM). Only a LISTENing socket
    # blocks bind(), so filtering by state avoids false positives.
    local listeners probe_rc=0
    listeners=$(port_listener_pids "$BACKEND_PORT") || probe_rc=$?
    if (( probe_rc == 2 )); then
        echo "Warning: cannot tell whether port $BACKEND_PORT is free (no lsof, no netstat); starting anyway." >&2
    elif [[ -n "$listeners" ]]; then
        local holder
        holder=$(port_pids "$BACKEND_PORT" | tr '\n' ' ')
        echo "Error: Port $BACKEND_PORT is already in use (pid: ${holder% })."
        echo "       Run '$0 --dev stop' first, or kill the holder."
        exit 1
    fi

    # Reap any stale H2 lock from a prior ungraceful shutdown. See the
    # prod-path counterpart for the rationale.
    check_stale_h2_lock_or_exit

    # Source certs/.env (the conf-named secret variable, plus any other
    # overrides) into the JVM environment. See the prod-mode counterpart
    # for the rationale.
    ensure_env_for_start
    load_env_file
    require_application_secret

    # Ensure dependencies are installed. Skipped under --backend-only: the
    # Nuxt dev server is staying up on the deps it already resolved, so this
    # is pure added latency on the in-app restart path.
    if [[ "$BACKEND_ONLY" != true ]]; then
        validate_corepack_pnpm

        echo "==> Checking frontend dependencies..."
        cd "$SCRIPT_DIR/frontend"
        pnpm install --frozen-lockfile 2>/dev/null || pnpm install
        cd "$SCRIPT_DIR"
    fi

    # Backend dep resolution is implicit — Gradle runs it as a transitive
    # step of `play run` below (1.13.x; PF-90). No more `play deps --sync`.

    # Wipe tmp/ on every start. See the prod-path counterpart for the full
    # rationale — same staleness concerns apply here. In dev the trade-off
    # is that the first request after restart re-enhances every accessed
    # class (a few seconds of latency on the first hit), which is the cost
    # of a guaranteed-clean enhancer cache and bytecode store.
    rm -rf tmp

    # HTTPS toggle: same cert-gated rule as prod (see do_start_prod).
    # Branched invocation rather than an empty-array expansion, since
    # `set -u` plus older bash (3.2 on macOS default) treats "${arr[@]}"
    # on an empty array as an unbound expansion error.
    echo "==> Starting Play backend on port $BACKEND_PORT (dev)..."
    if certs_valid; then
        echo "    HTTPS: enabled on 9443 (certs valid)"
        nohup play run --http.port="$BACKEND_PORT" -Dhttps.port=9443 > "$SCRIPT_DIR/logs/backend-dev.out" 2>&1 &
    else
        echo "    HTTPS: disabled — run '$0 https' to enable HTTPS/h2/h3"
        nohup play run --http.port="$BACKEND_PORT" > "$SCRIPT_DIR/logs/backend-dev.out" 2>&1 &
    fi
    local play_pid=$!
    # play run doesn't create server.pid — store the wrapper pid ourselves
    echo "$play_pid" > "$SCRIPT_DIR/server.pid"

    # Wait for backend to be ready. Three exit conditions, in priority
    # order: (1) wrapper died → fail with log tail; (2) port responds →
    # success; (3) timeout → fail. The original loop conflated (1) and
    # (3) and treated any listener on the port as our process — letting
    # a still-shutting-down prior instance mask a fresh bind failure.
    echo "    Waiting for backend to start..."
    local waited=0
    while true; do
        if ! kill -0 "$play_pid" 2>/dev/null; then
            echo "Error: Play backend exited during startup (pid $play_pid no longer alive)."
            echo "       Last lines of logs/backend-dev.out:"
            tail -20 "$SCRIPT_DIR/logs/backend-dev.out" 2>/dev/null | sed 's/^/         /'
            rm -f "$SCRIPT_DIR/server.pid"
            exit 1
        fi
        if curl -s -o /dev/null "http://localhost:$BACKEND_PORT" 2>/dev/null; then
            break
        fi
        sleep 1
        waited=$((waited + 1))
        if [[ $waited -ge 60 ]]; then
            echo "Error: Backend did not start within 60 seconds."
            echo "       Check logs/backend-dev.out for details."
            kill_tree "$play_pid"
            rm -f "$SCRIPT_DIR/server.pid"
            exit 1
        fi
    done

    if [[ "$BACKEND_ONLY" == true ]]; then
        echo ""
        echo "JClaw backend restarted (dev):"
        echo "  Backend:  http://localhost:$BACKEND_PORT  (pid: $play_pid)"
        echo "  Frontend: untouched (--backend-only)"
        echo "  Logs:     logs/backend-dev.out"
        return
    fi

    echo "==> Starting Nuxt dev server on port $FRONTEND_PORT..."
    cd "$SCRIPT_DIR/frontend"
    PORT="$FRONTEND_PORT" JCLAW_BACKEND_PORT="$BACKEND_PORT" nohup pnpm dev > "$SCRIPT_DIR/logs/frontend-dev.out" 2>&1 &
    echo $! > "$SCRIPT_DIR/$FRONTEND_PID_FILE"

    echo ""
    echo "JClaw is running (dev):"
    echo "  Backend:  http://localhost:$BACKEND_PORT  (pid: $play_pid)"
    echo "  Frontend: http://localhost:$FRONTEND_PORT  (pid: $(cat "$SCRIPT_DIR/$FRONTEND_PID_FILE"))"
    echo "  Logs:     logs/backend-dev.out, logs/frontend-dev.out"
    echo ""
    echo "Tail logs with: $0 --dev logs"
    echo "Stop with:      $0 --dev stop"
}

kill_tree() {
    local pid=$1
    # No pgrep under Git Bash, so the descent below cannot enumerate children;
    # taskkill /T does the same job natively. The doubled slashes stop MSYS
    # rewriting /PID and /T into paths (JCLAW-1105).
    if [[ "$IS_WINDOWS" == 1 ]] && command -v taskkill >/dev/null 2>&1; then
        taskkill //PID "$pid" //T >/dev/null 2>&1 || true
        return 0
    fi
    local children
    children=$(pgrep -P "$pid" 2>/dev/null) || true
    for child in $children; do
        kill_tree "$child"
    done
    kill "$pid" 2>/dev/null || true
}

# Block until $port has no listener, or $timeout seconds elapse.
# Returns 0 when freed, 1 on timeout. We rely on this during restart
# because Play's shutdown hooks (telegram cooldown, DB pool, etc.) hold
# the socket for several seconds after SIGTERM — without waiting, the
# next `play run` races the dying JVM and silently fails to bind 9000.
wait_for_port_free() {
    local port=$1
    local timeout=${2:-30}
    local waited=0
    while [[ -n "$(port_pids "$port")" ]]; do
        sleep 1
        waited=$((waited + 1))
        (( waited >= timeout )) && return 1
    done
    return 0
}

do_stop_dev() {
    cd "$SCRIPT_DIR"

    local stopped=0

    # Stop frontend (pnpm dev)
    if [[ "$BACKEND_ONLY" == true ]]; then
        echo "    Leaving Nuxt dev server running (--backend-only)"
    elif [[ -f "$FRONTEND_PID_FILE" ]]; then
        local fpid
        fpid=$(cat "$FRONTEND_PID_FILE")
        if kill -0 "$fpid" 2>/dev/null; then
            echo "==> Stopping Nuxt dev server (pid: $fpid)..."
            kill_tree "$fpid"
            rm -f "$FRONTEND_PID_FILE"
            stopped=1
        else
            echo "    Frontend not running (stale pid file)"
            rm -f "$FRONTEND_PID_FILE"
        fi
    else
        echo "    No frontend pid file found"
    fi

    # Clean up any orphan still holding the frontend port. Skipped under
    # --backend-only, where the live Nuxt we are deliberately sparing is
    # itself the process holding that port — sweeping it here would kill
    # exactly what the flag exists to preserve.
    if [[ "$BACKEND_ONLY" != true ]]; then
        local orphan
        orphan=$(port_pids "$FRONTEND_PORT") || true
        if [[ -n "$orphan" ]]; then
            echo "    Cleaning up orphan process on port $FRONTEND_PORT (pid: $orphan)..."
            kill $orphan 2>/dev/null || true
        fi
    fi

    # Stop backend (play run — we manage the pid file, not Play). The
    # wrapper may have grandchildren (sh `play` → Gradle/JVM → forked workers),
    # so kill_tree's recursive descent is necessary; pkill -P only catches
    # direct children. We then BLOCK until port 9000 is actually free so a
    # subsequent restart can't race the dying JVM's shutdown hooks.
    if [[ -f "server.pid" ]]; then
        local bpid
        bpid=$(cat "server.pid")
        if kill -0 "$bpid" 2>/dev/null; then
            echo "==> Stopping Play backend (pid: $bpid)..."
            kill_tree "$bpid"

            if ! wait_for_port_free "$BACKEND_PORT" 30; then
                local stragglers
                stragglers=$(port_pids "$BACKEND_PORT") || true
                if [[ -n "$stragglers" ]]; then
                    echo "    Port $BACKEND_PORT still bound after 30s; SIGKILL on residual pids: $stragglers"
                    kill -9 $stragglers 2>/dev/null || true
                    wait_for_port_free "$BACKEND_PORT" 5 || true
                fi
            fi

            rm -f "server.pid"
            stopped=1
        else
            echo "    Backend not running (stale pid file)"
            rm -f "server.pid"
        fi
    else
        echo "    No backend pid file found"
    fi

    if [[ $stopped -eq 1 ]]; then
        echo ""
        echo "JClaw stopped."
    else
        echo ""
        echo "Nothing to stop — JClaw does not appear to be running in $SCRIPT_DIR"
    fi
    # Same zombie-JVM sweep as prod stop.
    kill_orphan_jvms
}

# ─── Status ───

do_status() {
    cd "$SCRIPT_DIR"

    local mode="production"
    [[ "$DEV_MODE" == true ]] && mode="dev"

    echo "JClaw status ($SCRIPT_DIR, $mode):"
    echo ""

    # Backend
    if [[ -f "server.pid" ]] && kill -0 "$(cat server.pid)" 2>/dev/null; then
        echo "  Backend:  running (pid: $(cat server.pid))"
    else
        echo "  Backend:  stopped"
    fi

    # Frontend (dev mode only — production serves SPA from Play)
    if [[ "$DEV_MODE" == true ]]; then
        if [[ -f "$FRONTEND_PID_FILE" ]] && kill -0 "$(cat "$FRONTEND_PID_FILE")" 2>/dev/null; then
            echo "  Frontend: running (pid: $(cat "$FRONTEND_PID_FILE"))"
        else
            echo "  Frontend: stopped"
        fi
    else
        if [[ -f "$SCRIPT_DIR/public/spa/index.html" ]]; then
            echo "  Frontend: built (served from public/spa/)"
        else
            echo "  Frontend: not built (run ${INVOKE} dist or nuxi generate)"
        fi
    fi
}

# ─── Logs ───

do_logs() {
    cd "$SCRIPT_DIR"

    if [[ "$DEV_MODE" == true ]]; then
        local files=()
        [[ -f "logs/backend-dev.out" ]]  && files+=("logs/backend-dev.out")
        [[ -f "logs/frontend-dev.out" ]] && files+=("logs/frontend-dev.out")
        if [[ ${#files[@]} -eq 0 ]]; then
            echo "No dev log files found in $SCRIPT_DIR/logs/"
            exit 1
        fi
        tail -f "${files[@]}"
    else
        if [[ ! -f "logs/application.log" ]]; then
            echo "No log file found at $SCRIPT_DIR/logs/application.log"
            exit 1
        fi
        tail -f "logs/application.log"
    fi
}

# ─── Load test ───

# Drive the in-process mock-provider load test against the running backend.
# Auth: sends the application secret in the X-Loadtest-Auth header; the
# matching server-side guard on /api/metrics/loadtest checks both the header
# AND that the request comes from a loopback address. The secret lives in
# certs/.env (gitignored) under whatever variable name application.conf's
# `application.secret=${VARNAME}` declares, and is the same value Play uses
# to sign session cookies — reusing it here avoids introducing a separate
# operator-managed credential. JCLAW-181: the previous flow read
# jclaw.admin.password from application.conf and POSTed /api/auth/login,
# but commit caf9422 moved the admin password to a PBKDF2 hash in the
# Config DB, leaving the script with no plaintext to log in with.
do_loadtest() {
    cd "$SCRIPT_DIR"

    if [[ ! -f "conf/application.conf" ]]; then
        echo "Error: Not a JClaw directory (conf/application.conf not found)"
        exit 1
    fi

    # Source certs/.env so the secret variable is available, then resolve
    # its name the same way the start paths do (read from conf — operator-
    # renameable).
    load_env_file
    local var_name secret
    var_name=$(secret_var_name)
    secret=${!var_name:-}
    if [[ -z "$secret" ]]; then
        echo "Error: $var_name is not set."
        echo "       Loadtest auth uses $var_name (the same secret"
        echo "       Play signs session cookies with), sent in the"
        echo "       X-Loadtest-Auth header. It must be present in"
        echo "       $SCRIPT_DIR/certs/.env or exported in the parent shell."
        echo "       Generate or rotate via: $0 secret"
        exit 1
    fi

    # Verify the backend is reachable before doing anything else
    if ! curl -s -o /dev/null -w '%{http_code}' "http://localhost:$BACKEND_PORT/" | grep -q '^[23]'; then
        echo "Error: Backend is not responding on port $BACKEND_PORT."
        echo "       Start it first: $0 ${DEV_MODE:+--dev }start"
        exit 1
    fi

    # --clean: delete loadtest data and exit
    if [[ "$LT_CLEAN" == true ]]; then
        echo "==> Cleaning loadtest data..."
        local clean_status
        clean_status=$(curl -s -o /dev/null -w '%{http_code}' \
            -H "X-Loadtest-Auth: $secret" \
            -X DELETE "http://localhost:$BACKEND_PORT/api/metrics/loadtest/data")
        if [[ "$clean_status" == "200" ]]; then
            echo "==> Loadtest conversations, messages, events and latency samples deleted."
        else
            echo "Error: Cleanup failed (HTTP $clean_status)"
            exit 1
        fi
        return
    fi

    local lt_extra=""
    if [[ "$LT_REAL" == true ]]; then
        lt_extra=" provider=$LT_PROVIDER model=$LT_MODEL"
        # Warn when mock-shape knobs were passed alongside a real-provider
        # run. They'd be accepted by the JSON body silently and then ignored
        # by LoadTestRunner which routes through the real provider; print
        # the warning here so operators don't waste time tweaking a knob
        # that has no effect.
        if [[ ${#LT_MOCK_FLAGS_SET[@]} -gt 0 ]]; then
            echo "Warning: ${LT_MOCK_FLAGS_SET[*]} ignored when --provider/--model are set" \
                 "(only the in-process mock harness reads them)."
        fi
    fi
    echo "==> Running load test: concurrency=$LT_CONCURRENCY turns=$LT_TURNS$lt_extra"
    # Mock-only knobs: ttft / tokens-per-second / response-tokens drive the
    # in-process LoadTestHarness scenario and have no effect when routing
    # through an external provider. Hide them in real-provider mode so the
    # banner doesn't misleadingly imply they shape the run.
    if [[ "$LT_REAL" != true ]]; then
        echo "    ttft=${LT_TTFT_MS}ms tokens/s=$LT_TOKENS_PER_SECOND response=${LT_RESPONSE_TOKENS} tokens compress=$LT_COMPRESS"
    else
        echo "    compress=$LT_COMPRESS"
    fi
    # Show what the workers will actually send. Three modes:
    #  --prompts    → show file path + prompt count (varied prompt per turn)
    #  --message    → show the operator-supplied single message
    #  (neither)    → show the backend default DEFAULT_USER_MESSAGE explicitly
    if [[ -n "$LT_PROMPTS_FILE" ]]; then
        local lt_prompt_count=$(echo "$LT_PROMPTS_JSON" | python3 -c "import json, sys; print(len(json.load(sys.stdin)))")
        echo "    prompts=$LT_PROMPTS_FILE ($lt_prompt_count available, first $LT_TURNS will be used)"
    else
        local lt_msg_display="${LT_MESSAGE:-Why is the sky blue? Answer in exactly 50 words.}"
        if [[ ${#lt_msg_display} -gt 100 ]]; then
            lt_msg_display="${lt_msg_display:0:97}..."
        fi
        echo "    message=\"$lt_msg_display\""
    fi
    echo ""

    # Build the JSON body. Include provider / model only when both are set
    # — their joint presence is the wire-side signal for real-provider mode
    # (the controller derives it the same way the CLI did above). JSON-quote
    # $LT_MODEL because Ollama tags carry a colon (`gemma4:latest`) which
    # would otherwise look like a JSON struct.
    local body
    body=$(printf '{"concurrency":%s,"turns":%s,"ttftMs":%s,"tokensPerSecond":%s,"responseTokens":%s,"compress":%s' \
        "$LT_CONCURRENCY" "$LT_TURNS" "$LT_TTFT_MS" "$LT_TOKENS_PER_SECOND" "$LT_RESPONSE_TOKENS" "$LT_COMPRESS")
    if [[ "$LT_REAL" == true ]]; then
        body="$body,\"provider\":\"$LT_PROVIDER\",\"model\":\"$LT_MODEL\""
    fi
    if [[ -n "$LT_MESSAGE" ]]; then
        # JSON-escape via python3 so the operator can pass quotes/backslashes/
        # non-ASCII through --message without breaking the wire format.
        local msg_json
        msg_json=$(MSG="$LT_MESSAGE" python3 -c 'import json, os; print(json.dumps(os.environ["MSG"]))')
        body="$body,\"userMessage\":$msg_json"
    fi
    # --prompts mode: embed the pre-built JSON array. LT_PROMPTS_JSON was
    # produced by the global validation block above, so it's guaranteed
    # well-formed and long enough by the time we get here.
    if [[ -n "$LT_PROMPTS_JSON" ]]; then
        body="$body,\"prompts\":$LT_PROMPTS_JSON"
    fi
    body="$body}"

    # The okhttp/real path can take much longer than the default mock run
    # (each turn waits on a real model), so raise the curl wall-clock cap
    # accordingly. Default 300s stays for the existing mock path.
    local lt_max_time=300
    if [[ "$LT_REAL" == true ]]; then lt_max_time=1800; fi

    local response http_code
    response=$(curl -s \
        -H "X-Loadtest-Auth: $secret" \
        -H 'Content-Type: application/json' \
        -X POST "http://localhost:$BACKEND_PORT/api/metrics/loadtest" \
        -d "$body" \
        -w '\n%{http_code}' \
        --max-time "$lt_max_time")
    http_code=$(echo "$response" | tail -1)
    local json
    json=$(echo "$response" | sed '$d')

    if [[ "$http_code" != "200" ]]; then
        echo "Error: Load test failed (HTTP $http_code)"
        echo "$json"
        exit 1
    fi

    # Pretty-print with python if available, otherwise raw JSON. When the
    # response carries a turnBuckets array (turns > 1), render an additional
    # tabular per-turn breakdown below the JSON — TTFT and duration mean/p50/
    # p95 per turn position. Reveals provider prompt-cache cliffs (turn 1 ttft
    # dropping sharply at turn 2) and growing-history TTFT creep that flat
    # aggregates hide.
    if command -v python3 >/dev/null 2>&1; then
        echo "$json" | python3 -c '
import json, sys
data = json.load(sys.stdin)
print(json.dumps(data, indent=2))
cost = data.get("costUsd") or 0
pt, ct = data.get("promptTokens") or 0, data.get("completionTokens") or 0
if pt or ct or cost:
    print()
    # Reported here because teardown deletes the rows this is computed from, so a
    # run never reaches the Chat Cost dashboard (JCLAW-942). costUsd is the
    # figure reported by the provider; absent for the mock and unpriced providers.
    print("Run cost: %s  (prompt %s tok, completion %s tok)"
          % (("$%.4f" % cost) if cost else "not reported by provider",
             format(pt, ","), format(ct, ",")))
buckets = data.get("turnBuckets") or []
if buckets:
    print()
    print("Per-turn breakdown (TTFT and duration are client-measured, ms):")
    headers = ("Turn", "N", "TTFT mean", "TTFT p50", "TTFT p95",
               "Dur mean", "Dur p50", "Dur p95")
    fmt = "  {:>4}  {:>3}  {:>9}  {:>8}  {:>8}  {:>8}  {:>7}  {:>7}"
    print(fmt.format(*headers))
    print(fmt.format(*("-" * len(h) for h in headers)))
    for b in buckets:
        print(fmt.format(
            b["turn"], b["count"],
            b["ttftMeanMs"], b["ttftP50Ms"], b["ttftP95Ms"],
            b["durationMeanMs"], b["durationP50Ms"], b["durationP95Ms"]))
segs = data.get("serverSegments") or []
segs = [s for s in segs if s["count"] > 0]
if segs:
    print()
    print("Server-side latency segments (this run only, mean across all requests):")
    headers = ("Segment", "N", "Mean (ms)", "Sum (ms)")
    fmt = "  {:>16}  {:>5}  {:>10}  {:>10}"
    print(fmt.format(*headers))
    print(fmt.format(*("-" * len(h) for h in headers)))
    for s in segs:
        print(fmt.format(s["segment"], s["count"], s["meanMs"], s["sumMs"]))
'
    else
        echo "$json"
    fi

    echo ""
    echo "==> Tip: GET /api/metrics/latency for per-segment histograms"
}

# ─── Eval dataset (JCLAW-875) ───

# Runs services.evals.EvalRunner over the suites in evals/.  Offline:
# no backend, no model, no DB — so it can run on a laptop mid-edit and costs
# nothing on the serving path.
#
# Invoked as a plain `java -cp`, not through `play`, because the eval classes
# deliberately depend on nothing from the Play runtime; booting the framework
# just to read JSON files would trade a 200 ms command for a multi-second one.
# Drives a suite against a live agent via POST /api/evals/capture and writes the
# recorded run to a file (JCLAW-883). Unlike the offline path below this needs the
# running backend: capturing means real agent turns, which need JPA, a configured
# provider and the tool registry. Scoring what it writes stays offline.
#
# Auth mirrors loadtest — loopback plus the X-Loadtest-Auth header carrying the
# application secret — because it is the same trust boundary: an operator-run
# harness on the local host with no plaintext admin credential to log in with.
do_scrapetest() {
    local rung="1" concurrency="" out=""
    while [[ $# -gt 0 ]]; do
        case "$1" in
            --rung)        rung="${2:-}";        shift 2 ;;
            --concurrency) concurrency="${2:-}"; shift 2 ;;
            --out)         out="${2:-}";         shift 2 ;;
            --help|-h)     usage_scrapetest; exit 0 ;;
            *) echo "Error: unknown option for scrapetest: $1"; usage_scrapetest; exit 2 ;;
        esac
    done

    cd "$SCRIPT_DIR"
    if [[ ! -f evals/scrape/corpus.json ]]; then
        echo "Error: no corpus at evals/scrape/corpus.json."
        echo "       Build it: python3 evals/scrape/build_corpus.py --sample 40000 --per-stratum 25"
        exit 1
    fi

    load_env_file
    local var_name secret
    var_name=$(secret_var_name)
    secret=${!var_name:-}
    if [[ -z "$secret" ]]; then
        echo "Error: $var_name is not set - scrapetest authenticates with it via the"
        echo "       X-Loadtest-Auth header. Generate or rotate via: $0 secret"
        exit 1
    fi

    if ! curl -s -o /dev/null -w '%{http_code}' "http://localhost:$BACKEND_PORT/" | grep -q '^[23]'; then
        echo "Error: Backend is not responding on port $BACKEND_PORT."
        echo "       Start it first: $0 ${DEV_MODE:+--dev }start"
        exit 1
    fi

    local body
    body=$(printf '{"rung":"%s"' "$rung")
    [[ -n "$concurrency" ]] && body+=$(printf ',"concurrency":%s' "$concurrency")
    body+='}'

    echo "==> Running corpus against rung $rung"
    local tmp status
    tmp=$(mktemp)
    status=$(curl -s -o "$tmp" -w '%{http_code}' \
        -H "X-Loadtest-Auth: $secret" \
        -H "Content-Type: application/json" \
        -X POST --data "$body" \
        "http://localhost:$BACKEND_PORT/api/scrape/harness")

    if [[ "$status" != "200" ]]; then
        echo "Error: scrapetest failed (HTTP $status)"
        cat "$tmp"; echo; rm -f "$tmp"
        exit 1
    fi

    python3 - "$tmp" <<'PYSUM'
import json, sys
r = json.load(open(sys.argv[1]))
def table(title, d, order=None):
    keys = [k for k in (order or []) if k in d] + [k for k in d if not order or k not in order]
    print("  %-20s %6s %6s %8s" % (title, "ok", "total", "rate"))
    for k in keys:
        sc = d[k]
        print("  %-20s %6d %6d %7.1f%%" % (k, sc["ok"], sc["total"], sc["rate"]))
    print()
STRATA = ["unprotected-ssr", "unprotected-spa", "edge-served",
          "denied", "challenge", "interactive"]
print()
print("  %-22s %s" % ("rung", r["rung"]))
print("  %-22s %d/%d = %.1f%%" % ("access rate", r["ok"], r["attempted"], r["rate"]))
print()
table("stratum", r["byStratum"], STRATA)
table("edge vendor", r["byVendor"])
table("rendering", r["byRendering"])
print("  reasons:")
for reason, n in sorted(r["byReason"].items(), key=lambda kv: -kv[1]):
    print("    %-16s %4d" % (reason, n))
print()
if r.get("byNextRung"):
    # What the aggregate cannot say: which rung would have to exist for these
    # failures to become successes.
    print("  failures by rung that would address them:")
    for rung, n in sorted(r["byNextRung"].items(), key=lambda kv: -kv[1]):
        print("    %-16s %4d" % (rung, n))
    print()
pc = r.get("prerenderCapable", 0)
if pc:
    print("  %d failed origin(s) carry prerendering markers \u2014 they would serve a" % pc)
    print("  declared crawler more than they served us (evidence for JCLAW-1091).")
    print()
PYSUM

    if [[ -n "$out" ]]; then
        mkdir -p "$(dirname "$out")"
        mv "$tmp" "$out"
        echo "==> Full report written to $out"
    else
        rm -f "$tmp"
    fi
}

do_evals_capture() {
    local out="" agent="" suite="" concurrency="" local_suites=""
    local -a rest=()
    while [[ $# -gt 0 ]]; do
        case "$1" in
            --capture)     out="${2:-}";         shift 2 ;;
            --agent)       agent="${2:-}";       shift 2 ;;
            --suite)       suite="${2:-}";       shift 2 ;;
            --concurrency) concurrency="${2:-}"; shift 2 ;;
            --local)       local_suites=1;       shift   ;;
            *)             rest+=("$1");         shift   ;;
        esac
    done

    if [[ ${#rest[@]} -gt 0 ]]; then
        echo "Error: unknown option(s) for capture: ${rest[*]}"
        usage_evals
        exit 2
    fi
    # The agent is required rather than defaulted: a sweep that silently ran
    # against the operator's working agent is the accident worth designing out.
    if [[ -z "$out" || -z "$agent" || -z "$suite" ]]; then
        echo "Error: --capture needs --agent <name> and --suite <id>."
        usage_evals
        exit 2
    fi

    load_env_file
    local var_name secret
    var_name=$(secret_var_name)
    secret=${!var_name:-}
    if [[ -z "$secret" ]]; then
        echo "Error: $var_name is not set — capture authenticates with it via the"
        echo "       X-Loadtest-Auth header. Generate or rotate via: $0 secret"
        exit 1
    fi

    if ! curl -s -o /dev/null -w '%{http_code}' "http://localhost:$BACKEND_PORT/" | grep -q '^[23]'; then
        echo "Error: Backend is not responding on port $BACKEND_PORT."
        echo "       Start it first: $0 ${DEV_MODE:+--dev }start"
        exit 1
    fi

    local body
    body=$(printf '{"suite":"%s","agent":"%s"' "$suite" "$agent")
    [[ -n "$concurrency" ]] && body+=$(printf ',"concurrency":%s' "$concurrency")
    # Benchmark-derived suites cannot be committed, so they live untracked under
    # evals/local/suites and the backend only reads that directory when asked.
    [[ -n "$local_suites" ]] && body+=',"local":true'
    body+='}'

    echo "==> Capturing suite '$suite' against agent '$agent'..."
    local tmp status
    tmp=$(mktemp)
    status=$(curl -s -o "$tmp" -w '%{http_code}' \
        -H "X-Loadtest-Auth: $secret" \
        -H "Content-Type: application/json" \
        -X POST --data "$body" \
        "http://localhost:$BACKEND_PORT/api/evals/capture")

    if [[ "$status" != "200" ]]; then
        echo "Error: capture failed (HTTP $status)"
        cat "$tmp"
        echo
        rm -f "$tmp"
        exit 1
    fi

    mkdir -p "$(dirname "$out")"
    mv "$tmp" "$out"
    echo "==> Recorded run written to $out"
    echo "    Score it: $0 evals --responses $out --out reports/\$(date +%Y%m%d-%H%M).json"
}

do_evals() {
    cd "$SCRIPT_DIR"

    # --capture drives a live agent, so it goes over HTTP to the running backend
    # rather than through the offline java path below.
    local arg
    for arg in ${EVAL_ARGS[@]+"${EVAL_ARGS[@]}"}; do
        if [[ "$arg" == "--capture" ]]; then
            do_evals_capture ${EVAL_ARGS[@]+"${EVAL_ARGS[@]}"}
            return
        fi
    done

    # Recompile when the classes are missing OR stale (JCLAW-889). Checking only
    # for a missing directory meant that after any source edit this command
    # silently ran the previous build — and reported on it confidently, which is
    # the worst failure mode for the thing that decides whether the dataset is
    # valid. It is also what Jenkins runs. The find short-circuits on the first
    # newer file, so the check costs milliseconds and the command stays fast.
    local classes="$SCRIPT_DIR/precompiled/java"
    local reason=""
    if [[ ! -d "$classes" ]]; then
        reason="No compiled classes yet"
    elif [[ -n "$(find "$SCRIPT_DIR/app" -name '*.java' -newer "$classes" -print -quit 2>/dev/null)" ]]; then
        reason="Sources are newer than precompiled/java"
    fi
    if [[ -n "$reason" ]]; then
        echo "==> $reason — running 'play precompile' first..."
        check_play
        play precompile
    fi

    # Gson ships in the framework's lib/, which is exactly where the H2 jar the
    # reset path needs lives — reuse that resolver rather than re-deriving the
    # play install location (it already handles dist, bundle, and jenv-shim
    # layouts).
    local h2_jar lib_dir
    h2_jar=$(locate_h2_jar || true)
    if [[ -z "$h2_jar" ]]; then
        echo "Error: cannot locate the Play framework lib/ directory (needed for Gson)."
        echo "       Ensure 'play' is on PATH: https://github.com/tsukhani/play1"
        exit 1
    fi
    lib_dir=$(dirname "$h2_jar")

    java -cp "$(native_path "$classes")${CP_SEP}$(native_path "$lib_dir")/*" services.evals.EvalRunner ${EVAL_ARGS[@]+"${EVAL_ARGS[@]}"}
}

# ─── Consolidated test runner ───

# Runs the full pre-push validation suite: backend tests (play autotest),
# frontend tests (pnpm test), and frontend quality gates (stylelint, lint,
# typecheck). Streams each check's output and prints a consolidated summary
# at the end. Continues past failures so the user sees every result in one
# round-trip — the whole point of this subcommand. Exits non-zero if any
# Run the Playwright e2e suite against an already-running server. Kept out of
# `test` and out of Jenkins deliberately (see frontend/playwright.config.ts):
# it needs a live server and a real admin credential, so it is a local UAT
# safety net rather than a merge gate. This wrapper exists because all three
# of those prerequisites failed silently when the suite went unrun for months.
do_e2e() {
    cd "$SCRIPT_DIR"
    load_env_file

    local base="${JCLAW_E2E_BASE_URL:-}"
    if [[ -z "$base" ]]; then
        # -sTCP:LISTEN is load-bearing: a plain `lsof -ti :PORT` also matches
        # client sockets connected to that port, not just the listener.
        if [[ -n "$(port_listener_pids "$FRONTEND_PORT")" ]]; then
            base="http://localhost:$FRONTEND_PORT"
        elif [[ -n "$(port_listener_pids "$BACKEND_PORT")" ]]; then
            base="http://localhost:$BACKEND_PORT"
        else
            echo "Error: no JClaw server is listening on :$FRONTEND_PORT or :$BACKEND_PORT." >&2
            echo "       The e2e specs connect to a running server rather than starting one." >&2
            echo "         ${INVOKE} start          # production, SPA served on :$BACKEND_PORT" >&2
            echo "         ${INVOKE} --dev start    # dev, Nuxt on :$FRONTEND_PORT" >&2
            return 1
        fi
    fi

    if [[ -z "${JCLAW_ADMIN_PASSWORD:-}" ]]; then
        echo "Error: JCLAW_ADMIN_PASSWORD is not set — the suite signs in as admin" >&2
        echo "       before any spec runs, and the built-in default fails the" >&2
        echo "       password policy on a real install." >&2
        echo "       Set it in certs/.env (gitignored, alongside PLAY_SECRET):" >&2
        echo "         JCLAW_ADMIN_PASSWORD=your-admin-password" >&2
        echo "       Not in conf/application.conf — that file is tracked and ships" >&2
        echo "       to both remotes, one of which is public." >&2
        return 1
    fi

    # Playwright pins a browser build per package version, so an unattended
    # dependency bump leaves the cache behind and the suite cannot launch at
    # all. Idempotent and near-instant once the matching build is present.
    echo "==> Reconciling the Playwright browser build..."
    (cd "$SCRIPT_DIR/frontend" && pnpm exec playwright install chromium) || return 1

    echo ""
    echo "==> Running e2e against $base"
    (cd "$SCRIPT_DIR/frontend" && JCLAW_E2E_BASE_URL="$base" pnpm test:e2e)
}

# check failed so CI/git hooks can depend on it.
#
# play autotest sometimes returns 0 even when assertions fail, so we also
# scrape its log for the terminal "All tests passed" banner as a second
# confirmation before declaring backend green.
do_test() {
    cd "$SCRIPT_DIR"
    mkdir -p "$SCRIPT_DIR/logs"
    local backend_log="$SCRIPT_DIR/logs/test-backend.log"
    local frontend_log="$SCRIPT_DIR/logs/test-frontend.log"
    local stylelint_log="$SCRIPT_DIR/logs/test-stylelint.log"
    local lint_log="$SCRIPT_DIR/logs/test-lint.log"
    local typecheck_log="$SCRIPT_DIR/logs/test-typecheck.log"
    local backend_rc=0 frontend_rc=0 stylelint_rc=0 lint_rc=0 typecheck_rc=0
    local t0 backend_elapsed frontend_elapsed stylelint_elapsed lint_elapsed typecheck_elapsed
    local backend_passed backend_failed frontend_summary

    # Sub-second timing. Bash 3.2 (Apple's frozen build) lacks $EPOCHREALTIME,
    # but BSD `date +%s.%N` gives microsecond resolution on Darwin 25, and awk
    # handles the float subtraction. Without this, integer $SECONDS arithmetic
    # collapses sub-2s steps to 0s and drifts ±1s on everything else.
    _now() { date +%s.%N; }
    _elapsed() { awk -v a="$1" -v b="$(date +%s.%N)" 'BEGIN { printf "%.1f", b - a }'; }

    echo "==> Running backend tests (play autotest)..."
    t0=$(_now)
    # JCLAW-684: clear this run's per-class result sentinels first so the
    # post-run check below can't trip on stale *.class.failed.html from an
    # earlier run (play does not purge them).
    rm -f "$SCRIPT_DIR"/test-result/*.class.failed.html 2>/dev/null || true
    set +e
    play autotest 2>&1 | tee "$backend_log"
    backend_rc=${PIPESTATUS[0]}
    set -e
    if ! grep -q "^~ All tests passed" "$backend_log" 2>/dev/null; then
        backend_rc=1
    fi
    # JCLAW-684: belt-and-braces #3 — a per-class failed sentinel means a real
    # failure even if the exit code and summary line were spuriously green (the
    # "FirePhoque exit=0 but tests failed" / concurrent-collision class). This
    # bakes the manual sentinel-truth-check into the gate itself.
    if ls "$SCRIPT_DIR"/test-result/*.class.failed.html >/dev/null 2>&1; then
        echo "[jclaw test] Failed per-class result sentinels present — backend FAILED." >&2
        backend_rc=1
    fi
    backend_elapsed=$(_elapsed "$t0")

    echo ""
    echo "==> Running frontend tests (pnpm test)..."
    t0=$(_now)
    set +e
    # JCLAW-680: clean-prepare before vitest so the suite can't false-green
    # against a stale .nuxt / vite transform cache — the class of failure that
    # slipped a broken settings.vue past the local pre-push but failed the clean
    # CI build. Regenerating codegen makes the local gate match Jenkins (~15s).
    # `pnpm exec`, not `npx`: npx resolves nuxi from its own cache when the tree has
    # no node_modules (a fresh worktree), and that copy cannot resolve @nuxt/kit from
    # the project — prepare then fails and && skips the suite, leaving .nuxt deleted
    # so the later lint leg fails too. pnpm exec installs first, then runs the
    # project's own nuxi.
    (cd "$SCRIPT_DIR/frontend" && rm -rf .nuxt node_modules/.vite && pnpm exec nuxi prepare && pnpm test) 2>&1 | tee "$frontend_log"
    frontend_rc=${PIPESTATUS[0]}
    set -e
    frontend_elapsed=$(_elapsed "$t0")

    echo ""
    echo "==> Running frontend stylelint..."
    t0=$(_now)
    set +e
    (cd "$SCRIPT_DIR/frontend" && pnpm stylelint) 2>&1 | tee "$stylelint_log"
    stylelint_rc=${PIPESTATUS[0]}
    set -e
    stylelint_elapsed=$(_elapsed "$t0")

    echo ""
    echo "==> Running frontend lint (eslint)..."
    t0=$(_now)
    set +e
    (cd "$SCRIPT_DIR/frontend" && pnpm lint) 2>&1 | tee "$lint_log"
    lint_rc=${PIPESTATUS[0]}
    set -e
    lint_elapsed=$(_elapsed "$t0")

    echo ""
    echo "==> Running frontend typecheck (vue-tsc)..."
    t0=$(_now)
    set +e
    (cd "$SCRIPT_DIR/frontend" && pnpm typecheck) 2>&1 | tee "$typecheck_log"
    typecheck_rc=${PIPESTATUS[0]}
    set -e
    typecheck_elapsed=$(_elapsed "$t0")

    # Extract human-readable counts for the summary. Each grep is shielded
    # with `|| true` so a missing match under `set -e` + `pipefail` doesn't
    # tank the whole function before we get to print the verdict.
    backend_passed=$(grep -cE "PASSED " "$backend_log" 2>/dev/null || true)
    backend_failed=$(grep -cE "FAILED " "$backend_log" 2>/dev/null || true)
    # Vitest emits ANSI color escapes even when teed to a file, so strip them
    # once before line-anchored matching. Pull files count, tests count, and
    # Vitest's self-reported Duration so the summary tracks what the user saw
    # live — the script's own elapsed includes pnpm/node startup (≈2s) which
    # makes a 3.89s test run look like 6s.
    frontend_clean=$(sed $'s/\x1b\\[[0-9;]*m//g' "$frontend_log" 2>/dev/null || true)
    frontend_files=$(printf '%s\n' "$frontend_clean" | grep -E "^[[:space:]]+Test Files[[:space:]]" | tail -1 | grep -oE '\([0-9]+\)$' | tr -d '()' || true)
    frontend_tests=$(printf '%s\n' "$frontend_clean" | grep -E "^[[:space:]]+Tests[[:space:]]" | tail -1 | grep -oE '\([0-9]+\)$' | tr -d '()' || true)
    frontend_duration=$(printf '%s\n' "$frontend_clean" | grep -E "^[[:space:]]+Duration[[:space:]]" | tail -1 | sed -E 's/.*Duration[[:space:]]+([0-9.]+s).*/\1/' || true)
    if [[ -n "$frontend_files" && -n "$frontend_tests" && -n "$frontend_duration" ]]; then
        frontend_summary="(${frontend_files} files, ${frontend_tests} tests, ${frontend_duration})"
    else
        frontend_summary="(summary unavailable, ${frontend_elapsed}s)"
    fi

    echo ""
    echo "────────────────────────────────────────────────────────────"
    echo " jclaw test summary"
    echo "────────────────────────────────────────────────────────────"
    if [[ "$backend_rc" -eq 0 ]]; then
        printf " backend  : PASSED  (%s classes, %ss)\n" "${backend_passed:-?}" "$backend_elapsed"
    else
        printf " backend  : FAILED  (%s passed / %s failed, %ss)\n" \
            "${backend_passed:-?}" "${backend_failed:-?}" "$backend_elapsed"
        echo "            log: $backend_log"
    fi
    if [[ "$frontend_rc" -eq 0 ]]; then
        printf " frontend : PASSED  %s\n" "$frontend_summary"
    else
        printf " frontend : FAILED  %s\n" "$frontend_summary"
        echo "            log: $frontend_log"
    fi
    if [[ "$stylelint_rc" -eq 0 ]]; then
        printf " stylelint: PASSED  (%ss)\n" "$stylelint_elapsed"
    else
        printf " stylelint: FAILED  (%ss)\n" "$stylelint_elapsed"
        echo "            log: $stylelint_log"
    fi
    if [[ "$lint_rc" -eq 0 ]]; then
        printf " lint     : PASSED  (%ss)\n" "$lint_elapsed"
    else
        printf " lint     : FAILED  (%ss)\n" "$lint_elapsed"
        echo "            log: $lint_log"
    fi
    if [[ "$typecheck_rc" -eq 0 ]]; then
        printf " typecheck: PASSED  (%ss)\n" "$typecheck_elapsed"
    else
        printf " typecheck: FAILED  (%ss)\n" "$typecheck_elapsed"
        echo "            log: $typecheck_log"
    fi
    echo "────────────────────────────────────────────────────────────"

    if [[ "$backend_rc" -ne 0 || "$frontend_rc" -ne 0 || "$stylelint_rc" -ne 0 || "$lint_rc" -ne 0 || "$typecheck_rc" -ne 0 ]]; then
        exit 1
    fi
}

# Emit a shell completion script for `jclaw` (the installed shim) and `jclaw.sh`.
# The completable command + option lists track this install's audience, so a
# dist exposes the end-user surface and a developer clone the full one — driven
# by is_developer_clone, the same gate the parser and usage banners use.
do_completion() {
    # (name description) pairs — names feed bash, name:desc feed zsh. Order is
    # most-used first so the menu reads sensibly.
    local -a pairs=(
        start    "Start JClaw"
        stop     "Stop the running instance"
        restart  "Stop and start (restart)"
        status   "Show whether it is running"
        logs     "Tail the application log"
        upgrade  "Install the newest release in place"
        https    "Enable HTTPS (generate cert+key)"
        no-https "Disable HTTPS"
        secret   "Generate or rotate the application secret"
        reset    "Clear the admin password hash"
    )
    if is_developer_clone; then
        pairs+=(
            setup         "One-time per-clone bootstrap"
            init-worktree "Per-worktree bootstrap"
            loadtest      "Run the load-test harness"
            evals         "Validate or score the eval dataset"
            test          "Run backend + frontend tests"
            dist          "Build the developer-distribution zip"
            bundle        "Build the self-contained bundle zip"
        )
    fi
    pairs+=(
        completion "Print a shell completion script"
        shim       "Relink the jclaw command to this install"
        uninstall  "Remove JClaw and undo completion"
        help       "Show usage"
    )

    local names="" i
    for ((i=0; i<${#pairs[@]}; i+=2)); do names+="${pairs[i]} "; done
    names="${names% }"
    local opts="--backend-port --check --version --yes --help"
    is_developer_clone && opts="--dev --backend-port --frontend-port --help"

    case "$COMPLETION_SHELL" in
        bash)      _completion_bash "$names" "$opts" ;;
        zsh)       _completion_zsh "${pairs[@]}" ;;
        install)   _completion_install "$names" "$opts" "${pairs[@]}" ;;
        uninstall) _completion_uninstall ;;
        "")        echo "Usage: ${INVOKE} completion <bash|zsh|install|uninstall>" >&2; exit 1 ;;
        *)         echo "Error: unknown target '$COMPLETION_SHELL' (expected bash, zsh, install, or uninstall)." >&2; exit 1 ;;
    esac
}

# Bash completer. $1 = space-separated command names, $2 = option list. Registers
# for both `jclaw` and `jclaw.sh`. Resolves the already-typed subcommand by scan
# (so `jclaw --backend-port 8080 <TAB>` still offers commands), then: bash/zsh
# after `completion`, commands after `help`, options for a leading dash, else
# the command list. No dependency on the bash-completion package.
_completion_bash() {
    cat <<EOF
# jclaw(1) bash completion — generated by \`jclaw completion bash\`.
_jclaw() {
    local cur prev cmd="" i
    cur="\${COMP_WORDS[COMP_CWORD]}"
    prev="\${COMP_WORDS[COMP_CWORD-1]}"
    local commands="$1"
    local options="$2"
    for ((i=1; i<COMP_CWORD; i++)); do
        case " \$commands " in *" \${COMP_WORDS[i]} "*) cmd="\${COMP_WORDS[i]}"; break ;; esac
    done
    if [[ "\$cmd" == completion && "\$prev" == completion ]]; then
        COMPREPLY=( \$(compgen -W "bash zsh" -- "\$cur") ); return
    fi
    if [[ "\$cmd" == help ]]; then
        COMPREPLY=( \$(compgen -W "\$commands" -- "\$cur") ); return
    fi
    if [[ "\$cur" == -* ]]; then
        COMPREPLY=( \$(compgen -W "\$options" -- "\$cur") ); return
    fi
    if [[ -z "\$cmd" ]]; then
        COMPREPLY=( \$(compgen -W "\$commands" -- "\$cur") ); return
    fi
}
complete -F _jclaw jclaw jclaw.sh
EOF
}

# Zsh completer. Args are the (name desc) pairs. Works both as an fpath autoload
# file (#compdef) and via \`source <(jclaw completion zsh)\` — the funcstack guard
# picks the right registration path (the docker/podman idiom).
_completion_zsh() {
    local entries="" name desc
    while [[ $# -gt 0 ]]; do
        name="$1"; desc="$2"; shift 2
        entries="${entries}        '${name}:${desc}'"$'\n'
    done
    cat <<EOF
#compdef jclaw jclaw.sh
# jclaw(1) zsh completion — generated by \`jclaw completion zsh\`.
_jclaw() {
    local curcontext="\$curcontext" state line
    typeset -A opt_args
    local -a _jclaw_cmds
    _jclaw_cmds=(
$entries    )
    _arguments -C '1: :->cmd' '*:: :->args'
    case "\$state" in
        cmd) _describe -t commands 'jclaw command' _jclaw_cmds ;;
        args)
            case "\$line[1]" in
                completion) _values 'shell' bash zsh ;;
                help)       _describe -t commands 'jclaw command' _jclaw_cmds ;;
            esac
            ;;
    esac
}
if [ "\$funcstack[1]" = "_jclaw" ]; then
    _jclaw "\$@"
else
    compdef _jclaw jclaw jclaw.sh
fi
EOF
}

# Generate completion scripts to standard per-user dirs and wire the active
# shell's rc file (idempotent, sentinel-guarded, existing-rc-only). Best-effort:
# never aborts (always returns 0) so the one-line installer can't fail on it.
# Honors JCLAW_NO_RC_EDIT=1. Args: names opts pair...  (forwarded from do_completion)
_completion_install() {
    local names="$1" opts="$2"; shift 2   # remaining args = (name desc) pairs
    local data_dir="${XDG_DATA_HOME:-$HOME/.local/share}"
    local bash_file="$data_dir/bash-completion/completions/jclaw"
    local zsh_dir="$data_dir/zsh/site-functions"

    if mkdir -p "${bash_file%/*}" 2>/dev/null && _completion_bash "$names" "$opts" >"$bash_file" 2>/dev/null; then
        echo "    bash completion → $bash_file"
    fi
    if mkdir -p "$zsh_dir" 2>/dev/null && _completion_zsh "$@" >"$zsh_dir/_jclaw" 2>/dev/null; then
        echo "    zsh completion → $zsh_dir/_jclaw"
    fi

    if [[ -n "${JCLAW_NO_RC_EDIT:-}" ]]; then
        echo "    (skipped shell-rc wiring: JCLAW_NO_RC_EDIT is set)"
        return 0
    fi
    local shell_name="${SHELL:-}"; shell_name="${shell_name##*/}"
    case "$shell_name" in
        bash) _rc_wire "$HOME/.bashrc" "[ -f \"$bash_file\" ] && . \"$bash_file\"" ;;
        zsh)  _rc_wire "$HOME/.zshrc" "fpath=(\"$zsh_dir\" \$fpath); autoload -Uz compinit; compinit" ;;
        *)    echo "    shell '${shell_name:-unknown}' not auto-wired — source the generated script to enable completion" ;;
    esac
    return 0
}

# Append a sentinel-guarded line to an existing rc file, once. Always returns 0.
_rc_wire() {
    local rc="$1" line="$2"
    if [[ ! -f "$rc" ]]; then
        echo "    no $rc yet — add this line to enable completion: $line"
        return 0
    fi
    if grep -q 'jclaw completion (managed)' "$rc" 2>/dev/null; then
        echo "    shell completion already enabled in $rc"
        return 0
    fi
    if {
        printf '\n# >>> jclaw completion (managed) >>>\n'
        printf '%s\n' "$line"
        printf '# <<< jclaw completion (managed) <<<\n'
    } >>"$rc" 2>/dev/null; then
        echo "    enabled tab-completion in $rc (restart your shell to load it)"
    else
        echo "    could not write $rc — add this line yourself: $line"
    fi
    return 0
}

# Reverse of `completion install`: delete the generated scripts and strip the
# managed block from both shell rc files. Best-effort; always returns 0.
_completion_uninstall() {
    local data_dir="${XDG_DATA_HOME:-$HOME/.local/share}" f
    for f in "$data_dir/bash-completion/completions/jclaw" "$data_dir/zsh/site-functions/_jclaw"; do
        [[ -e "$f" ]] && rm -f "$f" && echo "    removed $f"
    done
    _rc_unwire "$HOME/.bashrc" completion
    _rc_unwire "$HOME/.zshrc" completion
    return 0
}

# Strip a sentinel-bounded managed block (by label, e.g. "completion" or "PATH")
# from an rc file, if present. Rewrites in place (preserving inode/permissions).
# Always returns 0.
_rc_unwire() {
    local rc="$1" label="$2" tmp
    [[ -f "$rc" ]] || return 0
    grep -q "jclaw $label (managed)" "$rc" 2>/dev/null || return 0
    tmp="$(mktemp 2>/dev/null)" || return 0
    if sed "/# >>> jclaw $label (managed) >>>/,/# <<< jclaw $label (managed) <<</d" "$rc" >"$tmp" 2>/dev/null; then
        cat "$tmp" >"$rc" && echo "    removed jclaw $label block from $rc"
    fi
    rm -f "$tmp"
    return 0
}

# Write the `jclaw` shim that puts this install on PATH. Single-sourced here
# rather than in install.sh so the installer and `upgrade` cannot write two
# different shims — the same reason install.sh delegates completion here.
#
# Unconditional: a fresh install claims the `jclaw` command. Callers that must
# NOT claim it — `upgrade`, which refreshes a shim rather than taking one over —
# check ownership before calling.
write_shim() {
    local bin_dir="${JCLAW_BIN_DIR:-$HOME/.local/bin}"
    mkdir -p "$bin_dir" || return 1
    cat > "$bin_dir/jclaw" <<EOF
#!/bin/sh
# An upgrade replaces the install directory, leaving any shell sitting in it
# with a CWD that no longer resolves; recover before exec so jclaw.sh starts
# clean instead of behind a getcwd error.
pwd -P >/dev/null 2>&1 || cd / 2>/dev/null
exec "$SCRIPT_DIR/jclaw.sh" "\$@"
EOF
    chmod +x "$bin_dir/jclaw"

    # PowerShell and cmd.exe cannot run the extensionless POSIX shim above, so
    # Windows needs a .cmd alongside it — .cmd rather than .ps1 because it works
    # from both shells and needs no execution-policy change. Emitted here rather
    # than by install.ps1 for the reason this function exists: `upgrade` refreshes
    # whatever write_shim produces, and an installer-written shim would go stale.
    #
    # `bash -l <script> %*` rather than `bash -lc "... $@"`: passing the script
    # directly sidesteps a second round of quote parsing, so `jclaw config set x
    # "a b"` survives cmd -> bash intact. -l is required for play/java to be on
    # PATH. install.ps1 owns the one part bash cannot do — the Windows PATH entry.
    #
    # Written to the install root as well as the PATH directory. Same generator,
    # so the two cannot drift — and the root copy is the fallback when the PATH
    # entry does not take (locked-down profile, policy-blocked registry write):
    # `cd` to the folder the installer names and run `.\jclaw.cmd start`. It is a
    # degraded fallback, not a replacement — no file in the install folder can
    # give you `jclaw` from anywhere — but it beats the alternative, which is
    # knowing to invoke bash.exe against jclaw.sh by hand.
    if [[ "$IS_WINDOWS" == 1 ]]; then
        local bash_win cmd_body
        bash_win="$(native_path "$(command -v bash)")" || return 1
        cmd_body="$(printf '@echo off\n"%s" -l "%s/jclaw.sh" %%*\n' "$bash_win" "$SCRIPT_DIR")"
        printf '%s' "$cmd_body" > "$bin_dir/jclaw.cmd"
        printf '%s' "$cmd_body" > "$SCRIPT_DIR/jclaw.cmd"
    fi
}

# True when writing the shim would not take one over from a different install:
# either there is no `jclaw` on PATH yet, or the one there already points here.
# Mirrors the ownership test do_uninstall applies before it removes anything.
shim_is_free() {
    local shim
    shim="$(command -v jclaw 2>/dev/null || true)"
    [[ -z "$shim" && -f "${JCLAW_BIN_DIR:-$HOME/.local/bin}/jclaw" ]] \
        && shim="${JCLAW_BIN_DIR:-$HOME/.local/bin}/jclaw"
    [[ -z "$shim" ]] && return 0
    grep -qF "$SCRIPT_DIR/jclaw.sh" "$shim" 2>/dev/null
}

# Remove an installed JClaw entirely. Refuses on a dev clone. Resolves the
# install root from SCRIPT_DIR (never CWD), guards against unsafe targets, prints
# exactly what it will do, confirms (unless --yes; no-TTY without --yes refuses),
# then: stop → undo completion → remove shim → delete the install dir LAST (so
# this running script's already-open fd keeps it readable to EOF).
do_uninstall() {
    if is_developer_clone; then
        echo "Refusing to uninstall: this is a developer git clone, not an installed copy." >&2
        echo "Delete the clone manually; to undo completion wiring run: ${INVOKE} completion uninstall" >&2
        exit 1
    fi

    local root shim
    root="$(cd "$SCRIPT_DIR/.." && pwd)"
    case "$root" in
        ""|"/"|"$HOME") echo "Refusing: install root resolved to '${root:-empty}' (unsafe)." >&2; exit 1 ;;
    esac
    if [[ ! -f "$root/jclaw/jclaw.sh" ]]; then
        echo "Refusing: '$root' does not look like a JClaw install (no jclaw/jclaw.sh)." >&2
        exit 1
    fi

    # The shell shim, only if it points at THIS install (don't touch a stranger's `jclaw`).
    shim="$(command -v jclaw 2>/dev/null || true)"
    [[ -z "$shim" && -f "$HOME/.local/bin/jclaw" ]] && shim="$HOME/.local/bin/jclaw"
    if [[ -n "$shim" ]] && ! grep -qF "$root/jclaw/jclaw.sh" "$shim" 2>/dev/null; then
        shim=""
    fi

    echo "This will permanently:"
    echo "  • stop JClaw if it is running"
    echo "  • remove the jclaw completion and PATH entries from ~/.bashrc and ~/.zshrc"
    [[ -n "$shim" ]] && echo "  • remove the jclaw command:  $shim"
    echo "  • delete the install directory (including the bundled JRE):"
    echo "        $root"
    echo

    if [[ -z "$ASSUME_YES" ]]; then
        # Try to actually open the terminal — a bare `[ -r /dev/tty ]` passes on the
        # device node even when there's no controlling tty (CI, `docker run` without -t).
        if { : >/dev/tty; } 2>/dev/null; then
            printf 'Proceed with uninstall? [y/N] ' >/dev/tty
            local ans=''
            read -r ans </dev/tty 2>/dev/null || ans=''
            case "$ans" in
                [Yy]|[Yy][Ee][Ss]) ;;
                *) echo "Aborted — nothing was removed."; exit 0 ;;
            esac
        else
            echo "Refusing to uninstall without confirmation (no terminal). Re-run with --yes." >&2
            exit 1
        fi
    fi

    echo
    echo "Stopping JClaw (if running)…"
    do_stop_prod || true

    echo "Removing shell completion and PATH entries…"
    _completion_uninstall
    _rc_unwire "$HOME/.bashrc" PATH
    _rc_unwire "$HOME/.zshrc" PATH

    if [[ -n "$shim" ]]; then
        rm -f "$shim" && echo "    removed $shim"
        # The Windows sibling write_shim emits; harmless to attempt elsewhere.
        if [[ -f "${shim%/*}/jclaw.cmd" ]]; then
            rm -f "${shim%/*}/jclaw.cmd" && echo "    removed ${shim%/*}/jclaw.cmd"
            echo "    NOTE: the Windows PATH entry is not removed here — drop"
            echo "          ${shim%/*} from your User PATH if nothing else uses it."
        fi
    fi

    cd / 2>/dev/null || true   # step out of the tree before deleting it
    if rm -rf "$root"; then
        echo "    removed $root"
    else
        echo "    WARNING: could not fully remove $root — delete it by hand." >&2
    fi
    echo
    echo "JClaw has been uninstalled."
}

# ─── Upgrade ───

UPGRADE_REPO="${JCLAW_UPGRADE_REPO:-tsukhani/jclaw}"
UPGRADE_API="${JCLAW_UPGRADE_API:-https://api.github.com}"
UPGRADE_DL="${JCLAW_UPGRADE_DOWNLOAD:-https://github.com}"

# Build outputs that must come from the new release verbatim. Everything else
# in the old tree that the release doesn't ship is carried over (see
# merge_absent) — an inverted allowlist, so a runtime directory added in a
# later version is preserved without anyone remembering to list it here.
# These five are the inverse case: merging them would leave a deleted class on
# the classpath, two versions of a jar in lib/, or orphaned SPA chunks.
UPGRADE_NO_MERGE="precompiled lib framework modules .classpath public/spa server.pid"

# Paths that must survive the swap, relative to the app root. Recorded only for
# the preflight's reassurance text — the carry-over itself is rule-driven.
UPGRADE_PRESERVED="data workspace logs certs public/apps sidecar (virtualenvs) conf/application.conf (if edited)"

sha256_file() {
    if command -v shasum >/dev/null 2>&1; then
        shasum -a 256 "$1" 2>/dev/null | awk '{print $1}'
    elif command -v sha256sum >/dev/null 2>&1; then
        sha256sum "$1" 2>/dev/null | awk '{print $1}'
    fi
}

# True inside a container. The image is the upgrade unit there — rewriting the
# tree in place is discarded by the next `docker compose up`, so upgrade
# refuses. JCLAW_CONTAINER is set by the Dockerfile; /.dockerenv covers images
# built elsewhere.
is_container() {
    [[ -n "${JCLAW_CONTAINER:-}" || -f /.dockerenv ]]
}

# bundle = self-contained (framework/ + ./play); dist = jclaw.zip against a
# system play. Drives which release asset we fetch, so the upgrade never swaps
# a bundle install for a dist tree (which would leave it with no launcher).
upgrade_install_kind() {
    if [[ -d "$SCRIPT_DIR/framework" && -x "$SCRIPT_DIR/play" ]]; then
        echo bundle
    else
        echo dist
    fi
}

upgrade_asset_name() {
    [[ "$(upgrade_install_kind)" == bundle ]] && echo "jclaw-bundle.zip" || echo "jclaw.zip"
}

# Why this install can't be upgraded, or empty when it can. Single source for
# the CLI guard and the API preflight, so the UI never offers a button the
# helper would refuse.
upgrade_unavailable_reason() {
    if is_developer_clone || [[ -d "$SCRIPT_DIR/app" ]]; then
        echo "this is a source checkout; update it with 'git pull'."
        return
    fi
    if is_container; then
        echo "this instance runs in a container; upgrade the image instead (docker compose pull && docker compose up -d)."
        return
    fi
    if [[ ! -f "$SCRIPT_DIR/conf/application.conf" ]]; then
        echo "$SCRIPT_DIR does not look like a JClaw install (no conf/application.conf)."
        return
    fi
    local parent
    parent="$(cd "$SCRIPT_DIR/.." && pwd)"
    if [[ ! -w "$parent" || ! -w "$SCRIPT_DIR" ]]; then
        echo "$SCRIPT_DIR is not writable — upgrade needs to replace it in place."
        return
    fi
    if ! command -v curl >/dev/null 2>&1 && ! command -v wget >/dev/null 2>&1; then
        echo "neither curl nor wget is available to download the release."
        return
    fi
    if ! command -v unzip >/dev/null 2>&1 && ! command -v jar >/dev/null 2>&1 \
        && ! { command -v powershell.exe >/dev/null 2>&1 && command -v cygpath >/dev/null 2>&1; }; then
        echo "no unzip, JDK 'jar' or PowerShell is available to extract the release."
        return
    fi
}

upgrade_current_version() {
    sed -n 's/^application\.version=\(.*\)/\1/p' "$SCRIPT_DIR/conf/application.conf" 2>/dev/null | head -1 | tr -d '\r'
}

# Newest published release tag (e.g. "v0.17.49"). Only tag_name is parsed —
# the asset URLs follow GitHub's stable releases/download/<tag>/<asset> form,
# and release-note bodies carry arbitrary quotes that naive JSON parsing trips
# on. grep -o + head -1 takes the first occurrence, which is the release's own
# tag regardless of field order.
upgrade_latest_tag() {
    local json
    json=$(http_get_text "$UPGRADE_API/repos/$UPGRADE_REPO/releases/latest") || return 1
    printf '%s' "$json" | tr -d '\n' \
        | grep -o '"tag_name"[[:space:]]*:[[:space:]]*"[^"]*"' \
        | head -1 | sed 's/.*"\([^"]*\)"$/\1/'
}

http_get_text() {
    if command -v curl >/dev/null 2>&1; then curl -fsSL "$1"
    elif command -v wget >/dev/null 2>&1; then wget -qO- "$1"
    else return 1; fi
}

# Content-Length of a release asset, following redirects to the CDN. Used for
# the free-space gate and the download percentage. Empty when unavailable —
# both callers degrade rather than fail.
upgrade_remote_size() {
    command -v curl >/dev/null 2>&1 || return 0
    curl -fsIL "$1" 2>/dev/null \
        | tr -d '\r' | awk 'BEGIN{IGNORECASE=1} /^content-length:/ {n=$2} END{if (n) print n}'
}

# 0 when $1 is a strictly newer version than $2. Leading "v" optional on
# either side; missing components read as 0, so 0.18 > 0.17.49.
version_gt() {
    local a="${1#v}" b="${2#v}" i x y
    [[ "$a" == "$b" ]] && return 1
    local -a A B
    IFS='.' read -r -a A <<<"$a"
    IFS='.' read -r -a B <<<"$b"
    for ((i = 0; i < 3; i++)); do
        x="${A[i]:-0}"; y="${B[i]:-0}"
        x="${x//[!0-9]/}"; y="${y//[!0-9]/}"
        x=$((10#${x:-0})); y=$((10#${y:-0}))
        ((x > y)) && return 0
        ((x < y)) && return 1
    done
    return 1
}

# Progress/outcome for the in-app panel, at $SCRIPT_DIR/logs/upgrade-status.json.
# logs/ is carried across the swap, so this path stays valid on both the success
# and rollback paths — and the file the new JVM serves is the one this wrote.
# Skipped during the swap window itself, when logs/ is briefly in neither tree.
upgrade_status() {
    local phase="$1" pct="$2" message="$3"
    local dir="$SCRIPT_DIR/logs"
    [[ -d "$dir" ]] || return 0
    printf '{"phase":"%s","pct":%s,"message":"%s","fromVersion":"%s","toVersion":"%s","startedAt":"%s"}\n' \
        "$phase" "${pct:-0}" "${message//\"/\'}" "$UPGRADE_FROM" "$UPGRADE_TO" "$UPGRADE_STARTED" \
        >"$dir/upgrade-status.json.tmp" 2>/dev/null || return 0
    mv -f "$dir/upgrade-status.json.tmp" "$dir/upgrade-status.json" 2>/dev/null || true
}

# Move everything under $1 that $2 doesn't already have into $2. A whole
# subtree absent from the release moves in one mv (so a sidecar .venv with
# 30k files costs one rename, not 30k); a directory present in both recurses;
# a file present in both is left behind, because the release owns it.
#
# The glob options are set once here rather than inside the recursion: an
# inner call's `shopt -u` on return would switch dotglob off for the rest of
# the *outer* loop, silently skipping every remaining dotfile.
merge_absent() {
    local saved
    # `|| true` is load-bearing, and it has to be INSIDE the substitution:
    # `shopt -p` reports whether the options are SET via its exit status, so it
    # returns 1 whenever either is off. Outside, that failure escapes the
    # subshell and aborts the upgrade at the exact point the tree has been
    # swapped but no state carried across.
    saved=$(shopt -p nullglob dotglob || true)
    shopt -s nullglob dotglob
    _merge_absent "$1" "$2" "${3:-}"
    eval "$saved"
}

# $3 is the path relative to the app root, for UPGRADE_NO_MERGE matching.
_merge_absent() {
    local old="$1" new="$2" rel="$3"
    [[ -d "$old" ]] || return 0
    local entry base childrel
    for entry in "$old"/*; do
        base="${entry##*/}"
        childrel="${rel:+$rel/}$base"
        case " $UPGRADE_NO_MERGE " in
            *" $childrel "*) continue ;;
        esac
        if [[ ! -e "$new/$base" && ! -L "$new/$base" ]]; then
            mv "$entry" "$new/$base"
        elif [[ -d "$entry" && -d "$new/$base" ]]; then
            _merge_absent "$entry" "$new/$base" "$childrel"
        fi
    done
}

# Download with a coarse percentage. curl's own progress bar can't be read
# without parsing a carriage-return stream, so poll the output file against the
# Content-Length we already fetched for the disk gate.
upgrade_download() {
    local url="$1" out="$2" total="${3:-}"
    if ! command -v curl >/dev/null 2>&1; then
        wget -qO "$out" "$url"
        return
    fi
    curl -fL --silent --show-error -o "$out" "$url" &
    local dl=$! got
    while kill -0 "$dl" 2>/dev/null; do
        if [[ -n "$total" && "$total" -gt 0 && -f "$out" ]]; then
            got=$(wc -c <"$out" 2>/dev/null || echo 0)
            upgrade_status downloading $((got * 100 / total)) "Downloading $(basename "$url")…"
        fi
        sleep 2
    done
    wait "$dl"
}

do_upgrade() {
    cd "$SCRIPT_DIR"

    local reason
    reason=$(upgrade_unavailable_reason)
    if [[ -n "$reason" ]]; then
        echo "Error: cannot upgrade — $reason" >&2
        exit 1
    fi

    local kind asset current
    kind=$(upgrade_install_kind)
    asset=$(upgrade_asset_name)
    current=$(upgrade_current_version)
    [[ -n "$current" ]] || { echo "Error: could not read application.version from conf/application.conf." >&2; exit 1; }

    local target="$UPGRADE_VERSION"
    if [[ -z "$target" ]]; then
        echo "==> Checking for a newer release…"
        target=$(upgrade_latest_tag) \
            || { echo "Error: could not reach $UPGRADE_API to resolve the latest release." >&2; exit 1; }
        [[ -n "$target" ]] || { echo "Error: no release tag found for $UPGRADE_REPO." >&2; exit 1; }
    fi
    case "$target" in v*) ;; *) target="v$target" ;; esac

    # $target is interpolated straight into the release URL below, and both ways in are
    # untrusted: --version comes from the caller, and upgrade_latest_tag echoes whatever
    # the API returned. A release tag is structurally incapable of carrying a dot-segment,
    # so anything that is not one is refused here rather than hardened downstream —
    # --path-as-is does NOT help, because GitHub collapses ../ server-side either way
    # (measured: with and without the flag both return the traversed resource).
    if ! [[ "$target" =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
        echo "Error: '$target' is not a release tag (expected vMAJOR.MINOR.PATCH)." >&2
        exit 1
    fi

    echo "    Installed: $current  ($kind install)"
    echo "    Latest:    ${target#v}"

    if [[ "$UPGRADE_CHECK" == true ]]; then
        if version_gt "$target" "$current"; then
            echo ""
            echo "An upgrade is available. Install it with: ${INVOKE} upgrade"
        else
            echo ""
            echo "JClaw is up to date."
        fi
        return 0
    fi

    if [[ -z "$UPGRADE_VERSION" ]] && ! version_gt "$target" "$current"; then
        echo ""
        echo "Already up to date — nothing to do."
        echo "(Pass --version <tag> to install a specific release anyway.)"
        return 0
    fi

    if [[ -z "$ASSUME_YES" ]]; then
        if { : >/dev/tty; } 2>/dev/null; then
            echo ""
            echo "This will replace $SCRIPT_DIR with ${target#v} and restart JClaw."
            echo "Your data, workspace, credentials and installed apps are preserved."
            printf 'Proceed? [y/N] ' >/dev/tty
            local ans=''
            read -r ans </dev/tty 2>/dev/null || ans=''
            case "$ans" in
                [Yy]|[Yy][Ee][Ss]) ;;
                *) echo "Aborted — nothing was changed."; return 0 ;;
            esac
        else
            echo "Refusing to upgrade without confirmation (no terminal). Re-run with --yes." >&2
            exit 1
        fi
    fi

    UPGRADE_FROM="$current"
    UPGRADE_TO="${target#v}"
    UPGRADE_STARTED="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

    local parent staging url
    parent="$(cd "$SCRIPT_DIR/.." && pwd)"
    staging="$parent/.jclaw-upgrade.$$"
    url="$UPGRADE_DL/$UPGRADE_REPO/releases/download/$target/$asset"
    [[ -n "${JCLAW_UPGRADE_ASSET_URL:-}" ]] && url="$JCLAW_UPGRADE_ASSET_URL"

    # Everything from here to the stop happens with the app still serving, so a
    # network failure, a bad checksum or a full disk costs no downtime at all.
    mkdir -p "$SCRIPT_DIR/logs"
    upgrade_status resolving 0 "Preparing to upgrade to ${target#v}…"

    local size free_kb need_kb
    size=$(upgrade_remote_size "$url" || true)
    if [[ -n "$size" && "$size" -gt 0 ]]; then
        free_kb=$(df -Pk "$parent" 2>/dev/null | awk 'NR==2{print $4}')
        # zip + extracted tree + the old tree we keep until the health gate passes.
        need_kb=$(( size / 1024 * 4 ))
        if [[ -n "$free_kb" && "$free_kb" -lt "$need_kb" ]]; then
            upgrade_status failed 0 "Not enough free disk space."
            echo "Error: need ~$((need_kb / 1024)) MB free under $parent, but only $((free_kb / 1024)) MB available." >&2
            exit 1
        fi
    fi

    rm -rf "$staging"
    mkdir -p "$staging"
    UPGRADE_STAGING="$staging"
    trap upgrade_cleanup EXIT

    echo "==> Downloading $asset (${target#v})…"
    upgrade_status downloading 0 "Downloading ${asset}…"
    if ! upgrade_download "$url" "$staging/$asset" "$size"; then
        upgrade_status failed 0 "Download failed."
        echo "Error: could not download $url" >&2
        exit 1
    fi

    # SHA256SUMS is a newer release artifact; releases published before it
    # existed simply don't have one. Warn and continue rather than blocking an
    # upgrade on an asset the publisher hadn't started attaching yet.
    upgrade_status verifying 100 "Verifying download…"
    local sums want got
    if [[ -n "${JCLAW_UPGRADE_ASSET_URL:-}" ]]; then
        # The operator chose the source (air-gapped mirror, fork build, or the
        # archive install.sh already fetched). Checking it against the published
        # release's SHA256SUMS would reject exactly the artifacts this override
        # exists to install.
        echo "    asset source overridden — skipping checksum verification"
    elif sums=$(http_get_text "$UPGRADE_DL/$UPGRADE_REPO/releases/download/$target/SHA256SUMS" 2>/dev/null) && [[ -n "$sums" ]]; then
        want=$(printf '%s\n' "$sums" | awk -v a="$asset" '$2 == a || $2 == "*" a {print $1}' | head -1)
        got=$(sha256_file "$staging/$asset")
        if [[ -n "$want" && -n "$got" && "$want" != "$got" ]]; then
            upgrade_status failed 0 "Checksum mismatch — refusing to install."
            echo "Error: checksum mismatch for $asset (wanted $want, got $got)." >&2
            exit 1
        fi
        [[ -n "$want" && -n "$got" ]] && echo "    checksum verified (sha256)"
    else
        echo "    no SHA256SUMS published for $target — skipping checksum verification"
    fi

    echo "==> Extracting…"
    upgrade_status extracting 0 "Extracting ${target#v}…"
    extract_zip "$staging/$asset" "$staging"
    local new_tree="$staging/jclaw"
    [[ -d "$new_tree" && -f "$new_tree/conf/application.conf" ]] \
        || { upgrade_status failed 0 "The downloaded archive has an unexpected layout."
             echo "Error: $asset did not extract to a jclaw/ tree with conf/application.conf." >&2; exit 1; }
    chmod +x "$new_tree/jclaw.sh" "$new_tree/play" "$new_tree/gradlew" 2>/dev/null || true

    # Hash the conf as shipped, before the merge can replace it with the
    # operator's copy — this is what the next upgrade compares against to tell
    # "never touched" from "edited".
    local new_conf_sha installed
    new_conf_sha=$(sha256_file "$new_tree/conf/application.conf")

    # Take the version from the archive rather than the tag we resolved: with
    # --version, or an overridden asset URL, the two can legitimately differ,
    # and the manifest has to describe what is actually on disk.
    installed=$(sed -n 's/^application\.version=\(.*\)/\1/p' "$new_tree/conf/application.conf" | head -1 | tr -d '\r')
    [[ -n "$installed" ]] || installed="${target#v}"
    UPGRADE_TO="$installed"

    # ─── Downtime starts here ───
    echo "==> Stopping JClaw…"
    upgrade_status stopping 0 "Stopping JClaw…"
    do_stop_prod || true

    # The tree swap is reversible; the schema migration the new version runs on
    # first boot is not (jpa.ddl=update). Without this copy, "roll back" would
    # mean old code against a newer schema.
    local db="$SCRIPT_DIR/data/jclaw.mv.db" db_backup=""
    if [[ -f "$db" ]]; then
        mkdir -p "$SCRIPT_DIR/data/backups"
        db_backup="$SCRIPT_DIR/data/backups/jclaw-$current-$(date -u +%Y%m%dT%H%M%SZ).mv.db"
        echo "==> Backing up the database…"
        cp "$db" "$db_backup"
        # Keep the three most recent; older ones are dead weight at DB size.
        # shellcheck disable=SC2012
        ls -1t "$SCRIPT_DIR/data/backups"/jclaw-*.mv.db 2>/dev/null | tail -n +4 | while read -r stale; do
            rm -f "$stale"
        done
    fi

    # Read the conf fingerprints while the old tree is still whole. They cannot
    # be read after the merge: the release ships no .jclaw-manifest, so the
    # merge is precisely what moves the old one out of $prev — leaving the
    # comparison below with nothing to compare against and every conf looking
    # edited.
    # `|| true` on both: pipefail turns a missing .jclaw-manifest (every install
    # predating it) into a failed pipeline, and an absent conf into a failed
    # hash. Both are legitimate, and both correctly read as "assume edited".
    # tr -d '\r': install.ps1 writes this file on Windows, where a stray CR
    # would ride into the hash and make every conf look edited.
    local recorded old_conf_sha
    recorded=$(sed -n 's/^conf_sha256=//p' "$SCRIPT_DIR/.jclaw-manifest" 2>/dev/null | head -1 | tr -d '\r' || true)
    old_conf_sha=$(sha256_file "$SCRIPT_DIR/conf/application.conf" || true)

    local prev="$SCRIPT_DIR.previous"
    rm -rf "$prev"

    # Arm the safety net before touching the tree. From here until the new
    # version is started, ANY failure — not just "it didn't come up" — has to
    # put the old install back. Every step below is therefore guarded
    # explicitly, with the EXIT trap as the backstop for anything unforeseen.
    UPGRADE_PREV="$prev"
    UPGRADE_DB_BACKUP="$db_backup"

    echo "==> Installing ${target#v}…"
    # The first move is the point of no return; before it, a failure has changed
    # nothing, so it exits rather than rolling back.
    mv "$SCRIPT_DIR" "$prev" || {
        echo "Error: could not move the current install aside — nothing was changed." >&2
        upgrade_status failed 0 "Could not replace the install directory."
        exit 1
    }
    UPGRADE_SWAPPED=true
    mv "$new_tree" "$SCRIPT_DIR" || upgrade_abort "could not move the new release into place"
    cd "$SCRIPT_DIR" || upgrade_abort "could not enter the new install directory"
    hash -r
    upgrade_status swapping 0 "Installing ${target#v}…"

    # Carry state forward. db_backup was written under data/, so it rides along.
    merge_absent "$prev" "$SCRIPT_DIR" "" || upgrade_abort "could not carry your data across"
    [[ -n "$db_backup" ]] && db_backup="$SCRIPT_DIR/data/backups/${db_backup##*/}"

    local conf_preserved=false
    if [[ -z "$recorded" || "$recorded" != "$old_conf_sha" ]]; then
        # Edited by the operator (or predates the manifest, where assuming
        # "edited" is the side that can't lose their work). Keep theirs and
        # leave the release's copy beside it so new keys are discoverable.
        cp "$SCRIPT_DIR/conf/application.conf" "$SCRIPT_DIR/conf/application.conf.new-$installed" \
            || upgrade_abort "could not stage the new conf/application.conf"
        cp "$prev/conf/application.conf" "$SCRIPT_DIR/conf/application.conf" \
            || upgrade_abort "could not restore your conf/application.conf"

        # application.version is release metadata that happens to live in the
        # operator's file. Carrying their copy over verbatim would leave the new
        # install reporting the old version — and since the update check reads
        # that line, offering this same upgrade forever.
        local conf_tmp="$SCRIPT_DIR/conf/.application.conf.upgrade.$$"
        sed "s/^application\.version=.*/application.version=$installed/" \
            "$SCRIPT_DIR/conf/application.conf" >"$conf_tmp" \
            && mv "$conf_tmp" "$SCRIPT_DIR/conf/application.conf" \
            || upgrade_abort "could not set application.version in your conf/application.conf"
        conf_preserved=true
    fi

    printf 'version=%s\nconf_sha256=%s\ninstalled_at=%s\n' \
        "$installed" "$new_conf_sha" "$UPGRADE_STARTED" >"$SCRIPT_DIR/.jclaw-manifest" \
        || upgrade_abort "could not write .jclaw-manifest"

    # Refresh the `jclaw` command from the release just installed, so a shim
    # written by an older installer picks up changes to it. Runs the NEW
    # jclaw.sh: this script is the old one, and its copy of the shim is exactly
    # the stale text we are replacing. Best-effort and skipped when `jclaw`
    # belongs to a different install — a shim is worth refreshing, never worth
    # failing a good upgrade or taking over.
    if shim_is_free; then
        "$SCRIPT_DIR/jclaw.sh" shim >/dev/null 2>&1 || true
    fi

    echo "==> Starting ${installed}…"
    upgrade_status starting 0 "Starting ${installed}…"
    # Past the filesystem work: from here a failure is "the new version doesn't
    # run", which the health gate below handles with the same rollback. Clearing
    # the flag keeps the EXIT backstop from rolling back a second time.
    UPGRADE_SWAPPED=false
    local started=true
    "$SCRIPT_DIR/jclaw.sh" start --backend-port "$BACKEND_PORT" || started=false

    if [[ "$started" == true ]] && upgrade_health_ok; then
        echo ""
        echo "==> Upgraded $current → $installed"
        [[ "$conf_preserved" == true ]] \
            && echo "    Your edited conf/application.conf was kept; the new one is at conf/application.conf.new-$installed"
        [[ -n "$db_backup" ]] && echo "    Database backup: ${db_backup#"$SCRIPT_DIR"/}"
        rm -rf "$prev"
        UPGRADE_SWAPPED=false
        upgrade_status done 100 "Upgraded to $installed."
        return 0
    fi

    echo ""
    echo "Error: $installed did not come up — rolling back to $current." >&2
    upgrade_status rolling-back 0 "Upgrade failed — rolling back to ${current}…"
    upgrade_rollback "$prev" "$db_backup"
    upgrade_status rolled-back 0 "Upgrade to $installed failed; rolled back to $current."
    echo "Rolled back to $current. See logs/upgrade.log for what failed." >&2
    exit 1
}

# Roll back and stop. Called explicitly by every step in the window between
# replacing the tree and starting the new version, and as a backstop from the
# EXIT trap — because a failure in there leaves an install carrying new code and
# none of its state, beside a .previous directory the operator has no reason to
# know about.
#
# Deliberately NOT an ERR trap: that needs `set -E` to reach inside functions,
# and an inherited ERR trap also fires inside command substitutions, where the
# handler's own output lands in the variable being assigned and its `exit` only
# leaves the subshell. EXIT traps are not inherited by `$( )`, so the backstop
# uses one of those.
upgrade_abort() {
    UPGRADE_SWAPPED=false
    echo "" >&2
    echo "Error: $1 — rolling back to $UPGRADE_FROM." >&2
    upgrade_rollback "$UPGRADE_PREV" "$UPGRADE_DB_BACKUP"
    upgrade_status rolled-back 0 "Upgrade to $UPGRADE_TO failed; rolled back to $UPGRADE_FROM."
    echo "Rolled back to $UPGRADE_FROM. See logs/upgrade.log for what failed." >&2
    exit 1
}

# Single EXIT handler for the upgrade: drop the staging tree, and if we died
# with the swap half-applied, put the old install back.
upgrade_cleanup() {
    local rc=$?
    [[ -n "$UPGRADE_STAGING" ]] && rm -rf "$UPGRADE_STAGING"
    if [[ "$UPGRADE_SWAPPED" == true ]]; then
        UPGRADE_SWAPPED=false
        echo "" >&2
        echo "Error: the upgrade stopped unexpectedly after the tree was replaced." >&2
        upgrade_rollback "$UPGRADE_PREV" "$UPGRADE_DB_BACKUP"
        upgrade_status rolled-back 0 "Upgrade to $UPGRADE_TO failed; rolled back to $UPGRADE_FROM."
        echo "Rolled back to $UPGRADE_FROM. See logs/upgrade.log for what failed." >&2
        exit 1
    fi
    exit "$rc"
}

# Poll until the new instance answers, or give up. Two consecutive successes,
# for the same reason SettingsRestartPanel requires them: a half-initialised
# Play can answer one request mid-boot.
upgrade_health_ok() {
    local budget=240 elapsed=0 ok=0 url="http://127.0.0.1:$BACKEND_PORT/api/status"
    while ((elapsed < budget)); do
        if upgrade_probe "$url"; then
            ((ok++))
            ((ok >= 2)) && return 0
        else
            ok=0
        fi
        sleep 2
        elapsed=$((elapsed + 2))
    done
    return 1
}

upgrade_probe() {
    if command -v curl >/dev/null 2>&1; then
        curl -fsS --max-time 4 "$1" >/dev/null 2>&1
    else
        wget -q --timeout=4 -O /dev/null "$1" >/dev/null 2>&1
    fi
}

# Undo the swap: state moves back out of the new tree, the old tree returns,
# and the pre-upgrade database is restored over whatever schema migration the
# new version applied on its way up.
upgrade_rollback() {
    local prev="$1" db_backup="$2" parent
    parent="$(cd "$SCRIPT_DIR/.." && pwd)"
    "$SCRIPT_DIR/jclaw.sh" stop --backend-port "$BACKEND_PORT" >/dev/null 2>&1 || true

    rm -f "$SCRIPT_DIR"/conf/application.conf.new-* 2>/dev/null || true
    merge_absent "$SCRIPT_DIR" "$prev" ""
    cd "$parent" 2>/dev/null || cd / || true
    rm -rf "$SCRIPT_DIR"
    mv "$prev" "$SCRIPT_DIR"
    cd "$SCRIPT_DIR"
    hash -r

    if [[ -n "$db_backup" ]]; then
        local restored="$SCRIPT_DIR/data/backups/${db_backup##*/}"
        [[ -f "$restored" ]] && cp "$restored" "$SCRIPT_DIR/data/jclaw.mv.db"
    fi

    "$SCRIPT_DIR/jclaw.sh" start --backend-port "$BACKEND_PORT" || true
}

extract_zip() {
    local zip="$1" dest="$2"
    if command -v unzip >/dev/null 2>&1; then
        unzip -q -o "$zip" -d "$dest"
    elif command -v jar >/dev/null 2>&1; then
        ( cd "$dest" && jar xf "$zip" )
    else
        # Git Bash ships neither unzip nor jar, and the Windows installer lays down
        # a JRE, so PowerShell is the only extractor a stock Windows install has —
        # which is why install.ps1 unpacks the first release with it too.
        local zip_win dest_win
        # Doubled, not backslash-escaped: '' is how a literal quote goes into a
        # PowerShell single-quoted string, and C:\Users\O'Brien is a real home.
        zip_win=$(cygpath -w "$zip"); zip_win=${zip_win//\'/\'\'}
        dest_win=$(cygpath -w "$dest"); dest_win=${dest_win//\'/\'\'}
        powershell.exe -NoProfile -NonInteractive -Command \
            "Expand-Archive -LiteralPath '$zip_win' -DestinationPath '$dest_win' -Force"
    fi
}

# ─── Execute ───

case "$COMMAND" in
    https)
        do_https
        ;;
    no-https)
        do_no_https
        ;;
    secret)
        do_secret
        ;;
    setup)
        do_setup
        ;;
    init-worktree)
        do_init_worktree
        ;;
    reset)
        do_reset
        ;;
    start)
        check_prereqs
        if [[ "$DEV_MODE" == true ]]; then
            mkdir -p "$SCRIPT_DIR/logs"
            do_start_dev
        else
            mkdir -p "$SCRIPT_DIR/logs"
            do_start_prod
        fi
        ;;
    stop)
        if [[ "$DEV_MODE" == true ]]; then
            do_stop_dev
        else
            do_stop_prod
        fi
        ;;
    restart)
        check_prereqs
        if [[ "$DEV_MODE" == true ]]; then
            do_stop_dev
            sleep 1
            mkdir -p "$SCRIPT_DIR/logs"
            do_start_dev
        else
            # JCLAW-190: do_stop_prod now waits for server.pid removal
            # before returning, so we don't need a separate sleep here.
            # The new JVM only boots once the old one has fully exited.
            do_stop_prod
            mkdir -p "$SCRIPT_DIR/logs"
            do_start_prod
        fi
        ;;
    status)
        do_status
        ;;
    logs)
        do_logs
        ;;
    upgrade)
        do_upgrade
        ;;
    completion)
        do_completion
        ;;
    shim)
        write_shim && echo "Linked jclaw → ${JCLAW_BIN_DIR:-$HOME/.local/bin}/jclaw"
        ;;
    uninstall)
        do_uninstall
        ;;
    loadtest)
        do_loadtest
        ;;
    scrapetest)
        do_scrapetest ${SCRAPETEST_ARGS[@]+"${SCRAPETEST_ARGS[@]}"}
        ;;
    evals)
        do_evals
        ;;
    test)
        check_prereqs
        do_test
        ;;
    e2e)
        do_e2e
        ;;
    dist)
        check_prereqs
        do_dist
        ;;
    bundle)
        check_prereqs
        do_bundle
        ;;
esac
