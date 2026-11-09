import { describe, it, expect } from 'vitest'
import { ref } from 'vue'
import { isCommandContext, filterSlashCommands, slashCommandSource } from '~/composables/slashCommandSource'
import { modelCompletionSource } from '~/composables/modelCompletionSource'
import { useComposerCompleter } from '~/composables/useComposerCompleter'
import type { Provider } from '~/composables/useProviders'
import type { SlashCommand } from '~/types/api'

/**
 * JCLAW-1071: the "/" command menu — context detection, filtering, and the
 * handoff between the command source and the /model argument source inside
 * the shared completer.
 */

const COMMANDS: SlashCommand[] = [
  { literal: '/new', name: 'new', description: 'Start a fresh conversation' },
  { literal: '/model', name: 'model', description: 'Show current model and its capabilities' },
  { literal: '/compact', name: 'compact', description: 'Summarize older turns to free context' },
  { literal: '/stop', name: 'stop', description: 'Stop the current reply' },
]

const PROVIDERS: Provider[] = [{ name: 'openrouter', models: [{ id: 'gpt-4.1' }] }]

function completer() {
  return useComposerCompleter([
    slashCommandSource(ref(COMMANDS)),
    modelCompletionSource(ref(PROVIDERS)),
  ])
}

// ── isCommandContext ──

describe('isCommandContext', () => {
  it('matches a bare slash and a partial command', () => {
    expect(isCommandContext('/')).toBe(true)
    expect(isCommandContext('/mo')).toBe(true)
    expect(isCommandContext('/subagent')).toBe(true)
  })

  it('stops at the first whitespace, so arguments are not command context', () => {
    expect(isCommandContext('/model ')).toBe(false)
    expect(isCommandContext('/compact focus on auth')).toBe(false)
  })

  it('rejects text that does not start with a slash', () => {
    expect(isCommandContext('')).toBe(false)
    expect(isCommandContext('hello')).toBe(false)
    // A slash mid-sentence must not pop the menu — the common case is a path
    // or a date inside an ordinary message.
    expect(isCommandContext('see app/models/Prompt.java')).toBe(false)
    expect(isCommandContext('what about 3/4 of them')).toBe(false)
  })
})

// ── filterSlashCommands ──

describe('filterSlashCommands', () => {
  it('returns every command for a bare slash', () => {
    expect(filterSlashCommands(COMMANDS, '/')).toHaveLength(4)
  })

  it('narrows by literal prefix', () => {
    expect(filterSlashCommands(COMMANDS, '/mo').map(c => c.literal)).toEqual(['/model'])
  })

  it('is case-insensitive', () => {
    expect(filterSlashCommands(COMMANDS, '/MO').map(c => c.literal)).toEqual(['/model'])
  })

  it('returns empty when nothing matches the prefix', () => {
    expect(filterSlashCommands(COMMANDS, '/zzz')).toEqual([])
  })

  it('returns empty outside command context', () => {
    expect(filterSlashCommands(COMMANDS, '/model gpt')).toEqual([])
  })
})

// ── Inside the shared completer ──

describe('slash command source in the shared completer', () => {
  it('opens on "/" with every command and its description', () => {
    const ac = completer()
    ac.update('/')
    expect(ac.open.value).toBe(true)
    expect(ac.activeSourceId.value).toBe('slash-command')
    expect(ac.ariaLabel.value).toBe('Slash command options')
    expect(ac.options.value[0]).toEqual({
      value: '/new',
      label: '/new',
      detail: 'Start a fresh conversation',
    })
  })

  it('filters as the user types further', () => {
    const ac = completer()
    ac.update('/')
    expect(ac.options.value).toHaveLength(4)
    ac.update('/mo')
    expect(ac.options.value.map(o => o.value)).toEqual(['/model'])
  })

  it('accepting inserts the literal plus a trailing space, without sending', () => {
    const ac = completer()
    ac.update('/mo')
    expect(ac.accept('/mo')).toBe('/model ')
    expect(ac.open.value).toBe(false)
  })

  it('hands off to the model source once a space ends command context', () => {
    const ac = completer()
    ac.update('/mo')
    expect(ac.activeSourceId.value).toBe('slash-command')
    // Exactly what accepting "/model" leaves in the textarea.
    ac.update('/model ')
    expect(ac.open.value).toBe(true)
    expect(ac.activeSourceId.value).toBe('model')
    expect(ac.options.value.map(o => o.value)).toEqual(['openrouter/gpt-4.1'])
  })

  it('closes when no command matches rather than falling through to another source', () => {
    const ac = completer()
    ac.update('/zzz')
    expect(ac.open.value).toBe(false)
  })

  it('stays closed for ordinary text containing a slash', () => {
    const ac = completer()
    ac.update('deploy to prod/main today')
    expect(ac.open.value).toBe(false)
  })

  it('is empty until the lazy fetch resolves', () => {
    const empty = useComposerCompleter([slashCommandSource(ref<SlashCommand[]>([]))])
    empty.update('/')
    expect(empty.open.value).toBe(false)
  })
})
