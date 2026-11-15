import { afterEach, describe, expect, it, vi } from 'vitest'
import { mountSuspended, registerEndpoint } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import { defineComponent, h } from 'vue'
import { clearNuxtData } from '#app'
import ConfirmDialog from '~/components/ConfirmDialog.vue'
import SettingsUnmanagedBanner from '~/components/settings/SettingsUnmanagedBanner.vue'
import type { ConfigEntry } from '~/types/api'

// The banner reads the store the Settings page provides; ConfirmDialog is mounted at the app root in production.
const Harness = defineComponent({
  async setup() {
    const { asyncConfig } = useProvideSettingsConfig()
    await asyncConfig
    return () => h('div', [h(SettingsUnmanagedBanner), h(ConfirmDialog)])
  },
})

const unregister: Array<() => void> = []

function serve(entries: () => ConfigEntry[]) {
  unregister.push(registerEndpoint('/api/config', { method: 'GET', handler: () => ({ entries: entries() }) }))
  unregister.push(registerEndpoint('/api/providers', () => []))
}

function serveDelete(key: string, onDelete: () => void, status = 200) {
  unregister.push(registerEndpoint(`/api/config/${encodeURIComponent(key)}`, {
    method: 'DELETE',
    handler: (event) => {
      onDelete()
      if (status !== 200) {
        event.node.res.statusCode = status
        return { error: 'forbidden', message: 'That key cannot be deleted here.' }
      }
      return { status: 'ok', key }
    },
  }))
}

async function mountBanner() {
  const wrapper = await mountSuspended(Harness)
  await flushPromises()
  return wrapper
}

async function openUnused(wrapper: Awaited<ReturnType<typeof mountBanner>>) {
  await wrapper.find('[data-testid="unused-banner-toggle"]').trigger('click')
  await flushPromises()
}

function unusedKeys(wrapper: Awaited<ReturnType<typeof mountBanner>>): string[] {
  return wrapper.findAll('[data-testid="unused-row"]').map(r => r.find('span').text())
}

function dialog(): HTMLElement | null {
  return document.body.querySelector('[role="dialog"]')
}

function dialogButton(label: string): HTMLButtonElement {
  const button = [...(dialog()?.querySelectorAll('button') ?? [])].find(b => b.textContent?.trim() === label)
  if (!button) throw new Error(`no ${label} button in the dialog`)
  return button as HTMLButtonElement
}

afterEach(() => {
  const { _state, _resolve } = useConfirm()
  if (_state.open) _resolve(false)
  while (unregister.length) unregister.pop()!()
  clearNuxtData()
})

