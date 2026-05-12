<script setup lang="ts">
/**
 * API Tokens management section for the Settings page (JCLAW-282).
 *
 * Lives as a standalone component rather than inline in settings.vue
 * because the mint-once modal, copy-to-clipboard fallback, and per-row
 * revoke confirmation add enough complexity that putting it in the
 * already-3900-line settings.vue would hurt rather than help. The
 * containing page mounts it as a section between Password and the
 * Unmanaged-keys diagnostic.
 */
import { CheckIcon, ClipboardIcon, TrashIcon } from '@heroicons/vue/24/outline'
import { computed, ref } from 'vue'
import type { ApiToken } from '~/types/api'

const { data: tokens, refresh, pending } = await useFetch<ApiToken[]>(
  '/api/api-tokens',
  { default: () => [] },
)

const { mutate, loading: mutating } = useApiMutation()
const { confirm } = useConfirm()

const newTokenName = ref('')
const newTokenScope = ref<'READ_ONLY' | 'FULL'>('READ_ONLY')

// Plaintext from the most recent successful mint. Held only in this
// ref — never persisted, never replayed. Cleared when the operator
// acknowledges the modal so an accidental browser screenshot afterward
// doesn't leak it. The backend cannot recover this value if lost.
const justMinted = ref<ApiToken | null>(null)
const copyAcknowledged = ref(false)

const activeTokens = computed(() => (tokens.value ?? []).filter(t => !t.revokedAt))
const revokedTokens = computed(() => (tokens.value ?? []).filter(t => t.revokedAt))

async function handleMint() {
  if (!newTokenName.value.trim()) return
  const result = await mutate<ApiToken>('/api/api-tokens', {
    method: 'POST',
    body: { name: newTokenName.value.trim(), scope: newTokenScope.value },
  })
  if (result?.plaintext) {
    justMinted.value = result
    copyAcknowledged.value = false
    newTokenName.value = ''
    newTokenScope.value = 'READ_ONLY'
    await refresh()
  }
}

async function handleCopy() {
  if (!justMinted.value?.plaintext) return
  // The Clipboard API is the modern path; fall through to the legacy
  // execCommand on insecure-context or old-browser stacks rather than
  // silently failing, because losing the plaintext here forces the
  // operator to revoke + remint from scratch.
  try {
    await navigator.clipboard.writeText(justMinted.value.plaintext)
    copyAcknowledged.value = true
  }
  catch {
    const el = document.createElement('textarea')
    el.value = justMinted.value.plaintext
    el.setAttribute('readonly', '')
    el.style.position = 'absolute'
    el.style.left = '-9999px'
    document.body.appendChild(el)
    el.select()
    document.execCommand('copy')
    document.body.removeChild(el)
    copyAcknowledged.value = true
  }
}

function dismissMintedModal() {
  justMinted.value = null
  copyAcknowledged.value = false
}

async function handleRevoke(token: ApiToken) {
  const ok = await confirm({
    title: 'Revoke API token?',
    message: `Revoking "${token.name}" (${token.displayPrefix}…) immediately rejects every `
      + `further request that uses this token. This is reversible only by minting a new token.`,
    confirmText: 'Revoke',
    variant: 'danger',
  })
  if (!ok) return
  await mutate(`/api/api-tokens/${token.id}`, { method: 'DELETE' })
  await refresh()
}

function formatDate(iso: string | null): string {
  if (!iso) return '—'
  try {
    return new Date(iso).toLocaleString()
  }
  catch {
    return iso
  }
}
</script>

