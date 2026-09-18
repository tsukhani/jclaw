import { describe, it, expect, beforeEach } from 'vitest'
import { mountSuspended, registerEndpoint } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import { setResponseStatus } from 'h3'
import { clearNuxtData } from '#app'
import Settings from '~/pages/settings.vue'
import { looksLikeEmbeddingModel } from '~/utils/embeddingModels'

/**
 * Settings → Memory Embeddings (JCLAW-932).
 *
 * The panel's whole job is to stop an operator committing an embedding model that
 * will not actually be used: the dimension has to come from the model rather than
 * from typing, and a provider that silently serves a different model has to be
 * rejected. These tests pin that gate.
 */
const EMBED_MODEL = 'text-embedding-nomic-embed-text-v1.5'
const CHAT_MODEL = 'qwen3.5-4b-mlx'

function baseEndpoints(configEntries: { key: string, value: string }[] = [],
  providers = [localProvider('lm-studio')]) {
  registerEndpoint('/api/agents', () => [])
  registerEndpoint('/api/channels', () => [])
  registerEndpoint('/api/ocr/status', () => ({ providers: [] }))
  registerEndpoint('/api/config', () => ({ entries: configEntries }))
  registerEndpoint('/api/providers', () => providers)
}

function localProvider(name: string) {
  return { name, paymentModality: 'SUBSCRIPTION', subscriptionMonthlyUsd: 0, supportedModalities: ['SUBSCRIPTION'], local: true }
}

function cloudProvider(name: string) {
  return { name, paymentModality: 'PER_TOKEN', subscriptionMonthlyUsd: 0, supportedModalities: ['PER_TOKEN'], local: false }
}

function vectorEnabledConfig() {
  return [
    { key: 'memory.jpa.vector.enabled', value: 'true' },
    {
      key: 'provider.lm-studio.models',
      value: JSON.stringify([{ id: EMBED_MODEL, name: EMBED_MODEL }, { id: CHAT_MODEL, name: CHAT_MODEL }]),
    },
  ]
}

async function mountSettingsSection(sectionId: string) {
  const component = await mountSuspended(Settings)
  ;(component.vm as unknown as { activeSectionId: string }).activeSectionId = sectionId
  await flushPromises()
  await flushPromises()
  return component
}

describe('embedding-model shortlist heuristic', () => {
  it('shortlists ids that name a known embedding family', () => {
    expect(looksLikeEmbeddingModel('text-embedding-3-small')).toBe(true)
    expect(looksLikeEmbeddingModel(EMBED_MODEL)).toBe(true)
    expect(looksLikeEmbeddingModel('bge-large-en-v1.5')).toBe(true)
    expect(looksLikeEmbeddingModel('mxbai-embed-large')).toBe(true)
  })

  it('does not shortlist chat models', () => {
    expect(looksLikeEmbeddingModel(CHAT_MODEL)).toBe(false)
    expect(looksLikeEmbeddingModel('gpt-4o')).toBe(false)
  })

  it('matches on the display name too, since ids are not always descriptive', () => {
    expect(looksLikeEmbeddingModel('provider-internal-id-42', 'Nomic Embed Text')).toBe(true)
  })
})

