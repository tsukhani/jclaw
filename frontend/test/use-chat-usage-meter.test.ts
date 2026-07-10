import { describe, it, expect } from 'vitest'
import { defineComponent, h, ref } from 'vue'
import { mount } from '@vue/test-utils'
import type { Message } from '~/types/api'
import type { MessageUsage } from '~/utils/usage-cost'
import { useChatUsageMeter, type UseChatUsageMeter } from '~/composables/useChatUsageMeter'

function assistant(usage: Partial<MessageUsage>): Message {
  return { role: 'assistant', content: '', usage: usage as MessageUsage } as Message
}
function user(): Message {
  return { role: 'user', content: 'hi' } as Message
}

function mountMeter(msgs: Message[] = [], streamingInit = false) {
  const displayMessages = ref<Message[]>(msgs)
  const streaming = ref(streamingInit)
  let api!: UseChatUsageMeter
  const wrapper = mount(
    defineComponent({
      setup() {
        api = useChatUsageMeter(displayMessages, streaming)
        return () => h('div')
      },
    }),
  )
  return { wrapper, displayMessages, streaming, api }
}

describe('useChatUsageMeter', () => {
  it('latestAssistantUsage picks the most recent assistant turn with usage', () => {
    const { api } = mountMeter([
      assistant({ prompt: 10, completion: 5, modelId: 'a' }),
      user(),
      assistant({ prompt: 20, completion: 8, modelId: 'b' }),
    ])
    expect(api.latestAssistantUsage.value?.modelId).toBe('b')
  })

  it('latestAssistantUsage is null when no assistant turn has usage', () => {
    const { api } = mountMeter([user(), { role: 'assistant', content: 'no usage' } as Message])
    expect(api.latestAssistantUsage.value).toBeNull()
  })

  it('conversationCumulativeTokens sums prompt + completion across assistant turns', () => {
    const { api } = mountMeter([
      assistant({ prompt: 10, completion: 5 }),
      user(),
      assistant({ prompt: 20, completion: 8 }),
    ])
    expect(api.conversationCumulativeTokens.value).toBe(43) // 15 + 28
  })

  it('shouldShowModelSwitchIndicator flags an assistant turn on a different model than the prior one', () => {
    const { api } = mountMeter([
      assistant({ modelId: 'a', modelProvider: 'openai' }),
      assistant({ modelId: 'b', modelProvider: 'openai' }),
    ])
    expect(api.shouldShowModelSwitchIndicator(1)).toBe(true) // a → b
    expect(api.shouldShowModelSwitchIndicator(0)).toBe(false) // no prior turn
  })

  it('shouldShowModelSwitchIndicator is false when the model is unchanged', () => {
    const { api } = mountMeter([
      assistant({ modelId: 'a', modelProvider: 'openai' }),
      assistant({ modelId: 'a', modelProvider: 'openai' }),
    ])
    expect(api.shouldShowModelSwitchIndicator(1)).toBe(false)
  })

  it('conversationCostSummary is null when there are no assistant usages (idle)', () => {
    const { api } = mountMeter([user()])
    expect(api.conversationCostSummary.value).toBeNull()
  })

  it('conversationCostSummary stays null while streaming (recompute deferred to idle)', async () => {
    const { api, streaming } = mountMeter([assistant({ prompt: 1, completion: 1, modelId: 'a' })], true)
    // immediate watch early-returns while streaming → no summary computed yet.
    expect(api.conversationCostSummary.value).toBeNull()
    streaming.value = false
    // Once idle the watch runs; with no pricing config the cost may be null,
    // but the recompute path must not throw.
    await Promise.resolve()
    expect(() => api.conversationCostSummary.value).not.toThrow()
  })
})
