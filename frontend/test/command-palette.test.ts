import { describe, it, expect, afterEach } from 'vitest'
import { mountSuspended, registerEndpoint } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import { defineComponent, h, nextTick, ref } from 'vue'
import CommandPalette from '~/components/CommandPalette.vue'

function setupMockApi() {
  registerEndpoint('/api/agents', () => [
    { id: 1, name: 'main', modelProvider: 'ollama-cloud', modelId: 'kimi-k2.5', enabled: true },
    { id: 2, name: 'Test', modelProvider: 'ollama-cloud', modelId: 'kimi-k2.5', enabled: true },
  ])
  registerEndpoint('/api/conversations', () => [
    { id: 1, agentName: 'main', channelType: 'web', preview: 'Hello world', updatedAt: '2026-04-16T10:00:00Z' },
  ])
}

// The CommandDialog uses Teleport to render at document.body level,
// so we need to query document.body for the rendered content.
function getDialogText(): string {
  return document.body.textContent ?? ''
}

afterEach(() => {
  // Clean up any teleported dialog content
  document.body.querySelectorAll('[role="dialog"]').forEach(el => el.remove())
})

// `mountSuspended` renders its target inside a <Suspense> it owns, and
// @vue/test-utils' setProps() does NOT thread a post-mount prop change into that
// child — so a closed→open transition can't be driven with setProps. Instead bind
// `open` through a reactive parent, mirroring how the app mounts the palette
// (`<CommandPalette v-model:open="paletteOpen" />`, layouts/default.vue). Flipping
// the returned ref re-renders the parent and propagates the prop change for real.
async function mountWithOpen(initial: boolean) {
  const open = ref(initial)
  await mountSuspended(
    defineComponent({
      setup: () => () => h(CommandPalette, {
        'open': open.value,
        'onUpdate:open': (v: boolean) => { open.value = v },
      }),
    }),
  )
  return open
}

// ---------------------------------------------------------------------------
// Component exports
// ---------------------------------------------------------------------------

describe('Command component exports', () => {
  it('exports all command subcomponents', async () => {
    const cmd = await import('~/components/ui/command')
    expect(cmd.Command).toBeDefined()
    expect(cmd.CommandDialog).toBeDefined()
    expect(cmd.CommandEmpty).toBeDefined()
    expect(cmd.CommandGroup).toBeDefined()
    expect(cmd.CommandInput).toBeDefined()
    expect(cmd.CommandItem).toBeDefined()
    expect(cmd.CommandList).toBeDefined()
    expect(cmd.CommandSeparator).toBeDefined()
  })
})

// ---------------------------------------------------------------------------
// CommandPalette component
// ---------------------------------------------------------------------------

describe('CommandPalette', () => {
  it('renders navigation items when open', async () => {
    setupMockApi()
    await mountSuspended(CommandPalette, {
      props: { open: true },
    })
    await nextTick()
    const text = getDialogText()
    expect(text).toContain('Navigation')
    expect(text).toContain('Dashboard')
    expect(text).toContain('Chats')
    expect(text).toContain('Settings')
  })

  it('shows all 10 navigation pages', async () => {
    setupMockApi()
    await mountSuspended(CommandPalette, {
      props: { open: true },
    })
    await nextTick()
    const text = getDialogText()
    const navItems = ['Dashboard', 'Chats', 'Channels', 'Conversations', 'Tasks', 'Agents', 'Skills', 'Tools', 'Settings', 'Logs']
    for (const item of navItems) {
      expect(text).toContain(item)
    }
  })

  it('shows Actions group with toggle theme', async () => {
    setupMockApi()
    await mountSuspended(CommandPalette, {
      props: { open: true },
    })
    await nextTick()
    const text = getDialogText()
    expect(text).toContain('Actions')
    expect(text).toContain('Toggle theme')
  })

  it('does not render dialog content when closed', async () => {
    setupMockApi()
    await mountSuspended(CommandPalette, {
      props: { open: false },
    })
    await nextTick()
    const dialog = document.body.querySelector('[role="dialog"]')
    expect(dialog).toBeNull()
  })

  it('contains a search input when open', async () => {
    setupMockApi()
    await mountSuspended(CommandPalette, {
      props: { open: true },
    })
    await nextTick()
    const input = document.body.querySelector('[role="dialog"] input')
    expect(input).not.toBeNull()
    expect(input?.getAttribute('placeholder')).toContain('Search')
  })

  it('fetches agents and recent conversations when transitioning from closed → open', async () => {
    let agentsHits = 0
    let convoHits = 0
    registerEndpoint('/api/agents', () => {
      agentsHits++
      return [{ id: 7, name: 'fetched', modelProvider: 'ollama-cloud', modelId: 'kimi-k2.5', enabled: true }]
    })
    registerEndpoint('/api/conversations', () => {
      convoHits++
      return [{ id: 9, agentName: 'main', channelType: 'web', preview: 'fresh hit', updatedAt: '2026-05-15T10:00:00Z' }]
    })
    const open = await mountWithOpen(false)
    await flushPromises()
    expect(agentsHits).toBe(0)
    expect(convoHits).toBe(0)

    open.value = true
    // Allow the watcher's Promise.all + Vue render tick to settle. Two
    // flushPromises rounds — the first drains the awaited $fetch pair,
    // the second drains the subsequent reactive update that pushes the
    // fetched agents into the template.
    await flushPromises()
    await nextTick()
    await flushPromises()

    expect(agentsHits).toBe(1)
    expect(convoHits).toBe(1)
  })

  it('silently survives an API failure (palette still shows static items)', async () => {
    registerEndpoint('/api/agents', () => {
      throw createError({ statusCode: 500 })
    })
    registerEndpoint('/api/conversations', () => {
      throw createError({ statusCode: 500 })
    })
    // Mount closed first, then transition open to fire the watcher's try/catch.
    const open = await mountWithOpen(false)
    await flushPromises()
    open.value = true
    await flushPromises()
    await nextTick()
    // The catch block swallows the error so the static items still render.
    expect(getDialogText()).toContain('Dashboard')
    expect(getDialogText()).toContain('Toggle theme')
  })
})
