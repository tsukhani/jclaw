---
description: Run the Playwright UAT suite against a live JClaw instance and triage every failure into stale-spec vs real regression. Fixes stale specs after approval; files regressions rather than papering over them.
argument-hint: "[spec names to scope, optional — e.g. settings prompts channels]"
---

# E2E UAT audit

Run `frontend/tests/e2e` against a running JClaw instance and produce a **triaged** result: for each failure, whether the *spec* is stale or the *app* regressed. The suite is excluded from Jenkins CI by design (`playwright.config.ts` — it needs a live server and a real admin credential), so it is the only layer that catches spec-vs-app drift, and nothing else will surface it.

The triage is the work. A raw pass/fail count is not the deliverable.

## Preconditions — check before running, and stop rather than reporting a false red

1. **The app must be up.** `./jclaw.sh status`. If the backend is stopped, say so and stop — do NOT start it. A down instance fails `global-setup`'s login and every spec fails identically, which looks exactly like catastrophic drift.
2. **Know which instance you are pointed at, and say so.** The suite is safe against a live instance as of JCLAW-1140, but do not treat that as permanent — check rather than assume. Measured 2026-08-31: two specs create persistent rows (`agents`, `prompts`) and both delete them in `afterAll`; `skills-tools` flips one tool grant and restores it in a `finally`; every other spec either creates nothing or POSTs at a negative case. Verified after a full run — no leftover rows, no changed grant.

   What to re-check before running against something you care about: whether a *new* spec creates data without cleanup, and whether anything now exercises the config or restart paths that `settings.uat.spec.ts` and `api-contract.uat.spec.ts` only read today. Confirm with the user if the target holds data that would be expensive to lose.
3. **Use the wrapper.** `./jclaw.sh e2e` handles the three things that otherwise fail the suite before any spec runs: the credential from `certs/.env`, base-URL detection (`:3000` in dev, else `:9000`), and Playwright's per-version browser build. Do not invoke `playwright test` directly.

## Run

```bash
./jclaw.sh status          # confirm backend running; stop here if not
./jclaw.sh e2e             # ~55s against a live instance
```

Scope to specific specs when the user named some: `cd frontend && pnpm exec playwright test tests/e2e/<name>.uat.spec.ts`.

Capture the full output. **Do not pipe the run through `tail`** — that returns tail's exit code and hides the result.

## Triage — one verdict per failure, with evidence

For each failure, decide between exactly two verdicts and say which:

**Stale spec** — the app changed deliberately and the spec was not updated. Prove it: find the commit that changed the app (`git log -S'<the thing the spec expects>' -- <source path>`) and show that the spec predates it (`git log -1 -- <spec path>`). Name both commits. A hardcoded list that has drifted from its source of truth is the common shape.

**Real regression** — the app is wrong. Prove it by exercising the underlying endpoint directly against the running instance with `curl`, independently of the browser. If the API behaves correctly and only the UI fails, that is a *frontend* defect, not a backend one — say which layer, and do not stop at "the test failed".

If a failure cannot be attributed either way, report it as unattributed. Do not guess, and do not call it flaky: a flake is a defect until the mechanism is named.

## Output

A per-failure table — spec, verdict, the commits or the `curl` evidence, and the fix — then:

- **Stale specs:** propose the fix and ask before editing. Prefer deriving the expectation from its source of truth over re-hardcoding the new value; a spec that reads the section list from `components/settings/sections.ts` cannot drift again, one that hardcodes the new ids will drift on the next refactor.
- **Real regressions:** do not fix them here. File them, or hand them back with the reproduction.

## Notes

- The suite takes ~55s. Re-running is cheap; guessing is not.
- Drift here is caused by *merges*, not by elapsed time, so this is worth running after frontend changes land rather than on a fixed clock.
- Unit tests do not substitute. `settings.toc.test.ts` passed throughout the JCLAW-1057 section consolidation because it tests the TOC *mechanism*; the section *list* lived only in the e2e spec, which is why five specs rotted unnoticed (JCLAW-1139).
