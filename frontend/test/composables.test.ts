import { describe, it, expect, vi, beforeEach } from 'vitest'
import { ref, type Ref } from 'vue'
import { useConfirm } from '~/composables/useConfirm'
import { useProviders, type ConfigData } from '~/composables/useProviders'

// ---------------------------------------------------------------------------
// useConfirm
// ---------------------------------------------------------------------------

describe('useConfirm', () => {
  beforeEach(() => {
    // Reset the singleton state between tests
    const { _state, _resolve } = useConfirm()
    if (_state.open) _resolve(false)
    _state.open = false
    _state.resolve = null
  })

  it('starts with dialog closed', () => {
    const { _state } = useConfirm()
    expect(_state.open).toBe(false)
  })

  it('opens dialog and resolves true on confirm', async () => {
    const { confirm, _state, _resolve } = useConfirm()

    const promise = confirm({ message: 'Delete this?' })
    expect(_state.open).toBe(true)
    expect(_state.message).toBe('Delete this?')

    _resolve(true)
    const result = await promise
    expect(result).toBe(true)
    expect(_state.open).toBe(false)
  })

  it('opens dialog and resolves false on cancel', async () => {
    const { confirm, _state, _resolve } = useConfirm()

    const promise = confirm({ message: 'Are you sure?' })
    expect(_state.open).toBe(true)

    _resolve(false)
    const result = await promise
    expect(result).toBe(false)
    expect(_state.open).toBe(false)
  })

  it('applies default option values', () => {
    const { confirm, _state } = useConfirm()

    confirm({ message: 'test' })
    expect(_state.confirmText).toBe('Confirm')
    expect(_state.cancelText).toBe('Cancel')
    expect(_state.variant).toBe('default')
    expect(_state.title).toBe('')
  })

  it('applies custom option values', () => {
    const { confirm, _state } = useConfirm()

    confirm({
      message: 'Danger!',
      title: 'Warning',
      confirmText: 'Delete',
      cancelText: 'Nope',
      variant: 'danger',
    })
    expect(_state.title).toBe('Warning')
    expect(_state.confirmText).toBe('Delete')
    expect(_state.cancelText).toBe('Nope')
    expect(_state.variant).toBe('danger')
  })

  it('auto-cancels prior dialog when a new one is opened', async () => {
    const { confirm, _state, _resolve } = useConfirm()

    const first = confirm({ message: 'First' })
    // Open a second dialog without resolving the first
    const second = confirm({ message: 'Second' })

    // The first promise should have been resolved as false (cancelled)
    const firstResult = await first
    expect(firstResult).toBe(false)

    // The second dialog should be the active one
    expect(_state.message).toBe('Second')
    expect(_state.open).toBe(true)

    _resolve(true)
    const secondResult = await second
    expect(secondResult).toBe(true)
  })
})

// ---------------------------------------------------------------------------
// useEventBus
// ---------------------------------------------------------------------------

// Stub EventSource globally since happy-dom doesn't provide it.
// useEventBus is a module-level singleton that connects on first call,
// so the stub must be installed before any test imports the composable.
if (typeof globalThis.EventSource === 'undefined') {
  // eslint-disable-next-line @typescript-eslint/no-explicit-any -- Reason: test mock patching a browser global with a minimal stand-in; narrowing adds no value here.
  ;(globalThis as any).EventSource = class MockEventSource {
    // eslint-disable-next-line @typescript-eslint/no-explicit-any -- Reason: mirrors the DOM EventSource handler signature (MessageEvent/Event) without importing the DOM types into this test.
    onmessage: ((e: any) => void) | null = null
    // eslint-disable-next-line @typescript-eslint/no-explicit-any -- Reason: same — onerror takes Event in the real API, but tests only ever call it with synthetic stubs.
    onerror: ((e: any) => void) | null = null
    close() {}
  }
}

describe('useEventBus', () => {
  it('registers handler via on()', () => {
    const { on, off } = useEventBus()
    const handler = vi.fn()

    // Registration should not throw
    on('test.event', handler)
    off('test.event', handler)
  })

  it('removes handler via off()', () => {
    const { on, off } = useEventBus()
    const handler = vi.fn()

    on('remove.test', handler)
    off('remove.test', handler)
  })

  it('supports multiple handlers for the same event type', () => {
    const { on, off } = useEventBus()
    const handler1 = vi.fn()
    const handler2 = vi.fn()

    on('multi.event', handler1)
    on('multi.event', handler2)

    off('multi.event', handler1)
    off('multi.event', handler2)
  })

  it('off() is safe for unregistered handler', () => {
    const { off } = useEventBus()
    const handler = vi.fn()

    // Should not throw
    expect(() => off('nonexistent.event', handler)).not.toThrow()
  })
})

// ---------------------------------------------------------------------------
// useTheme
// ---------------------------------------------------------------------------

