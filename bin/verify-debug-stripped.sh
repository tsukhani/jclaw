#!/usr/bin/env bash
# Assert that a packaged zip's precompiled app classes carry NO LocalVariableTable
# attribute — i.e. the ProGuard debug-strip (build.gradle.kts stripPrecompiledDebugInfo,
# -Pjclaw.stripDebugInfo=true) actually ran before the zip was built. A future change to
# the ProGuard config, the task wiring, or the Package stage that silently disabled the
# strip would ship decompilable classes with a green build; this catches that.
#
# Uses javap, not a grep over the class bytes: the attribute name lives in the constant
# pool as modified-UTF8, and ugrep/grep skip binary files by default, so a byte-grep
# returns a false "clean". javap decodes the attributes and is authoritative.
#
# Usage: verify-debug-stripped.sh <zip>
set -euo pipefail

ZIP="${1:?usage: verify-debug-stripped.sh <zip>}"
[ -f "$ZIP" ] || { echo "verify-debug-stripped: no such zip: $ZIP" >&2; exit 2; }
command -v javap >/dev/null || { echo "verify-debug-stripped: javap not on PATH" >&2; exit 2; }

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

# Extract only the precompiled Java classes (a few MB), not the whole bundle.
unzip -q "$ZIP" '*/precompiled/java/*' -d "$TMP" || true
JDIR="$(find "$TMP" -type d -path '*/precompiled/java' -print -quit)"
[ -n "$JDIR" ] || { echo "verify-debug-stripped: no precompiled/java in $ZIP" >&2; exit 2; }

COUNT="$(find "$JDIR" -name '*.class' | wc -l | tr -d ' ')"
# Positive control: a real precompile of app/ is ~1500+ classes. A tiny count means the
# extract or the layout changed, and a scan over almost nothing would pass vacuously.
if [ "$COUNT" -lt 1000 ]; then
    echo "verify-debug-stripped: only $COUNT classes found under $JDIR — refusing to trust the scan" >&2
    exit 2
fi

# One streaming javap pass, counting three attributes at once.
read -r LVT LNT MP < <(
    find "$JDIR" -name '*.class' -print0 | xargs -0 javap -v 2>/dev/null | awk '
        /LocalVariableTable/ { lvt++ }
        /LineNumberTable/    { lnt++ }
        /MethodParameters/   { mp++ }
        END { print lvt+0, lnt+0, mp+0 }'
)

# Positive control: LineNumberTable + MethodParameters must still be present. If javap had
# silently produced nothing (wrong path, unreadable classes), all three would be 0 and the
# LocalVariableTable==0 assertion would pass for the wrong reason.
if [ "$LNT" -eq 0 ] || [ "$MP" -eq 0 ]; then
    echo "verify-debug-stripped: scan produced no LineNumberTable/MethodParameters ($COUNT classes, LNT=$LNT MP=$MP) — javap likely read nothing" >&2
    exit 2
fi

if [ "$LVT" -ne 0 ]; then
    echo "verify-debug-stripped: FAIL — $LVT LocalVariableTable attributes remain across $COUNT classes in $(basename "$ZIP")." >&2
    echo "The debug-strip did not run. Check -Pjclaw.stripDebugInfo=true and the stripPrecompiledDebugInfo task." >&2
    exit 1
fi

echo "verify-debug-stripped: OK — $(basename "$ZIP"): $COUNT classes, 0 LocalVariableTable, LineNumberTable+MethodParameters intact."
