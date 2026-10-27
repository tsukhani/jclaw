import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mountSuspended, registerEndpoint } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import { readBody, setResponseStatus } from 'h3'
import { clearNuxtData } from '#app'
import Settings from '~/pages/settings.vue'
import { sectionGroups } from '~/components/settings/sections'

/**
 * Settings > Decision Providers (JCLAW-1302): JEV's card holds the TypeSafe key both consumers
 * send, the retention note, which consumers have chosen JEV, and the circuit breaker they share.
 * The key editor keeps the JCLAW-1274 behaviours it had in the Browser panel. Ollama's card (JCLAW-1336)
 * holds the server, its decision models and the router's use of them. Laya's card (JCLAW-1447) holds
 * the server the operator runs, its optional key, the checkpoints with their context, and its limits.
 * Baseten's card (JF-133) holds its key and its two hosted models. Since JF-135 every card lists its models as
 * information: the Model Router is where one is chosen.
 */

let stored: Map<string, string>
let posted: Array<{ key: string, value: string }>
let breakers: Array<Record<string, unknown>>
let ollama: Record<string, unknown>
let laya: Record<string, unknown>
let deleted: string[]

function jevBreaker(over: Record<string, unknown> = {}) {
  return {
    name: 'decision:jev', subsystem: 'decision', target: 'jev', state: 'CLOSED',
    samples: 4, failures: 0, slowCalls: 0, failureRate: 0, slowCallRate: 0, reason: null, manual: false, ...over,
  }
}

interface Saved { key: string, value: string }

/** `onSave` runs once an accepted POST is stored, so a test can change what the next read answers. */
function baseEndpoints(opts: { failSaves?: boolean, holdSaves?: Promise<void>, onSave?: (saved: Saved) => void } = {}) {
  registerEndpoint('/api/agents', () => [])
  registerEndpoint('/api/channels', () => [])
  registerEndpoint('/api/ocr/status', () => ({ providers: [] }))
  registerEndpoint('/api/providers', () => [])
  registerEndpoint('/api/breakers', () => breakers)
  registerEndpoint('/api/decision/ollama', () => ollama)
  registerEndpoint('/api/decision/laya', () => laya)
  for (const key of ['decision.ollama.baseUrl', 'decision.laya.baseUrl']) {
    registerEndpoint(`/api/config/${key}`, {
      method: 'DELETE',
      handler: () => {
        deleted.push(key)
        stored.delete(key)
        return { status: 'ok' }
      },
    })
  }
  registerEndpoint('/api/config', () => ({
    entries: [...stored].map(([key, value]) => ({ key, value, updatedAt: '2026-09-26T00:00:00Z' })),
  }))
  registerEndpoint('/api/config', {
    method: 'POST',
    handler: async (event) => {
      const body = await readBody(event) as Saved
      posted.push(body)
      if (opts.holdSaves) await opts.holdSaves
      if (opts.failSaves) {
        setResponseStatus(event, 403)
        return { error: 'invalid_request', message: 'decision.jev.apiKey must be printable ASCII with no spaces.' }
      }
      // The API masks a key on read, as ConfigService.maskValue does; a blank one stays blank.
      stored.set(body.key, body.key.endsWith('apiKey') && body.value ? `${body.value.slice(0, 4)}****` : body.value)
      opts.onSave?.(body)
      return { status: 'ok' }
    },
  })
}

/** Wrapped template text, as it reads on one line. */
function words(text: string) {
  return text.replace(/\s+/g, ' ').trim()
}

/** An Ollama decision model as GET /api/decision/ollama lists it, stating nothing unless given. */
function model(name: string, stated: Partial<{ contextTokens: number, parameterSize: string, sizeBytes: number }> = {}) {
  return { name, contextTokens: null, parameterSize: null, sizeBytes: null, ...stated }
}

async function mountDecisionProviders() {
  const component = await mountSuspended(Settings)
  ;(component.vm as unknown as { activeSectionId: string }).activeSectionId = 'decision-providers'
  await flushPromises()
  await flushPromises()
  return component
}