<template>
  <div class="mb-6 space-y-4">
    <h2 class="text-sm font-medium text-fg-muted">
      API Tokens
    </h2>
    <p class="text-xs text-fg-muted">
      Bearer credentials for the JClaw MCP server and any out-of-process API client. The
      plaintext is shown <strong>once</strong> at creation and stored as a SHA-256 hash thereafter.
      Read-only tokens reject mutating verbs (POST, PUT, DELETE) at the auth filter; full-scope
      tokens carry the same authority as a logged-in admin.
    </p>

    <!-- Mint form -->
    <div class="bg-surface-elevated border border-border">
      <div class="px-4 py-3 border-b border-border">
        <h3 class="text-xs font-medium text-fg-strong">
          Mint new token
        </h3>
      </div>
      <div class="px-4 py-3 flex flex-wrap items-end gap-3">
        <label class="flex flex-col gap-1">
          <span class="text-[11px] text-fg-muted">Name</span>
          <input
            v-model="newTokenName"
            type="text"
            placeholder="e.g. claude-desktop"
            maxlength="100"
            class="w-64 px-2 py-1.5 text-xs bg-surface border border-border focus:border-ring focus:outline-none text-fg-primary"
            aria-label="New API token name"
            @keyup.enter="handleMint"
          >
        </label>
        <label class="flex flex-col gap-1">
          <span class="text-[11px] text-fg-muted">Scope</span>
          <select
            v-model="newTokenScope"
            class="px-2 py-1.5 text-xs bg-surface border border-border focus:border-ring focus:outline-none text-fg-primary"
            aria-label="New API token scope"
          >
            <option value="READ_ONLY">
              Read-only (GET only)
            </option>
            <option value="FULL">
              Full (all verbs)
            </option>
          </select>
        </label>
        <button
          :disabled="!newTokenName.trim() || mutating"
          class="px-3 py-1.5 text-xs font-medium text-white bg-emerald-600 hover:bg-emerald-700
                 disabled:bg-emerald-600/40 disabled:cursor-not-allowed transition-colors"
          @click="handleMint"
        >
          {{ mutating ? 'Minting…' : 'Mint token' }}
        </button>
      </div>
    </div>

    <!-- Token list -->
    <div class="bg-surface-elevated border border-border">
      <div class="px-4 py-3 border-b border-border flex items-center justify-between">
        <h3 class="text-xs font-medium text-fg-strong">
          Active tokens ({{ activeTokens.length }})
        </h3>
        <span
          v-if="pending"
          class="text-[11px] text-fg-muted"
        >Loading…</span>
      </div>
      <div
        v-if="activeTokens.length === 0 && !pending"
        class="px-4 py-3 text-xs text-fg-muted"
      >
        No active tokens. Mint one above to authenticate the JClaw MCP server.
      </div>
      <ul
        v-else
        class="divide-y divide-border"
      >
        <li
          v-for="t in activeTokens"
          :key="t.id"
          class="px-4 py-2.5 flex items-center gap-3"
        >
          <div class="flex-1 min-w-0">
            <div class="flex items-center gap-2">
              <span class="text-sm font-medium text-fg-strong truncate">{{ t.name }}</span>
              <span class="text-[11px] px-1.5 py-0.5 font-mono text-fg-muted bg-muted border border-border">
                {{ t.scope === 'FULL' ? 'full' : 'read-only' }}
              </span>
            </div>
            <div class="mt-0.5 text-[11px] text-fg-muted font-mono truncate">
              {{ t.displayPrefix }}…
              · created {{ formatDate(t.createdAt) }}
              · last used {{ formatDate(t.lastUsedAt) }}
            </div>
          </div>
          <button
            class="p-1 text-fg-muted hover:text-red-600 dark:hover:text-red-400 transition-colors"
            title="Revoke"
            :aria-label="`Revoke token ${t.name}`"
            @click="handleRevoke(t)"
          >
            <TrashIcon
              class="w-3.5 h-3.5"
              aria-hidden="true"
            />
          </button>
        </li>
      </ul>
    </div>

    <!-- Revoked tokens (collapsed history) -->
    <details
      v-if="revokedTokens.length > 0"
      class="bg-surface-elevated border border-border"
    >
      <summary class="px-4 py-2.5 text-xs text-fg-muted cursor-pointer hover:text-fg-strong">
        Revoked tokens ({{ revokedTokens.length }})
      </summary>
      <ul class="divide-y divide-border border-t border-border">
        <li
          v-for="t in revokedTokens"
          :key="t.id"
          class="px-4 py-2 text-[11px] text-fg-muted"
        >
          <span class="font-mono">{{ t.displayPrefix }}…</span>
          <span class="ml-2">{{ t.name }}</span>
          <span class="ml-2">revoked {{ formatDate(t.revokedAt) }}</span>
        </li>
      </ul>
    </details>

    <!-- Mint-success modal: shows plaintext exactly once. -->
    <Teleport
      v-if="justMinted"
      to="body"
    >
      <!-- eslint-disable-next-line vuejs-accessibility/click-events-have-key-events, vuejs-accessibility/no-static-element-interactions -- modal backdrop matches ConfirmDialog convention -->
      <div
        class="fixed inset-0 z-50 flex items-center justify-center bg-black/60 backdrop-blur-sm"
        role="dialog"
        aria-modal="true"
        aria-labelledby="api-token-mint-title"
        @click.self="copyAcknowledged && dismissMintedModal()"
      >
        <div class="w-full max-w-lg mx-4 bg-surface-elevated border border-border shadow-2xl">
          <div class="px-5 py-4 border-b border-border">
            <h2
              id="api-token-mint-title"
              class="text-sm font-semibold text-fg-strong"
            >
              Token minted — copy it now
            </h2>
          </div>
          <div class="px-5 py-4 space-y-3">
            <p class="text-xs text-fg-primary leading-relaxed">
              This is the only time the full token is shown. JClaw stores only its SHA-256
              hash. If you close this dialog without copying, you'll need to revoke this
              token and mint a new one.
            </p>
            <div class="flex items-stretch gap-0 border border-border bg-muted">
              <code class="flex-1 px-2 py-2 font-mono text-xs break-all text-fg-strong select-all">{{
                justMinted.plaintext
              }}</code>
              <button
                class="px-3 flex items-center gap-1.5 text-xs font-medium border-l border-border
                       hover:bg-surface-elevated transition-colors text-fg-strong"
                @click="handleCopy"
              >
                <CheckIcon
                  v-if="copyAcknowledged"
                  class="w-3.5 h-3.5 text-emerald-600 dark:text-emerald-400"
                  aria-hidden="true"
                />
                <ClipboardIcon
                  v-else
                  class="w-3.5 h-3.5"
                  aria-hidden="true"
                />
                {{ copyAcknowledged ? 'Copied' : 'Copy' }}
              </button>
            </div>
          </div>
          <div class="px-5 py-3 border-t border-border flex items-center justify-end gap-2">
            <button
              type="button"
              :disabled="!copyAcknowledged"
              :title="copyAcknowledged ? '' : 'Copy the token first'"
              class="px-3 py-1.5 text-xs border border-emerald-200 dark:border-emerald-900/60
                     bg-emerald-50 dark:bg-emerald-950/40 text-emerald-700 dark:text-emerald-300
                     hover:bg-emerald-100 dark:hover:bg-emerald-900/40
                     disabled:opacity-40 disabled:cursor-not-allowed transition-colors"
              @click="dismissMintedModal"
            >
              Done
            </button>
          </div>
        </div>
      </div>
    </Teleport>
  </div>
</template>
