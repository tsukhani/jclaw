<script setup lang="ts">
// The Software Factory panel: Setup (JCLAW-1393), then status, controls, settings, board and logs (JCLAW-1394).
import FactorySetupSection from './FactorySetupSection.vue'
import FactorySettingsForm from './FactorySettingsForm.vue'
import FactoryStoryBoard from './FactoryStoryBoard.vue'
import FactoryStoryDetail from './FactoryStoryDetail.vue'
import { FACTORY_BUTTON, type CommandResult, type SandboxView, type StatusView } from './factory-board'

const REFRESH_MS = 5_000
const OUTPUT_TAIL = 4_000

const { data: status, error: statusError, refresh } = useLazyFetch<StatusView>('/api/factory')
const { confirm } = useConfirm()
const { mutate, loading: acting, errorDetails: actionError } = useApiMutation()

const now = ref(Date.now())
watch(status, () => {
  now.value = Date.now()
})

const installed = computed(() => status.value?.installed === true)

const selectedKey = ref<string | null>(null)
const selectedStory = computed(() =>
  status.value?.board?.stories.find(s => s.key === selectedKey.value) ?? null)
watch(selectedStory, (story) => {
  if (!story) selectedKey.value = null
})

const harnessState = computed(() => status.value?.harness.state ?? 'unknown')
const gatewayState = computed(() => status.value?.gateway.state ?? 'unknown')
// Pause is `docker stop`, so a paused gateway reports Docker's `exited`.
const gatewayPaused = computed(() => ['exited', 'paused', 'created'].includes(gatewayState.value))
const gatewayLabel = computed(() => gatewayState.value === 'running'
  ? 'running'
  : gatewayPaused.value ? 'paused' : gatewayState.value)

const result = ref<CommandResult | null>(null)

async function act(url: string, ask?: { title: string, message: string, confirmText: string }) {
  if (ask && !(await confirm({ ...ask, variant: 'danger' }))) return
  result.value = null
  const res = await mutate<CommandResult>(url, { method: 'POST' })
  if (res !== null) result.value = res
  await refresh()
}

const startHarness = () => act('/api/factory/harness/start')
const resumeGateway = () => act('/api/factory/gateway/resume')
const stopHarness = () => act('/api/factory/harness/stop', {
  title: 'Stop harness',
  message: 'Stop the factory harness? Stories running now will fail.',
  confirmText: 'Stop',
})
const pauseGateway = () => act('/api/factory/gateway/pause', {
  title: 'Pause gateway',
  message: 'Pause the factory gateway? No new stories start, and stories running now will fail.',
  confirmText: 'Pause',
})
const stopSandbox = (s: SandboxView) => act(`/api/factory/sandboxes/${encodeURIComponent(s.name)}/stop`, {
  title: 'Stop sandbox',
  message: `Stop sandbox for ${s.story ?? s.name}? The story running in it will fail.`,
  confirmText: 'Stop',
})

let timer: ReturnType<typeof setInterval> | null = null

function start() {
  if (!timer) timer = setInterval(() => refresh(), REFRESH_MS)
}

function stop() {
  if (timer) {
    clearInterval(timer)
    timer = null
  }
}

function onVisibility() {
  if (document.visibilityState === 'visible') {
    refresh()
    start()
  }
  else {
    stop()
  }
}

onMounted(() => {
  start()
  document.addEventListener('visibilitychange', onVisibility)
})

onBeforeUnmount(() => {
  stop()
  document.removeEventListener('visibilitychange', onVisibility)
})
</script>

