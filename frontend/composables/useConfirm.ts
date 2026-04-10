import { reactive } from 'vue'

export type ConfirmVariant = 'default' | 'danger'

export interface ConfirmOptions {
  title?: string
  message: string
  confirmText?: string
  cancelText?: string
  variant?: ConfirmVariant
}

interface ConfirmState extends Required<ConfirmOptions> {
  open: boolean
  resolve: ((value: boolean) => void) | null
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
