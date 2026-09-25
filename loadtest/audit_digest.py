#!/usr/bin/env python3
"""Reduce a loadtest/audit.sh output directory to the numbers /loadtest-audit judges.

Usage: audit_digest.py <out-dir> <path-to-jfr-tool>

Arithmetic only — every verdict belongs to the reader of the digest.
"""
import csv
import json
import re
import statistics
import subprocess
import sys
from collections import Counter, defaultdict
from pathlib import Path

OUT = Path(sys.argv[1])
JFR = sys.argv[2]
PASSES = ("mock1", "mock2", "real")
SNAPSHOTS = ("baseline", "after-mock1", "after-mock2", "after-real")
BEFORE = {"mock1": "baseline", "mock2": "after-mock1", "real": "after-mock2"}
# Top-level packages under app/; com is the vendored Aspose shim, not JClaw code.
JCLAW = ("agents.", "channels.", "controllers.", "jobs.", "llm.", "mcp.", "memory.",
         "models.", "services.", "slash.", "tools.", "utils.")
JCLAW_SEGMENTS = {"queue_wait", "prologue", "prologue_parse", "prologue_conv", "prologue_prompt",
                  "prologue_tools", "dispatcher_wait", "persist", "terminal_tail"}


def read(name):
    path = OUT / name
    return path.read_text(errors="replace") if path.exists() else ""


def jclaw_frame(frame):
    return frame.split("/", 1)[-1].startswith(JCLAW)


def owner(cls):
    base = cls.lstrip("[")
    if base != cls:
        base = base.removeprefix("L")
    return "JClaw" if base.startswith(JCLAW) else "H2" if base.startswith("org.h2.") else "JDK/lib"


def mb(n, unit):
    return float(n) * {"K": 1 / 1024, "KB": 1 / 1024, "M": 1, "MB": 1, "G": 1024, "GB": 1024}[unit]


def seconds(iso):
    m = re.fullmatch(r"PT(?:(\d+)H)?(?:(\d+)M)?([\d.]+)S", iso or "")
    return int(m[1] or 0) * 3600 + int(m[2] or 0) * 60 + float(m[3]) if m else 0.0


def table(headers, rows):
    lines = ["| " + " | ".join(headers) + " |", "|" + "---|" * len(headers)]
    lines += ["| " + " | ".join(str(c) for c in r) + " |" for r in rows]
    return "\n".join(lines)


# ─── Harness results ───

def harness(tag):
    text = read(f"{tag}.out")
    i = text.find("\n{")
    data = json.JSONDecoder().raw_decode(text, i + 1)[0] if i >= 0 else None
    shape = re.search(r"concurrency=(\d+) turns=(\d+)", text)
    stub = re.search(r"ttft=(\d+)ms tokens/s=(\d+) response=(\d+) tokens", text)
    return data, (tuple(map(int, shape.groups())) if shape else None), \
        (tuple(map(int, stub.groups())) if stub else None)


def stub_budget(ttft, tps, tokens):
    """Mirrors LoadTestHarness.streamResponse: first frame at ttft, the rest evenly spaced."""
    total = 1000.0 * tokens / tps if tps > 0 else 20.0 * tokens
    frames = max(1, min(tokens, int(total)))
    return ttft, (frames - 1) * total / frames


