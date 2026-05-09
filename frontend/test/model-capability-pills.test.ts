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
      props: { model: { id: 'plain' } },
    })
    await nextTick()
    expect(wrapper.html()).toBe('<!--v-if-->')
  })

  it('renders only supported capabilities', async () => {
    // JCLAW-165: audio pill retired since transcription gives every model
    // an audio path; only thinking/vision render now.
    const wrapper = await mountSuspended(ModelCapabilityPills, {
      props: { model: { id: 'm', supportsThinking: true, supportsAudio: true } },
    })
    await nextTick()
    const text = wrapper.text()
    expect(text).toContain('thinking')
    expect(text).not.toContain('vision')
    expect(text).not.toContain('audio')
  })

  it('thinking pill is on when thinkingMode is a non-empty string', async () => {
    const wrapper = await mountSuspended(ModelCapabilityPills, {
      props: { model: { id: 'm', supportsThinking: true }, thinkingMode: 'medium' },
    })
    await nextTick()
    const btn = wrapper.find('button')
    expect(btn.attributes('aria-pressed')).toBe('true')
    expect(btn.attributes('title')).toContain('thinking is on')
  })

  it('thinking pill is off when thinkingMode is null or empty', async () => {
    const wrapper = await mountSuspended(ModelCapabilityPills, {
      props: { model: { id: 'm', supportsThinking: true }, thinkingMode: null },
    })
    await nextTick()
    const btn = wrapper.find('button')
    expect(btn.attributes('aria-pressed')).toBe('false')
    expect(btn.attributes('title')).toContain('thinking is off')
  })

  it('vision pill treats null as on (inherit) and explicit false as off', async () => {
    const nullWrapper = await mountSuspended(ModelCapabilityPills, {
      props: { model: { id: 'm', supportsVision: true }, visionEnabled: null },
    })
    await nextTick()
    expect(nullWrapper.find('button').attributes('aria-pressed')).toBe('true')

    const falseWrapper = await mountSuspended(ModelCapabilityPills, {
      props: { model: { id: 'm', supportsVision: true }, visionEnabled: false },
    })
    await nextTick()
    expect(falseWrapper.find('button').attributes('aria-pressed')).toBe('false')
  })

  it('emits toggle event with the capability name on click', async () => {
    const wrapper = await mountSuspended(ModelCapabilityPills, {
      props: {
        model: { id: 'm', supportsThinking: true, supportsVision: true, supportsAudio: true },
      },
    })
    await nextTick()
    const buttons = wrapper.findAll('button')
    await buttons[1]!.trigger('click')
    const emits = wrapper.emitted('toggle') ?? []
    expect(emits).toHaveLength(1)
    expect(emits[0]).toEqual(['vision'])
  })

  it('md size pills carry larger padding classes than sm', async () => {
    const md = await mountSuspended(ModelCapabilityPills, {
      props: { model: { id: 'm', supportsThinking: true }, size: 'md' },
    })
    await nextTick()
    expect(md.find('button').classes().join(' ')).toContain('px-2.5')

    const sm = await mountSuspended(ModelCapabilityPills, {
      props: { model: { id: 'm', supportsThinking: true }, size: 'sm' },
    })
    await nextTick()
    expect(sm.find('button').classes().join(' ')).toContain('px-1.5')
  })
})

describe('findProviderModel', () => {
  const providers: Provider[] = [
    {
      name: 'openrouter',
      models: [
        { id: 'google/gemini-3-flash-preview', supportsThinking: true },
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
