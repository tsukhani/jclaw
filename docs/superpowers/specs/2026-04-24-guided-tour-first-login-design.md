# Guided Tour — First-Login Auto-Trigger & Reset

**Date:** 2026-04-24
**Status:** Approved (design phase complete)
**Author:** Claude Code session, brainstorming with Tarun

## Problem

JClaw already has a working guided tour (`useGuidedTour.ts` + `driver.js`), but it only runs when the user clicks the "Guided Tour" item in the sidebar. New users can complete login without ever discovering the tour, so the onboarding investment is wasted.

We want the tour to:

1. Auto-appear the **first time** a user lands on the dashboard after logging in.
2. **Not auto-appear again** once the user has completed at least 4 of the 6 tour steps.
3. Be **resettable** from the Settings page (in the same section that contains the Reset Password card today).
4. Open with a **brief introductory dialog** that explains JClaw before the step-by-step walkthrough begins.

## Non-Goals

- No changes to the existing 6 tour steps, anchors, or driver.js styling.
- No multi-tenant scoping of the tour flag — JClaw is single-admin today; revisit when auth-with-users lands.
- No telemetry / analytics on tour engagement.
- No A/B test of tour copy.

## Existing Infrastructure (unchanged)

- `frontend/composables/useGuidedTour.ts` — driver.js wrapper, 6-step tour, localStorage-resumable.
- `frontend/assets/css/driver-theme.css` — JClaw-themed popovers.
- `frontend/layouts/default.vue` — sidebar item that calls `startGuidedTour()`.
- `[data-tour="..."]` anchors on `/settings`, `/agents`, `/chat`, `/skills`, `/channels`.

## Architecture

### Two-layer persistence

| Layer | Key | Purpose | Cleared by |
|---|---|---|---|
| Config DB | `onboarding.tourMaxStep` (string-int) | Source of truth for "have they progressed enough that we should stop auto-showing the tour?" | Settings → Reset Guided Tour |
| `localStorage` | `jclaw.tour.state` (existing) | In-progress resume state — which step is the popover currently on | Tour completion / cancellation |
| `sessionStorage` | `jclaw.tour.skippedThisSession` | "User clicked Skip on the intro dialog this session" — suppresses the dialog until the next login | Browser session end |

**Threshold rule:** auto-show the intro dialog when `Number(onboarding.tourMaxStep || 0) < 4` AND the session-skip flag is not set.

The "4" threshold corresponds to advancing past the first four steps (LLM provider → Main Agent → Provider/model picker → Chat composer). Steps 5 (Skills) and 6 (Channels) are documented as "optional" in the existing tour, so completing the four core steps is treated as "user has been onboarded."

### Auto-trigger location

`frontend/pages/index.vue` (Dashboard), in `onMounted`:

```text
1. Wait for auth state to settle.
2. Fetch /api/onboarding/tour-status.
3. If shouldAutoShow && !sessionStorage.getItem('jclaw.tour.skippedThisSession'):
     show <TourIntroDialog />
```

This runs only on the dashboard route, so navigating to a deep link (e.g., `/agents/42`) does not pop the dialog over a screen the user explicitly requested.

### Step progress writeback

Inside `useGuidedTour.ts`, in the existing `onNextClick` / step-advance code path, after we move to a new step:

```text
recordStepReached(newStepIndex + 1)   // 1-based count of steps reached
```

`recordStepReached` POSTs to `/api/onboarding/tour-progress` with `{step}`. Backend upserts `Math.max(existing, step)` into Config so re-running the tour can never lower the recorded max.

Debounce: a single in-flight POST at a time; coalesce if the user clicks Next rapidly.

## Backend

### New controller: `app/controllers/ApiOnboardingController.java`

Three actions (all gated by the existing `AuthCheck` annotation):