def harness_section(results):
    out = ["## Harness results", ""]
    rows = []
    for tag in ("smoke",) + PASSES:
        data, shape, _ = results[tag]
        if not data:
            rows.append((tag, "no JSON report — read " + tag + ".out", "", "", "", "", ""))
            continue
        rows.append((tag, f"{shape[0]}x{shape[1]}" if shape else "?", data["totalRequests"],
                     data["errorCount"], round(data["wallClockMs"] / 1000, 1),
                     data["avgPerRequestMs"], data["avgTtftMs"]))
    out += [table(("pass", "c x t", "requests", "errors", "wall s", "mean req ms", "mean ttft ms"), rows), ""]

    for tag in PASSES:
        data, shape, stub = results[tag]
        if not data:
            continue
        segs = {s["segment"]: s for s in data.get("serverSegments") or [] if s["count"] > 0}
        out += [f"### {tag}: server segments, mean per request", ""]
        rows = []
        for name, s in segs.items():
            owner = "JClaw" if name in JCLAW_SEGMENTS else ("stub + JClaw" if stub else "provider")
            rows.append((name, owner, s["count"], f"{s['sumMs'] / s['count']:.2f}"))
        out += [table(("segment", "attributable to", "n", "mean ms"), rows), ""]
        if stub and shape:
            ttft, stream = stub_budget(*stub)
            t, b = segs.get("ttft"), segs.get("stream_body")
            ideal_wall = shape[1] * (ttft + stream)
            out.append(f"- Stub budget per request: ttft {ttft} ms + stream {stream:.0f} ms = {ttft + stream:.0f} ms.")
            if t:
                out.append(f"- ttft above stub: {t['sumMs'] / t['count'] - ttft:+.1f} ms/request.")
            if b:
                out.append(f"- stream_body above stub: {b['sumMs'] / b['count'] - stream:+.1f} ms/request.")
            out.append(f"- Mean request above stub: {data['avgPerRequestMs'] - ttft - stream:+.1f} ms.")
            out.append(f"- Wall clock vs ideal ({shape[1]} turns x {ttft + stream:.0f} ms): "
                       f"{data['wallClockMs'] / ideal_wall:.3f}x.")
        # Buckets count successful turns only; a position where every turn failed is empty.
        buckets = [b for b in data.get("turnBuckets") or [] if b["count"] > 0]
        if len(buckets) >= 3 and buckets[1]["turn"] == 2 and buckets[1]["durationMeanMs"] > 0:
            dur = [b["durationMeanMs"] for b in buckets]
            late = statistics.median(dur[-5:])
            out.append(f"- Per-turn duration mean: turn 1 {dur[0]} ms, turn 2 {dur[1]} ms, "
                       f"median of last 5 turns {late:.0f} ms ({late / dur[1]:.3f}x turn 2).")
            if not stub:
                ttfts = [b["ttftMeanMs"] for b in buckets]
                out.append(f"- Per-turn ttft mean (provider): turn 2 {ttfts[1]} ms, "
                           f"median of last 5 {statistics.median(ttfts[-5:]):.0f} ms.")
        if data.get("costUsd"):
            out.append(f"- Provider-reported cost: ${data['costUsd']:.4f}.")
        out.append("")
    return out


# ─── Settled snapshots ───

def snapshot(tag):
    s = {}
    # The histogram forces a full collection and counts only live objects. GC.heap_info's "used",
    # read seconds after GC.run, also holds whatever was allocated since, so it is not a live set.
    m = re.search(r"^Total\s+\d+\s+(\d+)", read(f"{tag}.histo.txt"), re.M)
    if m:
        s["heap_mb"] = int(m[1]) / 2 ** 20
    heap = read(f"{tag}.heap.txt")
    # ZGC: "capacity 1302M"; G1: "committed 262144K".
    m = re.search(r"(?:capacity|committed) (\d+)([KMG])", heap)
    if m:
        s["committed_mb"] = mb(m[1], m[2])
    meta = read(f"{tag}.metaspace.txt")
    m = re.search(r"Total Usage - (\d+) loaders, (\d+) classes", meta)
    if m:
        s["loaders"], s["classes"] = int(m[1]), int(m[2])
        both = next((l for l in meta[m.end():].splitlines() if l.strip().startswith("Both:")), "")
        u = re.search(r"([\d.]+) (KB|MB|GB) \([^)]*\) used", both)
        if u:
            s["metaspace_mb"] = mb(u[1], u[2])
    rss = read(f"{tag}.rss.txt").strip()
    if rss.isdigit():
        s["rss_mb"] = int(rss) // 1024
        if "committed_mb" in s:
            s["rss_outside_heap_mb"] = s["rss_mb"] - s["committed_mb"]

    dump = read(f"{tag}.threads.json")
    s["platform_families"], s["virtual_sites"] = Counter(), Counter()
    if dump:
        for container in json.loads(dump)["threadDump"]["threadContainers"]:
            for t in container.get("threads", []):
                if t.get("virtual"):
                    stack = t.get("stack") or []
                    site = next((f for f in stack if jclaw_frame(f)), stack[0] if stack else "(no stack)")
                    s["virtual_sites"][re.sub(r":\d+\)", ")", site)] += 1
                else:
                    s["platform_families"][re.sub(r"\d+", "N", t.get("name") or "")] += 1
    s["platform_threads"] = sum(s["platform_families"].values())
    s["virtual_threads"] = sum(s["virtual_sites"].values())

    lines = read(f"{tag}.lsof.txt").splitlines()[1:]
    s["fds"] = len(lines)
    s["fd_types"] = Counter(l.split()[4] for l in lines if len(l.split()) > 4)
    listening = {l.split()[-2].rsplit(":", 1)[-1] for l in lines if l.endswith("(LISTEN)")}
    s["tcp"] = Counter()
    for l in lines:
        m = re.search(r"(\S+)->(\S+) \((\w+)\)$", l)
        if m:
            local_port = m[1].rsplit(":", 1)[-1]
            key = f"{m[3]} inbound :{local_port}" if local_port in listening else f"{m[3]} -> {m[2]}"
            s["tcp"][key] += 1
    return s


