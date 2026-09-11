#!/bin/bash
# Synology FileStation API helper — no MCP server required.
# Config is read from ../credentials/nas.env (relative to this script),
# with environment variables taking precedence for overrides.
#
# Usage:
#   nas.sh upload <local-file> <nas-dest-dir> [nas-filename]
#   nas.sh list <nas-path>
#   nas.sh shares
#   nas.sh mkdir <nas-path>
#   nas.sh delete <nas-path>
#   nas.sh size <nas-path>          (stat a single file)
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ENV_FILE="${NAS_ENV_FILE:-$SCRIPT_DIR/../credentials/nas.env}"
if [ -f "$ENV_FILE" ]; then
  # shellcheck disable=SC1090
  source "$ENV_FILE"
fi

: "${NAS_URL:?NAS_URL not set — configure credentials/nas.env (see nas.env.example)}"
: "${NAS_USER:?NAS_USER not set — configure credentials/nas.env (see nas.env.example)}"
: "${NAS_PASS:?NAS_PASS not set (URL-encoded) — configure credentials/nas.env (see nas.env.example)}"
PROXY_USER="${PROXY_USER:-}"
PROXY_PASS="${PROXY_PASS:-}"

login() {
  curl -sk ${PROXY_USER:+-u "$PROXY_USER:$PROXY_PASS"} \
    "$NAS_URL/webapi/auth.cgi?api=SYNO.API.Auth&version=7&method=login&account=$NAS_USER&passwd=$NAS_PASS&session=FileStation&format=sid" \
    | node -e "let d='';process.stdin.on('data',c=>d+=c).on('end',()=>{const j=JSON.parse(d);if(!j.success){console.error('Login failed:',d);process.exit(1)}console.log(j.data.sid)})"
}

SID=$(login)
AUTH=(-b "id=$SID")
[ -n "$PROXY_USER" ] && AUTH=(-u "$PROXY_USER:$PROXY_PASS" -b "id=$SID")

case "${1:-}" in
  upload)
    LOCAL="$2"; DEST="$3"; FNAME="${4:-$(basename "$2")}"
    curl -sk "${AUTH[@]}" "$NAS_URL/webapi/entry.cgi?api=SYNO.FileStation.Upload&version=2&method=upload&_sid=$SID" \
      -F "path=$DEST" -F "overwrite=true" -F "create_parents=true" \
      -F "file=@$LOCAL;filename=$FNAME"
    echo
    ;;
  list)
    curl -sk "${AUTH[@]}" -G "$NAS_URL/webapi/entry.cgi" \
      --data-urlencode "api=SYNO.FileStation.List" --data-urlencode "version=2" \
      --data-urlencode "method=list" --data-urlencode "folder_path=$2" \
      --data-urlencode "additional=[\"size\",\"time\"]"
    echo
    ;;
  shares)
    curl -sk "${AUTH[@]}" "$NAS_URL/webapi/entry.cgi?api=SYNO.FileStation.List&version=2&method=list_share&_sid=$SID"
    echo
    ;;
  mkdir)
    curl -sk "${AUTH[@]}" -X POST "$NAS_URL/webapi/entry.cgi" \
      -F "api=SYNO.FileStation.CreateFolder" -F "version=2" -F "method=create" \
      -F "_sid=$SID" -F "folder_path=$(dirname "$2")" -F "name=$(basename "$2")"
    echo
    ;;
  delete)
    curl -sk "${AUTH[@]}" "$NAS_URL/webapi/entry.cgi?api=SYNO.FileStation.Delete&version=2&method=start&path=$2&recursive=true&_sid=$SID"
    echo
    ;;
  size)
    curl -sk "${AUTH[@]}" -G "$NAS_URL/webapi/entry.cgi" \
      --data-urlencode "api=SYNO.FileStation.List" --data-urlencode "version=2" \
      --data-urlencode "method=getinfo" --data-urlencode "path=[\"$2\"]" \
      --data-urlencode "additional=[\"size\",\"time\"]"
    echo
    ;;
  *)
    echo "Usage: nas.sh {upload|list|shares|mkdir|delete|size} ..." >&2
    exit 1
    ;;
esac
