#!/usr/bin/env bash
# A candidate's upgrade rehearsal (JF-450): the last published release, installed from its own bundle and seeded with
# invented data, upgraded to the candidate's bundle by that release's own `jclaw.sh upgrade`, then read back. Usage,
# steps and exit codes: bin/README.md.
set -euo pipefail

if [[ $# -ne 1 || ! -f "$1" ]]; then
    echo "Usage: bin/upgrade-rehearsal.sh <the candidate's jclaw-bundle.zip>" >&2
    exit 2
fi
candidate_zip="$(cd "$(dirname "$1")" && pwd)/$(basename "$1")"
repo=${JCLAW_REHEARSAL_REPO:-tsukhani/jclaw}
work=$(mktemp -d "${TMPDIR:-/tmp}/jclaw-rehearsal.XXXXXX")
app="$work/jclaw"
jar="$work/cookies"
port=""
provider_pid=""
step="finding the last release"
started=$SECONDS

say() { echo "[rehearsal] $*"; }
fail() {
    echo "[rehearsal] FAILED while $step: $1" >&2
    [[ -n "${2:-}" && -f "$2" ]] && sed -n '1,$p' "$2" | tail -n 40 >&2
    exit 1
}
cleanup() {
    if [[ -n "$port" && -x "$app/jclaw.sh" ]]; then
        "$app/jclaw.sh" --backend-port "$port" stop > /dev/null 2>&1 || true
    fi
    if [[ -f "$app/server.pid" ]]; then
        kill -TERM "$(cat "$app/server.pid")" 2> /dev/null || true
    fi
    [[ -z "$provider_pid" ]] || kill -TERM "$provider_pid" 2> /dev/null || true
    rm -rf "$work"
}
trap cleanup EXIT

# The first port from $1 nothing answers on: a build host runs other services, and the test suite's own port.
free_port() {
    local p
    for p in $(seq "$1" 19499); do
        if ! (exec 3<> "/dev/tcp/127.0.0.1/$p") 2> /dev/null; then
            echo "$p"
            return 0
        fi
    done
    return 1
}

api() {
    local method=$1 path=$2
    shift 2
    curl -fsS --max-time 60 -X "$method" -c "$jar" -b "$jar" -H 'Content-Type: application/json' "$@" \
        "http://127.0.0.1:$port$path"
}

wait_ready() {
    local want=$1 status=""
    for _ in $(seq 1 120); do
        status=$(curl -fsS --max-time 5 "http://127.0.0.1:$port/api/status" 2> /dev/null) && break
        status=""
        sleep 2
    done
    jq -e --arg v "$want" '.status == "ok" and .applicationVersion == $v' <<< "${status:-null}" > /dev/null \
        || fail "/api/status did not report ok for $want within 240 s (it answered: ${status:-nothing})" "$app/logs/application.log"
}

# What the upgraded boot added to the log, which survives a rollback.
boot_log() {
    tail -n "+$((log_lines + 1))" "$app/logs/application.log" 2> /dev/null || true
}

# A failed schema change. Hibernate logs one and boots on regardless, so a boot that answers can still have lost it.
ddl_failure() {
    boot_log | grep -m 1 -E 'Error executing DDL|GenerationTarget encountered exception|SchemaManagementException' || true
}

# Why a boot that never answered failed: a failed schema change first, else its first error.
boot_failure() {
    local line
    line=$(ddl_failure)
    [[ -n "$line" ]] || line=$(boot_log | grep -m 1 -E ' ERROR ' || true)
    echo "${line:-nothing}"
}

login() {
    api POST /api/auth/login -d "$(jq -nc --arg p "$password" '{username: "admin", password: $p}')" > /dev/null \
        || fail "the admin login was refused"
}

# What the rehearsal seeded, as the API reads it back: a change in any of it is data the upgrade lost or altered.
snapshot() {
    local conversations
    conversations=$(api GET "/api/conversations?agentId=$agent_id&limit=200")
    {
        api GET /api/agents | jq -c '[.[] | select(.name == "rehearsal-agent") | {name, modelProvider, modelId}]'
        api GET /api/prompts | jq -c '[.[] | select(.title == "Rehearsal prompt") | {title, content, category}]'
        api GET /api/config/rehearsal.marker | jq -c '{key, value}'
        jq -r '.[].id' <<< "$conversations" | sort -n | while read -r id; do
            api GET "/api/conversations/$id/messages?limit=500" \
                | jq -c --arg id "$id" '{conversation: $id, messages: [.[] | {role, content}]}'
        done
    }
}

prev=$(gh release view --repo "$repo" --json tagName --jq .tagName) || fail "gh could not read the latest release of $repo"
candidate=$(unzip -p "$candidate_zip" jclaw/conf/application.conf | sed -n 's/^application\.version=//p')
[[ -n "$candidate" ]] || fail "$candidate_zip holds no jclaw/conf/application.conf with an application.version"
say "Upgrading $prev, as users install it, to the candidate's $candidate."

step="downloading $prev"
mkdir "$work/download"
gh release download "$prev" --repo "$repo" -p jclaw-bundle.zip -p SHA256SUMS -D "$work/download" > /dev/null \
    || fail "gh could not download $prev's jclaw-bundle.zip and SHA256SUMS"
(cd "$work/download" && grep ' jclaw-bundle.zip$' SHA256SUMS | shasum -a 256 -c - > /dev/null) \
    || fail "$prev's jclaw-bundle.zip does not match its published SHA256SUMS"
unzip -q "$work/download/jclaw-bundle.zip" -d "$work"

step="booting $prev"
port=$(free_port 19400) || fail "no free port between 19400 and 19499"
"$app/jclaw.sh" --backend-port "$port" start > "$work/start.log" 2>&1 || fail "jclaw.sh start exited non-zero" "$work/start.log"
wait_ready "${prev#v}"

step="seeding $prev"
password=$(head -c 48 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | head -c 32)
api POST /api/auth/setup -d "$(jq -nc --arg p "$password" '{password: $p}')" > /dev/null || fail "the admin password was refused"
login
# Chat turns need a provider, so an OpenAI-compatible stand-in answers each with invented text. The turns then run
# through the release's own chat code and store real conversations, which nothing sweeps away afterwards.
provider_port=$(free_port "$((port + 1))") || fail "no free port for the stand-in provider"
cat > "$work/provider.py" <<'PY'
import json
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

REPLY = "An invented reply for the upgrade rehearsal."
USAGE = {"prompt_tokens": 10, "completion_tokens": 8, "total_tokens": 18}


class Provider(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def send_json(self, payload):
        body = json.dumps(payload).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        self.send_json({"object": "list", "data": [{"id": "rehearsal-model", "object": "model"}]})

    def do_POST(self):
        length = int(self.headers.get("Content-Length") or 0)
        request = json.loads(self.rfile.read(length) or b"{}")
        if not request.get("stream"):
            message = {"role": "assistant", "content": REPLY}
            self.send_json({"id": "r", "object": "chat.completion", "model": "rehearsal-model", "usage": USAGE,
                            "choices": [{"index": 0, "message": message, "finish_reason": "stop"}]})
            return
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.end_headers()
        for choice, extra in (({"index": 0, "delta": {"role": "assistant", "content": REPLY}}, {}),
                              ({"index": 0, "delta": {}, "finish_reason": "stop"}, {"usage": USAGE})):
            chunk = {"id": "r", "object": "chat.completion.chunk", "model": "rehearsal-model", "choices": [choice]}
            self.wfile.write(b"data: " + json.dumps({**chunk, **extra}).encode() + b"\n\n")
        self.wfile.write(b"data: [DONE]\n\n")


ThreadingHTTPServer(("127.0.0.1", int(sys.argv[1])), Provider).serve_forever()
PY
python3 -I "$work/provider.py" "$provider_port" &
provider_pid=$!
api POST /api/config -d "$(jq -nc --arg u "http://127.0.0.1:$provider_port/v1" '{key: "provider.rehearsal.baseUrl", value: $u}')" \
    > /dev/null || fail "the provider's base URL was refused"
api POST /api/config -d '{"key":"provider.rehearsal.apiKey","value":"rehearsal"}' > /dev/null || fail "the provider's key was refused"
agent_id=$(api POST /api/agents -d '{"name":"rehearsal-agent","modelProvider":"rehearsal","modelId":"rehearsal-model"}' \
    | jq -er '.id') || fail "the agent was refused"
category=$(api GET /api/prompts/categories | jq -r '.[0].value')
api POST /api/prompts -d "$(jq -nc --arg c "$category" \
    '{title: "Rehearsal prompt", content: "Invented text for the upgrade rehearsal.", category: $c}')" > /dev/null \
    || fail "the prompt was refused"
api POST /api/config -d '{"key":"rehearsal.marker","value":"seeded before the upgrade"}' > /dev/null \
    || fail "the config key was refused"
# A send with no conversation joins the agent's live one, so /new opens each of the three, with its greeting.
for n in 1 2 3; do
    conversation=$(api POST /api/chat/send -d "$(jq -nc --argjson a "$agent_id" '{agentId: $a, message: "/new"}')" \
        | jq -er '.conversationId') || fail "/new opened no conversation for turn $n"
    for m in "Invented question $n for the rehearsal." "A follow-up to question $n."; do
        api POST /api/chat/send -d "$(jq -nc --argjson a "$agent_id" --argjson c "$conversation" --arg m "$m" \
            '{agentId: $a, conversationId: $c, message: $m}')" > /dev/null \
            || fail "a chat turn in conversation $conversation was refused" "$app/logs/application.log"
    done
done
before=$(snapshot)
counts=$(jq -sc '[.[] | select(type == "object" and has("messages")) | .messages | length]' <<< "$before")
[[ "$counts" == "[5,5,5]" ]] || fail "the three conversations stored $counts messages, not five each: $before"
say "Seeded $prev in $((SECONDS - started)) s: an agent, a prompt, a config key and three conversations of five messages."

step="upgrading $prev to $candidate"
log_lines=$(wc -l < "$app/logs/application.log")
upgrading=$SECONDS
if ! JCLAW_UPGRADE_ASSET_URL="file://$candidate_zip" "$app/jclaw.sh" --backend-port "$port" upgrade --version "v$candidate" \
    --yes > "$work/upgrade.log" 2>&1; then
    fail "jclaw.sh upgrade exited non-zero, and the failed boot logged: $(boot_failure)" "$work/upgrade.log"
fi
# The candidate may carry the release's own version, so the tree itself shows that the swap happened.
[[ "$(unzip -p "$candidate_zip" jclaw/jclaw.sh | shasum -a 256 | cut -d' ' -f1)" \
    == "$(shasum -a 256 < "$app/jclaw.sh" | cut -d' ' -f1)" ]] \
    || fail "the install still holds $prev's jclaw.sh, so the upgrade did not swap the tree" "$work/upgrade.log"
grep '^==> ' "$work/upgrade.log" | sed 's/^/[rehearsal]   /'

step="booting the upgraded $candidate"
wait_ready "$candidate"
# Hibernate logs a failed schema update and boots on regardless, so its log says what /api/status cannot.
migration=$(ddl_failure)
[[ -z "$migration" ]] || fail "the upgraded boot logged a failed schema change: $migration"

step="reading back what $prev stored"
login
after=$(snapshot)
if [[ "$after" != "$before" ]]; then
    diff <(echo "$before") <(echo "$after") > "$work/diff.txt" || true
    fail "the upgraded $candidate reads back the seeded data differently (before < > after)" "$work/diff.txt"
fi

# A file $prev shipped and the candidate does not goes with the upgrade, or, after one from a release that listed no
# files, with the candidate's first start (JF-522). Only an unchanged copy counts: one the install changed stays.
step="looking for what $prev shipped and $candidate does not"
entries() { unzip -Z1 "$1" | sed -n 's|^jclaw/||p' | grep -v '/$' | sort; }
entries "$candidate_zip" > "$work/candidate-files.txt"
left=$(entries "$work/download/jclaw-bundle.zip" \
    | grep -v -E '^(precompiled|lib|framework|modules|public/spa|workspace|data|logs|certs|tmp)/|^\.classpath$|^skills/[^/]+/credentials/' \
    | comm -23 - "$work/candidate-files.txt" \
    | while IFS= read -r rel; do
        [[ -f "$app/$rel" ]] || continue
        [[ "$(shasum -a 256 < "$app/$rel" | cut -d' ' -f1)" \
            == "$(unzip -p "$work/download/jclaw-bundle.zip" "jclaw/$rel" | shasum -a 256 | cut -d' ' -f1)" ]] && echo "$rel"
    done || true)
[[ -z "$left" ]] || fail "the upgraded install still holds, unchanged, files $prev shipped and $candidate does not: $(tr '\n' ' ' <<< "$left")"
say "$prev upgraded to $candidate in $((SECONDS - upgrading)) s and read back everything it stored unchanged; $((SECONDS - started)) s in all."
