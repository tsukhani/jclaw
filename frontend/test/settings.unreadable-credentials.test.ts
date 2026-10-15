import { describe, it, expect, beforeEach } from 'vitest'
import { mountSuspended, registerEndpoint } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import { clearNuxtData } from '#app'
import SettingsUnreadableCredentialsBanner from '~/components/settings/SettingsUnreadableCredentialsBanner.vue'

/**
 * Settings asks for re-entry of each stored credential that does not decrypt under this installation's secret
 * (JCLAW-1455), with a link to the page that takes it, and shows nothing while every one decrypts.
 */
let unreadable: Array<Record<string, string>> = []
registerEndpoint('/api/credentials/unreadable', { method: 'GET', handler: () => ({ unreadable }) })

async function mount() {
  const c = await mountSuspended(SettingsUnreadableCredentialsBanner)
  for (let i = 0; i < 4; i++) await flushPromises()
  return c
}

beforeEach(() => {
  clearNuxtData()
  unreadable = []
})

describe('Settings — credentials that do not decrypt', () => {
  it('shows nothing while every stored credential decrypts', async () => {
    const c = await mount()
    expect(c.find('[data-testid="unreadable-credentials"]').exists()).toBe(false)
  })

  it('names each one and links to where it is entered again', async () => {
    unreadable = [
      { store: 'config', owner: 'provider.openrouter.apiKey', field: 'provider.openrouter.apiKey', page: '/settings' },
      { store: 'config', owner: 'app.unknown.secret', field: 'app.unknown.secret', page: '/settings' },
      { store: 'telegram', owner: 'main', field: 'bot token', page: '/channels/telegram' },
      { store: 'mcp', owner: 'github', field: 'headers.Authorization', page: '/mcp-servers' },
    ]
    const c = await mount()
    const banner = c.find('[data-testid="unreadable-credentials"]')
    expect(banner.text()).toContain('4 stored credentials do not decrypt')
    expect(banner.text()).toContain('certs/.env')
    expect(banner.text()).toContain('provider.openrouter.apiKey')
    expect(banner.text()).toContain('telegram · main · bot token')
    expect(banner.text()).toContain('mcp · github · headers.Authorization')
    expect(banner.findAll('a').map(a => a.attributes('href'))).toEqual([
      '/settings?section=providers',
      '/settings',
      '/channels/telegram',
      '/mcp-servers',
    ])
  })
})
