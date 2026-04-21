import { describe, it, expect } from 'vitest'
import { ref } from 'vue'
import {
  applyModelOption,
  buildModelOptions,
  filterModelOptions,
  isModelArgumentContext,
  nextAutocompleteIndex,
  useModelAutocomplete,
  MODEL_COMMAND_PREFIX,
} from '~/composables/useModelAutocomplete'
import type { Provider } from '~/composables/useProviders'

/**
 * JCLAW-114: pure-logic coverage for the /model autocomplete helpers and
 * the composable's reactive state machine. No DOM, no Vue-page mount.
 */

function makeProviders(): Provider[] {
  return [
    {
      name: 'openrouter',
      models: [
        { id: 'gpt-4.1' },
        { id: 'google-flash-preview' },
        { id: 'claude-sonnet-4-6' },
      ],
    },
    {
      name: 'ollama-cloud',
      models: [
        { id: 'kimi-k2.5' },
        { id: 'qwen3.5' },
      ],
    },
  ]
}

// ── isModelArgumentContext ──

describe('isModelArgumentContext', () => {
  it('matches /model with trailing space', () => {
    expect(isModelArgumentContext('/model ')).toBe(true)
    expect(isModelArgumentContext('/model openrouter')).toBe(true)
  })

  it('is case-insensitive on the command prefix', () => {
    expect(isModelArgumentContext('/Model openrouter')).toBe(true)
    expect(isModelArgumentContext('/MODEL ')).toBe(true)
  })

  it('does not match without trailing space', () => {
    expect(isModelArgumentContext('/model')).toBe(false)
    expect(isModelArgumentContext('/modelx')).toBe(false)
  })

  it('skips fixed sub-keywords status and reset', () => {
    expect(isModelArgumentContext('/model status')).toBe(false)
    expect(isModelArgumentContext('/model reset')).toBe(false)
    expect(isModelArgumentContext('/model STATUS')).toBe(false)
    expect(isModelArgumentContext('/model Reset')).toBe(false)
  })

  it('still matches when user has typed past "status" (e.g. starting a partial provider name)', () => {
    // "statu" isn't an exact fixed keyword — could be start of a provider name.
    expect(isModelArgumentContext('/model statu')).toBe(true)
  })

  it('rejects non-slash text and other slash commands', () => {
    expect(isModelArgumentContext('hello')).toBe(false)
    expect(isModelArgumentContext('/help')).toBe(false)
    expect(isModelArgumentContext('/usage ')).toBe(false)
    expect(isModelArgumentContext('')).toBe(false)
  })
})

// ── buildModelOptions ──

describe('buildModelOptions', () => {
  it('flattens providers into provider/model-id strings', () => {
    const opts = buildModelOptions(makeProviders())
    expect(opts).toEqual([
      'openrouter/gpt-4.1',
      'openrouter/google-flash-preview',
      'openrouter/claude-sonnet-4-6',
      'ollama-cloud/kimi-k2.5',
      'ollama-cloud/qwen3.5',
    ])
  })

  it('returns empty when no providers', () => {
    expect(buildModelOptions([])).toEqual([])
  })

  it('skips providers with no models', () => {
    const providers: Provider[] = [{ name: 'empty', models: [] }]
    expect(buildModelOptions(providers)).toEqual([])
  })
})

// ── filterModelOptions ──

describe('filterModelOptions', () => {
  const allOptions = buildModelOptions(makeProviders())

  it('returns empty when not in /model argument context', () => {
    expect(filterModelOptions(allOptions, 'hello')).toEqual([])
    expect(filterModelOptions(allOptions, '/model status')).toEqual([])
    expect(filterModelOptions(allOptions, '/model reset')).toEqual([])
  })

  it('returns all options for empty query after /model ', () => {
    expect(filterModelOptions(allOptions, '/model ')).toEqual(allOptions)
  })

  it('matches substring in provider portion', () => {
    expect(filterModelOptions(allOptions, '/model open')).toEqual([
      'openrouter/gpt-4.1',
      'openrouter/google-flash-preview',
      'openrouter/claude-sonnet-4-6',
    ])
  })

  it('matches substring in model-id portion', () => {
    expect(filterModelOptions(allOptions, '/model gpt')).toEqual(['openrouter/gpt-4.1'])
    expect(filterModelOptions(allOptions, '/model kimi')).toEqual(['ollama-cloud/kimi-k2.5'])
  })

  it('matches across the slash boundary', () => {
    // Provider prefix plus slash plus partial model id — covered by substring.
    expect(filterModelOptions(allOptions, '/model ollama-cloud/q')).toEqual(['ollama-cloud/qwen3.5'])
  })

  it('is case-insensitive', () => {
    expect(filterModelOptions(allOptions, '/model GPT')).toEqual(['openrouter/gpt-4.1'])
    expect(filterModelOptions(allOptions, '/model KIMI')).toEqual(['ollama-cloud/kimi-k2.5'])
  })

  it('returns empty when nothing matches', () => {
    expect(filterModelOptions(allOptions, '/model zzz-nonexistent')).toEqual([])
  })
})

// ── applyModelOption ──

