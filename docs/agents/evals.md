# Evals

```bash
./jclaw.sh evals                                       # validate the eval dataset
./jclaw.sh evals --responses run.json --out rep.json   # score a recorded agent run
./jclaw.sh evals --capture run.json --agent __evaltest__ --suite <id>   # drive a live agent
```

Agent-behaviour datasets live in `evals/suites/` (`<id>.json`);
`evals/README.md` is the format contract. Validating and scoring are offline —
no backend, no model call, no DB — and `play autotest` validates the dataset via
`EvalSuiteConformanceTest`, so a malformed suite fails the build.

Suites are measuring sticks, so edit them in place and let git hold the history:
every run records a content fingerprint (`tool-selection@6e7927aeefe4`), and
comparing two runs whose fingerprints differ says so before printing anything
else. The fingerprint covers case inputs and checks, not `rubric`/`description`,
so clarifying prose never invalidates a baseline. JCLAW-883 replaced the older
`<id>.v<N>.json` convention, which nothing enforced — an in-place edit under an
unchanged version was undetectable.

`--capture` is the exception: it drives real agent turns, so it needs the
backend running and it spends model calls. It POSTs to `/api/evals/capture`
behind the same loopback + `X-Loadtest-Auth` gate as the loadtest endpoints, and
`--agent` is required rather than defaulted. Capture turns leave no
conversation, no history and no memories behind, and are never recorded into the
Chat Performance histograms — see `evals/README.md` for why each of those holds.

What capture does **not** isolate is tool side effects: tools execute for real,
so a suite case that provokes a `task_manager` call creates an actual scheduled
task. Run sweeps against `__evaltest__` — the eval sibling of `__loadtest__`,
auto-provisioned on first capture, for which **every tool is opt-in**. Each suite
declares its own `requiredTools` and capture grants exactly those before the
sweep, revoking the rest, so a run is reproducible from the repo rather than from
whatever was last clicked in the agent editor. Only `__evaltest__` is calibrated
this way — an agent you configured yourself is never rewritten. Deleting the eval
agent cleans up everything a sweep created.

Related, and not eval-specific: per-agent tool config used to be schema-only —
it hid a tool from the model but did not stop it running, so a model that guessed
a real tool name bypassed the operator's configuration. JCLAW-883 added the
execution guard in `ToolRegistry.execute`/`executeRich` for native tools; MCP
tools already gated through `AgentSkillAllowedTool`.