<template>
  <div class="mb-6 space-y-4">
    <h2 class="text-sm font-medium text-fg-muted">
      Software Factory
    </h2>
    <p class="text-xs text-fg-muted">
      The optional AFK factory builds Jira stories labelled <span class="font-mono">afk</span> unattended in
      sandboxes on this Mac and hands each back as a local <span class="font-mono">agent/&lt;KEY&gt;</span> branch.
    </p>
    <FactorySetupSection />

    <p
      v-if="!status"
      class="text-xs text-fg-muted"
      data-testid="factory-status-pending"
    >
      {{ statusError ? 'Could not read the factory status.' : 'Loading…' }}
    </p>

    <template v-else-if="installed && status">
      <p
        v-if="!status.supported"
        class="text-xs text-fg-muted"
        data-testid="factory-unsupported"
      >
        {{ status.reason ?? 'The factory cannot run on this machine.' }}
      </p>
      <section
        v-else
        class="space-y-3"
        data-testid="factory-status"
      >
        <h3 class="text-sm font-medium text-fg-strong">
          Status
        </h3>
        <ul class="bg-surface-elevated border border-border divide-y divide-border">
          <li
            class="px-4 py-2.5 flex max-sm:flex-wrap items-center gap-3"
            data-testid="factory-harness"
          >
            <span class="text-sm text-fg-strong w-56 max-sm:w-full shrink-0">Harness</span>
            <span class="text-xs text-fg-muted flex-1">{{ harnessState }}</span>
            <button
              v-if="harnessState === 'running'"
              type="button"
              :class="FACTORY_BUTTON"
              :disabled="acting"
              data-testid="factory-harness-stop"
              @click="stopHarness"
            >
              Stop
            </button>
            <button
              v-else-if="harnessState === 'stopped'"
              type="button"
              :class="FACTORY_BUTTON"
              :disabled="acting"
              data-testid="factory-harness-start"
              @click="startHarness"
            >
              Start
            </button>
          </li>
          <li
            class="px-4 py-2.5 flex max-sm:flex-wrap items-center gap-3"
            data-testid="factory-gateway"
          >
            <span class="text-sm text-fg-strong w-56 max-sm:w-full shrink-0">Gateway</span>
            <span class="text-xs text-fg-muted flex-1">{{ gatewayLabel }}</span>
            <button
              v-if="gatewayState === 'running'"
              type="button"
              :class="FACTORY_BUTTON"
              :disabled="acting"
              data-testid="factory-gateway-pause"
              @click="pauseGateway"
            >
              Pause
            </button>
            <button
              v-else-if="gatewayPaused"
              type="button"
              :class="FACTORY_BUTTON"
              :disabled="acting"
              data-testid="factory-gateway-resume"
              @click="resumeGateway"
            >
              Resume
            </button>
          </li>
          <li
            class="px-4 py-2.5 flex max-sm:flex-wrap items-start gap-3"
            data-testid="factory-sandboxes"
          >
            <span class="text-sm text-fg-strong w-56 max-sm:w-full shrink-0">Sandboxes</span>
            <span
              v-if="status.sandboxes === null"
              class="text-xs text-fg-muted"
            >Docker could not be asked.</span>
            <span
              v-else-if="status.sandboxes.length === 0"
              class="text-xs text-fg-muted"
            >None running.</span>
            <ul
              v-else
              class="flex-1 space-y-1"
            >
              <li
                v-for="s in status.sandboxes"
                :key="s.name"
                class="flex items-center gap-3"
              >
                <span class="text-xs text-fg-strong font-mono flex-1">{{ s.story ?? s.name }} · up {{ s.upTime }}</span>
                <button
                  type="button"
                  :class="FACTORY_BUTTON"
                  :disabled="acting"
                  :data-testid="`factory-sandbox-stop-${s.name}`"
                  @click="stopSandbox(s)"
                >
                  Stop
                </button>
              </li>
            </ul>
          </li>
        </ul>
        <ApiErrorAlert :error="actionError" />
        <div
          v-if="result"
          class="space-y-1"
          data-testid="factory-action-result"
        >
          <p
            class="text-xs text-fg-muted"
            role="status"
          >
            {{ result.message }}
          </p>
          <pre
            v-if="result.exitCode !== 0 && result.output"
            class="max-h-60 overflow-auto text-xs font-mono bg-muted p-2 whitespace-pre-wrap break-words"
          >{{ result.output.slice(-OUTPUT_TAIL) }}</pre>
        </div>
      </section>

      <FactorySettingsForm />

      <FactoryStoryBoard
        v-if="status.board"
        :stories="status.board.stories"
        :selected="selectedKey"
        :now="now"
        @select="selectedKey = $event"
      />
      <p
        v-else
        class="text-xs text-fg-muted"
        data-testid="factory-board-reason"
      >
        {{ status.boardReason ?? 'No board yet.' }}
      </p>

      <FactoryStoryDetail
        v-if="selectedStory"
        :story="selectedStory"
        @close="selectedKey = null"
      />
    </template>
  </div>
</template>