describe('Settings page — Decision Providers', () => {
  beforeEach(() => {
    clearNuxtData()
    stored = new Map()
    posted = []
    breakers = []
    deleted = []
    ollama = { baseUrl: 'http://localhost:11434', customized: false, reachable: true, error: null, models: [model('tev1:latest'), model('nimble:latest')] }
    laya = { baseUrl: 'http://localhost:8000', reachable: true, error: null, loaded: ['english'], device: 'cpu' }
  })

  it('sits between LLM Providers and Search Providers in the Providers group', () => {
    const ids = sectionGroups.find(g => g.label === 'Providers')!.sections.map(s => s.id)
    expect(ids).toEqual(['providers', 'decision-providers', 'search', 'proxy-providers'])
  })

  it('shows an unkeyed JEV card with the retention note and neither consumer in use', async () => {
    baseEndpoints()
    const component = await mountDecisionProviders()

    expect(component.html()).toMatch(/<h2[^>]*>\s*Decision Providers\s*</)
    expect(component.find('[data-testid="decision-jev-status"]').text()).toBe('needs API key')
    expect(component.find('[data-testid="decision-jev-key"]').text()).toBe('(not set)')
    const note = component.find('[data-testid="decision-jev-retention"]').text()
    expect(note).toContain('record or retain')
    expect(note).toContain('page content')
    expect(note).toContain('first 4000 characters of each prompt')
    const browser = component.find('[data-testid="decision-jev-consumer-browser"]')
    expect(browser.find('a').attributes('href')).toBe('/settings?section=browser')
    expect(browser.text()).toContain('not in use')
    const router = component.find('[data-testid="decision-jev-consumer-model-router"]')
    expect(router.find('a').attributes('href')).toBe('/settings?section=model-router')
    expect(router.text()).toContain('not in use')
    expect(component.find('[data-testid="decision-jev-breaker"]').exists()).toBe(false)
  })

  it('shows the JEV portrait beside the retention note, decorative and sized before it loads', async () => {
    baseEndpoints()
    const component = await mountDecisionProviders()

    const portrait = component.find('[data-testid="decision-jev-portrait"]')
    expect(portrait.attributes('src')).toBe('/jev.webp')
    expect(portrait.attributes('alt')).toBe('')
    expect([portrait.attributes('width'), portrait.attributes('height')]).toEqual(['143', '176'])
    const play = component.find('[data-testid="decision-jev-play"]')
    expect(play.element.nextElementSibling?.nextElementSibling?.getAttribute('data-testid')).toBe('decision-jev-retention')
  })

  it('plays the JEV clip from the start when the portrait is clicked, fetching nothing before', async () => {
    const playSpy = vi.spyOn(HTMLMediaElement.prototype, 'play').mockResolvedValue()
    try {
      baseEndpoints()
      const component = await mountDecisionProviders()

      const button = component.find('[data-testid="decision-jev-play"]')
      expect(button.attributes('type')).toBe('button')
      expect(button.attributes('aria-label')).toBe('Play JEV saying “My name is Jev”')
      expect(button.attributes('title')).toBe(button.attributes('aria-label'))
      const audio = component.find('audio')
      expect(audio.attributes('src')).toBe('/jev.mp3')
      expect(audio.attributes('preload')).toBe('none')

      ;(audio.element as HTMLAudioElement).currentTime = 0.8
      await button.trigger('click')
      expect(playSpy).toHaveBeenCalledTimes(1)
      expect((audio.element as HTMLAudioElement).currentTime).toBe(0)
    }
    finally {
      playSpy.mockRestore()
    }
  })

  it('marks each consumer that has chosen JEV as in use', async () => {
    baseEndpoints()
    stored.set('browser.engine', 'jev')
    stored.set('router.classifier.provider', 'jev')
    stored.set('router.classifier.model', 'jev-latest')
    const component = await mountDecisionProviders()

    for (const id of ['browser', 'model-router']) {
      const consumer = component.find(`[data-testid="decision-jev-consumer-${id}"]`).text()
      expect(consumer).toContain('in use')
      expect(consumer).not.toContain('not in use')
    }
  })

  it('counts the router as a JEV consumer only once both halves of the classifier pair are stored', async () => {
    baseEndpoints()
    stored.set('router.classifier.provider', ' jev ')
    const providerOnly = await mountDecisionProviders()
    expect(providerOnly.find('[data-testid="decision-jev-consumer-model-router"]').text()).toContain('not in use')

    stored.set('router.classifier.model', 'jev-latest')
    clearNuxtData()
    const paired = await mountDecisionProviders()
    expect(paired.find('[data-testid="decision-jev-consumer-model-router"]').text()).not.toContain('not in use')
  })

  it('sets the key through the masked editor, which starts blank', async () => {
    baseEndpoints()
    const component = await mountDecisionProviders()

    await component.find('button[aria-label="Edit TypeSafe API key"]').trigger('click')
    const input = component.find('input[aria-label="TypeSafe API key"]')
    expect((input.element as HTMLInputElement).value).toBe('')
    expect(input.attributes('type')).toBe('password')
    expect(input.attributes('autocomplete')).toBe('new-password')
    await input.setValue('ts-secret-123')
    await component.find('button[title="Save"]').trigger('click')
    await flushPromises()
    await flushPromises()

    expect(posted).toEqual([{ key: 'decision.jev.apiKey', value: 'ts-secret-123' }])
    // updateEntry refreshes the config without awaiting it.
    await vi.waitFor(() => expect(component.find('[data-testid="decision-jev-key"]').text()).toBe('••••••••'))
    expect(component.find('[data-testid="decision-jev-status"]').text()).toBe('configured')
  })

  it('saving the key editor untouched cancels rather than storing a blank key', async () => {
    baseEndpoints()
    stored.set('decision.jev.apiKey', 'ts-s****')
    const component = await mountDecisionProviders()

    await component.find('button[aria-label="Edit TypeSafe API key"]').trigger('click')
    await component.find('button[title="Save"]').trigger('click')
    await flushPromises()

    expect(posted).toEqual([])
    expect(component.find('input[aria-label="TypeSafe API key"]').exists()).toBe(false)
    expect(component.find('[data-testid="decision-jev-key"]').text()).toBe('••••••••')
  })

  it('the key cannot be saved again while its save is in flight', async () => {
    let release!: () => void
    baseEndpoints({ holdSaves: new Promise<void>((resolve) => {
      release = resolve
    }) })
    const component = await mountDecisionProviders()

    await component.find('button[aria-label="Edit TypeSafe API key"]').trigger('click')
    await component.find('input[aria-label="TypeSafe API key"]').setValue('ts-secret-123')
    await component.find('button[title="Save"]').trigger('click')

    await vi.waitFor(() => expect(component.find('button[title="Save"]').attributes('disabled')).toBeDefined())
    release()
    await vi.waitFor(() => expect(component.find('input[aria-label="TypeSafe API key"]').exists()).toBe(false))
    expect(posted).toHaveLength(1)
  })

  it('a refused key keeps the editor open with the reason beside it', async () => {
    baseEndpoints({ failSaves: true })
    const component = await mountDecisionProviders()

    await component.find('button[aria-label="Edit TypeSafe API key"]').trigger('click')
    await component.find('input[aria-label="TypeSafe API key"]').setValue('ts key')
    await component.find('button[title="Save"]').trigger('click')

    await vi.waitFor(() => expect(component.find('[data-testid="decision-provider-jev"] [data-testid="api-error"]').exists()).toBe(true))
    expect(component.find('input[aria-label="TypeSafe API key"]').exists()).toBe(true)
  })

  it('shows the shared breaker once JEV has been called, with Isolate', async () => {
    breakers = [jevBreaker()]
    baseEndpoints()
    const component = await mountDecisionProviders()

    await vi.waitFor(() => expect(component.find('[data-testid="decision-jev-breaker"]').exists()).toBe(true))
    const row = component.find('[data-testid="decision-jev-breaker"]')
    expect(row.find('[data-testid="breaker-state"]').text()).toBe('CLOSED')
    expect(row.find('button[aria-label="Isolate jev"]').exists()).toBe(true)
  })

  it('an isolated breaker reads as the operator\'s decision, with Restore', async () => {
    breakers = [jevBreaker({ state: 'OPEN', reason: 'MANUAL_TRIP', manual: true })]
    baseEndpoints()
    const component = await mountDecisionProviders()

    await vi.waitFor(() => expect(component.find('[data-testid="decision-jev-breaker"]').exists()).toBe(true))
    const row = component.find('[data-testid="decision-jev-breaker"]')
    expect(row.text()).toContain('isolated by you')
    expect(row.find('button[aria-label="Restore jev"]').exists()).toBe(true)
  })

  it('lists every card\'s models as information, with no checkbox on any card', async () => {
    // JF-135: the Model Router offers every decision model, so a card has nothing to tick.
    stored.set('router.classifier.provider', 'laya')
    stored.set('router.classifier.model', 'english')
    baseEndpoints()
    const component = await mountDecisionProviders()
    await vi.waitFor(() => expect(component.find('[data-testid="decision-ollama-model-tev1:latest"]').exists()).toBe(true))
    const cards = component.findAll('[data-testid^="decision-provider-"]')
    expect(cards.map(c => c.attributes('data-testid'))).toEqual([
      'decision-provider-jev', 'decision-provider-laya', 'decision-provider-ollama', 'decision-provider-baseten',
    ])
    for (const card of cards) expect(card.findAll('input[type="checkbox"]')).toHaveLength(0)
    expect(posted).toEqual([])
    expect(deleted).toEqual([])
  })

  describe('Ollama card (JCLAW-1336)', () => {
    it('sits directly below the Laya card', async () => {
      baseEndpoints()
      const component = await mountDecisionProviders()
      const laya = component.find('[data-testid="decision-provider-laya"]').element
      expect(laya.nextElementSibling?.getAttribute('data-testid')).toBe('decision-provider-ollama')
    })

    it('says a model on the server keeps the prompt there and links the guide for cloud models, without JEV\'s retention notice', async () => {
      baseEndpoints()
      const component = await mountDecisionProviders()
      const card = component.find('[data-testid="decision-provider-ollama"]')
      const note = card.find('[data-testid="decision-ollama-privacy"]')
      expect(words(note.text())).toBe(
        'Decision models such as d1 and tev1, served by your own Ollama server. They answer the same questions JEV '
        + 'does. A model that runs on this server keeps what it is sent there, so no third party sees or keeps it. '
        + 'One with cloud in its name, such as clef:cloud, runs on ollama.com instead: read Ollama\'s cloud models in '
        + 'the user guide before you choose one. The Model Router\'s classifier sends the first 4000 characters of each '
        + 'prompt to the model it uses.')
      expect(note.find('[data-testid="decision-ollama-cloud-link"]').attributes('href')).toBe('/guide#settings-ollama-cloud')
      expect(card.text()).not.toContain('record or retain')
    })

    it('lists each model with its context, and a local one with its parameters and disk size, and adds only the note under the list', async () => {
      ollama = {
        ...ollama, models: [
          model('clef-flash:latest', { contextTokens: 16384, parameterSize: '9.5B', sizeBytes: 12387093750 }),
          model('clef:cloud', { contextTokens: 65536 }),
        ],
      }
      baseEndpoints()
      const component = await mountDecisionProviders()
      const models = component.find('[data-testid="decision-ollama-models"]')
      await vi.waitFor(() => expect(models.findAll('li')).toHaveLength(2))
      expect(models.findAll('li').map(l => words(l.text()))).toEqual([
        'clef-flash:latest 16,384 tokens, 9.5B parameters, 11.5 GB on disk',
        'clef:cloud 65,536 tokens',
      ])
      expect(models.findAll('p').map(p => words(p.text()))).toEqual([
        'Each takes that many tokens per request; a longer prompt is refused, not cut. Choose one as the prompt '
        + 'classifier in Settings → Model Router.',
      ])
      expect(models.find('[data-testid="decision-ollama-models-note"] a').attributes('href')).toBe('/settings?section=model-router')
    })

    it('states only what the server states of a model', async () => {
      ollama = { ...ollama, models: [model('tev1:latest', { contextTokens: 2050, sizeBytes: 4361568327 }), model('nimble:latest')] }
      baseEndpoints()
      const component = await mountDecisionProviders()
      const models = component.find('[data-testid="decision-ollama-models"]')
      await vi.waitFor(() => expect(models.findAll('li')).toHaveLength(2))
      expect(models.findAll('li').map(l => words(l.text()))).toEqual(['tev1:latest 2,050 tokens, 4.1 GB on disk', 'nimble:latest'])
    })

    it('Refresh reads the server again, so a model pulled since the page opened appears', async () => {
      let reads = 0
      baseEndpoints()
      registerEndpoint('/api/decision/ollama', () => {
        reads++
        return ollama
      })
      const component = await mountDecisionProviders()
      const names = () => component.find('[data-testid="decision-ollama-models"]').findAll('li').map(l => words(l.text()))
      await vi.waitFor(() => expect(names()).toEqual(['tev1:latest', 'nimble:latest']))
      expect(reads).toBe(1)
      const refresh = component.find('[data-testid="decision-ollama-refresh"]')
      expect(refresh.attributes('type')).toBe('button')
      expect(words(refresh.text())).toBe('Refresh')

      ollama = {
        ...ollama,
        models: [model('tev1:latest'), model('nimble:latest'), model('d1:latest', { contextTokens: 16384 }), model('clef:cloud')],
      }
      await refresh.trigger('click')
      await vi.waitFor(() => expect(names()).toEqual(['tev1:latest', 'nimble:latest', 'd1:latest 16,384 tokens', 'clef:cloud']))
      expect(reads).toBe(2)
      expect(posted).toEqual([])
    })

    it('shows the llama beside the note, decorative and sized before it loads', async () => {
      baseEndpoints()
      const component = await mountDecisionProviders()
      const logo = component.find('[data-testid="decision-ollama-logo"]')
      expect(logo.attributes('src')).toBe('/ollama.webp')
      expect(logo.attributes('alt')).toBe('')
      expect([logo.attributes('width'), logo.attributes('height')]).toEqual(['200', '294'])
      const play = component.find('[data-testid="decision-ollama-play"]')
      expect(play.find('[data-testid="decision-ollama-logo"]').exists()).toBe(true)
      expect(play.element.nextElementSibling?.nextElementSibling?.getAttribute('data-testid')).toBe('decision-ollama-privacy')
    })

    it('plays the llama clip from the start when the logo is clicked, fetching nothing before', async () => {
      const playSpy = vi.spyOn(HTMLMediaElement.prototype, 'play').mockResolvedValue()
      try {
        baseEndpoints()
        const component = await mountDecisionProviders()

        const button = component.find('[data-testid="decision-ollama-play"]')
        expect(button.attributes('type')).toBe('button')
        expect(button.attributes('aria-label')).toBe('Play a llama’s call')
        expect(button.attributes('title')).toBe(button.attributes('aria-label'))
        const audio = component.find('audio[src="/ollama.mp3"]')
        expect(audio.attributes('preload')).toBe('none')

        ;(audio.element as HTMLAudioElement).currentTime = 1.2
        await button.trigger('click')
        expect(playSpy).toHaveBeenCalledTimes(1)
        expect(playSpy.mock.contexts[0]).toBe(audio.element)
        expect((audio.element as HTMLAudioElement).currentTime).toBe(0)
      }
      finally {
        playSpy.mockRestore()
      }
    })

    it('lists only the decision models the server reports, not Ollama Local\'s chat models', async () => {
      stored.set('provider.ollama-local.baseUrl', 'http://localhost:11434/v1')
      stored.set('provider.ollama-local.models', JSON.stringify([{ id: 'llama3.2' }]))
      baseEndpoints()
      const component = await mountDecisionProviders()
      await vi.waitFor(() => expect(component.find('[data-testid="decision-ollama-status"]').text()).toBe('reachable'))
      const models = component.find('[data-testid="decision-ollama-models"]')
      expect(models.findAll('li').map(l => l.text())).toEqual(['tev1:latest', 'nimble:latest'])
      expect(models.text()).not.toContain('llama3.2')
      expect(component.find('[data-testid="decision-ollama-address"]').text()).toContain('http://localhost:11434')
      expect(component.find('[data-testid="decision-ollama-address"]').text()).toContain('from Ollama Local')
    })

    it('does not credit Ollama Local for the built-in default address', async () => {
      baseEndpoints()
      const component = await mountDecisionProviders()
      await vi.waitFor(() => expect(component.find('[data-testid="decision-ollama-status"]').text()).toBe('reachable'))
      const address = component.find('[data-testid="decision-ollama-address"]').text()
      expect(address).toContain('http://localhost:11434')
      expect(address).not.toContain('from Ollama Local')
    })

    it('lists only what the server lists, and says the Model Router\'s model is not on this server', async () => {
      stored.set('router.classifier.provider', 'ollama-decision')
      stored.set('router.classifier.model', 'tev1:0.8b')
      baseEndpoints()
      const component = await mountDecisionProviders()
      const models = component.find('[data-testid="decision-ollama-models"]')
      await vi.waitFor(() => expect(models.findAll('li').map(l => words(l.text()))).toEqual(['tev1:latest', 'nimble:latest']))
      expect(models.text()).not.toContain('not installed')
      const row = component.find('[data-testid="decision-ollama-consumer-model-router"]')
      expect(row.findAll('span').map(span => words(span.text()))).toEqual(['prompt classifier (tev1:0.8b)', 'not on this server', 'in use'])
      expect(row.find('[data-testid="decision-ollama-router-model-gone"]').classes()).toContain('text-fg-muted')

      clearNuxtData()
      stored.set('router.classifier.model', 'nimble:latest')
      const listed = await mountDecisionProviders()
      await vi.waitFor(() => expect(listed.find('[data-testid="decision-ollama-status"]').text()).toBe('reachable'))
      expect(listed.find('[data-testid="decision-ollama-router-model-gone"]').exists()).toBe(false)

      clearNuxtData()
      ollama = { ...ollama, reachable: false, error: 'not reachable (ConnectException)', models: [] }
      const down = await mountDecisionProviders()
      await vi.waitFor(() => expect(down.find('[data-testid="decision-ollama-status"]').text()).toBe('not reachable'))
      expect(down.find('[data-testid="decision-ollama-router-model-gone"]').exists()).toBe(false)
    })

    it('an unreachable server says why, and lists nothing', async () => {
      ollama = { baseUrl: 'http://192.168.1.20:11434', customized: true, reachable: false, error: 'not reachable (ConnectException)', models: [] }
      baseEndpoints()
      const component = await mountDecisionProviders()
      await vi.waitFor(() => expect(component.find('[data-testid="decision-ollama-status"]').text()).toBe('not reachable'))
      expect(component.find('[data-testid="decision-ollama-error"]').text()).toBe('not reachable (ConnectException)')
      expect(component.find('[data-testid="decision-ollama-models"]').findAll('li')).toHaveLength(0)
      expect(component.find('[data-testid="decision-ollama-models-note"]').exists()).toBe(false)
      expect(component.find('[data-testid="decision-ollama-address"]').text()).not.toContain('from Ollama Local')
    })

    it('stores a different address, and clears the key when the inherited one is saved back', async () => {
      baseEndpoints()
      const component = await mountDecisionProviders()
      await vi.waitFor(() => expect(component.find('[data-testid="decision-ollama-address-edit"]').exists()).toBe(true))

      await component.find('[data-testid="decision-ollama-address-edit"]').trigger('click')
      await component.find('[data-testid="decision-ollama-address-input"]').setValue('http://192.168.1.20:11434/')
      await component.find('[data-testid="decision-ollama-address-save"]').trigger('click')
      await vi.waitFor(() => expect(posted).toEqual([{ key: 'decision.ollama.baseUrl', value: 'http://192.168.1.20:11434' }]))

      await vi.waitFor(() => expect(component.find('[data-testid="decision-ollama-address-edit"]').exists()).toBe(true))
      await component.find('[data-testid="decision-ollama-address-edit"]').trigger('click')
      await component.find('[data-testid="decision-ollama-address-input"]').setValue('http://localhost:11434')
      await component.find('[data-testid="decision-ollama-address-save"]').trigger('click')
      await vi.waitFor(() => expect(deleted).toEqual(['decision.ollama.baseUrl']))
      expect(posted).toHaveLength(1)
    })

    it('shows the Model Router as not in use until it classifies with an Ollama model, then names the model', async () => {
      stored.set('router.classifier.provider', 'jev')
      stored.set('router.classifier.model', 'jev-latest')
      baseEndpoints()
      const idle = await mountDecisionProviders()
      const row = idle.find('[data-testid="decision-ollama-consumer-model-router"]')
      expect(row.find('a').attributes('href')).toBe('/settings?section=model-router')
      expect(row.text()).toContain('not in use')
      expect(idle.find('[data-testid="decision-ollama-used-by"]').findAll('li')).toHaveLength(1)

      clearNuxtData()
      stored.set('router.classifier.provider', 'ollama-decision')
      stored.set('router.classifier.model', 'tev1:latest')
      const using = await mountDecisionProviders()
      const inUse = using.find('[data-testid="decision-ollama-consumer-model-router"]').text()
      expect(inUse).toContain('tev1:latest')
      expect(inUse).toContain('in use')
      expect(inUse).not.toContain('not in use')
    })

    it('shows its own breaker once the router has called it', async () => {
      breakers = [jevBreaker({ name: 'decision:ollama', target: 'ollama' })]
      baseEndpoints()
      const component = await mountDecisionProviders()
      await vi.waitFor(() => expect(component.find('[data-testid="decision-ollama-breaker"]').exists()).toBe(true))
      expect(component.find('[data-testid="decision-jev-breaker"]').exists()).toBe(false)
    })
  })

  describe('Laya card (JCLAW-1447)', () => {
    it('sits directly below the JEV card', async () => {
      baseEndpoints()
      const component = await mountDecisionProviders()
      const jev = component.find('[data-testid="decision-provider-jev"]').element
      expect(jev.nextElementSibling?.getAttribute('data-testid')).toBe('decision-provider-laya')
    })

    it('shows the Laya portrait beside the note, decorative and sized before it loads', async () => {
      baseEndpoints()
      const component = await mountDecisionProviders()
      const portrait = component.find('[data-testid="decision-laya-portrait"]')
      expect(portrait.attributes('src')).toBe('/laya.webp')
      expect(portrait.attributes('alt')).toBe('')
      expect([portrait.attributes('width'), portrait.attributes('height')]).toEqual(['200', '200'])
      const play = component.find('[data-testid="decision-laya-play"]')
      expect(play.find('[data-testid="decision-laya-portrait"]').exists()).toBe(true)
      expect(play.element.nextElementSibling?.nextElementSibling?.getAttribute('data-testid')).toBe('decision-laya-about')
    })

    it('plays the Laya clip from the start when the portrait is clicked, fetching nothing before', async () => {
      const playSpy = vi.spyOn(HTMLMediaElement.prototype, 'play').mockResolvedValue()
      try {
        baseEndpoints()
        const component = await mountDecisionProviders()

        const button = component.find('[data-testid="decision-laya-play"]')
        expect(button.attributes('type')).toBe('button')
        expect(button.attributes('aria-label')).toBe('Play Laya shouting “Liar!”')
        expect(button.attributes('title')).toBe(button.attributes('aria-label'))
        const audio = component.find('audio[src="/laya.mp3"]')
        expect(audio.attributes('preload')).toBe('none')

        ;(audio.element as HTMLAudioElement).currentTime = 0.5
        await button.trigger('click')
        expect(playSpy).toHaveBeenCalledTimes(1)
        expect(playSpy.mock.contexts[0]).toBe(audio.element)
        expect((audio.element as HTMLAudioElement).currentTime).toBe(0)
      }
      finally {
        playSpy.mockRestore()
      }
    })

    it('says the operator runs the server and the prompt goes only to it', async () => {
      baseEndpoints()
      const component = await mountDecisionProviders()
      const about = words(component.find('[data-testid="decision-laya-about"]').text())
      expect(about).toContain('Apache-2.0')
      expect(about).toContain('You run its server, laya-serve, yourself; JClaw calls the address below and starts nothing.')
      expect(about).toContain('first 4000 characters of each prompt, only to this server')
      expect(component.find('[data-testid="decision-provider-laya"]').text()).not.toContain('record or retain')
    })

    it('points to Laya\'s limits in the user guide instead of listing them', async () => {
      baseEndpoints()
      const component = await mountDecisionProviders()
      const card = component.find('[data-testid="decision-provider-laya"]')
      const link = card.find('[data-testid="decision-laya-limits-link"]')
      expect(link.attributes('href')).toBe('/guide#settings-laya-limits')
      expect(words(component.find('[data-testid="decision-laya-about"]').text()))
        .toContain('Read Laya\'s limits in the user guide before you rely on it.')
      for (const fact of ['zero-shot', 'Over-confident', 'English only', 'LAYA_HOST', 'LAYA_MAX_LOADED', '2.4 s']) {
        expect(card.text()).not.toContain(fact)
      }
    })

    it('with the server not running, still states each checkpoint\'s context and the cut', async () => {
      laya = { baseUrl: 'http://localhost:8000', reachable: false, error: 'not reachable (ConnectException)', loaded: [], device: null }
      baseEndpoints()
      const component = await mountDecisionProviders()
      await vi.waitFor(() => expect(component.find('[data-testid="decision-laya-status"]').text()).toBe('not reachable'))
      expect(component.find('[data-testid="decision-laya-error"]').text()).toBe('not reachable (ConnectException)')
      const checkpoints = component.find('[data-testid="decision-laya-checkpoints"]')
      expect(checkpoints.findAll('li').map(l => words(l.text()))).toEqual([
        'english 512 tokens', 'multilingual 1,024 tokens', 'typed-decisions 1,024 tokens',
      ])
      const cut = component.find('[data-testid="decision-laya-cut"]')
      expect(words(cut.text())).toBe(
        'Each reads that many tokens per question; a longer prompt is cut, not refused (Laya\'s model card). '
        + 'Choose one as the prompt classifier in Settings → Model Router.')
      expect(cut.find('a').attributes('href')).toBe('/settings?section=model-router')
    })

    it('marks the checkpoints the server has loaded, and names its device', async () => {
      laya = { ...laya, loaded: ['english', 'typed-decisions'], device: 'mps' }
      baseEndpoints()
      const component = await mountDecisionProviders()
      await vi.waitFor(() => expect(component.find('[data-testid="decision-laya-status"]').text()).toBe('reachable'))
      expect(words(component.find('[data-testid="decision-laya-device"]').text())).toBe('Server device: mps')
      const row = (name: string) => words(component.find(`[data-testid="decision-laya-checkpoint-${name}"]`).text())
      expect(row('english')).toBe('english 512 tokens, loaded')
      expect(row('multilingual')).toBe('multilingual 1,024 tokens')
      expect(row('typed-decisions')).toBe('typed-decisions 1,024 tokens, loaded')
      expect(component.find('[data-testid="decision-laya-keyed"]').exists()).toBe(false)
    })

    it('a server that wants a key it was not sent reads as reachable, and says how to see more', async () => {
      laya = { ...laya, loaded: [], device: null }
      baseEndpoints()
      const component = await mountDecisionProviders()
      await vi.waitFor(() => expect(component.find('[data-testid="decision-laya-status"]').text()).toBe('reachable'))
      expect(words(component.find('[data-testid="decision-laya-keyed"]').text())).toBe(
        'The server answers /health but says no more. If it is laya-serve started with a key, '
        + 'set or correct the key above to see what it has loaded.')
      expect(component.find('[data-testid="decision-laya-device"]').exists()).toBe(false)
      const rows = component.find('[data-testid="decision-laya-checkpoints"]').findAll('li').map(l => words(l.text()))
      expect(rows).toEqual(['english 512 tokens', 'multilingual 1,024 tokens', 'typed-decisions 1,024 tokens'])
    })

    it('stores a different address and shows it, and clears the key when laya-serve\'s default is saved back', async () => {
      // The card shows the address the status read answers with, so only a re-read after the save can show the new one.
      baseEndpoints({
        onSave: (saved) => {
          if (saved.key === 'decision.laya.baseUrl') laya = { ...laya, baseUrl: saved.value }
        },
      })
      const component = await mountDecisionProviders()
      await vi.waitFor(() => expect(component.find('[data-testid="decision-laya-address"]').text()).toContain('http://localhost:8000'))

      await component.find('[data-testid="decision-laya-address-edit"]').trigger('click')
      const input = component.find('[data-testid="decision-laya-address-input"]')
      expect((input.element as HTMLInputElement).value).toBe('http://localhost:8000')
      await input.setValue('http://192.168.1.30:8000/')
      await component.find('[data-testid="decision-laya-address-save"]').trigger('click')
      await vi.waitFor(() => expect(posted).toEqual([{ key: 'decision.laya.baseUrl', value: 'http://192.168.1.30:8000' }]))
      await vi.waitFor(() => expect(component.find('[data-testid="decision-laya-address"]').text()).toContain('http://192.168.1.30:8000'))

      await vi.waitFor(() => expect(component.find('[data-testid="decision-laya-address-edit"]').exists()).toBe(true))
      await component.find('[data-testid="decision-laya-address-edit"]').trigger('click')
      await component.find('[data-testid="decision-laya-address-input"]').setValue('http://localhost:8000')
      await component.find('[data-testid="decision-laya-address-save"]').trigger('click')
      await vi.waitFor(() => expect(deleted).toEqual(['decision.laya.baseUrl']))
      expect(posted).toHaveLength(1)
    })

    it('a refused address keeps the editor open with the reason on the Laya card alone', async () => {
      baseEndpoints({ failSaves: true })
      const component = await mountDecisionProviders()
      await vi.waitFor(() => expect(component.find('[data-testid="decision-laya-address-edit"]').exists()).toBe(true))
      await component.find('[data-testid="decision-laya-address-edit"]').trigger('click')
      await component.find('[data-testid="decision-laya-address-input"]').setValue('http://169.254.169.254')
      await component.find('[data-testid="decision-laya-address-save"]').trigger('click')

      await vi.waitFor(() => expect(component.find('[data-testid="decision-provider-laya"] [data-testid="api-error"]').exists()).toBe(true))
      expect(component.find('[data-testid="decision-laya-address-input"]').exists()).toBe(true)
      expect(component.find('[data-testid="decision-provider-ollama"] [data-testid="api-error"]').exists()).toBe(false)

      await component.find('[data-testid="decision-laya-address-cancel"]').trigger('click')
      expect(component.find('[data-testid="decision-laya-address-input"]').exists()).toBe(false)
      expect(component.find('[data-testid="decision-provider-laya"] [data-testid="api-error"]').exists()).toBe(false)
    })

    it('the key is optional: unset by default, set through the masked editor, and removable', async () => {
      // A server started with a key says more once the key is sent, so only a re-read after the save can show it.
      laya = { ...laya, loaded: [], device: null }
      baseEndpoints({
        onSave: (saved) => {
          if (saved.key === 'decision.laya.apiKey') laya = { ...laya, loaded: ['english'], device: 'cpu' }
        },
      })
      const component = await mountDecisionProviders()
      expect(component.find('[data-testid="decision-laya-key"]').text()).toBe('(not set)')
      await vi.waitFor(() => expect(component.find('[data-testid="decision-laya-keyed"]').exists()).toBe(true))

      await component.find('button[aria-label="Edit Laya API key"]').trigger('click')
      const input = component.find('input[aria-label="Laya API key"]')
      expect(input.attributes('type')).toBe('password')
      expect((input.element as HTMLInputElement).value).toBe('')
      await input.setValue('laya-secret-123')
      await component.find('button[aria-label="Save Laya API key"]').trigger('click')
      await vi.waitFor(() => expect(posted).toEqual([{ key: 'decision.laya.apiKey', value: 'laya-secret-123' }]))
      await vi.waitFor(() => expect(component.find('[data-testid="decision-laya-key"]').text()).toBe('••••••••'))
      expect(component.html()).not.toContain('laya-secret-123')
      expect(component.find('[data-testid="decision-jev-key"]').text()).toBe('(not set)')
      await vi.waitFor(() => expect(component.find('[data-testid="decision-laya-device"]').exists()).toBe(true))
      expect(component.find('[data-testid="decision-laya-keyed"]').exists()).toBe(false)

      await component.find('button[aria-label="Edit Laya API key"]').trigger('click')
      await component.find('button[aria-label="Remove Laya API key"]').trigger('click')
      await vi.waitFor(() => expect(posted).toHaveLength(2))
      expect(posted[1]).toEqual({ key: 'decision.laya.apiKey', value: '' })
    })

    it('shows the Model Router as not in use until it classifies with a Laya checkpoint, then names it', async () => {
      stored.set('router.classifier.provider', 'ollama-decision')
      stored.set('router.classifier.model', 'tev1:latest')
      baseEndpoints()
      const idle = await mountDecisionProviders()
      const row = idle.find('[data-testid="decision-laya-consumer-model-router"]')
      expect(row.find('a').attributes('href')).toBe('/settings?section=model-router')
      expect(row.text()).toContain('not in use')
      expect(idle.find('[data-testid="decision-laya-used-by"]').findAll('li')).toHaveLength(1)

      clearNuxtData()
      stored.set('router.classifier.provider', 'laya')
      stored.set('router.classifier.model', 'english')
      const using = await mountDecisionProviders()
      const inUse = using.find('[data-testid="decision-laya-consumer-model-router"]').text()
      expect(inUse).toContain('english')
      expect(inUse).toContain('in use')
      expect(inUse).not.toContain('not in use')
      expect(using.find('[data-testid="decision-ollama-consumer-model-router"]').text()).toContain('not in use')
    })

    it('shows its own breaker once the router has called it, with Isolate, and Restore once isolated', async () => {
      breakers = [jevBreaker({ name: 'decision:laya', target: 'laya' })]
      baseEndpoints()
      const closed = await mountDecisionProviders()
      await vi.waitFor(() => expect(closed.find('[data-testid="decision-laya-breaker"]').exists()).toBe(true))
      expect(closed.find('[data-testid="decision-laya-breaker"] button[aria-label="Isolate laya"]').exists()).toBe(true)
      expect(closed.find('[data-testid="decision-jev-breaker"]').exists()).toBe(false)
      expect(closed.find('[data-testid="decision-ollama-breaker"]').exists()).toBe(false)

      clearNuxtData()
      breakers = [jevBreaker({ name: 'decision:laya', target: 'laya', state: 'OPEN', reason: 'MANUAL_TRIP', manual: true })]
      const isolated = await mountDecisionProviders()
      await vi.waitFor(() => expect(isolated.find('[data-testid="decision-laya-breaker"]').exists()).toBe(true))
      const breaker = isolated.find('[data-testid="decision-laya-breaker"]')
      expect(breaker.text()).toContain('isolated by you')
      expect(breaker.find('button[aria-label="Restore laya"]').exists()).toBe(true)
    })
  })

  describe('Baseten card (JF-133)', () => {
    const MAMMOTH = 'decision-models/baseten-mammoth-27b-latest'

    it('is the last card, directly below the Ollama card', async () => {
      baseEndpoints()
      const component = await mountDecisionProviders()
      const ollama = component.find('[data-testid="decision-provider-ollama"]').element
      expect(ollama.nextElementSibling?.getAttribute('data-testid')).toBe('decision-provider-baseten')
      expect(ollama.nextElementSibling?.nextElementSibling).toBeNull()
    })

    it('without a key reads needs API key and lists both models with their context and size', async () => {
      baseEndpoints()
      const component = await mountDecisionProviders()
      expect(component.find('[data-testid="decision-baseten-status"]').text()).toBe('needs API key')
      expect(component.find('[data-testid="decision-baseten-key"]').text()).toBe('(not set)')
      const models = component.find('[data-testid="decision-baseten-models"]')
      expect(models.findAll('li').map(l => words(l.text()))).toEqual([
        'elephant-1 16,384 tokens, 9B parameters',
        'mammoth-1 16,384 tokens, 27B parameters',
      ])
      expect(models.findAll('p').map(p => words(p.text()))).toEqual(['Choose one as the prompt classifier in Settings → Model Router.'])
      expect(models.find('[data-testid="decision-baseten-models-note"] a').attributes('href')).toBe('/settings?section=model-router')
      expect(component.find('[data-testid="decision-baseten-breaker"]').exists()).toBe(false)
    })

    it('says what the models are, that Baseten runs them and gets the prompt, and links the guide', async () => {
      baseEndpoints()
      const component = await mountDecisionProviders()
      const about = component.find('[data-testid="decision-baseten-about"]')
      expect(words(about.text())).toBe(
        'Decision models from Base Labs, Baseten\'s research team, which answer the same questions JEV does. '
        + 'Baseten runs them; JClaw calls Baseten\'s own address with the key below. The Model Router\'s classifier '
        + 'sends the first 4000 characters of each prompt to Baseten. Read Baseten\'s data and billing in the user '
        + 'guide before you choose one.')
      expect(about.find('[data-testid="decision-baseten-data-link"]').attributes('href')).toBe('/guide#settings-baseten-data')
      const card = component.find('[data-testid="decision-provider-baseten"]')
      expect(card.text()).not.toContain('record or retain')
      expect(card.find('[data-testid="decision-baseten-address"]').exists()).toBe(false)
    })

    it('shows the portrait beside the note, decorative and sized before it loads', async () => {
      baseEndpoints()
      const component = await mountDecisionProviders()
      const portrait = component.find('[data-testid="decision-baseten-portrait"]')
      expect(portrait.attributes('src')).toBe('/bass.webp')
      expect(portrait.attributes('alt')).toBe('')
      expect([portrait.attributes('width'), portrait.attributes('height')]).toEqual(['200', '268'])
      const play = component.find('[data-testid="decision-baseten-play"]')
      expect(play.find('[data-testid="decision-baseten-portrait"]').exists()).toBe(true)
      expect(play.element.nextElementSibling?.nextElementSibling?.getAttribute('data-testid')).toBe('decision-baseten-about')
    })

    it('plays the clip from the start when the portrait is clicked, fetching nothing before', async () => {
      const playSpy = vi.spyOn(HTMLMediaElement.prototype, 'play').mockResolvedValue()
      try {
        baseEndpoints()
        const component = await mountDecisionProviders()

        const button = component.find('[data-testid="decision-baseten-play"]')
        expect(button.attributes('type')).toBe('button')
        expect(button.attributes('aria-label')).toBe('Play a voice singing “I’m all about that bass”')
        expect(button.attributes('title')).toBe(button.attributes('aria-label'))
        const audio = component.find('audio[src="/bass.mp3"]')
        expect(audio.attributes('preload')).toBe('none')

        ;(audio.element as HTMLAudioElement).currentTime = 2.1
        await button.trigger('click')
        expect(playSpy).toHaveBeenCalledTimes(1)
        expect(playSpy.mock.contexts[0]).toBe(audio.element)
        expect((audio.element as HTMLAudioElement).currentTime).toBe(0)
      }
      finally {
        playSpy.mockRestore()
      }
    })

    it('sets the key through the masked editor, and then reads configured', async () => {
      baseEndpoints()
      const component = await mountDecisionProviders()

      await component.find('button[aria-label="Edit Baseten API key"]').trigger('click')
      const input = component.find('input[aria-label="Baseten API key"]')
      expect(input.attributes('type')).toBe('password')
      expect((input.element as HTMLInputElement).value).toBe('')
      await input.setValue('bt-secret-123')
      await component.find('button[aria-label="Save Baseten API key"]').trigger('click')
      await vi.waitFor(() => expect(posted).toEqual([{ key: 'decision.baseten.apiKey', value: 'bt-secret-123' }]))
      await vi.waitFor(() => expect(component.find('[data-testid="decision-baseten-key"]').text()).toBe('••••••••'))
      expect(component.find('[data-testid="decision-baseten-status"]').text()).toBe('configured')
      expect(component.html()).not.toContain('bt-secret-123')
      expect(component.find('[data-testid="decision-jev-status"]').text()).toBe('needs API key')
    })

    it('Remove sends a blank key, and the card then reads needs API key', async () => {
      stored.set('decision.baseten.apiKey', 'bt-s****')
      baseEndpoints()
      const component = await mountDecisionProviders()
      expect(component.find('[data-testid="decision-baseten-status"]').text()).toBe('configured')

      await component.find('button[aria-label="Edit Baseten API key"]').trigger('click')
      await component.find('button[aria-label="Remove Baseten API key"]').trigger('click')
      await vi.waitFor(() => expect(posted).toEqual([{ key: 'decision.baseten.apiKey', value: '' }]))
      await vi.waitFor(() => expect(component.find('[data-testid="decision-baseten-status"]').text()).toBe('needs API key'))
      expect(component.find('[data-testid="decision-baseten-key"]').text()).toBe('(not set)')
    })

    it('a refused key keeps the editor open with the reason on the Baseten card', async () => {
      baseEndpoints({ failSaves: true })
      const component = await mountDecisionProviders()

      await component.find('button[aria-label="Edit Baseten API key"]').trigger('click')
      await component.find('input[aria-label="Baseten API key"]').setValue('bt key')
      await component.find('button[aria-label="Save Baseten API key"]').trigger('click')

      await vi.waitFor(() => expect(component.find('[data-testid="decision-provider-baseten"] [data-testid="api-error"]').exists()).toBe(true))
      expect(component.find('input[aria-label="Baseten API key"]').exists()).toBe(true)
      expect(component.find('[data-testid="decision-provider-jev"] [data-testid="api-error"]').exists()).toBe(false)
    })

    it('shows the Model Router as not in use until it classifies with a Baseten model, then names it', async () => {
      stored.set('router.classifier.provider', 'laya')
      stored.set('router.classifier.model', 'english')
      baseEndpoints()
      const idle = await mountDecisionProviders()
      const row = idle.find('[data-testid="decision-baseten-consumer-model-router"]')
      expect(row.find('a').attributes('href')).toBe('/settings?section=model-router')
      expect(row.text()).toContain('not in use')
      expect(idle.find('[data-testid="decision-baseten-used-by"]').findAll('li')).toHaveLength(1)

      clearNuxtData()
      stored.set('router.classifier.provider', 'baseten-decision')
      stored.set('router.classifier.model', MAMMOTH)
      const using = await mountDecisionProviders()
      const inUse = using.find('[data-testid="decision-baseten-consumer-model-router"]').text()
      expect(inUse).toContain('prompt classifier (mammoth-1)')
      expect(inUse).toContain('in use')
      expect(inUse).not.toContain('not in use')
      expect(inUse).not.toContain(MAMMOTH)
      expect(using.find('[data-testid="decision-laya-consumer-model-router"]').text()).toContain('not in use')
    })

    it('shows its own breaker once the router has called it, with Isolate', async () => {
      breakers = [jevBreaker({ name: 'decision:baseten', target: 'baseten' })]
      baseEndpoints()
      const component = await mountDecisionProviders()
      await vi.waitFor(() => expect(component.find('[data-testid="decision-baseten-breaker"]').exists()).toBe(true))
      expect(component.find('[data-testid="decision-baseten-breaker"] button[aria-label="Isolate baseten"]').exists()).toBe(true)
      expect(component.find('[data-testid="decision-jev-breaker"]').exists()).toBe(false)
      expect(component.find('[data-testid="decision-laya-breaker"]').exists()).toBe(false)
      expect(component.find('[data-testid="decision-ollama-breaker"]').exists()).toBe(false)
    })
  })
})
