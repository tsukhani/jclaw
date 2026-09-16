<script setup lang="ts">
// Tool Approvals (JCLAW-1022). One instance-wide control: what a dangerous action does
// when it has no way to ask the operator. Deliberately its own section rather than part
// of Shell Execution — DangerousActionGate governs the exec tool, but the same policy
// also decides whether SubagentAcpRunner may launch the coding harness (JCLAW-669), and
// a reader of a "Shell Execution" panel would not expect to be changing that.
//
// JCLAW-1062 adds a read-only roll-up of standing grants below that control. Revoke
// lives on each agent's page, since a grant is per-agent — but "does anything still
// hold a standing grant?" is an instance-wide question, and answering it by opening
// every agent in turn is exactly the gap that ticket exists to close.
import SettingsConfigField from './SettingsConfigField.vue'

const { configData, saving, refresh } = useSettingsConfig()

interface AgentGrants { agentId: number, agentName: string, tools: string[] }
interface GrantSummary { totalGrants: number, agentsWithGrants: number, agents: AgentGrants[] }

// No await: a suspending settings panel stalls the whole page on cold boot.
const { data: summary } = useLazyFetch<GrantSummary>('/api/tool-approvals/summary')

const POLICY_KEY = 'tool.approval.offChannelPolicy'

const POLICIES = [
  {
    value: 'allow',
    label: 'Allow',
    detail: 'Run it. Applies to your own turns only — an origin that cannot ask you still fails closed.',
  },
  {
    value: 'ask',
    label: 'Ask',
    detail: 'Send a confirmation to the agent’s bound Telegram DM. Fails closed if there is none.',
  },
  {
    value: 'deny',
    label: 'Deny',
    detail: 'Refuse it, on every origin.',
  },
] as const

// 'allow' matches DangerousActionGate's DEFAULT_OFF_CHANNEL_POLICY, so an instance that
// never set the key reads the same here as it behaves.
const policy = computed(() =>
  configData.value?.entries?.find(e => e.key === POLICY_KEY)?.value?.toLowerCase() ?? 'allow')

const selected = ref(policy.value)
watch(policy, (v) => {
  selected.value = v
})

const error = ref<string | null>(null)

async function save(value: string) {
  saving.value = true
  error.value = null
  try {
    await $fetch('/api/config', { method: 'POST', body: { key: POLICY_KEY, value } })
    refresh()
  }
  catch (e) {
    // A ceiling in application.conf refuses a loosening value with a 403 naming it
    // (JCLAW-1022). Surface that instead of silently reverting the select.
    error.value = apiErrorDetails(e, 'Could not save the approval policy.').message
    selected.value = policy.value
  }
  finally {
    saving.value = false
  }
}
</script>

<template>
  <div class="mb-6 space-y-4">
    <h2 class="text-sm font-medium text-fg-muted">
      Tool Approvals
    </h2>
    <p class="text-xs text-fg-muted">
      What a dangerous action does when it cannot reach you for approval — the shell
      tool, and launching a coding-harness subagent. This decides your own turns: when
      someone else messages the agent on Telegram or Slack and that agent has a working
      binding, you are asked there regardless of this setting.
    </p>

    <div class="space-y-2">
      <label
        for="approval-off-channel-policy"
        class="block"
      >
        <span class="block text-xs font-medium text-fg-strong mb-1">No approval surface</span>
        <select
          id="approval-off-channel-policy"
          v-model="selected"
          class="w-full px-2 py-1.5 text-sm bg-surface border border-input text-fg-strong"
          data-testid="approval-off-channel-policy"
          :disabled="saving"
          @change="save(selected)"
        >
          <option
            v-for="p in POLICIES"
            :key="p.value"
            :value="p.value"
          >
            {{ p.label }} — {{ p.detail }}
          </option>
        </select>
      </label>

      <p
        v-if="error"
        class="text-xs text-danger"
        data-testid="approval-policy-error"
      >
        {{ error }}
      </p>

      <p class="text-xs text-fg-muted">
        An agent that you have previously granted &ldquo;always allow&rdquo; for a tool
        runs it without a prompt on any origin, independently of this setting.
      </p>

      <div class="bg-surface-elevated border border-border">
        <SettingsConfigField
          config-key="telegram.approval.timeout-seconds"
          label="promptTimeoutSeconds"
          kind="number"
          fallback="300"
          :min="1"
          tip="How long an approval prompt in Telegram or Slack waits for your answer before it expires unanswered. Minimum 1."
        />
      </div>

      <!-- JCLAW-1062 roll-up. Read-only by design: revoke belongs on the agent's own
           page, since a grant is per-agent. This answers the question that page cannot —
           whether anything still holds one. -->
      <div
        v-if="summary"
        class="pt-2 border-t border-border space-y-2"
        data-testid="standing-grants-rollup"
      >
        <p
          v-if="!summary.totalGrants"
          class="text-xs text-fg-muted"
        >
          No standing grants. Every dangerous action is prompted.
        </p>
        <template v-else>
          <p class="text-xs text-fg-strong">
            {{ summary.totalGrants }} standing
            {{ summary.totalGrants === 1 ? 'grant' : 'grants' }} across
            {{ summary.agentsWithGrants }}
            {{ summary.agentsWithGrants === 1 ? 'agent' : 'agents' }}.
          </p>
          <ul class="space-y-1">
            <li
              v-for="a in summary.agents"
              :key="a.agentId"
              class="text-xs text-fg-muted"
            >
              <NuxtLink
                :to="`/agents/${a.agentName}`"
                class="text-fg-strong underline underline-offset-2 hover:no-underline"
              >{{ a.agentName }}</NuxtLink>
              — <span class="font-mono">{{ a.tools.join(', ') }}</span>
            </li>
          </ul>
          <p class="text-xs text-fg-muted">
            Revoke from the agent&rsquo;s page.
          </p>
        </template>
      </div>
    </div>
  </div>
</template>