| Method | Route | Body | Response |
|---|---|---|---|
| `status()` | `GET /api/onboarding/tour-status` | — | `{maxStepReached: int, totalSteps: int, shouldAutoShow: boolean}` |
| `recordProgress()` | `POST /api/onboarding/tour-progress` | `{step: int}` | `{maxStepReached: int}` |
| `reset()` | `POST /api/onboarding/tour-reset` | — | `{maxStepReached: 0}` |

`totalSteps` is derived from a constant the backend mirrors from the frontend tour-step list (currently 6). It is exposed in the response so the frontend can compute `shouldAutoShow = maxStepReached < 4` server-side without hard-coding "4" in two places.

Backend constants:

- `TOUR_TOTAL_STEPS = 6`
- `TOUR_AUTO_SHOW_THRESHOLD = 4`
- `CONFIG_KEY = "onboarding.tourMaxStep"`

Validation:

- `step` must be `1 <= step <= TOUR_TOTAL_STEPS`; reject with HTTP 400 otherwise.
- Upsert clamps to `Math.max(existing, step)` so out-of-order writes are safe.

### Routes (`conf/routes`)

```text
GET     /api/onboarding/tour-status     ApiOnboardingController.status
POST    /api/onboarding/tour-progress   ApiOnboardingController.recordProgress
POST    /api/onboarding/tour-reset      ApiOnboardingController.reset
```

### Why a dedicated controller (not `/api/config`)

- Hides the Config key name from the frontend (single source of truth for the constant).
- Clamps `step` to a valid range (the generic `/api/config` accepts arbitrary string values).
- Matches the existing pattern of feature-scoped controllers (`ApiAuthController`, `ApiAgentsController`, etc.).
- Keeps Settings page diagnostics clean — add `'onboarding.'` to the `MANAGED_PREFIXES` array in `frontend/pages/settings.vue` (line 60) so the unmanaged-keys diagnostic doesn't flag `onboarding.tourMaxStep`.

## Frontend

### New component: `frontend/components/TourIntroDialog.vue`

- Built on existing shadcn `Dialog` primitives (matches modal style across the app).
- Visual: JClaw mascot header (reuse mascot-typing or login mascot at a moderate size), title, ~80–120 word body, two buttons.
- Body copy (draft, finalize during implementation):

  > **Welcome to JClaw.**
  > JClaw helps you wire AI agents to chat, skills, and channels — all from one place.
  > This 30-second tour walks you through the four things you need to set up before your first conversation: an LLM provider, your Main Agent, the model you want it to use, and the chat composer itself. You can come back to it any time from the sidebar.

- Buttons:
  - **Start tour** (primary) → emit `start`
  - **Skip for now** (secondary) → emit `skip`
- Closing the dialog with the X or Escape behaves like Skip.

### Composable extensions: `frontend/composables/useGuidedTour.ts`

New exported functions:

- `loadTourStatus(): Promise<{maxStepReached, totalSteps, shouldAutoShow}>` — wraps GET.
- `recordStepReached(step: number): Promise<void>` — wraps POST, single-flight debounced.
- `resetTourThreshold(): Promise<void>` — wraps POST + clears `localStorage['jclaw.tour.state']`.

Wire `recordStepReached` into the existing step-advance handler in the same file.

### Dashboard mount: `frontend/pages/index.vue`

Add `onMounted` hook that:

1. Calls `loadTourStatus()`.
2. If `shouldAutoShow && !sessionStorage.getItem('jclaw.tour.skippedThisSession')`, sets a reactive `showIntro = true`.
3. Renders `<TourIntroDialog v-model:open="showIntro" @start="onStart" @skip="onSkip" />`.
4. `onStart` → `showIntro = false; startGuidedTour()`.
5. `onSkip` → `showIntro = false; sessionStorage.setItem('jclaw.tour.skippedThisSession', '1')`.

### Settings page: `frontend/pages/settings.vue`

Add a new card just below the Password / Account Management card. Same visual pattern (`bg-surface-elevated border border-border`, header row + content row).

