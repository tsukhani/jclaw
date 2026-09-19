# JCLAW-1269 — `logs.retentionDays=0` deletes every event log

## What was wrong

`EventLogCleanupJob.resolveRetentionDays` parsed the raw value and returned it unfiltered. A `0`
made `doJob` compute `AppClock.now().minus(0, DAYS)` — a cutoff at the present instant — and
`EventLog.deleteOlderThan(cutoff)` then took the whole table. A negative value put the cutoff in
the future and did the same. Both sibling jobs (`TaskCleanupJob`, `LatencyMetricCleanupJob`)
declare `RETENTION_DISABLED = 0` and return before deleting; this one did not.

## The fix

Four regions of `app/jobs/EventLogCleanupJob.java`, matching the siblings' spelling:

- `public static final int RETENTION_DISABLED = 0;` beside the default.
- `if (retentionDays == RETENTION_DISABLED) return;` in `doJob`, before the cutoff is computed —
  the same bare guard `LatencyMetricCleanupJob` uses.
- `if (parsed <= 0) return RETENTION_DISABLED;` in the resolver, after the parse.
- One clause in the resolver's Javadoc naming what zero-or-less means.

`resolveRetentionDays(@Nullable String raw)` keeps its raw-`String` parameter and stays
`public static`. Three existing tests call it that way and `ConfigService` imports the class for
`CONFIG_KEY`. **JCLAW-1231 may re-implement the body but must keep a raw-taking entry point on
this class**, or it breaks these tests and that import in the same commit.

`conf/application.conf` line ~586 now says what 0 means and that Settings itself accepts only 1
or more.

## The design fork, and why Option A

`ConfigService.setWithSideEffects` already refuses this key below 1 ("must be a whole number of
days, at least 1"), `SettingsLoggingPanel.vue` pins `:min="1"`, and
`ConfigServiceTest.setWithSideEffectsRejectsARetentionThatWouldEmptyTheEventLog` asserts the 403.

Taken narrowly (Option A), the resolver's `<= 0` branch is a defence-in-depth net against a value
that reaches the Config table around the API — which is precisely what the method's own Javadoc
already claimed to be for. Nothing in `ConfigService` or the frontend changed.

The alternative (Option B) — relaxing the validator to `isIntAtLeast(value, 0)` so an operator can
*choose* 0 from Settings — was rejected here: it removes the guard that currently prevents the
data loss, changes what the Settings UI offers, and rewrites two tests that exist to pin the
refusal. That is a product decision about the key's semantics, not a defect fix, and it should be
taken deliberately rather than as a side effect of this story. It remains open if the operator
wants "off" to be selectable; with this change already in, it would then be safe.

## Divergence from the siblings, deliberate

For a **negative** value the siblings fall back to the 30/14-day default plus a warn; this story's
AC says "a negative value is treated the same as 0". Implemented as the AC states. JCLAW-1231
unifies the three and will have to pick one rule; the safe reading (disable) is the one recorded
here.

## Claims that did not reproduce

- **"`conf/application.conf` documents what 0 means … as it does for the other two."** Neither
  `tasks.retentionDays` nor `latency.metrics.retentionDays` appears in `conf/application.conf` at
  all. `logs.retentionDays` was the only retention key in the file. The AC's requirement was met;
  its premise was wrong.
- **"nothing in the UI or `conf/application.conf` says this one differs."** The UI does say so:
  `:min="1"` plus the tip "Minimum 1.", and the API answers 403 with the reason. The reachable
  path is a value written around the API (a direct DB edit), not the Settings page. `ConfigService.get`
  reads the Config table only — it has no `application.conf` fallback — so an operator cannot
  reach 0 by editing that file either. The defect is real; the severity framing overstated how one
  arrives at it.

## Verification

`test/EventLogCleanupJobTest.java` gained `zeroRetentionDisablesCleanup` and
`negativeRetentionDisablesCleanup`, both through one helper that seeds a stale and a fresh row,
writes the key with `ConfigService.set` (not `setWithSideEffects`, which refuses it), runs
`doJob()`, and asserts both rows survive — on the row count, never on a log line. Both were seen
RED before the fix (`expected: <1> but was: <0>` on the stale row) and green after.

The helper deletes the key in a `finally`: it is a row in the shared Config table behind a 60s
cache, and play1 runs test classes concurrently.
