#!/bin/bash
# Installs the factory as a macOS LaunchAgent (started at login, restarted when it exits), or removes it with --remove.
# The agent records this checkout's path and the current Node install, so re-run it after moving either.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
LABEL=com.jclaw.factory
PLIST="$HOME/Library/LaunchAgents/$LABEL.plist"
DOMAIN="gui/$(id -u)"
FACTORY_HOME="${FACTORY_HOME:-$HOME/.jclaw-factory}"

if [[ "${1:-}" == "--remove" ]]; then
    launchctl bootout "$DOMAIN/$LABEL" 2>/dev/null || true
    rm -f "$PLIST"
    echo "removed $LABEL"
    exit 0
fi

fail() { echo "install-agent: $*" >&2; exit 1; }
[[ "$(uname)" == Darwin ]] || fail "LaunchAgents are macOS only"
command -v node >/dev/null || fail "node is not on PATH (Node 24 or newer)"
node -e 'process.exit(Number(process.versions.node.split(".")[0]) >= 24 ? 0 : 1)' || fail "Node 24 or newer is required, found $(node --version)"
command -v docker >/dev/null || fail "docker is not on PATH: install Docker Desktop"
docker info >/dev/null 2>&1 || fail "Docker is not running: start Docker Desktop"
mkdir -p "$FACTORY_HOME/logs" "$(dirname "$PLIST")"
chmod 700 "$FACTORY_HOME"
[[ -f "$FACTORY_HOME/.env" ]] || fail "missing $FACTORY_HOME/.env: the model credential, CLAUDE_CODE_OAUTH_TOKEN=… (from \`claude setup-token\`) or ANTHROPIC_API_KEY=…"
[[ -f "$FACTORY_HOME/jira.env" ]] || fail "missing $FACTORY_HOME/jira.env: JIRA_URL=… and JIRA_PERSONAL_TOKEN=… (a Jira personal access token)"
chmod 600 "$FACTORY_HOME/.env" "$FACTORY_HOME"/jira.env 2>/dev/null || true
if ! docker image inspect jclaw-devcontainer:local >/dev/null 2>&1; then
    echo "building the sandbox image jclaw-devcontainer:local (several minutes, once)"
    docker build -f "$HERE/../.devcontainer/Dockerfile" -t jclaw-devcontainer:local "$HERE/.."
fi

NODE_DIR="$(dirname "$(command -v node)")"
DOCKER_DIR="$(dirname "$(command -v docker)")"
"$HERE/run.sh" --install-only
cat > "$PLIST" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key><string>$LABEL</string>
  <key>ProgramArguments</key>
  <array><string>$HERE/run.sh</string></array>
  <key>WorkingDirectory</key><string>$HERE</string>
  <key>EnvironmentVariables</key>
  <dict>
    <key>PATH</key><string>$NODE_DIR:$DOCKER_DIR:/usr/bin:/bin:/usr/sbin:/sbin</string>
    <key>FACTORY_HOME</key><string>$FACTORY_HOME</string>
    <key>FACTORY_SUPERVISED</key><string>launchd</string>
  </dict>
  <key>RunAtLoad</key><true/>
  <key>KeepAlive</key><true/>
  <key>ThrottleInterval</key><integer>60</integer>
  <key>StandardOutPath</key><string>$FACTORY_HOME/logs/factory.log</string>
  <key>StandardErrorPath</key><string>$FACTORY_HOME/logs/factory.log</string>
</dict>
</plist>
PLIST
plutil -lint "$PLIST" >/dev/null
# Replacing a running agent interrupts its stories; the new one resumes them. bootout returns before launchd has let go
# of the job, and bootstrapping it meanwhile fails with "5: Input/output error".
launchctl bootout "$DOMAIN/$LABEL" 2>/dev/null || true
for _ in $(seq 1 30); do
    launchctl print "$DOMAIN/$LABEL" >/dev/null 2>&1 || break
    sleep 1
done
launchctl bootstrap "$DOMAIN" "$PLIST"
echo "installed $LABEL; log: $FACTORY_HOME/logs/factory.log"