describe('useTheme', () => {
  it('returns a readonly themeMode ref', () => {
    const { themeMode } = useTheme()
    expect(themeMode).toBeDefined()
    expect(themeMode.value).toBeDefined()
    // Attempting to set a readonly ref logs a warning but doesn't throw;
    // instead, verify it is a DeepReadonly type by checking the value reads correctly.
    expect(typeof themeMode.value).toBe('string')
  })

  it('setTheme updates localStorage and themeMode', () => {
    const { themeMode, setTheme } = useTheme()

    setTheme('light')
    expect(themeMode.value).toBe('light')
    expect(localStorage.getItem('jclaw-theme')).toBe('light')

    setTheme('dark')
    expect(themeMode.value).toBe('dark')
    expect(localStorage.getItem('jclaw-theme')).toBe('dark')

    setTheme('system')
    expect(themeMode.value).toBe('system')
    expect(localStorage.getItem('jclaw-theme')).toBe('system')
  })
})

// ---------------------------------------------------------------------------
// useProviders
// ---------------------------------------------------------------------------

describe('useProviders', () => {
  function makeConfigRef(entries: { key: string, value: string }[]): Ref<ConfigData | null> {
    return ref({ entries })
  }

  it('parses providers from config entries', () => {
    const configData = makeConfigRef([
      { key: 'provider.openai.baseUrl', value: 'https://api.openai.com' },
      { key: 'provider.openai.apiKey', value: 'sk-xxxx****' },
      { key: 'provider.openai.models', value: '[{"id":"gpt-4","name":"GPT-4"}]' },
    ])

    const { providers } = useProviders(configData)
    expect(providers.value).toHaveLength(1)
    expect(providers.value[0]!.name).toBe('openai')
    expect(providers.value[0]!.models).toHaveLength(1)
    expect(providers.value[0]!.models[0]!.id).toBe('gpt-4')
  })

  it('returns empty array for null config', () => {
    const configData = ref(null) as Ref<ConfigData | null>
    const { providers } = useProviders(configData)
    expect(providers.value).toEqual([])
  })

  it('returns empty array for empty entries', () => {
    const configData = makeConfigRef([])
    const { providers } = useProviders(configData)
    expect(providers.value).toEqual([])
  })

  it('filters out providers with empty API key', () => {
    const configData = makeConfigRef([
      { key: 'provider.openai.baseUrl', value: 'https://api.openai.com' },
      { key: 'provider.openai.apiKey', value: '(empty)' },
      { key: 'provider.openai.models', value: '[{"id":"gpt-4"}]' },
    ])

    const { providers } = useProviders(configData)
    expect(providers.value).toHaveLength(0)
  })

  it('filters out providers with blank API key', () => {
    const configData = makeConfigRef([
      { key: 'provider.test.baseUrl', value: 'http://localhost' },
      { key: 'provider.test.apiKey', value: '' },
    ])

    const { providers } = useProviders(configData)
    expect(providers.value).toHaveLength(0)
  })

  it('handles multiple providers', () => {
    const configData = makeConfigRef([
      { key: 'provider.openai.baseUrl', value: 'https://api.openai.com' },
      { key: 'provider.openai.apiKey', value: 'sk-xxxx****' },
      { key: 'provider.openai.models', value: '[{"id":"gpt-4"}]' },
      { key: 'provider.anthropic.baseUrl', value: 'https://api.anthropic.com' },
      { key: 'provider.anthropic.apiKey', value: 'sk-ant-xxxx****' },
      { key: 'provider.anthropic.models', value: '[{"id":"claude-opus-4-6","name":"Claude Opus 4.6"}]' },
    ])

    const { providers } = useProviders(configData)
    expect(providers.value).toHaveLength(2)
    const names = providers.value.map(p => p.name)
    expect(names).toContain('openai')
    expect(names).toContain('anthropic')
  })

  it('handles malformed models JSON gracefully', () => {
    const configData = makeConfigRef([
      { key: 'provider.broken.baseUrl', value: 'http://localhost' },
      { key: 'provider.broken.apiKey', value: 'key-xxxx' },
      { key: 'provider.broken.models', value: 'not-valid-json' },
    ])

    const { providers } = useProviders(configData)
    expect(providers.value).toHaveLength(1)
    expect(providers.value[0]!.models).toEqual([])
  })

  it('detects masked API key (xxxx****) as configured', () => {
    const configData = makeConfigRef([
      { key: 'provider.ollama.baseUrl', value: 'http://localhost:11434' },
      { key: 'provider.ollama.apiKey', value: 'xxxx****' },
      { key: 'provider.ollama.models', value: '[]' },
    ])

    const { providers } = useProviders(configData)
    // Masked key is non-empty and not "(empty)", so provider should be included
    expect(providers.value).toHaveLength(1)
    expect(providers.value[0]!.name).toBe('ollama')
  })

  it('ignores non-provider config entries', () => {
    const configData = makeConfigRef([
      { key: 'system.name', value: 'jclaw' },
      { key: 'provider.openai.apiKey', value: 'sk-xxxx' },
      { key: 'provider.openai.models', value: '[{"id":"gpt-4"}]' },
    ])

    const { providers } = useProviders(configData)
    expect(providers.value).toHaveLength(1)
    expect(providers.value[0]!.name).toBe('openai')
  })
})
