import { describe, it, expect, afterEach, vi } from 'vitest'
import { mountSuspended, registerEndpoint, mockNuxtImport } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import { setResponseStatus } from 'h3'
import { defineComponent, h } from 'vue'
import SettingsPasswordPanel from '~/components/settings/SettingsPasswordPanel.vue'
import ConfirmDialog from '~/components/ConfirmDialog.vue'
import { useConfirm } from '~/composables/useConfirm'

const navigateToMock = vi.hoisted(() => vi.fn())
mockNuxtImport('navigateTo', () => navigateToMock)

const Harness = defineComponent({
  setup() {
    return () => h('div', [h(SettingsPasswordPanel), h(ConfirmDialog)])
  },
})

afterEach(() => {
  const { _state, _resolve } = useConfirm()
  if (_state.open) _resolve(false)
  document.body.querySelectorAll('[role="dialog"]').forEach(el => el.remove())
})

describe('Settings — password reset that fails (JCLAW-1221)', () => {
  it('says why and stays on the page instead of doing nothing', async () => {
    const off = registerEndpoint('/api/auth/reset-password', {
      method: 'POST',
      handler: (event) => {
        setResponseStatus(event, 502)
        return '<html><body>Bad Gateway</body></html>'
      },
    })
    try {
      const c = await mountSuspended(Harness)
      await flushPromises()
      await c.findAll('button').find(b => b.text().trim() === 'Reset')!.trigger('click')
      await flushPromises()
      useConfirm()._resolve(true)

      await vi.waitFor(() => expect(c.find('[data-testid="api-error"]').exists()).toBe(true))
      expect(c.find('[data-testid="api-error"]').text()).toContain('/api/auth/reset-password')
      expect(navigateToMock).not.toHaveBeenCalledWith('/setup-password')
    }
    finally {
      off()
    }
  })
})
