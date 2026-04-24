# Backend Java Audit — Coverage Addendum (2026-04-24)

Addendum to [`backend-java-audit-2026-04-22.md`](./backend-java-audit-2026-04-22.md). Scope limited to converting that audit's open P1 item ("no active numeric backend coverage report") from speculative to measured, and using those measurements to re-prioritise the coverage backlog.

Nothing in this addendum supersedes the 2026-04-22 audit's jar, concurrency, or clean-code findings; refer to the parent audit for those.

## What changed since the parent audit

The parent audit could not produce a numeric coverage baseline because `play auto-test` failed to bind in the audit environment. That blocker was environmental, not infrastructural — the Play test agent is already wired to JaCoCo via `%test.javaagent.path=bin/jacocoagent.jar` (see `conf/application.conf`), and the Jenkins pipeline already runs the XML conversion step (`Jenkinsfile:75`). Running `play autotest` locally produces a valid `jacoco.exec` dump; converting it with the same command Jenkins uses produces a report.

This addendum captures the first end-to-end run of that pipeline against the Apr 24 codebase.

## Methodology

```bash
# 1. Run full backend suite (produces jacoco.exec at repo root via the Play test agent).
play autotest

# 2. Convert binary dump to XML using the same invocation the Jenkinsfile uses.
java -jar bin/jacococli.jar report jacoco.exec \
  --classfiles precompiled/java --sourcefiles app \
  --xml jacoco.xml
```

The `pre-push` git hook runs `play autotest` on every push and leaves a fresh `jacoco.exec` behind, so this conversion can be re-run at any time without re-executing the suite. Source of truth: `bin/jacococli.jar` and `bin/jacocoagent.jar`, both checked into the repo.

Report commit: `bce27c4` (Release v0.10.20). Test suite state at capture: **81 classes, all passed**. Classes analysed by JaCoCo: 362 (includes inner classes, test classes, and Play framework stubs; app-code classes are a subset).

## Overall coverage

| Metric       | Covered | Missed | Coverage |
|--------------|--------:|-------:|---------:|
| Class        |     327 |     35 |   90.3 % |
| Method       |   3 155 |    940 |   77.1 % |
| Line         |  17 935 |  4 342 |   80.5 % |
| Branch       |   3 812 |  4 233 |   47.4 % |
| Instruction  |  92 757 | 39 040 |   70.4 % |
| Complexity   |   4 339 |  3 909 |   52.6 % |

The headline is the gap between line coverage (80.5 %) and branch coverage (47.4 %). Happy paths are well covered; conditional branches — error handling, validation rejections, fallback paths, guard clauses — are not. Over half of the code's decision points are untested.

## Package breakdown

Sorted worst to best by line coverage. LOC and branch totals in parentheses.

| Package                | Line coverage     | Branch coverage   |
|------------------------|------------------:|------------------:|
| `helpers`              | 0.0 % (56 lines)  | 0.0 % (48)        |
| `plugins`              | 33.3 % (9)        | 33.3 % (6)        |
| `memory`               | 40.8 % (130)      | 31.4 % (35)       |
| `controllers`          | 42.1 % (1 933)    | 24.6 % (2 279)    |
| `services`             | 56.0 % (1 893)    | 46.2 % (1 102)    |
| `agents`               | 59.9 % (1 412)    | 53.5 % (795)      |
| `tools`                | 67.3 % (1 870)    | 56.1 % (990)      |
| `channels`             | 70.3 % (1 722)    | 62.3 % (923)      |
| `llm`                  | 72.8 % (533)      | 56.0 % (352)      |
| `jobs`                 | 79.1 % (349)      | 57.4 % (108)      |
| `slash`                | 87.4 % (340)      | 59.6 % (270)      |
| `models`               | 87.9 % (224)      | 82.1 % (56)       |
| `utils`                | 90.5 % (304)      | 87.1 % (170)      |
| `services/scanners`    | 93.2 % (207)      | 65.9 % (135)      |

Two packages distort the picture and should be read with caveats:

- **`helpers`** (0 %): contains `CheatSheetHelper` and `LangMenuHelper`, which are invoked from Groovy `#{...}` template tags. JaCoCo does not see those invocations because Play's template engine compiles templates separately. These are not genuinely untested app paths; they are a measurement artifact.
- **`services/scanners`** (93.2 %): refers to the provider implementations (`VirusTotalScanner`, `MetaDefenderCloudScanner`, `MalwareBazaarScanner`), each individually 93–94 %. The binary-scanner *orchestrator* is `services/SkillBinaryScanner` — a different class, only 24 % covered. See below.

## Coverage gaps worth action

### Zero-coverage app controllers (real gaps)

| Class                                      | Lines | Notes                                                                          |
|--------------------------------------------|------:|--------------------------------------------------------------------------------|
| `controllers/ApiBindingsController`        |    43 | Agent↔binding CRUD. HTTP surface for a multi-tenant-sensitive table.           |
| `controllers/ApiTelegramBindingsController`|    98 | Telegram-specific binding management; largest untested controller by LOC.      |
| `controllers/ApiEventsController`          |    31 | SSE endpoint for `NotificationBus` — real-time path with no test at all.       |

`controllers/PlayDocumentation` (0 %, 84 lines) is a Play framework artifact (docviewer module), not app code. Ignore.

