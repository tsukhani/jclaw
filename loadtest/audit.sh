#!/usr/bin/env bash
# Evidence collector for /loadtest-audit: a real-provider smoke call, two identical
# mock passes and one real-provider pass through `jclaw.sh loadtest`, with the
# backend JVM sampled every 2 s, one JFR recording per pass, and a settled snapshot
# (heap, class histogram, metaspace, threads, fds) before the first pass and after
# each. Ends by writing digest.md via audit_digest.py. Never starts, stops or
# restarts the backend.
#
# Usage: loadtest/audit.sh <new-out-dir> [--provider P] [--model M] [--concurrency N] [--turns N]
#   --concurrency/--turns size the real pass (default 50 x 20); the mock passes are 100 x 50.
# Exit: 0 collected, 2 usage or no backend, 3 the smoke call failed, 4 the backend died.
set -euo pipefail

cd "$(dirname "$0")/.."

USAGE="usage: loadtest/audit.sh <new-out-dir> [--provider P] [--model M] [--concurrency N] [--turns N]"
[[ $# -ge 1 ]] || { echo "$USAGE" >&2; exit 2; }
OUT=$1
shift
PROVIDER=ollama-cloud
MODEL=nemotron-3-super
REAL_C=50
REAL_T=20
while [[ $# -gt 0 ]]; do
    case "$1" in
        --provider) PROVIDER=$2; shift 2 ;;
        --model) MODEL=$2; shift 2 ;;
        --concurrency) REAL_C=$2; shift 2 ;;
        --turns) REAL_T=$2; shift 2 ;;
        *) echo "$USAGE" >&2; exit 2 ;;
    esac
done
MOCK_C=100
MOCK_T=50
PROMPTS=loadtest/prompts.txt
PORT=9000

mkdir -p "$OUT"
OUT=$(cd "$OUT" && pwd)
# Thread.dump_to_file refuses to overwrite, and mixing two runs' files would corrupt the digest.
if [[ -n "$(ls -A "$OUT")" ]]; then
    echo "Output directory must be empty: $OUT" >&2
    exit 2
fi

PID=$(lsof -nP -iTCP:"$PORT" -sTCP:LISTEN -t 2>/dev/null | head -1 || true)
if [[ -z "$PID" ]]; then
    echo "No backend is listening on :$PORT. This script never starts one." >&2
    exit 2
fi
# Attach with the target's own JDK. A jenv shim costs ~10 s per jcmd, which turns a
# 2 s sampler into a once-a-minute one.
if [[ -r /proc/$PID/exe ]]; then
    JDK_BIN=$(dirname "$(readlink /proc/"$PID"/exe)")
else
    JDK_BIN=$(dirname "$(ps -o comm= -p "$PID")")
fi
JCMD=$JDK_BIN/jcmd
[[ -x "$JCMD" ]] || { echo "No jcmd beside the backend's java: $JDK_BIN" >&2; exit 2; }

SAMPLER=""
RECORDING=""
cleanup() {
    touch "$OUT/.stop"
    if [[ -n "$SAMPLER" ]]; then wait "$SAMPLER" 2>/dev/null || true; fi
    # An abandoned profile recording would keep writing inside the live JVM.
    if [[ -n "$RECORDING" ]]; then "$JCMD" "$PID" JFR.stop name="$RECORDING" > /dev/null 2>&1 || true; fi
}
trap cleanup EXIT

heap_mb() {
    # ZGC prints "ZHeap used 534M, ..."; G1 prints "... used 123456K [...".
    "$JCMD" "$PID" GC.heap_info 2>/dev/null | awk '
        { for (i = 1; i < NF; i++) if ($i == "used") {
            v = $(i + 1); gsub(/,/, "", v)
            u = substr(v, length(v)); n = substr(v, 1, length(v) - 1) + 0
            if (u == "K") n /= 1024; else if (u == "G") n *= 1024
            printf "%d", n; exit } }' || true
}

os_threads() {
    if [[ -d /proc/$PID/task ]]; then
        ls /proc/"$PID"/task | wc -l | tr -d ' '
    else
        ps -M -p "$PID" 2>/dev/null | tail -n +2 | wc -l | tr -d ' '
    fi
}

