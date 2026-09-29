#!/bin/bash
# Installs the factory as a macOS LaunchAgent (started at login, restarted when it exits), or removes it with --remove.
# The agent records this checkout's path and the current Node install, so re-run it after moving either.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
LABEL=com.jclaw.factory
PLIST="$HOME/Library/LaunchAgents/$LABEL.plist"
DOMAIN="gui/$(id -u)"
FACTORY_HOME="${FACTORY_HOME:-$HOME/.jclaw-factory}"

launchctl bootout "$DOMAIN/$LABEL" 2>/dev/null || true
if [[ "${1:-}" == "--remove" ]]; then
    rm -f "$PLIST"
    echo "removed $LABEL"
    exit 0
fi

NODE_DIR="$(dirname "$(command -v node)")"
DOCKER_DIR="$(dirname "$(command -v docker)")"
[[ -x "$HERE/node_modules/.bin/tsx" ]] || (cd "$HERE" && npm ci --no-audit --no-fund)
mkdir -p "$FACTORY_HOME/logs" "$(dirname "$PLIST")"
cat > "$PLIST" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key><string>$LABEL</string>
  <key>ProgramArguments</key>
  <array><string>$HERE/node_modules/.bin/tsx</string><string>main.ts</string></array>
  <key>WorkingDirectory</key><string>$HERE</string>
  <key>EnvironmentVariables</key>
  <dict>
    <key>PATH</key><string>$NODE_DIR:$DOCKER_DIR:/usr/bin:/bin:/usr/sbin:/sbin</string>
    <key>FACTORY_HOME</key><string>$FACTORY_HOME</string>
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
launchctl bootstrap "$DOMAIN" "$PLIST"
echo "installed $LABEL; log: $FACTORY_HOME/logs/factory.log"
