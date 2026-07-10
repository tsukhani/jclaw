<script setup lang="ts">
// Password / account management settings panel (JCLAW-680). Resets the admin
// password hash in the Config DB and signs the operator out. Moved verbatim from
// pages/settings.vue; owns its own useAuth() / useConfirm() composable calls.

// ──────────────────── Password / account management ─────────────────────
const { resetPassword } = useAuth()
const { confirm } = useConfirm()
const resettingPassword = ref(false)

async function handleResetPassword() {
  // Destructive — confirm via the shared in-app dialog (same pattern as
  // the conversation-delete flow) rather than window.confirm, which
  // looks like a browser alert and escapes theming. On success the
  // backend clears the session too, so the very next request is
  // unauthenticated and the auth middleware routes to /setup-password.
  const ok = await confirm({
    title: 'Reset admin password',
    message: 'This wipes the stored password from the database and signs you out. '
      + 'On next access you\'ll be taken to the setup screen to choose a new password.',
    confirmText: 'Reset',
    variant: 'danger',
  })
  if (!ok) return
  resettingPassword.value = true
  const success = await resetPassword()
  resettingPassword.value = false
  if (success) {
    navigateTo('/setup-password')
  }
}
</script>

<template>
  <!-- Password / account management -->
  <div class="mb-6 space-y-4">
    <h2 class="text-sm font-medium text-fg-muted">
      Password
    </h2>
    <p class="text-xs text-fg-muted">
      The admin password is stored as a PBKDF2-SHA256 hash in the Config DB. Resetting wipes the
      stored hash and signs you out — on the next access you'll be routed to the setup screen to
      choose a new password.
    </p>
    <div class="bg-surface-elevated border border-border">
      <div class="px-4 py-2.5 flex items-center justify-between gap-4">
        <div class="min-w-0">
          <span class="text-sm font-medium text-fg-strong">Reset password</span>
          <div class="text-xs text-fg-muted mt-0.5">
            Wipe the stored hash and return to the setup flow.
          </div>
        </div>
        <button
          :disabled="resettingPassword"
          class="shrink-0 px-3 py-1.5 text-xs font-medium text-white
                 bg-red-600 hover:bg-red-700 disabled:bg-red-600/40
                 disabled:cursor-not-allowed rounded-full transition-colors"
          @click="handleResetPassword"
        >
          {{ resettingPassword ? 'Resetting…' : 'Reset' }}
        </button>
      </div>
    </div>
  </div>
</template>