def snapshot_section(snaps):
    out = ["## Settled state (two GC.run 5 s apart before each reading)", ""]
    fields = (("heap_mb", "live heap MB (histogram)"), ("committed_mb", "committed heap MB"),
              ("metaspace_mb", "metaspace MB"), ("classes", "classes"), ("loaders", "class loaders"),
              ("platform_threads", "platform threads"), ("virtual_threads", "virtual threads"),
              ("fds", "open fds"), ("rss_mb", "RSS MB"), ("rss_outside_heap_mb", "RSS outside heap MB"))
    rows = []
    for key, label in fields:
        vals = [snaps[t].get(key) for t in SNAPSHOTS]
        fmt = [f"{v:.0f}" if isinstance(v, float) else ("?" if v is None else v) for v in vals]
        d = [f"{b - a:+.0f}" if a is not None and b is not None else "?" for a, b in zip(vals, vals[1:])]
        rows.append((label, *fmt, *d))
    out += [table(("measure", *SNAPSHOTS, "Δ mock1", "Δ mock2", "Δ real"), rows), ""]

    for key, label in (("platform_families", "Platform thread families"),
                       ("virtual_sites", "Virtual threads by parking site (first JClaw frame)"),
                       ("tcp", "TCP connections"), ("fd_types", "fd types")):
        names = set().union(*(snaps[t][key] for t in SNAPSHOTS))
        changed = [n for n in names if len({snaps[t][key][n] for t in SNAPSHOTS}) > 1]
        out.append(f"### {label}: entries whose count changed")
        out.append("")
        if not changed:
            out += ["None.", ""]
            continue
        changed.sort(key=lambda n: -abs(snaps["after-real"][key][n] - snaps["baseline"][key][n]))
        out += [table(("entry", *SNAPSHOTS), [(n, *(snaps[t][key][n] for t in SNAPSHOTS))
                                             for n in changed[:20]]), ""]
    return out


# ─── Class histograms ───

HISTO = re.compile(r"^\s*\d+:\s+(\d+)\s+(\d+)\s+(\S+)")


def histogram(tag):
    h = defaultdict(lambda: [0, 0])
    for line in read(f"{tag}.histo.txt").splitlines():
        m = HISTO.match(line)
        if m:
            name = re.sub(r"/0x[0-9a-f]+", "/0x*", m[3])
            h[name][0] += int(m[1])
            h[name][1] += int(m[2])
    return h


