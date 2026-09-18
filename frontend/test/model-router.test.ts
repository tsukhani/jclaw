import { describe, it, expect } from 'vitest'
import { routerProvider, type Provider } from '~/composables/useProviders'
import { latestRouteOf, routeDescription, routeOf } from '~/utils/model-route'
import type { Message } from '~/types/api'

function providers(...list: Provider[]): Map<string, Provider> {
  return new Map(list.map(p => [p.name, p]))
}

const cloud: Provider = {
  name: 'ollama-cloud',
  models: [
    { id: 'glm-5.3-flash', contextWindow: 1_048_576, supportsThinking: true, thinkingLevels: ['max', 'low'] },
    { id: 'kimi-k3', contextWindow: 262_144, supportsVision: true, supportsThinking: true, thinkingLevels: ['high'] },
  ],
}

describe('routerProvider (JCLAW-1222)', () => {
  it('is offered only once the chat list names a model', () => {
    expect(routerProvider([], providers(cloud))).toBeNull()
    const reasoningOnly = [{ key: 'router.reasoning.models', value: '[{"provider":"ollama-cloud","model":"kimi-k3"}]' }]
    expect(routerProvider(reasoningOnly, providers(cloud))).toBeNull()
  })

  it('advertises the union of what its models can do', () => {
    const entries = [
      { key: 'router.chat.models', value: '[{"provider":"ollama-cloud","model":"glm-5.3-flash"}]' },
      { key: 'router.agentic.models', value: '[{"provider":"ollama-cloud","model":"kimi-k3"},{"provider":"gone","model":"x"}]' },
      { key: 'router.coding.models', value: 'not json' },
    ]
    const router = routerProvider(entries, providers(cloud))!
    expect(router.name).toBe('router')
    expect(router.models).toHaveLength(1)
    const auto = router.models[0]!
    expect(auto.id).toBe('auto')
    expect(auto.contextWindow).toBe(1_048_576)
    expect(auto.supportsVision).toBe(true)
    expect(auto.thinkingLevels).toEqual(['low', 'high', 'max'])
  })
})

describe('route display helpers (JCLAW-1222)', () => {
  const route = { class: 'agentic', provider: 'ollama-cloud', model: 'glm-5.3-flash', reason: 'actions: send, schedule', downshifted: true }

  it('prefers the persisted route over the live one', () => {
    const m = { role: 'assistant', content: '', createdAt: '', usage: { route }, _route: { ...route, model: 'live' } } as unknown as Message
    expect(routeOf(m)?.model).toBe('glm-5.3-flash')
    expect(routeOf({ role: 'assistant', content: '', createdAt: '' } as Message)).toBeNull()
  })

  it('names the latest routed turn, and nothing before one exists', () => {
    const routed = (model: string) => ({ role: 'assistant', content: '', createdAt: '', _route: { ...route, model } }) as Message
    const plain = { role: 'user', content: 'hi', createdAt: '' } as Message
    expect(latestRouteOf([])).toBeNull()
    expect(latestRouteOf([plain])).toBeNull()
    expect(latestRouteOf([plain, routed('first'), plain, routed('second'), plain])?.model).toBe('second')
  })

  it('describes the class, the model and why it was bent', () => {
    const text = routeDescription(route)
    expect(text).toContain('Agent work → ollama-cloud/glm-5.3-flash')
    expect(text).toContain('lighter model')
    expect(text).toContain('actions: send, schedule')
  })
})
