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

This creates the `harnessbench` user, installs pnpm for it, and makes a sparse checkout of this folder in `~harnessbench/jclaw`. Also needed on the host: Node.js 24.16 or newer, `git`, `curl`, `unzip` and `openssl`. JClaw's installer brings its own Java, and Hermes's setup its own Python.

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

A full run took 31 minutes on the abundent server (2026-11-24): about a minute to install JClaw and start the three harnesses, and 30 for the 390 turns. The first run on a host, and the first after a new OpenClaw or Hermes release, adds about 10 minutes: OpenClaw's build (4 minutes; a commit already built is reused) and Hermes's runtime (kept in `work/hermes-runtime`). A run stops everything it started and deletes its installs, keeping the results. Useful options (`run.sh --help` lists them all):

- `--smoke`: one run of the question and one-search tasks, to check the setup (2 minutes once the builds are reused); it is not added to the history.
- `--openclaw main`, `--hermes v0.23.3`, `--jclaw v1.0.30`: a version other than the latest release.
- `--keep`: keep the installs and state for inspection.

Update the benchmark itself with `git -C ~/jclaw pull` as `harnessbench`.

Exit codes: 0 for a run that passed conformance, 1 for one that failed it or failed to run, 3 for an unapproved plugin pin.

### Approving Hermes's plugin pin

Hermes's catalog pins the web-ollama-cloud plugin to one commit, and the run installs only a commit someone has reviewed: one listed in `approved-plugin-pins.txt` here, or in `~/harness-bench/approved-plugin-pins.txt`. When a new Hermes release moves the pin, the run stops with exit code 3 and prints a link comparing the last approved commit with the new one. Read the change. The plugin should call only Ollama's `/api/web_search` and `/api/web_fetch`. Then run again with `--approve-hermes-plugin <sha>`.

## Reading the results

Each run writes `~harnessbench/harness-bench/runs/<UTC time>/`:

| File | What it holds |
|---|---|
| `report.md` | the versions, the conformance table, the verdict, changes since earlier runs, and a table per task |
| `turns.jsonl` | one line per turn: the driver's timestamps, the answer and its grade, and the ground truth |
| `proxy/proxy-*.jsonl` | one line per request through the proxy |
| `analysis.json`, `conformance.json`, `versions.json` | the split of each turn, the checks, the exact versions |
| `logs/` | each step's output, the build and the harnesses' own logs |

The verdict follows JF-399: JClaw is comparable on a task when its median own time per turn is no more than 1.10 of the faster of the other two (`BENCH_BAR` changes the bar). The comparison with earlier runs uses `~/harness-bench/history.jsonl`, which holds runs that passed conformance on the same host with the same model. A median own or end-to-end time that moved by 20% or more, and out of the earlier run's 25th–75th percentile range, is reported as a marked change, beside the versions that changed in between.

## Settings

| Variable | Default | What it sets |
|---|---|---|
| `BENCH_HOME` | `~/harness-bench` | the key, the harness checkouts and Hermes's runtime (in `work/`, reused by every run), the runs and the history |
| `BENCH_MODEL` | `deepseek-v4.1-flash` | the model; set `BENCH_CONTEXT_WINDOW` and `BENCH_MAX_TOKENS` to match |
| `PORT_BASE` | `18100` | the proxy's status port; the three harness ports follow it |
| `JCLAW_PORT`, `OPENCLAW_PORT`, `HERMES_PORT` | `19100`, `18789`, `8642` | each harness's own port; JClaw's AOT training also uses `JCLAW_PORT` + 1000 |
| `BENCH_BAR` | `1.10` | the comparable bar |

## Tests

`node --test test/` runs the proxy's self-test (which `run.sh` also runs before every benchmark) and the tests of the analysis, the conformance checks and the change detection on recorded runs.