def histogram_section(histos, requests):
    out = ["## Class histogram growth (settled, per pass)", ""]
    delta = {}
    for tag in PASSES:
        a, b = histos[BEFORE[tag]], histos[f"after-{tag}"]
        delta[tag] = {n: (b[n][0] - a[n][0], b[n][1] - a[n][1]) for n in set(a) | set(b)}
    for tag in PASSES:
        n_req = requests.get(tag) or 0
        growers = sorted(((n, d) for n, d in delta[tag].items() if d[1] > 0), key=lambda x: -x[1][1])[:15]
        out += [f"### {tag}: top growers by bytes ({n_req} requests)", ""]
        rows = [(n, owner(n), f"{d[0]:+d}", f"{d[1] / 1024:+.0f}", f"{d[0] / n_req:.3f}" if n_req else "?",
                 f"{delta['mock1'].get(n, (0, 0))[0]:+d}" if tag == "mock2" else "")
                for n, d in growers]
        out += [table(("class", "owner", "Δ instances", "Δ KB", "Δ inst / request", "Δ inst in mock1"), rows), ""]
    n1, n2 = requests.get("mock1") or 0, requests.get("mock2") or 0
    if n1 and n2:
        suspects = [(n, delta["mock1"].get(n, (0, 0)), d) for n, d in delta["mock2"].items()
                    if d[0] >= 0.05 * n2 and delta["mock1"].get(n, (0, 0))[0] >= 0.5 * d[0] * n1 / n2]
        suspects.sort(key=lambda x: (owner(x[0]) != "JClaw", -x[2][1]))
        out += ["### Grew on both identical mock passes (≥ 0.05 instances/request on mock2, "
                "and mock1 at least half that rate)", ""]
        out += [table(("class", "owner", "Δ inst mock1", "Δ inst mock2", "Δ KB mock2"),
                      [(n, owner(n), f"{d1[0]:+d}", f"{d2[0]:+d}", f"{d2[1] / 1024:+.0f}")
                       for n, d1, d2 in suspects[:20]])
                if suspects else "None.", ""]
    return out


# ─── Sampler ───

def sampler_section(max_heap_mb, soft_max_mb):
    rows = defaultdict(list)
    with open(OUT / "samples.csv", newline="") as f:
        for r in csv.DictReader(f):
            rows[r["phase"]].append(r)
    out = ["## Sampler (every 2 s)", "",
           f"MaxHeapSize {max_heap_mb:.0f} MB, SoftMaxHeapSize {soft_max_mb:.0f} MB.", ""]
    body = []
    for phase, rs in rows.items():
        def peak(col):
            vals = [int(r[col]) for r in rs if r[col].isdigit()]
            return max(vals) if vals else None
        heap = peak("heap_used_mb")
        body.append((phase, len(rs), sum(1 for r in rs if r["heap_used_mb"].isdigit()), heap,
                     f"{100 * heap / max_heap_mb:.0f}%" if heap and max_heap_mb else "?",
                     peak("rss_mb"), peak("os_threads"), peak("fds"),
                     peak("tcp_established"), peak("tcp_close_wait")))
    out += [table(("phase", "samples", "with heap", "peak heap MB", "of max", "peak RSS MB",
                   "peak OS threads", "peak fds", "peak ESTABLISHED", "peak CLOSE_WAIT"), body), ""]
    return out


# ─── JFR ───

def jfr_json(path, event):
    run = subprocess.run([JFR, "print", "--json", "--events", event, str(path)],
                         capture_output=True, text=True)
    return json.loads(run.stdout)["recording"]["events"] if run.returncode == 0 and run.stdout else []


def cpu_attribution(path):
    """Leaf-most JClaw frame per execution sample, from 64-frame stacks."""
    proc = subprocess.Popen([JFR, "print", "--events", "jdk.ExecutionSample", "--stack-depth", "64",
                             str(path)], stdout=subprocess.PIPE, text=True)
    total, attributed, in_stack, first = 0, Counter(), False, None
    for line in proc.stdout:
        s = line.strip()
        if s == "stackTrace = [":
            in_stack, first = True, None
            total += 1
        elif in_stack and s == "]":
            in_stack = False
            attributed[first or "(no JClaw frame within 64)"] += 1
        elif in_stack and first is None and s.startswith(JCLAW):
            first = s.split("(", 1)[0]
    proc.wait()
    return total, attributed


