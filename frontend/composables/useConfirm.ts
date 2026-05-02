import { reactive } from 'vue'

export type ConfirmVariant = 'default' | 'danger'

export interface ConfirmOptions {
  title?: string
  message: string
  confirmText?: string
  cancelText?: string
  variant?: ConfirmVariant
  /**
   * When set, the confirm button is disabled until the user types this exact
   * string into a text field rendered above the buttons. Used as a footgun
   * guard for irreversible destructive actions like wiping all conversations.
   * Comparison is case-sensitive and ignores leading/trailing whitespace.
   */
  requireText?: string
}

interface ConfirmState extends Required<Omit<ConfirmOptions, 'requireText'>> {
  open: boolean
  resolve: ((value: boolean) => void) | null
  requireText: string | null
}

// Module-level singleton state — a single <ConfirmDialog /> mounted at the
// app root reads this and every useConfirm() caller writes to it.
const state = reactive<ConfirmState>({
  open: false,
  title: '',
  message: '',
  confirmText: 'Confirm',
  cancelText: 'Cancel',
  variant: 'default',
  requireText: null,
  resolve: null,
})

function confirm(opts: ConfirmOptions): Promise<boolean> {
  // If a prior dialog is still open (shouldn't normally happen), resolve it
  // as cancelled before replacing.
  if (state.open && state.resolve) state.resolve(false)

  state.title = opts.title ?? ''
  state.message = opts.message
  state.confirmText = opts.confirmText ?? 'Confirm'
  state.cancelText = opts.cancelText ?? 'Cancel'
  state.variant = opts.variant ?? 'default'
  state.requireText = opts.requireText ?? null
  state.open = true

  return new Promise<boolean>((resolve) => {
    state.resolve = resolve
  })
}

function _resolve(value: boolean) {
  if (state.resolve) state.resolve(value)
  state.resolve = null
  state.open = false
}

export function useConfirm() {
  return { confirm, _state: state, _resolve }
}
