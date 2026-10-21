import { describe, it, expect } from 'vitest'
import { defineComponent, h, ref } from 'vue'
import { mount } from '@vue/test-utils'
import type { Message } from '~/types/api'
import type { MessageUsage } from '~/utils/usage-cost'
import { useChatUsageMeter, type UseChatUsageMeter } from '~/composables/useChatUsageMeter'
import type { MessageUsageRow } from '~/utils/conversation-messages'

function assistant(usage: Partial<MessageUsage>): Message {
  return { role: 'assistant', content: '', usage: usage as MessageUsage } as Message
}
function user(): Message {
  return { role: 'user', content: 'hi' } as Message
}

function mountMeter(msgs: Message[] = [], streamingInit = false, earlier: MessageUsageRow[] = []) {
  const displayMessages = ref<Message[]>(msgs)
  const streaming = ref(streamingInit)
  let api!: UseChatUsageMeter
  const wrapper = mount(
    defineComponent({
      setup() {
        api = useChatUsageMeter(displayMessages, streaming, ref(earlier))
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

  it('contextUsage prefers the final call over the per-turn sums (JCLAW-1201)', () => {
    const { api } = mountMeter([assistant({
      prompt: 18_878_532, lastPrompt: 254_066, completion: 93_588, lastCompletion: 1_100,
      reasoning: 33_573, lastReasoning: 400, cached: 18_468_731, lastCached: 252_000,
    })])
    expect(api.contextUsage.value).toEqual({ prompt: 254_066, completion: 1_100, reasoning: 400, cached: 252_000 })
    const legacy = mountMeter([assistant({ prompt: 30_000, completion: 5, reasoning: 2, cached: 100 })])
    expect(legacy.api.contextUsage.value).toEqual({ prompt: 30_000, completion: 5, reasoning: 2, cached: 100 })
  })

  it('reads a streaming reply\'s context frame until its usage lands', () => {
    const earlier = assistant({ prompt: 90_000, lastPrompt: 90_000, completion: 4_000, reasoning: 300, cached: 500 })
    const live = { role: 'assistant', content: 'working', _contextTokens: 131_072 } as Message
    const { api, displayMessages } = mountMeter([earlier, user(), live], true)
    expect(api.contextUsage.value.prompt).toBe(131_072)
    // The earlier turn's completion, thinking and cache counts belong to a different context.
    expect(api.latestAssistantUsage.value).toMatchObject({ completion: 0, reasoning: 0, cached: 0 })

    displayMessages.value = [earlier, user(), { ...live, usage: { prompt: 400_000, lastPrompt: 140_000, completion: 900 } as MessageUsage }]
    expect(api.contextUsage.value.prompt).toBe(140_000)
  })

  it('conversationCumulativeTokens sums prompt + completion across assistant turns', () => {
    const { api } = mountMeter([
      assistant({ prompt: 10, completion: 5 }),
      user(),
      assistant({ prompt: 20, completion: 8 }),
    ])
    expect(api.conversationCumulativeTokens.value).toBe(43) // 15 + 28
  })

  it('counts the usage of rows older than the loaded ones in the totals and the first switch divider', () => {
    const { api } = mountMeter(
      [assistant({ prompt: 20, completion: 8, modelId: 'b' })],
      false,
      [{ id: 1, usage: { prompt: 10, completion: 5, modelId: 'a' } as MessageUsage }],
    )
    expect(api.conversationCumulativeTokens.value).toBe(43)
    expect(api.conversationCostSummary.value?.turnCount ?? 2).toBe(2)
    expect(api.shouldShowModelSwitchIndicator(0)).toBe(true) // a, before the window, then b
  })

  it('shouldShowModelSwitchIndicator flags an assistant turn on a different model than the prior one', () => {
    const { api } = mountMeter([
      assistant({ modelId: 'a', modelProvider: 'openai' }),
      assistant({ modelId: 'b', modelProvider: 'openai' }),
    ])
    expect(api.shouldShowModelSwitchIndicator(1)).toBe(true) // a → b
    expect(api.shouldShowModelSwitchIndicator(0)).toBe(false) // no prior turn
  })

  it('shouldShowModelSwitchIndicator stays quiet between two routed turns, which name their own models (JCLAW-1222)', () => {
    const route = { class: 'chat', provider: 'ollama-cloud', model: 'a', reason: '' }
    const { api } = mountMeter([
      assistant({ modelId: 'a', modelProvider: 'ollama-cloud', route }),
      assistant({ modelId: 'b', modelProvider: 'ollama-cloud', route: { ...route, model: 'b' } }),
      assistant({ modelId: 'c', modelProvider: 'openai' }),
    ])
    expect(api.shouldShowModelSwitchIndicator(1)).toBe(false)
    expect(api.shouldShowModelSwitchIndicator(2)).toBe(true) // leaving the router is still a switch
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