describe('Settings page — Memory Embeddings', () => {
  beforeEach(() => {
    clearNuxtData()
  })

  it('shows the section with vector memory off by default', async () => {
    baseEndpoints()
    const component = await mountSettingsSection('memory')

    expect(component.text()).toContain('Embeddings')
    expect(component.find('[data-testid="memory-vector-toggle"]').text()).toBe('Enable')
    expect(component.find('[data-testid="memory-embedding-provider"]').exists()).toBe(false)
  })

  it('groups Limits, Embeddings and Reranker under Memory in the rail', async () => {
    // Memory is its own TOC group, not one entry with headings inside it — the three
    // are separately addressable sections, each with its own panel.
    baseEndpoints()
    const component = await mountSettingsSection('memory')
    const rail = component.text()

    expect(rail).toContain('Limits')
    expect(rail).toContain('Embeddings')
    expect(rail).toContain('Reranker')
  })

  it('defaults the limits to the backend code defaults', async () => {
    // These two are the ONLY bounds on their blocks, so a wrong default here silently
    // changes how much memory reaches the prompt.
    baseEndpoints()
    const component = await mountSettingsSection('memory-limits')

    expect((component.find('[data-testid="memory-core-max-count"]').element as HTMLInputElement).value).toBe('20')
    expect((component.find('[data-testid="memory-recall-limit"]').element as HTMLInputElement).value).toBe('10')
  })

  it('refuses to save a limit below one', async () => {
    baseEndpoints()
    const component = await mountSettingsSection('memory-limits')

    await component.find('[data-testid="memory-recall-limit"]').setValue('0')
    await flushPromises()

    expect(component.find('[data-testid="memory-limits-invalid"]').exists()).toBe(true)
    expect((component.find('[data-testid="memory-limits-save"]').element as HTMLButtonElement).disabled).toBe(true)
  })

  it('keeps the reranker pickers hidden until it is enabled', async () => {
    baseEndpoints()
    const component = await mountSettingsSection('memory-reranker')

    expect(component.find('[data-testid="memory-rerank-toggle"]').text()).toBe('Enable')
    expect(component.find('[data-testid="memory-rerank-provider"]').exists()).toBe(false)
  })

  it('offers only local providers for the reranker', async () => {
    // Reranking renders the candidate memories into the prompt, so the same
    // local-only rule as embeddings applies — enforced in ConfigService, narrowed here.
    baseEndpoints([{ key: 'memory.rerank.enabled', value: 'true' }],
      [localProvider('lm-studio'), cloudProvider('openrouter')])
    const component = await mountSettingsSection('memory-reranker')

    const options = component.find('[data-testid="memory-rerank-provider"]').findAll('option')
    const values = options.map(o => (o.element as HTMLOptionElement).value)
    expect(values).toContain('lm-studio')
    expect(values).not.toContain('openrouter')
  })

  it('keeps the shipped `memory` id pointing at the embedding panel', async () => {
    // /settings?section=memory has always addressed embeddings; the split must not
    // silently repoint an operator's bookmark at a different panel.
    baseEndpoints(vectorEnabledConfig())
    const component = await mountSettingsSection('memory')

    expect(component.find('[data-testid="memory-vector-toggle"]').exists()).toBe(true)
    expect(component.find('[data-testid="memory-core-max-count"]').exists()).toBe(false)
  })

  it('offers provider and model pickers once vector memory is enabled', async () => {
    baseEndpoints(vectorEnabledConfig())
    const component = await mountSettingsSection('memory')

    expect(component.find('[data-testid="memory-embedding-provider"]').exists()).toBe(true)
    expect(component.text()).toContain('lm-studio')
  })

  it('lists models discovered from the provider, not the curated chat catalog', async () => {
    // The bug this covers: provider.<name>.models is the operator's chat list, and
    // embedding models are never added to it — so on a real instance the picker
    // offered only chat models and the embedding model in use was unselectable.
    baseEndpoints([...vectorEnabledConfig(), { key: 'memory.jpa.vector.provider', value: 'lm-studio' }])
    registerEndpoint('/api/providers/lm-studio/embedding-models', () => ({
      provider: 'lm-studio',
      models: [{ id: EMBED_MODEL, name: EMBED_MODEL }, { id: CHAT_MODEL, name: CHAT_MODEL }],
      count: 2,
    }))
    const component = await mountSettingsSection('memory')
    await flushPromises()

    const values = component.find('[data-testid="memory-embedding-model"]')
      .findAll('option').map(o => o.attributes('value'))
    expect(values).toContain(EMBED_MODEL)
  })

  it('keeps the saved model selectable even when the catalog omits it', async () => {
    // Otherwise the panel cannot represent the configuration it is editing.
    baseEndpoints([
      { key: 'memory.jpa.vector.enabled', value: 'true' },
      { key: 'memory.jpa.vector.provider', value: 'lm-studio' },
      { key: 'memory.jpa.vector.model', value: EMBED_MODEL },
      { key: 'provider.lm-studio.models', value: JSON.stringify([{ id: CHAT_MODEL, name: CHAT_MODEL }]) },
    ])
    const component = await mountSettingsSection('memory')
    await flushPromises()

    const values = component.find('[data-testid="memory-embedding-model"]')
      .findAll('option').map(o => o.attributes('value'))
    expect(values).toContain(EMBED_MODEL)
  })

  it('shortlists the model dropdown but keeps the rest reachable', async () => {
    baseEndpoints(vectorEnabledConfig())
    const component = await mountSettingsSection('memory')

    await component.find('[data-testid="memory-embedding-provider"]').setValue('lm-studio')
    await flushPromises()
    await flushPromises()

    const options = component.find('[data-testid="memory-embedding-model"]').findAll('option')
    const values = options.map(o => o.attributes('value'))
    expect(values).toContain(EMBED_MODEL)
    expect(values).not.toContain(CHAT_MODEL)

    // The heuristic narrows, it does not decide — the hidden model stays reachable.
    await component.find('[data-testid="memory-embedding-show-all"]').trigger('click')
    await flushPromises()
    const afterValues = component.find('[data-testid="memory-embedding-model"]')
      .findAll('option').map(o => o.attributes('value'))
    expect(afterValues).toContain(CHAT_MODEL)
  })

  it('fills dimensions from the probe and only then allows saving', async () => {
    baseEndpoints(vectorEnabledConfig())
    registerEndpoint('/api/providers/lm-studio/embedding-probe', () => ({
      provider: 'lm-studio', model: EMBED_MODEL, ok: true, dimensions: 768, error: null,
    }))
    const component = await mountSettingsSection('memory')

    await component.find('[data-testid="memory-embedding-provider"]').setValue('lm-studio')
    await flushPromises()
    await flushPromises()
    await component.find('[data-testid="memory-embedding-model"]').setValue(EMBED_MODEL)
    await flushPromises()

    // Unprobed: the model is picked but not yet confirmed, so Save stays shut.
    expect(component.find('[data-testid="memory-embedding-save"]').attributes('disabled')).toBeDefined()

    await component.find('[data-testid="memory-embedding-probe"]').trigger('click')
    await flushPromises()

    expect(component.find('[data-testid="memory-embedding-probe-ok"]').text()).toContain('768')
    expect(component.find('[data-testid="memory-embedding-dimensions"]').attributes('value')).toBe('768')
    expect(component.find('[data-testid="memory-embedding-save"]').attributes('disabled')).toBeUndefined()
  })

  it('refuses to save a model the provider does not honour', async () => {
    baseEndpoints(vectorEnabledConfig())
    // The LM Studio behaviour from JCLAW-931: a chat model returns a valid vector,
    // but for a different model than the one requested.
    registerEndpoint('/api/providers/lm-studio/embedding-probe', () => ({
      provider: 'lm-studio',
      model: CHAT_MODEL,
      ok: false,
      dimensions: 0,
      error: `Provider served '${EMBED_MODEL}' instead of '${CHAT_MODEL}'`,
    }))
    const component = await mountSettingsSection('memory')

    await component.find('[data-testid="memory-embedding-provider"]').setValue('lm-studio')
    await flushPromises()
    await flushPromises()
    await component.find('[data-testid="memory-embedding-show-all"]').trigger('click')
    await flushPromises()
    await component.find('[data-testid="memory-embedding-model"]').setValue(CHAT_MODEL)
    await flushPromises()
    await component.find('[data-testid="memory-embedding-probe"]').trigger('click')
    await flushPromises()

    expect(component.find('[data-testid="memory-embedding-probe-error"]').text()).toContain('instead of')
    expect(component.find('[data-testid="memory-embedding-save"]').attributes('disabled')).toBeDefined()
  })

  it('offers a re-embed and reports progress while one runs', async () => {
    baseEndpoints([...vectorEnabledConfig(), { key: 'memory.jpa.vector.provider', value: 'lm-studio' }])
    registerEndpoint('/api/memories/reembed', () => ({
      running: true, processed: 312, total: 616, model: EMBED_MODEL, error: null, upToDate: false,
    }))
    const component = await mountSettingsSection('memory')
    await flushPromises()

    const progress = component.find('[data-testid="memory-reembed-progress"]')
    expect(progress.exists()).toBe(true)
    expect(progress.text()).toContain('312 / 616')
    // The wording the operator acts on: capture keeps working, only recall degrades.
    expect(progress.text()).toContain('still being saved')
    expect(component.find('[data-testid="memory-reembed-start"]').attributes('disabled')).toBeDefined()
  })

  it('surfaces a refusal rather than leaving the button looking inert', async () => {
    baseEndpoints([...vectorEnabledConfig(), { key: 'memory.jpa.vector.provider', value: 'lm-studio' }])
    let started = false
    registerEndpoint('/api/memories/reembed', (event) => {
      if (event.method === 'POST') {
        started = true
        // Mirror ApiResponses.error's real body — {type, code, message} with a 409
        // status — so the panel's extraction is tested against what it will meet.
        setResponseStatus(event, 409)
        return {
          type: 'error',
          code: 'conflict',
          message: 'The configured model is 1536-dimensional, above the 1024 the search index supports.',
        }
      }
      return { running: false, processed: 0, total: 0, model: EMBED_MODEL, error: null, upToDate: false }
    })
    const component = await mountSettingsSection('memory')
    await flushPromises()

    await component.find('[data-testid="memory-reembed-start"]').trigger('click')
    await flushPromises()

    expect(started).toBe(true)
    expect(component.find('[data-testid="memory-reembed-error"]').text()).toContain('1536-dimensional')
  })

  it('warns that changing the model strands existing vectors', async () => {
    baseEndpoints([...vectorEnabledConfig(),
      { key: 'memory.jpa.vector.provider', value: 'lm-studio' },
      { key: 'memory.jpa.vector.model', value: EMBED_MODEL },
    ])
    registerEndpoint('/api/providers/lm-studio/embedding-probe', () => ({
      provider: 'lm-studio', model: CHAT_MODEL, ok: true, dimensions: 1024, error: null,
    }))
    const component = await mountSettingsSection('memory')

    // No change yet — no warning.
    expect(component.find('[data-testid="memory-embedding-reembed-warning"]').exists()).toBe(false)

    await component.find('[data-testid="memory-embedding-show-all"]').trigger('click')
    await flushPromises()
    await component.find('[data-testid="memory-embedding-model"]').setValue(CHAT_MODEL)
    await flushPromises()

    expect(component.find('[data-testid="memory-embedding-reembed-warning"]').exists()).toBe(true)
  })

  it('offers only local providers for embeddings', async () => {
    // JCLAW-939: embedding a memory sends its full text to the provider, so a cloud one
    // would ship the whole corpus off the machine. The picker must not offer that.
    baseEndpoints(vectorEnabledConfig(), [localProvider('lm-studio'), cloudProvider('openai')])
    const component = await mountSettingsSection('memory')
    await flushPromises()

    const values = component.find('[data-testid="memory-embedding-provider"]')
      .findAll('option').map(o => o.attributes('value'))
    expect(values).toContain('lm-studio')
    expect(values).not.toContain('openai')
  })

  it('explains why the provider list is empty when nothing local is configured', async () => {
    // An empty dropdown with no reason reads as a bug. The operator has to learn that
    // the requirement is local inference, not that vector memory is broken.
    baseEndpoints(vectorEnabledConfig(), [cloudProvider('openai')])
    const component = await mountSettingsSection('memory')
    await flushPromises()

    expect(component.find('[data-testid="memory-embedding-no-local-provider"]').exists()).toBe(true)
  })
})