### Low-coverage security-critical code

| Class                                  | Coverage          | Concern                                                                                                       |
|----------------------------------------|------------------:|---------------------------------------------------------------------------------------------------------------|
| `services/SkillBinaryScanner`          | 24.0 % (12/50)    | Orchestrator that delegates to provider scanners. Parent audit flagged fail-open behaviour; unit coverage is thin enough that fail-open logic is largely unverified. |
| `services/SkillPromotionService`       | 22.5 % (77/342)   | Handles skill copy, version selection, security policy enforcement. Largest security-relevant class with majority untested. |
| `controllers/ApiSkillsController`      | 18.9 % (53/280)   | HTTP entry point for skill CRUD / upload / promotion; 227 lines never exercised by tests.                      |

The parent audit already flagged the `SkillBinaryScanner` fail-open behaviour (P1 in the remediation backlog). This addendum quantifies the blast radius: the fail-open decision is made in code that is 76 % untested.

### Webhook / channel entry points

| Class                                       | Coverage         |
|---------------------------------------------|-----------------:|
| `channels/TelegramPollingRunner`            | 17.0 % (24/141)  |
| `controllers/WebhookWhatsAppController`     | 19.6 % (11/56)   |
| `controllers/WebhookSlackController`        | 25.5 % (12/47)   |
| `controllers/WebhookTelegramController`     | 26.6 % (25/94)   |
| `channels/WhatsAppChannel`                  | 39.5 % (32/81)   |
| `channels/SlackChannel`                     | 38.0 % (27/71)   |

These are external-input surfaces. Telegram has reasonable channel-side coverage (`TelegramChannel`, `TelegramStreamingSink`, etc.), so the low numbers here reflect the webhook *edge*, not the channel core.

### Core agent orchestration

| Class              | Coverage          |
|--------------------|------------------:|
| `agents/AgentRunner` | 47.2 % (367/777) |

Confirms the parent audit's "god class" flag. 410 lines of the primary agent execution path — streaming, tool loops, persistence, queue drain — are never executed by tests. Any refactor of this class to extract responsibilities should land a baseline test harness *first*; 47 % line coverage is too thin to catch behaviour drift during extraction.

### Harness / non-production

| Class                         | Coverage      | Note                                                   |
|-------------------------------|--------------:|--------------------------------------------------------|
| `services/LoadTestRunner`     | 4.3 % (6/141) | Load-test harness, not a production path. Low priority. |

## Impact on the parent audit's backlog

| Parent item                                                                    | Change                                                                                                                                                                                                     |
|--------------------------------------------------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| P1 "No active numeric backend coverage report" (parent §Prioritized Remediation Backlog) | **Downgrade to P2.** The tooling already works; what is missing is wiring the Jenkinsfile XML step into a reviewer-visible output (PR comment, dashboard, or committed report). Coverage is no longer speculative. |
| P1 "Fix `play auto-test` bind issue" (parent §Recommended Next Steps)         | **Close.** Not an app-code issue; was environmental (sandbox). `play autotest` runs cleanly on developer machines and in the `pre-push` hook.                                                             |
| P1 `SkillBinaryScanner` fail-open                                              | Priority unchanged, but add "bring orchestrator line coverage above 70 %" as a sub-task — fail-closed mode needs tests, not just code.                                                                     |
| P2 "Add fail-closed scanner mode, scanner-disabled status tests"               | Priority unchanged. Note that the provider scanners themselves are already well-tested; the gap is in the dispatcher and the disabled-mode path.                                                          |

## New coverage-targeted backlog items

Ordered by risk × ease-of-fix. These are additive to the parent audit's backlog.

| # | Priority | Target                                         | Why                                                                            | Effort   |
|---|----------|------------------------------------------------|--------------------------------------------------------------------------------|----------|
| 1 | P1       | `SkillBinaryScanner` orchestrator              | Fail-open security decision in 76 % untested code.                             | ~1 day   |
| 2 | P1       | `ApiSkillsController`                          | Largest untested HTTP surface in the app; handles skill upload / promotion.    | ~2 days  |
| 3 | P1       | `AgentRunner` branch-coverage lift             | Guard before any extraction refactor. Target branch coverage ≥ 60 %.           | ~3 days  |
| 4 | P2       | `ApiBindingsController`, `ApiEventsController` | Zero-coverage controllers; small enough to cover in one sitting each.          | ~1 day   |
| 5 | P2       | Webhook controllers (Telegram / Slack / WhatsApp) | External input surface; add request-shape and auth-rejection tests.          | ~2 days  |
| 6 | P2       | `SkillPromotionService`                        | 342 lines at 22 %; security-relevant but large. Split by concern before testing. | ~3 days  |
| 7 | P3       | Overall branch coverage to 60 %                | Aspirational gate; not a hard threshold until specific classes pass individually. | ongoing |

## Reproducibility

Anyone can regenerate this report on demand:

```bash
play autotest                                                # refresh jacoco.exec
java -jar bin/jacococli.jar report jacoco.exec \
  --classfiles precompiled/java --sourcefiles app \
  --html /tmp/jclaw-coverage                                 # browsable HTML
```

The numbers in this document came from `bce27c4`'s `jacoco.exec`; diffs from that baseline are diff-able by re-running on a later commit and comparing.
