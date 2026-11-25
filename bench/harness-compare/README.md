# Harness benchmark: JClaw against OpenClaw and Hermes

This folder measures how much time JClaw, OpenClaw and Hermes each add to an agent turn on the same model, the same tasks and the same tool. One command installs the latest stable release of all three, runs the tasks, checks the run was like for like, and writes a report that compares it with earlier runs. It began as JF-399's one-off measurement; JF-599 made it repeatable.

The folder is self-contained: nothing in it refers to a file outside it, so `git subtree split --prefix=bench/harness-compare` turns it into a repository of its own.

## What it measures

All three harnesses reach Ollama Cloud, for the model and for web search, through one local timing proxy, each on its own port. For every request the proxy records when it arrived, when the first and last bytes came back, its size and the token counts. It never records a header, a prompt or an answer, so the key and the conversation never reach its log. The driver times each turn on the same clock.

Each turn is split into:

- **Own time:** the harness's own time. This is the wait before its first model call, the gaps between model rounds (less the web searches in them), and the time after the last model byte. The verdict rests on this, because it leaves out the model's thinking and decoding.
- **Provider time:** each model request to its last byte.
- **End to end:** sending the turn to its last byte. It is reported but not judged: where two harnesses' 25th–75th percentile ranges overlap, the runs show no measurable difference.

The eight tasks, each run 10 times by default:

- a one-line question;
- a follow-up in the same conversation;
- a summary of a 3,009-word passage;
- a multi-step reasoning problem;
- a reply in a fixed JSON shape;
- a five-turn conversation;
- one web search;
- three independent web searches.

**Memory** is reported beside the times but never judged. The driver samples each harness's whole process tree: JClaw's JVM with the sidecars it starts, and everything under the process the run started for OpenClaw and for Hermes. It samples once after the warm-up turns, before every turn and once at the end, always when no request is in flight. Reading a process's memory map holds its lock for a moment, so a sample never lands inside a timed turn. A peak that rises and falls within one turn is therefore not seen. On Linux it reads PSS, which splits memory shared between processes among them, so a library two processes share counts once in the tree. Elsewhere only RSS is available. Each harness is measured as installed: JClaw's JVM starts with a 128 MB heap that can grow to 2 GB.

**The host's load** is sampled with memory and is reported, never judged: the 1-minute load average and, on Linux, the CPU that processes outside the three harnesses' trees used between samples, by user, from the tick counts in `/proc`. A Jenkins build on the same host shows as user jenkins. It says whether a slower run had company.

Every answer is graded mechanically. The web answers are checked against kernel.org's, python.org's and nodejs.org's own release feeds, read when the run starts. Run *k* of a task goes through all three harnesses before run *k*+1, and the order rotates each run. The driver waits until no request has been in flight for 3 s before each turn, so one harness's background calls never land in another's turn.

## Like for like, checked on every run

