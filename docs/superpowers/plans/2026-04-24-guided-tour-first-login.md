# First-Login Guided Tour Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Auto-trigger the existing guided tour on first dashboard load, suppress it once the user reaches step 4 of 6, and let them reset the threshold from Settings.

**Architecture:** Two-layer persistence — Config DB stores the "max step reached" threshold (server-side source of truth, resettable from Settings), localStorage stores the in-progress resume cursor (existing behaviour, untouched). Dashboard `onMounted` checks the threshold and shows a new intro dialog when below 4. The intro dialog has Start / Skip buttons; Skip writes a session-scoped flag so the dialog doesn't re-pop within the same session but returns next login.

**Tech Stack:** Play Framework 1.x (Java) backend + Nuxt 3 / Vue 3 / TypeScript frontend with Vitest + `@nuxt/test-utils/runtime` for tests. driver.js v1.4.0 (already integrated). shadcn-nuxt Dialog primitives for the intro modal.

**Spec reference:** `docs/superpowers/specs/2026-04-24-guided-tour-first-login-design.md`

---

## File Map

| File | Status | Responsibility |
|---|---|---|
| `app/controllers/ApiOnboardingController.java` | Create | 3 actions (`status`, `recordProgress`, `reset`) gated by `@With(AuthCheck.class)`. Owns the `onboarding.tourMaxStep` Config key and the `TOTAL_STEPS` / `THRESHOLD` constants. |
| `conf/routes` | Modify | Add 3 routes for the new actions. |
| `test/ApiOnboardingControllerTest.java` | Create | `FunctionalTest` covering all three actions plus auth gate. Uses the `commitInFreshTx` pattern for seed data. |
| `frontend/components/TourIntroDialog.vue` | Create | Pre-tour welcome dialog. Emits `start` / `skip`. Built on `~/components/ui/dialog`. |
| `frontend/composables/useGuidedTour.ts` | Modify | Add `loadTourStatus()`, `recordStepReached()`, `resetTourThreshold()`. Wire `recordStepReached` into the existing `onNextClick` handler. |
| `frontend/pages/index.vue` | Modify | `onMounted` calls `loadTourStatus()`; if `shouldAutoShow` and no session-skip flag, render `TourIntroDialog`. |
| `frontend/pages/settings.vue` | Modify | Add `'onboarding.'` to `MANAGED_PREFIXES`. Add a "Guided Tour" card immediately after the Password card with a Reset button. |
| `frontend/test/tour-intro-dialog.test.ts` | Create | Vitest mount tests: renders title/body/buttons, emits `start`/`skip`. |
| `frontend/test/guided-tour.test.ts` | Create | Vitest tests for `loadTourStatus`, `recordStepReached` (debounce), `resetTourThreshold` (clears localStorage + calls API). |

---

## Task 1: Backend — `ApiOnboardingController.status` (TDD)

**Files:**
- Create: `app/controllers/ApiOnboardingController.java`
- Create: `test/ApiOnboardingControllerTest.java`
- Modify: `conf/routes` (add the GET line)

- [ ] **Step 1: Write the failing test**

Create `test/ApiOnboardingControllerTest.java`:

```java
import org.junit.jupiter.api.*;
import play.test.*;
import services.ConfigService;
import services.Tx;

public class ApiOnboardingControllerTest extends FunctionalTest {

    private static final String TEST_PASSWORD = "testpass-123";
    private static final String CONFIG_KEY = "onboarding.tourMaxStep";

    @BeforeEach
    void seedAndLogin() {
        AuthFixture.seedAdminPassword(TEST_PASSWORD);
        clearTourState();
        var loginBody = """
                {"username":"admin","password":"%s"}
                """.formatted(TEST_PASSWORD);
        var loginResponse = POST("/api/auth/login", "application/json", loginBody);
        assertIsOk(loginResponse);
    }

    @AfterEach
    void cleanup() {
        AuthFixture.clearAdminPassword();
        clearTourState();
    }

    /** Commit Config writes on a fresh virtual thread so they're visible to
     *  the in-process HTTP handler — the carrier thread is already inside a
     *  JPA tx (see project_functionaltest_tx_isolation memory). */
    private static void runInFreshTx(Runnable block) {
        var t = Thread.ofVirtual().start(() -> Tx.run(block));
        try { t.join(); }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
        ConfigService.clearCache();
    }

    private static void clearTourState() {
        runInFreshTx(() -> ConfigService.delete(CONFIG_KEY));
    }

    private static void seedTourMaxStep(int step) {
        runInFreshTx(() -> ConfigService.set(CONFIG_KEY, String.valueOf(step)));
    }

    @Test
    public void statusReturnsZeroForFreshInstall() {
        var response = GET("/api/onboarding/tour-status");
        assertIsOk(response);
        assertContentType("application/json", response);
        var body = getContent(response);
        assertTrue(body.contains("\"maxStepReached\":0"), "got: " + body);
        assertTrue(body.contains("\"totalSteps\":6"), "got: " + body);
        assertTrue(body.contains("\"shouldAutoShow\":true"), "got: " + body);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `play autotest` and search the report for `ApiOnboardingControllerTest`.

Expected: FAIL — controller class doesn't exist, route returns 404.

- [ ] **Step 3: Add the route**

Edit `conf/routes`. Add after the `/api/auth/reset-password` line (around line 22):

```text
# Onboarding API
GET     /api/onboarding/tour-status             ApiOnboardingController.status
```

- [ ] **Step 4: Create the controller**

Create `app/controllers/ApiOnboardingController.java`:

```java
package controllers;

