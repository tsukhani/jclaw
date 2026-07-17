import { describe, it, expect } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import ChatModelCombobox from '~/components/ChatModelCombobox.vue'
import type { Provider } from '~/composables/useProviders'

/**
 * ChatModelCombobox — model selection.
 *
 * Guards the pick → emit contract the chat header relies on: choosing a row
 * emits `update:modelKey` as "<provider>::<modelId>", which the page routes to
 * onModelPicked (persist the model, then return the cursor to the composer).
 *
 * The focus return itself isn't asserted here — reka-ui's Popover teleport plus
 * jsdom focus handling is unreliable, the same reason chat-context-meter.test.ts
 * skips focus assertions. The emit that drives it is the stable, testable seam,
 * and it must keep firing after the close-auto-focus suppression added for the
 * focus fix.
 */
describe('ChatModelCombobox — model selection', () => {
  const providers: Provider[] = [
    {
      name: 'ollama-cloud',
      models: [
        { id: 'deepseek-v4-flash', name: 'deepseek-v4-flash' },
        { id: 'qwen3-coder', name: 'qwen3-coder' },
      ],
    },
  ]

  it('shows the current model on the trigger', async () => {
    const component = await mountSuspended(ChatModelCombobox, {
      props: { providers, modelKey: 'ollama-cloud::deepseek-v4-flash' },
    })
    expect(component.find('button').text()).toContain('deepseek-v4-flash')
  })

  it('emits "<provider>::<model>" when a row is picked', async () => {
    const component = await mountSuspended(ChatModelCombobox, {
      props: { providers, modelKey: 'ollama-cloud::deepseek-v4-flash' },
    })
    // Open the popover (reka Popover toggles open on trigger click).
    await component.find('button').trigger('click')
    await flushPromises()
    // Popover content teleports to the document body; click the qwen3-coder row.
    const rows = Array.from(document.body.querySelectorAll('button'))
    const target = rows.find(b => b.textContent?.includes('qwen3-coder'))
    expect(target, 'qwen3-coder row should render in the open popover').toBeTruthy()
    target!.dispatchEvent(new MouseEvent('click', { bubbles: true }))
    await flushPromises()
    expect(component.emitted('update:modelKey')?.[0]).toEqual(['ollama-cloud::qwen3-coder'])
  })
})