- Card title: **Guided Tour**
- One-line description: "Replay the introductory walkthrough."
- Action button: **Reset guided tour** (uses existing `useConfirm()` pattern).
- On confirm: call `resetTourThreshold()`, show success toast, then `navigateTo('/')` so the intro dialog appears immediately.

## Data Flow Summary

**First login:**

```text
/setup-password → auto-login → / (Dashboard)
  → onMounted → GET /api/onboarding/tour-status → shouldAutoShow=true
  → <TourIntroDialog open>
  → user clicks "Start tour" → startGuidedTour()
  → driver.js navigates to /settings, shows step 1
  → user clicks Next → recordStepReached(2) → POST /api/onboarding/tour-progress
  ...
  → user reaches step 4 → POST records maxStepReached=4
  → next login → GET tour-status → shouldAutoShow=false → no dialog
```

**Reset path:**

```text
Settings → "Reset guided tour" → confirm → POST /api/onboarding/tour-reset
  → maxStepReached=0 server-side, localStorage cleared
  → navigateTo('/') → onMounted → shouldAutoShow=true → dialog appears
```

**Skip path:**

```text
Dashboard → dialog → "Skip for now" → sessionStorage flag set, dialog closes
  → user reloads / navigates within the same session → no dialog (sessionStorage suppresses)
  → user logs out and back in → fresh session → dialog returns (still < 4)
```

## Error Handling

- `GET /api/onboarding/tour-status` failure → log to console, treat as `shouldAutoShow=false` (fail closed — don't pop a dialog over a broken state).
- `POST /api/onboarding/tour-progress` failure → log, do not block the tour. Worst case: user retakes the tour next login.
- `POST /api/onboarding/tour-reset` failure → surface error toast; do not navigate.

## Testing

### Backend

`test/controllers/ApiOnboardingControllerTest.java` — extends `FunctionalTest`:

- `status_returnsZeroForFreshInstall` — no Config key → `maxStepReached=0`, `shouldAutoShow=true`.
- `status_returnsRecordedValue` — seed Config → returns same value.
- `status_shouldAutoShowFalseAtThreshold` — seed `4` → `shouldAutoShow=false`.
- `recordProgress_clampsToMax` — record 2, then record 1 → max stays at 2.
- `recordProgress_rejectsOutOfRange` — `step=0` and `step=99` both 400.
- `recordProgress_rejectsMissingStep` — empty body → 400.
- `reset_clearsToZero` — seed 4 → POST reset → status returns 0.
- `unauthenticated_blocked` — no session → 401/302 on all three.

Use the `commitInFreshTx` pattern (per `feedback_functionaltest_tx_isolation` memory) so seed data is HTTP-visible.

### Frontend

`frontend/tests/composables/useGuidedTour.test.ts` (extend existing if present, or create):

- `recordStepReached` debounces concurrent calls.
- `resetTourThreshold` clears localStorage in addition to calling the API.

`frontend/tests/components/TourIntroDialog.test.ts`:

- Renders title, body, and both buttons.
- Emits `start` on primary click.
- Emits `skip` on secondary click and on close.

Manual / e2e checks (recorded in commit message, not automated):

- Fresh install → first dashboard hit shows the dialog.
- Click Start → tour begins, advancing past step 4 records the threshold.
- Log out and back in → no dialog.
- Settings → Reset → confirmation → dialog reappears.

## Risks / Open Issues

- **Anchor robustness:** the existing tour uses `[data-tour="..."]` selectors. If a user disables JS animations or has slow network, `waitForElement` could time out. Out of scope for this change — same risk exists today.
- **Intro copy lock-in:** the ~100-word body is a draft; finalize during implementation review. Easy to iterate later.
- **Multi-tenancy:** when per-user auth lands, `onboarding.tourMaxStep` will need to become per-user. Document a migration note in the Java controller.

## Out of scope (explicit non-goals revisited)

- No new tour steps.
- No internationalization of the dialog.
- No persistent "Don't show again" checkbox on the dialog itself (the threshold + reset already covers this).
- No deep-link parameter to force-show the dialog (Settings → Reset already covers this).