| Setting | JClaw | OpenClaw | Hermes |
|---|---|---|---|
| Model | `deepseek-v4.1-flash` on Ollama Cloud, no fallback model, no routing | the same | the same |
| Thinking | off: the bench agent has no thinking mode | `thinkingDefault: "off"` | `agent.reasoning_effort: none` (the proxy adds it where Hermes leaves it out, as it does for any address other than ollama.com) |
| Tools | `web_search` only | `web_search` only; Tool Search and Code Mode off | the `search` toolset only, through the community plugin web-ollama-cloud |
| Search-result cache | none | none for Ollama search | off (`web.cache_enabled: false`) |
| Prompt content | its defaults | its defaults; the first-run onboarding file is set aside after a seed turn | its defaults |
| Extra model calls | memory auto-capture off (it makes one call after every turn; JF-399's A/B found it slows no turn) | none | title generation, at its default (on) |

An update to any harness can rename one of these settings, and the harness then quietly falls back to its default. So every run is checked against what the proxy actually saw, and **a run that fails any check gets no verdict and is not added to the history.** The checks:

| Check | It catches |
|---|---|
| model | a request naming any other model, background calls included |
| thinking setting | a round that does not ask for thinking off |
| no reasoning returned | a response that streamed reasoning text anyway |
| tools offered | a round offering anything but `web_search` |
| tools called | a call to any other tool |
| searches reach the proxy | a web task answered with no search at the proxy: a cache, or a call that bypassed it |
| no searches on tool-free tasks | a harness that searches by itself: more than 25% of its tool-free turns made a search. A model that now and then chooses the offered search on one passes, and the report names each such turn |
| complete, errors, correctness | missing runs, more than 5% failed turns, or under 80% right answers, which is what a broken setup such as onboarding coming back looks like |

## Host setup, once

The benchmark runs third-party code (OpenClaw, Hermes, the community plugin and their dependencies) at whatever version is latest. So it runs as its own unprivileged user. On the abundent server, as `tsukhani`:

```bash
curl -fsSL https://raw.githubusercontent.com/tsukhani/jclaw/main/bench/harness-compare/setup-host.sh | bash
```

This creates the `harnessbench` user and installs for it alone Node.js 26, the version JClaw's CI uses, and pnpm. Node.js comes from nodejs.org, checked against its published checksums, into `~harnessbench/.local/node`. The system's own Node.js stays as it is for the host's other services. The script then makes a sparse checkout of this folder in `~harnessbench/jclaw`. Run it again to move to the newest Node.js 26 release. Also needed on the host: `git`, `curl`, `unzip`, `xz` and `openssl`. JClaw's installer brings its own Java, and Hermes's setup its own Python.

### The key

The Ollama Cloud key lives in `~harnessbench/harness-bench/ollama.env`, as one line `OLLAMA_API_KEY=…`, readable only by `harnessbench`. To copy it from a file on your machine without it appearing anywhere:

```bash
ssh tsukhani@abundent.com 'sudo -u harnessbench sh -c "umask 077; cat > /home/harnessbench/harness-bench/ollama.env"' < ollama.env
```

The run passes the key to each harness in its environment and to JClaw through its API. It never puts it on a command line or in a log.

## Running

```bash
ssh tsukhani@abundent.com sudo -iu harnessbench jclaw/bench/harness-compare/run.sh
```

A full run took 31 minutes on the abundent server (2026-11-24): about a minute to install JClaw and start the three harnesses, and 30 for the 390 turns. The first run on a host, and the first after a new OpenClaw or Hermes release, adds about 10 minutes: OpenClaw's build (4 minutes; a commit already built on the same Node.js is reused) and Hermes's runtime (kept in `work/hermes-runtime`). A new Node.js rebuilds OpenClaw from empty `node_modules`. A run stops everything it started and deletes its installs, keeping the results. Useful options (`run.sh --help` lists them all):

- `--smoke`: one run of the question and one-search tasks, to check the setup (2 minutes once the builds are reused); it is not added to the history.
- `--openclaw main`, `--hermes v0.23.3`, `--jclaw v1.0.30`: a version other than the latest release.
- `--keep`: keep the installs and state for inspection.

Update the benchmark itself with `git -C ~/jclaw pull` as `harnessbench`.

Exit codes: 0 for a run that passed conformance, 1 for one that failed it or failed to run, 2 for a malformed plugin pin, 3 for an unapproved one.

### Approving Hermes's plugin pin

Hermes's catalog pins the web-ollama-cloud plugin to one commit of one repository. The run installs it only when that exact pair has been reviewed, meaning it is listed in `approved-plugin-pins.txt` here or in `~/harness-bench/approved-plugin-pins.txt`. A catalog entry without a full 40-character commit stops the run with exit code 2. When a new Hermes release moves the pin, the run stops with exit code 3 and prints a link comparing the last approved commit with the new one. Read the change: the plugin should call only Ollama's `/api/web_search` and `/api/web_fetch`. Then run again with `--approve-hermes-plugin <commit>`, which records the pair only if the catalog pins that commit. A new repository has no earlier commit to compare with, so review it in full.

## Reading the results

Each run writes `~harnessbench/harness-bench/runs/<UTC time>/`:

| File | What it holds |
|---|---|
| `report.md` | the versions, the conformance table, the verdict, changes since earlier runs, a table per task, each harness's memory and the host's load |
| `turns.jsonl` | one line per turn: the driver's timestamps, the answer and its grade, and the ground truth; and one per memory sample, each harness's tree added up by process name, with the host's load and CPU ticks |
| `proxy/proxy-*.jsonl` | one line per request through the proxy |
| `analysis.json`, `conformance.json`, `versions.json` | the split of each turn, the checks, the exact versions |
| `logs/` | each step's output, the build and the harnesses' own logs |

The verdict follows JF-399: JClaw is comparable on a task when its median own time per turn is no more than 1.10 of the faster of the other two (`BENCH_BAR` changes the bar). The comparison with earlier runs uses `~/harness-bench/history.jsonl`, which holds every run that passed conformance. A run is compared only with runs on the same host, with the same model and on the same Node.js major version. Node.js runs OpenClaw and the proxy and driver that time all three harnesses, so a new major version starts a new baseline. A median own or end-to-end time that moved by 20% or more, with the two runs' 25th–75th percentile ranges apart, is reported as a marked change, beside the versions that changed in between, Node.js's included. Requiring only the median to leave the earlier range marked 5 of 48 medians between two runs of identical releases. Requiring the ranges to part marked 3 of 480 over the ten pairs of five such runs. Two of those were Hermes's three-search task, whose time between rounds has two modes about 210 ms apart. Three more runs of the same releases in the daytime, compared with each other and with the five, marked 22 of 864. Thirteen were end-to-end times that moved by 176 to 1,116 ms, of which the harness's own time made up at most 22 ms, so the move was outside the harness. The other nine were Hermes's own time: six its three-search task, all three daytime runs having fallen in the lower mode, and three its follow-up task. A report on unchanged releases can still mark a median or two, and an end-to-end change whose own time stayed put says nothing about the harness. A harness's median or peak memory that moved by 20% or more is a marked change too. Memory has no spread across runs to step out of, so the 20% alone decides; across eight identical full runs it moved by 15% at most (JClaw's median). A flagged JClaw change also shows its JVM heap in use and held, then and now. Its process memory follows the heap the JVM holds, which differed by 17% between two smoke runs of the same release. The heap in use is what says whether JClaw needed more.

Each harness's own time is also checked across its tasks. The change is the median of its tasks' changes in median own time. It is worth a look when it is 10% or more either way and a Mann–Whitney test over every timed turn of the two runs, about 120 each, gives p below 0.01. Ten turns a task are too few to catch a uniform 20% slowdown of JClaw's 13 ms own time. Scaled onto five identical runs, such a slowdown was caught by the per-task rule in 4 of 20 pairs and by the check across tasks in all 20. Between the identical runs themselves the check marked nothing: their change across tasks was at most 6.3%. The 10% line was set on those five runs, which ran back to back on one quiet night. Three more in the daytime, with 0.4 to 1.4 cores busy outside the harnesses at the median, were not used to set it: compared with each other and with the five, the check marked none of 54, its change was at most 6.8% and every p was above 0.09. Web search failed upstream in 2 of the first daytime run's 10 rounds, and its 12 search turns from those rounds are timed like any other, since a turn leaves the timing only when it errored or has no timing. With them left out the check still marked none, though OpenClaw's p fell to 0.031 on a 3.2% change, and the per-task rule marked 24 of 864. No Jenkins build overlapped any of the eight, so how a build's load moves the check is still unmeasured, and a flag is a first sign to read beside the host's load then and now, not proof. A slowdown confined to a few tasks barely moves the median of the changes, so only the per-task rule can see it, and ten turns a task limit what that rule sees. Scaled onto the same five runs, a 33% slowdown of JClaw's own time in its two search tasks alone was caught in 15 of 20 pairs, in its three-search task alone in 6, and in its question, follow-up and summary tasks together in 3; a 50% slowdown of the one task in 17, and a doubling every time. OpenClaw's tighter times were caught at 33% every time. A report with nothing marked rules out a broad slowdown, not a modest one in a few tasks. The history keeps each run's own time per turn for this check, and a run whose line has none is left out of it.

## Settings

| Variable | Default | What it sets |
|---|---|---|
| `BENCH_HOME` | `~/harness-bench` | the key, the harness checkouts and Hermes's runtime (in `work/`, reused by every run), the runs and the history |
| `BENCH_MODEL` | `deepseek-v4.1-flash` | the model; set `BENCH_CONTEXT_WINDOW` and `BENCH_MAX_TOKENS` to match |
| `PORT_BASE` | `18100` | the proxy's status port; the three harness ports follow it |
| `JCLAW_PORT`, `OPENCLAW_PORT`, `HERMES_PORT` | `19100`, `18789`, `8642` | each harness's own port; JClaw's AOT training also uses `JCLAW_PORT` + 1000 |
| `BENCH_BAR` | `1.10` | the comparable bar |
| `BENCH_NODE_DIR` | `~/.local/node` | the Node.js the run puts first on its PATH; it must be version 26 or newer |

## Tests

`node --test`, run in this folder, runs the proxy's self-test (which `run.sh` also runs before every benchmark) and the tests of the analysis, the conformance checks and the change detection on recorded runs. It also tests the memory and host sampler on known `/proc` text and on the test's own process tree, and the check across tasks, its rank test against the p-values scipy gives.
