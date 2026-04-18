import { describe, it, expect, afterEach } from 'vitest'
import { mountSuspended, registerEndpoint } from '@nuxt/test-utils/runtime'
import { nextTick } from 'vue'
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
    expect(text).toContain('Chat')
    expect(text).toContain('Settings')
  })

  it('shows all 10 navigation pages', async () => {
    setupMockApi()
    await mountSuspended(CommandPalette, {
      props: { open: true },
    })
    await nextTick()
    const text = getDialogText()
    const navItems = ['Dashboard', 'Chat', 'Channels', 'Conversations', 'Tasks', 'Agents', 'Skills', 'Tools', 'Settings', 'Logs']
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
})