describe('applyModelOption', () => {
  it('replaces the argument portion with the chosen id', () => {
    expect(applyModelOption('/model open', 'openrouter/gpt-4.1'))
      .toBe('/model openrouter/gpt-4.1')
  })

  it('works when the user had just typed /model with a trailing space', () => {
    expect(applyModelOption('/model ', 'ollama-cloud/kimi-k2.5'))
      .toBe('/model ollama-cloud/kimi-k2.5')
  })

  it('leaves non-/model text unchanged', () => {
    expect(applyModelOption('hello', 'openrouter/gpt-4.1')).toBe('hello')
  })

  it('preserves the command literal case-insensitively', () => {
    // We don't preserve the user's exact casing of /Model — we normalize to the canonical prefix.
    // That's acceptable: the command parser is case-insensitive.
    expect(applyModelOption('/Model open', 'openrouter/gpt-4.1'))
      .toBe(`${MODEL_COMMAND_PREFIX}openrouter/gpt-4.1`)
  })
})

// ── nextAutocompleteIndex ──

describe('nextAutocompleteIndex', () => {
  it('moves down with wrap', () => {
    expect(nextAutocompleteIndex(0, 3, 'down')).toBe(1)
    expect(nextAutocompleteIndex(1, 3, 'down')).toBe(2)
    expect(nextAutocompleteIndex(2, 3, 'down')).toBe(0) // wraps
  })

  it('moves up with wrap', () => {
    expect(nextAutocompleteIndex(2, 3, 'up')).toBe(1)
    expect(nextAutocompleteIndex(1, 3, 'up')).toBe(0)
    expect(nextAutocompleteIndex(0, 3, 'up')).toBe(2) // wraps
  })

  it('returns 0 for empty total', () => {
    expect(nextAutocompleteIndex(0, 0, 'down')).toBe(0)
    expect(nextAutocompleteIndex(0, 0, 'up')).toBe(0)
  })
})

// ── Composable state machine ──

describe('useModelAutocomplete composable', () => {
  it('opens when /model <query> matches options', () => {
    const providers = ref(makeProviders())
    const ac = useModelAutocomplete(providers)

    expect(ac.open.value).toBe(false)
    ac.update('/model gpt')
    expect(ac.open.value).toBe(true)
    expect(ac.options.value).toEqual(['openrouter/gpt-4.1'])
    expect(ac.highlighted.value).toBe('openrouter/gpt-4.1')
  })

  it('closes when query no longer matches anything', () => {
    const providers = ref(makeProviders())
    const ac = useModelAutocomplete(providers)
    ac.update('/model ')
    expect(ac.open.value).toBe(true)
    ac.update('/model zzz-nothing-matches')
    expect(ac.open.value).toBe(false)
  })

  it('closes on fixed sub-keywords', () => {
    const providers = ref(makeProviders())
    const ac = useModelAutocomplete(providers)
    ac.update('/model ')
    expect(ac.open.value).toBe(true)
    ac.update('/model status')
    expect(ac.open.value).toBe(false)
  })

  it('clamps highlighted index when filter shrinks options', () => {
    const providers = ref(makeProviders())
    const ac = useModelAutocomplete(providers)
    ac.update('/model ')
    ac.moveHighlight('down')
    ac.moveHighlight('down')
    ac.moveHighlight('down')
    // highlightedIndex now > 0
    ac.update('/model gpt') // only 1 option matches
    expect(ac.highlightedIndex.value).toBe(0)
  })

  it('moveHighlight cycles through options with wrap', () => {
    const providers = ref(makeProviders())
    const ac = useModelAutocomplete(providers)
    ac.update('/model open') // 3 options (all openrouter)
    expect(ac.highlightedIndex.value).toBe(0)
    ac.moveHighlight('down')
    expect(ac.highlightedIndex.value).toBe(1)
    ac.moveHighlight('down')
    expect(ac.highlightedIndex.value).toBe(2)
    ac.moveHighlight('down')
    expect(ac.highlightedIndex.value).toBe(0) // wrap
    ac.moveHighlight('up')
    expect(ac.highlightedIndex.value).toBe(2)
  })

  it('accept returns replacement text and closes', () => {
    const providers = ref(makeProviders())
    const ac = useModelAutocomplete(providers)
    ac.update('/model gpt')
    const result = ac.accept('/model gpt')
    expect(result).toBe('/model openrouter/gpt-4.1')
    expect(ac.open.value).toBe(false)
  })

  it('accept returns null when popup is closed', () => {
    const providers = ref(makeProviders())
    const ac = useModelAutocomplete(providers)
    expect(ac.accept('anything')).toBeNull()
  })

  it('close() hides popup and resets state', () => {
    const providers = ref(makeProviders())
    const ac = useModelAutocomplete(providers)
    ac.update('/model ')
    ac.moveHighlight('down')
    ac.close()
    expect(ac.open.value).toBe(false)
    expect(ac.options.value).toEqual([])
    expect(ac.highlightedIndex.value).toBe(0)
  })

  it('reacts to provider list changes on update()', () => {
    const providers = ref<Provider[]>([
      { name: 'openai', models: [{ id: 'gpt-5' }] },
    ])
    const ac = useModelAutocomplete(providers)
    ac.update('/model ')
    expect(ac.options.value).toEqual(['openai/gpt-5'])

    providers.value = [
      { name: 'anthropic', models: [{ id: 'opus-4-7' }] },
    ]
    ac.update('/model ')
    expect(ac.options.value).toEqual(['anthropic/opus-4-7'])
  })
})