def jfr_section(tag):
    path = OUT / f"{tag}.jfr"
    out = [f"### {tag}", ""]
    if not path.exists():
        return out + ["No recording.", ""]
    summary = subprocess.run([JFR, "summary", str(path)], capture_output=True, text=True).stdout
    counts = {m[1]: int(m[2]) for m in re.finditer(r"^\s*(jdk\.\w+)\s+(\d+)\s+\d+", summary, re.M)}
    for event in ("jdk.ZAllocationStall", "jdk.VirtualThreadPinned", "jdk.JavaMonitorEnter",
                  "jdk.ExecutionSample", "jdk.ObjectAllocationSample"):
        out.append(f"- {event}: {counts.get(event, 'absent')}")

    gcs = jfr_json(path, "jdk.GarbageCollection")
    if gcs:
        longest = max(seconds(e["values"]["longestPause"]) for e in gcs)
        total = sum(seconds(e["values"]["sumOfPauses"]) for e in gcs)
        causes = Counter(f"{e['values']['name']} / {e['values']['cause']}" for e in gcs)
        out.append(f"- GC cycles: {len(gcs)}; longest pause {longest * 1e6:.0f} µs; "
                   f"total pause {total * 1e3:.2f} ms; causes: "
                   + ", ".join(f"{k} ×{v}" for k, v in causes.most_common()))

    monitors = jfr_json(path, "jdk.JavaMonitorEnter")
    if monitors:
        by_class = Counter()
        for e in monitors:
            name = (e["values"].get("monitorClass") or {}).get("name", "?").replace("/", ".")
            by_class[name] += seconds(e["values"]["duration"])
        out.append("- Monitor contention (≥ profile threshold), total ms by class: "
                   + ", ".join(f"{k} {v * 1e3:.0f}" for k, v in by_class.most_common(5)))

    loads = [e["values"] for e in jfr_json(path, "jdk.CPULoad")]
    if loads:
        jvm = [v["jvmUser"] + v["jvmSystem"] for v in loads]
        machine = [v["machineTotal"] for v in loads]
        out.append(f"- CPU: JVM mean {100 * statistics.mean(jvm):.0f}% / max {100 * max(jvm):.0f}%; "
                   f"machine mean {100 * statistics.mean(machine):.0f}% / max {100 * max(machine):.0f}% "
                   "(share of all cores)")

    total, attributed = cpu_attribution(path)
    out += ["", f"CPU samples by leaf-most JClaw frame ({total} samples):", ""]
    out += [table(("frame", "samples", "share"),
                  [(k, v, f"{100 * v / total:.1f}%") for k, v in attributed.most_common(15)]), ""]
    return out


# ─── Application log ───

def log_section():
    out = ["## Application log during each pass", ""]
    line_re = re.compile(r"^\S+ \S+ \[[^\]]*\] (\w+)\s+\S+ - (.*)$")
    for tag in PASSES:
        levels, messages = Counter(), Counter()
        for line in read(f"{tag}.app.log").splitlines():
            m = line_re.match(line)
            if m:
                levels[m[1]] += 1
                if m[1] in ("WARN", "ERROR"):
                    msg = re.sub(r"\d+", "#", re.sub(r"[0-9a-f]{8}-[0-9a-f-]{27}", "<uuid>", m[2]))
                    messages[f"{m[1]} {msg[:160]}"] += 1
            elif re.match(r"^[\w.$]+(Exception|Error)\b", line):
                messages["THROWN " + re.sub(r"\d+", "#", line)[:160]] += 1
        out.append(f"### {tag}: " + (", ".join(f"{k} {v}" for k, v in sorted(levels.items())) or "no lines"))
        out.append("")
        out += [table(("count", "message (digits → #)"), [(v, k.replace("|", "\\|")) for k, v in
                                                          messages.most_common(12)]) if messages else "No WARN/ERROR.", ""]
    return out


def main():
    env = read("env.txt")
    max_heap = int(re.search(r"MaxHeapSize=(\d+)", env)[1]) / 2 ** 20 if "MaxHeapSize=" in env else 0
    soft_max = re.search(r"SoftMaxHeapSize=(\d+)", env)
    results = {tag: harness(tag) for tag in ("smoke",) + PASSES}
    requests = {tag: (results[tag][0] or {}).get("totalRequests") for tag in PASSES}
    snaps = {t: snapshot(t) for t in SNAPSHOTS}
    histos = {t: histogram(t) for t in SNAPSHOTS}

    header = [l for l in env.splitlines() if not l.startswith("-") and not re.fullmatch(r"\d+:", l)]
    lines = ["# Loadtest audit digest", "", "```", *header, read("phases.txt").strip(), "```", ""]
    lines += harness_section(results)
    lines += snapshot_section(snaps)
    lines += histogram_section(histos, requests)
    lines += sampler_section(max_heap, int(soft_max[1]) / 2 ** 20 if soft_max else 0)
    lines += ["## JFR, per pass", ""]
    for tag in PASSES:
        lines += jfr_section(tag)
    lines += log_section()
    print("\n".join(lines))


main()