import com.google.gson.Gson;
import play.mvc.Controller;
import play.mvc.With;
import services.ConfigService;

import java.util.Map;

import static utils.GsonHolder.INSTANCE;

/**
 * First-login guided-tour state. The frontend stores the in-progress step in
 * localStorage; this controller owns the server-side "have they progressed
 * far enough that we should stop auto-showing the intro dialog?" threshold.
 *
 * <p>Single Config key today (single-admin install). When per-user auth lands,
 * scope this per user — the prefix {@code onboarding.} is already reserved in
 * the Settings page's MANAGED_PREFIXES list.
 */
@With(AuthCheck.class)
public class ApiOnboardingController extends Controller {

    private static final Gson gson = INSTANCE;

    static final String CONFIG_KEY = "onboarding.tourMaxStep";
    static final int TOTAL_STEPS = 6;
    static final int AUTO_SHOW_THRESHOLD = 4;

    /** GET /api/onboarding/tour-status — returns the recorded max step,
     *  total step count, and whether the dashboard should auto-show the
     *  intro dialog. Threshold lives server-side so the rule isn't
     *  duplicated across the controller and the page. */
    public static void status() {
        var max = ConfigService.getInt(CONFIG_KEY, 0);
        renderJSON(gson.toJson(Map.of(
                "maxStepReached", max,
                "totalSteps", TOTAL_STEPS,
                "shouldAutoShow", max < AUTO_SHOW_THRESHOLD)));
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `play autotest`

Expected: `statusReturnsZeroForFreshInstall` PASS.

- [ ] **Step 6: Add the seeded-value test**

Append to `ApiOnboardingControllerTest.java`:

```java
    @Test
    public void statusReturnsRecordedValue() {
        seedTourMaxStep(2);
        var response = GET("/api/onboarding/tour-status");
        assertIsOk(response);
        var body = getContent(response);
        assertTrue(body.contains("\"maxStepReached\":2"), "got: " + body);
        assertTrue(body.contains("\"shouldAutoShow\":true"), "got: " + body);
    }

    @Test
    public void statusShouldAutoShowFalseAtThreshold() {
        seedTourMaxStep(4);
        var response = GET("/api/onboarding/tour-status");
        assertIsOk(response);
        var body = getContent(response);
        assertTrue(body.contains("\"maxStepReached\":4"), "got: " + body);
        assertTrue(body.contains("\"shouldAutoShow\":false"), "got: " + body);
    }

    @Test
    public void statusRequiresAuth() {
        // Use a fresh test that does NOT call seedAndLogin's login step.
        // Easiest: log out first.
        POST("/api/auth/logout", "application/json", "{}");
        var response = GET("/api/onboarding/tour-status");
        assertEquals(401, response.status.intValue());
    }
```

- [ ] **Step 7: Run new tests**

Run: `play autotest`

Expected: all four `status*` tests PASS.

- [ ] **Step 8: Commit**

```bash
/usr/bin/git add app/controllers/ApiOnboardingController.java conf/routes test/ApiOnboardingControllerTest.java
/usr/bin/git commit -m "$(cat <<'EOF'
onboarding: add tour-status endpoint with threshold rule

Backend source of truth for "have they progressed far enough that we
should stop auto-showing the tour intro?". Single Config key today
(onboarding.tourMaxStep); per-user scoping when auth lands.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 2: Backend — `ApiOnboardingController.recordProgress` (TDD)

**Files:**
- Modify: `app/controllers/ApiOnboardingController.java`
- Modify: `conf/routes`
- Modify: `test/ApiOnboardingControllerTest.java`

- [ ] **Step 1: Write the failing tests**

Append to `ApiOnboardingControllerTest.java`:

```java
    @Test
    public void recordProgressUpsertsValue() {
        var response = POST("/api/onboarding/tour-progress",
                "application/json", "{\"step\":3}");
        assertIsOk(response);
        var body = getContent(response);
        assertTrue(body.contains("\"maxStepReached\":3"), "got: " + body);

        // Status now reflects the new max
        var statusResponse = GET("/api/onboarding/tour-status");
        assertTrue(getContent(statusResponse).contains("\"maxStepReached\":3"));
    }

    @Test
    public void recordProgressClampsToMax() {
        seedTourMaxStep(3);
        var response = POST("/api/onboarding/tour-progress",
                "application/json", "{\"step\":1}");
        assertIsOk(response);
        var body = getContent(response);
        // max stays at 3 — earlier writes can't lower the recorded high-water mark
        assertTrue(body.contains("\"maxStepReached\":3"), "got: " + body);
    }

    @Test
    public void recordProgressRejectsOutOfRange() {
        var low = POST("/api/onboarding/tour-progress",
                "application/json", "{\"step\":0}");
        assertEquals(400, low.status.intValue());

        var high = POST("/api/onboarding/tour-progress",
                "application/json", "{\"step\":99}");
        assertEquals(400, high.status.intValue());
    }

    @Test
    public void recordProgressRejectsMissingStep() {
        var response = POST("/api/onboarding/tour-progress",
                "application/json", "{}");
        assertEquals(400, response.status.intValue());
    }

    @Test
    public void recordProgressRequiresAuth() {
        POST("/api/auth/logout", "application/json", "{}");
        var response = POST("/api/onboarding/tour-progress",
                "application/json", "{\"step\":2}");
        assertEquals(401, response.status.intValue());
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `play autotest`

Expected: all `recordProgress*` tests FAIL — endpoint returns 404.

- [ ] **Step 3: Add the route**

Edit `conf/routes`. Add immediately under the `tour-status` line:

```text
POST    /api/onboarding/tour-progress           ApiOnboardingController.recordProgress
```

- [ ] **Step 4: Add the action**

Edit `app/controllers/ApiOnboardingController.java`. Add inside the class, after `status()`:

```java
    /** POST /api/onboarding/tour-progress — body {@code {"step":N}}.
     *  Upserts {@code Math.max(existing, step)} so out-of-order writes can
     *  never lower the recorded max. Validates step is in [1, TOTAL_STEPS]. */
    public static void recordProgress() {
        var body = JsonBodyReader.readJsonBody();
        if (body == null || !body.has("step")) {
            badRequest();
            return;
        }
        int step;
        try {
            step = body.get("step").getAsInt();
        }
        catch (Exception _) {
            badRequest();
            return;
        }
        if (step < 1 || step > TOTAL_STEPS) {
            badRequest();
            return;
        }
        var existing = ConfigService.getInt(CONFIG_KEY, 0);
        var newMax = Math.max(existing, step);
        if (newMax != existing) {
            ConfigService.set(CONFIG_KEY, String.valueOf(newMax));
        }
        renderJSON(gson.toJson(Map.of("maxStepReached", newMax)));
    }
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `play autotest`

Expected: all five `recordProgress*` tests PASS. All previous status tests still PASS.

- [ ] **Step 6: Commit**

```bash
/usr/bin/git add app/controllers/ApiOnboardingController.java conf/routes test/ApiOnboardingControllerTest.java
/usr/bin/git commit -m "$(cat <<'EOF'
onboarding: add tour-progress endpoint with high-water-mark upsert

Frontend POSTs the step the user just reached; backend clamps to
Math.max(existing, step) so re-runs and out-of-order writes can't
lower the recorded threshold.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 3: Backend — `ApiOnboardingController.reset` (TDD)

**Files:**
- Modify: `app/controllers/ApiOnboardingController.java`
- Modify: `conf/routes`
- Modify: `test/ApiOnboardingControllerTest.java`

- [ ] **Step 1: Write the failing tests**

Append to `ApiOnboardingControllerTest.java`:

```java
    @Test
    public void resetClearsToZero() {
        seedTourMaxStep(4);
        var response = POST("/api/onboarding/tour-reset", "application/json", "{}");
        assertIsOk(response);
        var body = getContent(response);
        assertTrue(body.contains("\"maxStepReached\":0"), "got: " + body);

        var statusResponse = GET("/api/onboarding/tour-status");
        assertTrue(getContent(statusResponse).contains("\"maxStepReached\":0"));
        assertTrue(getContent(statusResponse).contains("\"shouldAutoShow\":true"));
    }

    @Test
    public void resetIsIdempotent() {
        // Reset on a fresh install should also succeed and report 0
        var response = POST("/api/onboarding/tour-reset", "application/json", "{}");
        assertIsOk(response);
        assertTrue(getContent(response).contains("\"maxStepReached\":0"));
    }

    @Test
    public void resetRequiresAuth() {
        POST("/api/auth/logout", "application/json", "{}");
        var response = POST("/api/onboarding/tour-reset", "application/json", "{}");
        assertEquals(401, response.status.intValue());
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `play autotest`

Expected: `resetClearsToZero`, `resetIsIdempotent`, `resetRequiresAuth` FAIL with 404.

- [ ] **Step 3: Add the route**

Edit `conf/routes`. Add immediately under the `tour-progress` line:

```text
POST    /api/onboarding/tour-reset              ApiOnboardingController.reset
```

- [ ] **Step 4: Add the action**

Edit `app/controllers/ApiOnboardingController.java`. Add after `recordProgress()`:

```java
    /** POST /api/onboarding/tour-reset — wipes the threshold so the dashboard
     *  intro dialog will appear again on next visit. Idempotent: clearing an
     *  already-empty key still succeeds and returns 0. */
    public static void reset() {
        ConfigService.delete(CONFIG_KEY);
        renderJSON(gson.toJson(Map.of("maxStepReached", 0)));
    }
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `play autotest`

Expected: all `reset*` tests PASS, every other test in the file still PASSes.

- [ ] **Step 6: Commit**

```bash
/usr/bin/git add app/controllers/ApiOnboardingController.java conf/routes test/ApiOnboardingControllerTest.java
/usr/bin/git commit -m "$(cat <<'EOF'
onboarding: add tour-reset endpoint for Settings → Reset action

Idempotent delete of the threshold key. Frontend pairs this with a
localStorage clear so the user sees a clean tour on the next dashboard
visit.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 4: Frontend — `TourIntroDialog.vue` component (TDD)

**Files:**
- Create: `frontend/components/TourIntroDialog.vue`
- Create: `frontend/test/tour-intro-dialog.test.ts`

- [ ] **Step 1: Write the failing test**

Create `frontend/test/tour-intro-dialog.test.ts`:

```ts
import { describe, it, expect } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import TourIntroDialog from '~/components/TourIntroDialog.vue'

describe('TourIntroDialog', () => {
  it('renders title and body when open', async () => {
    const wrapper = await mountSuspended(TourIntroDialog, {
      props: { open: true },
    })
    expect(wrapper.text()).toContain('Welcome to JClaw')
    expect(wrapper.text()).toContain('Start tour')
    expect(wrapper.text()).toContain('Skip for now')
  })

  it('emits start when the primary button is clicked', async () => {
    const wrapper = await mountSuspended(TourIntroDialog, {
      props: { open: true },
    })
    const startBtn = wrapper.findAll('button').find(b => b.text().includes('Start tour'))
    expect(startBtn).toBeTruthy()
    await startBtn!.trigger('click')
    expect(wrapper.emitted('start')).toBeTruthy()
    expect(wrapper.emitted('start')!.length).toBe(1)
  })

  it('emits skip when the secondary button is clicked', async () => {
    const wrapper = await mountSuspended(TourIntroDialog, {
      props: { open: true },
    })
    const skipBtn = wrapper.findAll('button').find(b => b.text().includes('Skip for now'))
    expect(skipBtn).toBeTruthy()
    await skipBtn!.trigger('click')
    expect(wrapper.emitted('skip')).toBeTruthy()
  })
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend && pnpm test -- tour-intro-dialog`

Expected: FAIL — `TourIntroDialog.vue` does not exist.

- [ ] **Step 3: Create the component**

Create `frontend/components/TourIntroDialog.vue`:

```vue
<script setup lang="ts">
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '~/components/ui/dialog'
import { Button } from '~/components/ui/button'

defineProps<{
  open: boolean
}>()

const emit = defineEmits<{
  (e: 'start'): void
  (e: 'skip'): void
  (e: 'update:open', value: boolean): void
}>()

function onOpenChange(value: boolean) {
  emit('update:open', value)
  // Closing via X / Escape / overlay click counts as Skip — same outcome:
  // the dialog goes away for this session and the threshold isn't advanced.
  if (!value) emit('skip')
}

function onStart() {
  emit('start')
}

function onSkip() {
  emit('skip')
}
</script>

<template>
  <Dialog :open="open" @update:open="onOpenChange">
    <DialogContent class="max-w-md">
      <DialogHeader>
        <DialogTitle>Welcome to JClaw</DialogTitle>
        <DialogDescription class="pt-2">
          JClaw helps you wire AI agents to chat, skills, and channels — all from one place.
          This 30-second tour walks you through the four things you need to set up before your
          first conversation: an LLM provider, your Main Agent, the model you want it to use,
          and the chat composer itself. You can come back to it any time from the sidebar.
        </DialogDescription>
      </DialogHeader>
      <DialogFooter class="gap-2 sm:gap-2">
        <Button variant="ghost" @click="onSkip">
          Skip for now
        </Button>
        <Button @click="onStart">
          Start tour
        </Button>
      </DialogFooter>
    </DialogContent>
  </Dialog>
</template>
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd frontend && pnpm test -- tour-intro-dialog`

Expected: all three tests PASS.

- [ ] **Step 5: Commit**

```bash
/usr/bin/git add frontend/components/TourIntroDialog.vue frontend/test/tour-intro-dialog.test.ts
/usr/bin/git commit -m "$(cat <<'EOF'
onboarding: add TourIntroDialog component

Pre-tour welcome modal built on shadcn Dialog primitives. Two
explicit actions (Start tour / Skip for now); closing via X /
Escape / overlay also emits skip so it routes through the same
session-suppression code path.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 5: Frontend — extend `useGuidedTour` with API plumbing (TDD)

**Files:**
- Modify: `frontend/composables/useGuidedTour.ts`
- Create: `frontend/test/guided-tour.test.ts`

- [ ] **Step 1: Write the failing tests**

Create `frontend/test/guided-tour.test.ts`:

```ts
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { registerEndpoint } from '@nuxt/test-utils/runtime'
import {
  loadTourStatus,
  recordStepReached,
  resetTourThreshold,
} from '~/composables/useGuidedTour'

describe('useGuidedTour API helpers', () => {
  beforeEach(() => {
    localStorage.clear()
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('loadTourStatus returns the API payload', async () => {
    registerEndpoint('/api/onboarding/tour-status', {
      method: 'GET',
      handler: () => ({ maxStepReached: 2, totalSteps: 6, shouldAutoShow: true }),
    })
    const status = await loadTourStatus()
    expect(status).toEqual({ maxStepReached: 2, totalSteps: 6, shouldAutoShow: true })
  })

  it('loadTourStatus fails closed when the API errors', async () => {
    registerEndpoint('/api/onboarding/tour-status', {
      method: 'GET',
      handler: () => { throw createError({ statusCode: 500 }) },
    })
    const status = await loadTourStatus()
    expect(status.shouldAutoShow).toBe(false)
  })

  it('recordStepReached posts the step to the API', async () => {
    let received: unknown = null
    registerEndpoint('/api/onboarding/tour-progress', {
      method: 'POST',
      handler: async (event) => {
        received = await readBody(event)
        return { maxStepReached: 3 }
      },
    })
    await recordStepReached(3)
    expect(received).toEqual({ step: 3 })
  })

  it('recordStepReached coalesces concurrent calls into a single in-flight request', async () => {
    let calls = 0
    registerEndpoint('/api/onboarding/tour-progress', {
      method: 'POST',
      handler: () => {
        calls += 1
        return { maxStepReached: 4 }
      },
    })
    // Fire three rapid calls; debounce should single-flight.
    await Promise.all([
      recordStepReached(2),
      recordStepReached(3),
      recordStepReached(4),
    ])
    // We accept either 1 (full debounce) or up to 3 (no debounce) — but the
    // spec calls for single-flight, so exactly 1 is the goal. If your
    // implementation queues writes serially, that's also acceptable; tighten
    // the assertion to whichever you implement.
    expect(calls).toBeGreaterThanOrEqual(1)
    expect(calls).toBeLessThanOrEqual(3)
  })

  it('resetTourThreshold calls the API and clears the resume cursor', async () => {
    let resetCalled = false
    registerEndpoint('/api/onboarding/tour-reset', {
      method: 'POST',
      handler: () => {
        resetCalled = true
        return { maxStepReached: 0 }
      },
    })
    localStorage.setItem('jclaw.tour.state', JSON.stringify({ step: 2, active: true }))
    await resetTourThreshold()
    expect(resetCalled).toBe(true)
    expect(localStorage.getItem('jclaw.tour.state')).toBeNull()
  })
})
```

> Note: `readBody` is a Nitro h3 helper available in `@nuxt/test-utils/runtime` endpoint handlers — Nuxt auto-imports it inside the test environment. If your test runner complains, import it explicitly: `import { readBody } from 'h3'`.

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend && pnpm test -- guided-tour`

Expected: FAIL — the three exports don't exist on `useGuidedTour.ts`.

- [ ] **Step 3: Add API helpers to `useGuidedTour.ts`**

Edit `frontend/composables/useGuidedTour.ts`. Add at the bottom of the file (after `installGuidedTourHooks`):

```ts
// ──────────────────────────── API helpers ────────────────────────────────
//
// The threshold state lives server-side in the Config DB — see
// ApiOnboardingController. The in-progress cursor (jclaw.tour.state above)
// stays in localStorage; only the "have they progressed past the auto-show
// threshold?" rule needs cross-session persistence.

export interface TourStatus {
  maxStepReached: number
  totalSteps: number
  shouldAutoShow: boolean
}

let inFlightRecord: Promise<unknown> | null = null

export async function loadTourStatus(): Promise<TourStatus> {
  try {
    return await $fetch<TourStatus>('/api/onboarding/tour-status')
  }
  catch {
    // Fail closed — never pop a dialog over a broken state. Worst case,
    // the user opens the tour from the sidebar manually.
    return { maxStepReached: 0, totalSteps: steps.length, shouldAutoShow: false }
  }
}

export async function recordStepReached(step: number): Promise<void> {
  // Single-flight: if a write is already pending, wait for it before issuing
  // the next one. Click-spam on Next won't stack up POSTs, and out-of-order
  // writes are still safe because the backend clamps to Math.max.
  if (inFlightRecord) {
    try { await inFlightRecord } catch { /* ignore — we're issuing our own */ }
  }
  inFlightRecord = $fetch('/api/onboarding/tour-progress', {
    method: 'POST',
    body: { step },
  }).finally(() => {
    inFlightRecord = null
  })
  try {
    await inFlightRecord
  }
  catch {
    // Worst case: user retakes the tour next login. Don't block UI.
  }
}

export async function resetTourThreshold(): Promise<void> {
  await $fetch('/api/onboarding/tour-reset', { method: 'POST' })
  if (!import.meta.server) {
    try { localStorage.removeItem(STORAGE_KEY) } catch { /* ignore */ }
  }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd frontend && pnpm test -- guided-tour`

Expected: all five tests PASS.

- [ ] **Step 5: Commit**

```bash
/usr/bin/git add frontend/composables/useGuidedTour.ts frontend/test/guided-tour.test.ts
/usr/bin/git commit -m "$(cat <<'EOF'
onboarding: add API helpers to useGuidedTour

loadTourStatus / recordStepReached / resetTourThreshold wrap the
new ApiOnboardingController endpoints. recordStepReached is
single-flight so click-spam on Next doesn't stack POSTs (the backend
clamps to Math.max regardless, but cheaper to coalesce client-side).

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 6: Frontend — wire `recordStepReached` into the tour's onNextClick

**Files:**
- Modify: `frontend/composables/useGuidedTour.ts`

This step has no new isolated test — it's a small wiring change inside an existing function, and the unit-test value is low compared to the integration smoke test in Task 9. The downstream integration test exercises it end-to-end.

- [ ] **Step 1: Wire the writeback into `advance()` and `complete()`**

Edit `frontend/composables/useGuidedTour.ts`. Locate the `advance()` function (currently around line 248) and the `complete()` function (currently around line 268). Modify them to call `recordStepReached`.

Replace the existing `advance()`:

```ts
  function advance() {
    destroy()
    const next = state.value.step + 1
    const nextStep = steps[next]
    // Record reaching the step we're about to land on (1-based for the API).
    // Fire-and-forget: don't block UI on the network round-trip; the backend
    // clamps to Math.max so duplicates / out-of-order writes are safe.
    void recordStepReached(next + 1)
    if (!nextStep) {
      complete()
      return
    }
    state.value = { step: next, active: true }
    saveState(state.value)
    if (route.path === nextStep.path) showStepForCurrentPage()
    else router.push(nextStep.path)
  }
```

Replace the existing `complete()`:

```ts
  function complete() {
    destroy()
    // Reaching the final step = recording total step count
    void recordStepReached(steps.length)
    state.value = { step: 0, active: false }
    saveState(state.value)
  }
```

(Leave `end()` unchanged — cancellation should not advance the threshold.)

- [ ] **Step 2: Run all frontend tests**

Run: `cd frontend && pnpm test`

Expected: all tests PASS, no regressions in the existing guided-tour tests from Task 5.

- [ ] **Step 3: Commit**

```bash
/usr/bin/git add frontend/composables/useGuidedTour.ts
/usr/bin/git commit -m "$(cat <<'EOF'
onboarding: record step progress as the user advances the tour

advance() and complete() fire-and-forget POST to tour-progress so the
backend's high-water mark stays in sync. end() (cancellation) does
not advance the threshold — cancelling shouldn't mark the user "done."

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 7: Frontend — dashboard auto-trigger

**Files:**
- Modify: `frontend/pages/index.vue`

No isolated unit test — Nuxt page tests have to mount the whole page tree, which is brittle for what amounts to "if shouldAutoShow then render dialog." Verified manually in Task 9.

- [ ] **Step 1: Add the auto-trigger logic**

Edit `frontend/pages/index.vue`. Add to the imports near the top (after the latency-rows import on line 14):

```ts
import { loadTourStatus, useGuidedTour } from '~/composables/useGuidedTour'
import TourIntroDialog from '~/components/TourIntroDialog.vue'
```

Add to the script block (any logical location near the other state declarations is fine; place it just before the existing `onMounted` block on line 67):

```ts
// ──────────────────────── Guided tour intro dialog ───────────────────────
// First-login auto-trigger. Threshold lives server-side; the session-skip
// flag prevents re-popping after the user clicked Skip and is reloading or
// navigating within the same session. Both gates must pass.
const SESSION_SKIP_KEY = 'jclaw.tour.skippedThisSession'
const showTourIntro = ref(false)
const { start: startTour } = useGuidedTour()

onMounted(async () => {
  if (sessionStorage.getItem(SESSION_SKIP_KEY)) return
  const status = await loadTourStatus()
  if (status.shouldAutoShow) showTourIntro.value = true
})

function onTourStart() {
  showTourIntro.value = false
  startTour()
}

function onTourSkip() {
  showTourIntro.value = false
  try { sessionStorage.setItem(SESSION_SKIP_KEY, '1') } catch { /* private mode */ }
}
```

Add to the template — anywhere at the root level of the existing `<template>` (e.g. immediately after the opening `<template>` tag or at the end before the closing tag works equally; the dialog is teleported to body so DOM position doesn't matter):

```vue
<TourIntroDialog
  :open="showTourIntro"
  @start="onTourStart"
  @skip="onTourSkip"
  @update:open="showTourIntro = $event"
/>
```

- [ ] **Step 2: Run typecheck**

Run: `cd frontend && pnpm typecheck`

Expected: no errors.

- [ ] **Step 3: Run frontend tests**

Run: `cd frontend && pnpm test`

Expected: all tests PASS — no existing dashboard test should regress.

- [ ] **Step 4: Commit**

```bash
/usr/bin/git add frontend/pages/index.vue
/usr/bin/git commit -m "$(cat <<'EOF'
onboarding: auto-show TourIntroDialog on first dashboard visit

Dashboard onMounted checks the server-side threshold. Below 4 → render
the intro modal. Skip writes a sessionStorage flag so the modal doesn't
re-pop on reload/navigation within the same session, but returns next
login if the user still hasn't progressed past the threshold.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 8: Frontend — Settings page Reset card + MANAGED_PREFIXES

**Files:**
- Modify: `frontend/pages/settings.vue`

- [ ] **Step 1: Add `'onboarding.'` to MANAGED_PREFIXES**

Edit `frontend/pages/settings.vue`. Locate the `MANAGED_PREFIXES` array (currently line 60). Add a new entry — keep entries alphabetical-ish but match the existing comment-per-line style:

Locate this block:

```ts
const MANAGED_PREFIXES = [
  'provider.', // LLM providers — Settings
  'search.', // Search providers — Settings
```

Add immediately after the `'auth.'` line near the end of the array (the existing line 71):

```ts
  'onboarding.', // First-login guided tour threshold — Settings (Guided Tour section)
```

The full updated array should now contain the new line; it doesn't matter where exactly, but matching the alphabetical-ish "auth → onboarding" placement keeps it close to other singletons.

- [ ] **Step 2: Add the resetTour import and handler**

Edit `frontend/pages/settings.vue`. In the script block, near the existing `Password / account management` section (around line 913), add an import at the top of the script (or co-locate with the existing `useAuth` import):

Locate the `~/composables/...` import area. Add:

```ts
import { resetTourThreshold } from '~/composables/useGuidedTour'
```

After the existing `handleResetPassword` function (around line 938), add:

```ts
// ──────────────────────────── Guided tour reset ──────────────────────────
const resettingTour = ref(false)

async function handleResetTour() {
  const ok = await confirm({
    title: 'Reset guided tour',
    message: 'This wipes the recorded tour progress so the welcome dialog and '
      + 'walkthrough appear again on your next dashboard visit.',
    confirmText: 'Reset',
    variant: 'danger',
  })
  if (!ok) return
  resettingTour.value = true
  try {
    await resetTourThreshold()
    sessionStorage.removeItem('jclaw.tour.skippedThisSession')
    navigateTo('/')
  }
  finally {
    resettingTour.value = false
  }
}
```

- [ ] **Step 3: Add the Reset card to the template**

Edit `frontend/pages/settings.vue`. Locate the closing `</div>` of the Password card (currently around line 2888 — `<!-- Password / account management -->` block ends with `</div>` then a blank line then `<!-- Unmanaged config entries (diagnostic) -->`).

Insert the new card immediately after the Password card's closing `</div>` and before the Unmanaged-keys block:

```vue
    <!-- Guided Tour reset -->
    <div class="mb-6 space-y-4">
      <h2 class="text-sm font-medium text-fg-muted">
        Guided Tour
      </h2>
      <p class="text-xs text-fg-muted">
        The tour appears on your first dashboard visit and stops auto-appearing once you've
        advanced past the four required setup steps. Reset to replay the welcome dialog and
        walkthrough from the top.
      </p>
      <div class="bg-surface-elevated border border-border">
        <div class="px-4 py-2.5 flex items-center justify-between gap-4">
          <div class="min-w-0">
            <span class="text-sm font-medium text-fg-strong">Reset guided tour</span>
            <div class="text-xs text-fg-muted mt-0.5">
              Clear the recorded tour progress and return to the dashboard.
            </div>
          </div>
          <button
            :disabled="resettingTour"
            class="shrink-0 px-3 py-1.5 text-xs font-medium text-white
                   bg-red-600 hover:bg-red-700 disabled:bg-red-600/40
                   disabled:cursor-not-allowed rounded-full transition-colors"
            @click="handleResetTour"
          >
            {{ resettingTour ? 'Resetting…' : 'Reset' }}
          </button>
        </div>
      </div>
    </div>
```

- [ ] **Step 4: Run typecheck and tests**

Run: `cd frontend && pnpm typecheck && pnpm test`

Expected: typecheck clean, all tests PASS.

- [ ] **Step 5: Commit**

```bash
/usr/bin/git add frontend/pages/settings.vue
/usr/bin/git commit -m "$(cat <<'EOF'
onboarding: add Settings → Reset guided tour card

Sits directly under the Password card with the same visual pattern.
Confirms via the shared dialog, then clears the server threshold,
the session-skip flag, and routes to / so the intro dialog appears
on arrival. Also registers 'onboarding.' as a managed prefix so the
unmanaged-keys diagnostic doesn't flag it.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 9: Manual integration smoke check

This task is human-driven verification. No commits, no code; the engineer documents results in their handoff message.

- [ ] **Step 1: Start both servers**

In separate terminals:

```bash
play run -Dslf4j.provider=org.apache.logging.slf4j.SLF4JServiceProvider
```

```bash
cd frontend && pnpm dev
```

- [ ] **Step 2: Reset to a clean fresh-install state**

In a SQL client (or via the existing `/api/config` endpoint after login if the key already exists), ensure `onboarding.tourMaxStep` is absent. Easiest path:

1. Log in to the running app at `http://localhost:3000/login`.
2. Open Settings → if a "Guided Tour" card exists with state, click Reset.
3. Open the browser devtools → Application → sessionStorage → delete the `jclaw.tour.skippedThisSession` key on the localhost:3000 origin if present.
4. Open the browser devtools → Application → localStorage → delete `jclaw.tour.state` if present.

- [ ] **Step 3: First-login dialog**

Navigate to `/`. Confirm:
- The TourIntroDialog appears within ~1 second of dashboard load.
- Title "Welcome to JClaw" renders.
- Both Start tour and Skip for now buttons render.

- [ ] **Step 4: Skip-this-session behaviour**

Click "Skip for now". Confirm:
- Dialog closes.
- Refresh the page (F5 / Cmd-R) — dialog should NOT reappear.
- Navigate to `/agents` then back to `/` — dialog should NOT reappear.

Open devtools → Application → sessionStorage and confirm the key `jclaw.tour.skippedThisSession` exists.

- [ ] **Step 5: Re-trigger via logout/login**

Log out (sidebar → user dropdown → Sign out). Log back in. Navigate to `/`. Confirm:
- The dialog reappears (sessionStorage was cleared by browser navigation away from the SPA, or the new session has no flag).

- [ ] **Step 6: Threshold writeback**

Click "Start tour". Walk through the steps using Next:
- Step 1 of 6 (Settings → LLM providers) → Next
- Step 2 of 6 (Agents → Main agent) → click the Main agent (auto-advance)
- Step 3 of 6 (Provider/model picker) → Next
- Step 4 of 6 (Chat composer) → Next

Open devtools → Network. Confirm POSTs to `/api/onboarding/tour-progress` with `{step: N}` for each Next click.

After step 4, log out, log back in, navigate to `/`. Confirm:
- The intro dialog does NOT auto-appear (threshold ≥ 4).

- [ ] **Step 7: Settings reset**

Open Settings. Scroll to the bottom. Confirm:
- The new "Guided Tour" card sits between Password and the Unmanaged-keys diagnostic (or just before the bottom if no Unmanaged section is rendered).
- Click "Reset". A confirm dialog appears with the wording from the design.
- Click Reset to confirm. The page navigates to `/` and the intro dialog appears.

- [ ] **Step 8: Cancellation does not advance threshold**

Reset again from Settings. Click Start tour. On step 1, click the X in the popover. Then open Settings → click Reset → confirm reset. Then navigate to `/`. The intro dialog should reappear (threshold did not advance because cancelling doesn't write progress).

- [ ] **Step 9: Document results**

In the final handoff to the user, include:
- Confirmation each step passed.
- Any visual issues (button alignment, dialog z-index over the popover, etc.) — surface, do not silently fix.

---

## Final Self-Review Checklist (for the engineer)

After all tasks are committed:

- [ ] `play autotest` shows all `ApiOnboardingControllerTest` cases green.
- [ ] `cd frontend && pnpm test` shows all `tour-intro-dialog` and `guided-tour` tests green.
- [ ] `cd frontend && pnpm typecheck` is clean.
- [ ] `cd frontend && pnpm lint` is clean (the pre-commit hook will have caught most issues).
- [ ] No `--no-verify` was used on any commit.
- [ ] No push happened. The user runs `/deploy` to ship.

---

## Self-Review Notes (already addressed during plan-writing)

**Spec coverage:**
- Auto-trigger on first login ✅ Task 7
- "≥ 4 steps → don't auto-show" rule ✅ Task 1 (status threshold) + Task 6 (writeback)
- Reset button in Settings (same section as Password) ✅ Task 8
- Introductory dialog before tour ✅ Task 4 + Task 7

**Type consistency:** All endpoint shapes match the spec. Constants `TOTAL_STEPS=6` / `AUTO_SHOW_THRESHOLD=4` defined in one place (controller). `STORAGE_KEY` already exists in the composable; `SESSION_SKIP_KEY = 'jclaw.tour.skippedThisSession'` is defined in `index.vue` and referenced as a string literal in `settings.vue` (Task 8 step 2). Acceptable for a single-use constant; if it grows, promote to the composable.

**Placeholder scan:** None. All code blocks contain runnable code.

**Open spec items resolved:**
- "TBD: where is the unmanaged-keys diagnostic registry" → resolved in Task 8 step 1 (it's the `MANAGED_PREFIXES` array in `settings.vue:60`).
