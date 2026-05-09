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
    // JCLAW-165: audio pill stays visible as a capability indicator (model
    // accepts native audio passthrough); thinking/vision stay interactive.
    const wrapper = await mountSuspended(ModelCapabilityPills, {
      props: { model: { id: 'm', supportsThinking: true, supportsAudio: true } },
    })
    await nextTick()
    const text = wrapper.text()
    expect(text).toContain('thinking')
    expect(text).not.toContain('vision')
    expect(text).toContain('audio')
  })

  it('audio pill renders as non-interactive (no toggle event)', async () => {
    // Capability indicator only — clicking should NOT emit a toggle.
    // Vision/thinking still emit; only audio short-circuits the click handler
    // because there's no per-agent audio override to drive.
    const wrapper = await mountSuspended(ModelCapabilityPills, {
      props: { model: { id: 'm', supportsAudio: true } },
    })
    await nextTick()
    // Audio pill renders as a span, not a button.
    const buttons = wrapper.findAll('button')
    expect(buttons.length).toBe(0)
    const text = wrapper.text()
    expect(text).toContain('audio')
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

  it('vision pill renders as non-interactive (no toggle event)', async () => {
    // Capability indicator only — no LLM provider exposes a vision-off
    // API parameter, so a client-side toggle would just mean "don't attach
    // images." Same treatment as audio.
    const wrapper = await mountSuspended(ModelCapabilityPills, {
      props: { model: { id: 'm', supportsVision: true } },
    })
    await nextTick()
    const buttons = wrapper.findAll('button')
    expect(buttons.length).toBe(0)
    expect(wrapper.text()).toContain('vision')
  })

  it('emits toggle event only for the thinking pill (the sole interactive one)', async () => {
    const wrapper = await mountSuspended(ModelCapabilityPills, {
      props: {
        model: { id: 'm', supportsThinking: true, supportsVision: true, supportsAudio: true },
      },
    })
    await nextTick()
    const buttons = wrapper.findAll('button')
    // Only the thinking pill renders as a button now.
    expect(buttons.length).toBe(1)
    await buttons[0]!.trigger('click')
    const emits = wrapper.emitted('toggle') ?? []
    expect(emits).toHaveLength(1)
    expect(emits[0]).toEqual(['thinking'])
  })

  it('alwaysThinks renders the thinking pill as a non-interactive on-color span', async () => {
    // Pure reasoning models (o1, R1, QwQ) — the toggle is locked-on. The
    // pill should appear active (aria-pressed semantics not on a button at
    // all, since it's a span) and not emit toggle on click.
    const wrapper = await mountSuspended(ModelCapabilityPills, {
      props: {
        model: { id: 'o1', supportsThinking: true, alwaysThinks: true },
        thinkingMode: null,
      },
    })
    await nextTick()
    const buttons = wrapper.findAll('button')
    expect(buttons.length).toBe(0)
    const span = wrapper.find('span[title*="always reasons"]')
    expect(span.exists()).toBe(true)
    expect(wrapper.text()).toContain('thinking')
  })

  it('alwaysThinks pill is on-color even when thinkingMode is null', async () => {
    // Honesty: the model thinks regardless of thinkingMode, so the pill
    // must show in the on color (emerald) not the neutral off color.
    const wrapper = await mountSuspended(ModelCapabilityPills, {
      props: {
        model: { id: 'o1', supportsThinking: true, alwaysThinks: true },
        thinkingMode: null,
      },
    })
    await nextTick()
    const span = wrapper.find('span[title*="always reasons"]')
    expect(span.classes().join(' ')).toContain('emerald')
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