describe('Settings — unused config keys (JF-440)', () => {
  it('lists only the rows the server marks unknown, under a managed prefix too', async () => {
    serve(() => [
      { key: 'chat.compaction.onToolRound', value: 'false', known: false },
      { key: 'zzz.leftover.flag', value: '1', known: false },
      { key: 'chat.maxToolRounds', value: '40', known: true },
      { key: 'chat.fixtureWithoutAFlag', value: 'x' },
    ])
    const wrapper = await mountBanner()

    expect(wrapper.find('[data-testid="unused-banner-toggle"]').text()).toContain('2 unused config keys')
    await openUnused(wrapper)
    expect(unusedKeys(wrapper)).toEqual(['chat.compaction.onToolRound', 'zzz.leftover.flag'])
    expect(wrapper.find('[data-testid="unmanaged-banner-toggle"]').exists()).toBe(false)
  })

  it('keeps the unmanaged list for rows the code reads outside a managed prefix', async () => {
    serve(() => [
      { key: 'groupChat.historyLimit', value: '30', known: true },
      { key: 'zzz.leftover.flag', value: '1', known: false },
    ])
    const wrapper = await mountBanner()

    expect(wrapper.find('[data-testid="unmanaged-banner-toggle"]').text()).toContain('1 unmanaged config key')
    expect(wrapper.find('[data-testid="unused-banner-toggle"]').text()).toContain('1 unused config key')
  })

  it('renders nothing when every row is read and managed', async () => {
    serve(() => [{ key: 'chat.maxToolRounds', value: '40', known: true }])
    const wrapper = await mountBanner()

    expect(wrapper.find('[data-testid="unused-banner"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="unmanaged-banner-toggle"]').exists()).toBe(false)
  })

  it('deletes a row once the operator confirms, and the list refreshes without it', async () => {
    let rows: ConfigEntry[] = [
      { key: 'transcription.language', value: 'en', known: false },
      { key: 'zzz.leftover.flag', value: '1', known: false },
    ]
    serve(() => rows)
    const deleted: string[] = []
    serveDelete('transcription.language', () => {
      deleted.push('transcription.language')
      rows = rows.filter(r => r.key !== 'transcription.language')
    })
    const wrapper = await mountBanner()
    await openUnused(wrapper)

    await wrapper.find('[aria-label="Delete transcription.language"]').trigger('click')
    await flushPromises()
    expect(dialog()?.textContent).toContain(
      'Delete transcription.language? JClaw found no code that reads it. This cannot be undone.')
    dialogButton('Delete').click()
    await flushPromises()
    await flushPromises()

    expect(deleted).toEqual(['transcription.language'])
    await vi.waitFor(() => expect(unusedKeys(wrapper)).toEqual(['zzz.leftover.flag']))
  })

  it('cancelling the confirmation deletes nothing', async () => {
    serve(() => [{ key: 'zzz.leftover.flag', value: '1', known: false }])
    const deleted: string[] = []
    serveDelete('zzz.leftover.flag', () => deleted.push('zzz.leftover.flag'))
    const wrapper = await mountBanner()
    await openUnused(wrapper)

    await wrapper.find('[aria-label="Delete zzz.leftover.flag"]').trigger('click')
    await flushPromises()
    dialogButton('Cancel').click()
    await flushPromises()

    expect(deleted).toEqual([])
    expect(unusedKeys(wrapper)).toEqual(['zzz.leftover.flag'])
  })

  it('warns that a credential cannot be recovered, and masks its value', async () => {
    serve(() => [{ key: 'provider.gone.apiKey', value: 'sk-live-0123456789', known: false }])
    const wrapper = await mountBanner()
    await openUnused(wrapper)

    expect(wrapper.find('[data-testid="unused-row"]').text()).not.toContain('sk-live')
    await wrapper.find('[aria-label="Delete provider.gone.apiKey"]').trigger('click')
    await flushPromises()
    expect(dialog()?.textContent).toContain('Delete provider.gone.apiKey? JClaw found no code that reads it. '
      + 'The stored credential goes with it and cannot be recovered.')
  })

  it('sends a key holding a slash encoded, as the route needs', async () => {
    const key = 'jtokkit.safetyMultiplier.gone.vendor/model-1'
    let rows: ConfigEntry[] = [{ key, value: '1.1', known: false }]
    serve(() => rows)
    const deleted: string[] = []
    serveDelete(key, () => {
      deleted.push(key)
      rows = []
    })
    const wrapper = await mountBanner()
    await openUnused(wrapper)

    await wrapper.find(`[aria-label="Delete ${key}"]`).trigger('click')
    await flushPromises()
    dialogButton('Delete').click()
    await flushPromises()
    await flushPromises()

    expect(deleted).toEqual([key])
    await vi.waitFor(() => expect(wrapper.find('[data-testid="unused-banner"]').exists()).toBe(false))
  })

  it('shows why a delete failed and keeps the row', async () => {
    serve(() => [{ key: 'zzz.leftover.flag', value: '1', known: false }])
    serveDelete('zzz.leftover.flag', () => {}, 403)
    const wrapper = await mountBanner()
    await openUnused(wrapper)

    await wrapper.find('[aria-label="Delete zzz.leftover.flag"]').trigger('click')
    await flushPromises()
    dialogButton('Delete').click()
    await flushPromises()
    await flushPromises()

    expect(wrapper.find('[role="alert"]').text()).toBe('That key cannot be deleted here.')
    expect(unusedKeys(wrapper)).toEqual(['zzz.leftover.flag'])
  })
})