sample_loop() {
    echo "epoch,phase,heap_used_mb,rss_mb,os_threads,fds,tcp_established,tcp_close_wait"
    while [[ ! -f "$OUT/.stop" ]] && kill -0 "$PID" 2>/dev/null; do
        local rss fds
        rss=$(ps -o rss= -p "$PID" 2>/dev/null | tr -d ' ' || true)
        fds=$(lsof -nP -p "$PID" 2>/dev/null | awk 'NR > 1 { n++ } /\(ESTABLISHED\)/ { e++ } /\(CLOSE_WAIT\)/ { w++ }
            END { printf "%d,%d,%d", n, e, w }' || true)
        echo "$(date +%s),$(cat "$OUT/.phase"),$(heap_mb),$(( ${rss:-0} / 1024 )),$(os_threads),$fds"
        sleep 2
    done
}

# One GC.run does not reliably complete a ZGC cycle: two, 5 s apart, before reading the live set.
snapshot() {
    local tag=$1
    echo "settle-$tag" > "$OUT/.phase"
    "$JCMD" "$PID" GC.run > /dev/null
    sleep 5
    "$JCMD" "$PID" GC.run > /dev/null
    sleep 5
    "$JCMD" "$PID" GC.heap_info > "$OUT/$tag.heap.txt"
    "$JCMD" "$PID" GC.class_histogram > "$OUT/$tag.histo.txt"
    "$JCMD" "$PID" VM.metaspace > "$OUT/$tag.metaspace.txt"
    "$JCMD" "$PID" Thread.dump_to_file -format=json "$OUT/$tag.threads.json" > /dev/null
    lsof -nP -p "$PID" > "$OUT/$tag.lsof.txt" 2>/dev/null || true
    ps -o rss= -p "$PID" | tr -d ' ' > "$OUT/$tag.rss.txt"
}

alive() {
    kill -0 "$PID" 2>/dev/null || { echo "Backend pid $PID died during $1." >&2; exit 4; }
}

run_pass() {
    local tag=$1
    shift
    local log=logs/application.log
    local log_start rc=0 t0
    log_start=$(wc -l < "$log" 2>/dev/null | tr -d ' ' || true)
    echo "$tag" > "$OUT/.phase"
    "$JCMD" "$PID" JFR.start name="$tag" settings=profile maxsize=1g > /dev/null
    RECORDING=$tag
    t0=$(date +%s)
    ./jclaw.sh "$@" loadtest > "$OUT/$tag.out" 2>&1 || rc=$?
    echo "$tag start=$t0 end=$(date +%s) exit=$rc" >> "$OUT/phases.txt"
    alive "$tag"
    "$JCMD" "$PID" JFR.dump name="$tag" filename="$OUT/$tag.jfr" > /dev/null
    "$JCMD" "$PID" JFR.stop name="$tag" > /dev/null
    RECORDING=""
    if [[ "$(wc -l < "$log" | tr -d ' ')" -ge "${log_start:-0}" ]]; then
        tail -n +"$(( ${log_start:-0} + 1 ))" "$log" > "$OUT/$tag.app.log"
    else
        cp "$log" "$OUT/$tag.app.log"
        echo "$tag: application.log rotated mid-pass; $tag.app.log holds only the new file" >> "$OUT/phases.txt"
    fi
    snapshot "after-$tag"
}

{
    echo "pid=$PID jdk=$JDK_BIN"
    echo "version=$(sed -n 's/^application\.version=//p' conf/application.conf)"
    echo "head=$(git rev-parse --short HEAD 2>/dev/null || true)"
    echo "mock=${MOCK_C}x${MOCK_T} real=$PROVIDER/$MODEL ${REAL_C}x${REAL_T}"
    echo "cpus=$(sysctl -n hw.ncpu 2>/dev/null || nproc)"
    echo "load-before=$(uptime)"
    "$JCMD" "$PID" VM.flags
} > "$OUT/env.txt"

echo "==> smoke: one real call to $PROVIDER/$MODEL"
echo smoke > "$OUT/.phase"
if ! ./jclaw.sh --concurrency 1 --turns 1 --prompts "$PROMPTS" \
        --provider "$PROVIDER" --model "$MODEL" loadtest > "$OUT/smoke.out" 2>&1 \
        || ! grep -q '"errorCount": 0' "$OUT/smoke.out"; then
    echo "The smoke call to $PROVIDER/$MODEL failed; nothing else ran. See $OUT/smoke.out." >&2
    exit 3
fi

echo baseline > "$OUT/.phase"
sample_loop > "$OUT/samples.csv" &
SAMPLER=$!

echo "==> baseline snapshot"
snapshot baseline
echo "==> mock1 (${MOCK_C}x${MOCK_T})"
run_pass mock1 --concurrency "$MOCK_C" --turns "$MOCK_T" --prompts "$PROMPTS"
echo "==> mock2 (${MOCK_C}x${MOCK_T})"
run_pass mock2 --concurrency "$MOCK_C" --turns "$MOCK_T" --prompts "$PROMPTS"
echo "==> real ($PROVIDER/$MODEL ${REAL_C}x${REAL_T})"
run_pass real --concurrency "$REAL_C" --turns "$REAL_T" --prompts "$PROMPTS" \
    --provider "$PROVIDER" --model "$MODEL"

echo "load-after=$(uptime)" >> "$OUT/env.txt"
touch "$OUT/.stop"
wait "$SAMPLER" 2>/dev/null || true
SAMPLER=""

# The jfr tool is CPU-heavy, so the views are rendered only after every pass has finished.
echo "==> rendering JFR views"
for tag in mock1 mock2 real; do
    for view in gc-pauses pinned-threads contention-by-site hot-methods allocation-by-class memory-leaks-by-class; do
        "$JDK_BIN/jfr" view --width 200 "$view" "$OUT/$tag.jfr" > "$OUT/$tag.view-$view.txt" 2>&1 || true
    done
done

python3 loadtest/audit_digest.py "$OUT" "$JDK_BIN/jfr" > "$OUT/digest.md"
cat "$OUT/digest.md"
echo
echo "==> artifacts: $OUT"
