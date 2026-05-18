import { describe, it, expect, afterEach } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import ConfirmDialog from '~/components/ConfirmDialog.vue'

/**
 * JCLAW-314 — ConfirmDialog coverage.
 *
 * The dialog is driven by useConfirm()'s module-level reactive state:
 * calling `confirm({ ... })` opens the dialog and returns a Promise that
 * resolves true (confirm) or false (cancel). Tests exercise the four
 * exit paths the JIRA AC specifies: confirm button, cancel button,
 * Escape key, and backdrop click.
 */

// The dialog teleports to <body>; query helpers consistently look there.
function getDialog(): HTMLElement | null {
  return document.body.querySelector<HTMLElement>('[role="dialog"]')
}

function findButton(label: string): HTMLButtonElement | null {
  const buttons = Array.from(document.body.querySelectorAll<HTMLButtonElement>('button'))
  return buttons.find(b => (b.textContent ?? '').trim() === label) ?? null
}

afterEach(() => {
  // Strip any teleported nodes so leaked state from one case doesn't bleed
  // into the next. Also reset the singleton state in case a test left it
  // open (e.g. a thrown assertion before reaching the close path).
  document.body.replaceChildren()
  const { _state, _resolve } = useConfirm()
  if (_state.open) _resolve(false)
})

describe('ConfirmDialog — visibility lifecycle', () => {
  it('is not rendered when state.open is false', async () => {
    await mountSuspended(ConfirmDialog)
    await flushPromises()
    expect(getDialog()).toBeNull()
  })

  it('renders title, message, and the two action buttons when opened via useConfirm', async () => {
    await mountSuspended(ConfirmDialog)
    const { confirm } = useConfirm()
    // Fire-and-forget — the promise resolves when the user (or test) acts.
    void confirm({
      title: 'Delete agent',
      message: 'This permanently removes the agent.',
      confirmText: 'Delete',
      cancelText: 'Keep',
    })
    await flushPromises()
    const dialog = getDialog()
    expect(dialog).not.toBeNull()
    const text = dialog!.textContent ?? ''
    expect(text).toContain('Delete agent')
    expect(text).toContain('This permanently removes the agent.')
    expect(findButton('Delete')).not.toBeNull()
    expect(findButton('Keep')).not.toBeNull()
  })

  it('renders the default "Confirm" header when no title is supplied', async () => {
    await mountSuspended(ConfirmDialog)
    const { confirm } = useConfirm()
    void confirm({ message: 'are you sure?' })
    await flushPromises()
    const text = getDialog()?.textContent ?? ''
    expect(text).toContain('Confirm')
    expect(text).toContain('are you sure?')
  })
})

describe('ConfirmDialog — confirm / cancel resolution', () => {
  it('resolves the confirm promise with true when the confirm button is clicked', async () => {
    await mountSuspended(ConfirmDialog)
    const { confirm } = useConfirm()
    const promise = confirm({ message: 'proceed?', confirmText: 'Yes' })
    await flushPromises()
    findButton('Yes')!.click()
    const result = await promise
    expect(result).toBe(true)
  })

  it('resolves the confirm promise with false when the cancel button is clicked', async () => {
    await mountSuspended(ConfirmDialog)
    const { confirm } = useConfirm()
    const promise = confirm({ message: 'proceed?', confirmText: 'Yes', cancelText: 'No' })
    await flushPromises()
    findButton('No')!.click()
    const result = await promise
    expect(result).toBe(false)
  })

  it('resolves with false when Escape is pressed', async () => {
    await mountSuspended(ConfirmDialog)
    const { confirm } = useConfirm()
    const promise = confirm({ message: 'abandon?' })
    await flushPromises()
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }))
    const result = await promise
    expect(result).toBe(false)
  })

  it('resolves with true when Enter is pressed (without a requireText gate)', async () => {
    await mountSuspended(ConfirmDialog)
    const { confirm } = useConfirm()
    const promise = confirm({ message: 'go?' })
    await flushPromises()
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter' }))
    const result = await promise
    expect(result).toBe(true)
  })

  it('resolves with false when the backdrop is clicked', async () => {
    await mountSuspended(ConfirmDialog)
    const { confirm } = useConfirm()
    const promise = confirm({ message: 'backdrop test' })
    await flushPromises()
    // The backdrop is the outer dialog element; @click.self only fires when
    // the click target IS the backdrop, not a descendant. Dispatch a click
    // event whose target is the backdrop itself to simulate that.
    const backdrop = getDialog()!
    backdrop.click()
    const result = await promise
    expect(result).toBe(false)
  })
})

describe('ConfirmDialog — danger variant styling', () => {
  it('applies danger-variant classes when opts.variant is "danger"', async () => {
    await mountSuspended(ConfirmDialog)
    const { confirm } = useConfirm()
    void confirm({ message: 'destructive', variant: 'danger', confirmText: 'Delete' })
    await flushPromises()
    const btn = findButton('Delete')
    expect(btn).not.toBeNull()
    // The variant flips the confirm button's color tokens to red.
    expect(btn!.className).toContain('text-red-700')
  })
})

describe('ConfirmDialog — requireText gate', () => {
  it('disables the confirm button until the user types the exact phrase', async () => {
    await mountSuspended(ConfirmDialog)
    const { confirm, _state } = useConfirm()
    void confirm({
      message: 'wipe all conversations',
      confirmText: 'Wipe',
      requireText: 'delete',
    })
    await flushPromises()

    const btn = findButton('Wipe')!
    expect(btn.disabled).toBe(true)

    // Type the required phrase into the dialog input.
    const input = document.body.querySelector<HTMLInputElement>('[role="dialog"] input')!
    input.value = 'delete'
    input.dispatchEvent(new Event('input'))
    await flushPromises()
    expect(btn.disabled).toBe(false)

    // Close the dialog so the singleton state is clean for the next test.
    btn.click()
    await flushPromises()
    expect(_state.open).toBe(false)
  })

  it('keeps confirm disabled when the typed phrase does not match', async () => {
    await mountSuspended(ConfirmDialog)
    const { confirm } = useConfirm()
    const promise = confirm({
      message: 'wipe',
      confirmText: 'Wipe',
      requireText: 'delete',
    })
    await flushPromises()
    const input = document.body.querySelector<HTMLInputElement>('[role="dialog"] input')!
    input.value = 'wrong'
    input.dispatchEvent(new Event('input'))
    await flushPromises()

    // Enter must NOT resolve the dialog while the gate is unsatisfied; the
    // global keydown handler short-circuits when !requireSatisfied.
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter' }))
    await flushPromises()
    // Cancel to settle the dangling promise (and verify the resolver still
    // works on the cancel path even though confirm was gated).
    findButton('Cancel')!.click()
    const result = await promise
    expect(result).toBe(false)
  })
})
