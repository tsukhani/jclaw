import { describe, it, expect } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import { nextTick } from 'vue'
import ModelCapabilityPills from '~/components/ModelCapabilityPills.vue'
import { findProviderModel, type Provider } from '~/composables/useProviders'

describe('ModelCapabilityPills', () => {
  it('renders nothing when model is null', async () => {
    const wrapper = await mountSuspended(ModelCapabilityPills, { props: { model: null } })
    await nextTick()
    expect(wrapper.html()).toBe('<!--v-if-->')
  })

  it('renders nothing when model has no capabilities', async () => {
    const wrapper = await mountSuspended(ModelCapabilityPills, {
      props: { model: { id: 'plain-model' } },
    })
    await nextTick()
    expect(wrapper.html()).toBe('<!--v-if-->')
  })

  it('renders only the capabilities the model advertises', async () => {
    const wrapper = await mountSuspended(ModelCapabilityPills, {
      props: { model: { id: 'm', supportsThinking: true, supportsVision: false, supportsAudio: true } },
    })
    await nextTick()
    const text = wrapper.text()
    expect(text).toContain('thinking')
    expect(text).not.toContain('vision')
    expect(text).toContain('audio')
  })

  it('renders all three pills when the model supports everything', async () => {
    const wrapper = await mountSuspended(ModelCapabilityPills, {
      props: { model: { id: 'm', supportsThinking: true, supportsVision: true, supportsAudio: true } },
    })
    await nextTick()
    const pills = wrapper.findAll('span')
    expect(pills).toHaveLength(3)
    expect(pills.map(p => p.text())).toEqual(['thinking', 'vision', 'audio'])
  })
})

describe('findProviderModel', () => {
  const providers: Provider[] = [
    {
      name: 'openrouter',
      models: [
        { id: 'google/gemini-3-flash-preview', name: 'Google: Gemini 3 Flash', supportsThinking: true },
        { id: 'anthropic/claude', supportsVision: true },
      ],
    },
    { name: 'ollama-cloud', models: [{ id: 'kimi-k2.6', supportsAudio: true }] },
  ]

  it('resolves a model by provider + id', () => {
    const m = findProviderModel(providers, 'openrouter', 'google/gemini-3-flash-preview')
    expect(m?.supportsThinking).toBe(true)
  })

  it('returns null for unknown provider', () => {
    expect(findProviderModel(providers, 'mystery', 'x')).toBeNull()
  })

  it('returns null for unknown model under a known provider', () => {
    expect(findProviderModel(providers, 'openrouter', 'ghost')).toBeNull()
  })

  it('returns null for missing inputs', () => {
    expect(findProviderModel(providers, null, 'x')).toBeNull()
    expect(findProviderModel(providers, 'openrouter', null)).toBeNull()
  })
})
