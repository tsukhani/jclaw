import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { mountSuspended, registerEndpoint, mockNuxtImport } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import { readBody } from 'h3'
import { createFetchError } from 'ofetch'
import { clearNuxtData } from '#app'
import Tasks from '~/pages/tasks.vue'
import Reminders from '~/pages/reminders.vue'

// A request that never gets a response. The in-process test server always answers, and the page's
// $fetch is bound when Nuxt's #build/fetch.mjs loads, so a later stub of the global never reaches it.
const unreachable = vi.hoisted(() => new Set<string>())
mockNuxtImport('$fetch', () => (url: string, opts?: Record<string, unknown>) =>
  unreachable.has(url) ? Promise.reject(noResponseTo(url, opts)) : untypedFetch(url, opts))

// Nitro types $fetch per route; a plain string URL sends vue-tsc past its stack depth.
function untypedFetch(url: string, opts?: Record<string, unknown>) {
  return (globalThis.$fetch as unknown as (u: string, o?: Record<string, unknown>) => Promise<unknown>)(url, opts)
}

function noResponseTo(url: string, opts?: Record<string, unknown>) {
  return createFetchError({ request: url, options: opts ?? {}, error: new TypeError('fetch failed') } as never)
}

/**
 * JCLAW-420 — task `delivery` (output channel) rendering + inline editor.
 *
 * <p>Two layers:
 * <ul>
 *   <li>Render: the Tasks page's Channel column / expander label parses the
 *       JCLAW-417 grammar into 3 states — {@code tool:<name>} → the tool name,
 *       null/blank/{@code none} → "none", {@code <channel>:<target>} → the
 *       channel. Reminders keep null → "web (auto)" but now unwrap
 *       {@code tool:} the same way.</li>
 *   <li>Edit: opening the expander's Channel editor, typing a value, and
 *       saving PATCHes {@code /api/tasks/:id} with {@code {delivery: ...}};
 *       a backend 400 surfaces its error message verbatim.</li>
 * </ul>
 */

interface TaskFixture {
  id: number
  name: string
  type: string
  status: string
  paused: boolean
  description?: string | null
  agentName: string | null
  nextRunAt: string | null
  retryCount: number
  maxRetries: number
  runningRunId: number | null
  delivery?: string | null
}

function task(over: Partial<TaskFixture> & { id: number, name: string }): TaskFixture {
  return {
    type: 'SCHEDULED',
    status: 'PENDING',
    paused: false,
    description: 'do the thing',
    agentName: 'main',
    nextRunAt: null,
    retryCount: 0,
    maxRetries: 3,
    runningRunId: null,
    delivery: null,
    ...over,
  }
}

const DELIVERY_TASKS: TaskFixture[] = [
  task({ id: 1, name: 'tool task', delivery: 'tool:send_gmail_message' }),
  task({ id: 2, name: 'none task', delivery: null }),
  task({ id: 3, name: 'blank task', delivery: '   ' }),
  task({ id: 4, name: 'literal none task', delivery: 'none' }),
  task({ id: 5, name: 'telegram task', delivery: 'telegram:12345' }),
  task({ id: 6, name: 'bare web task', delivery: 'web' }),
  task({ id: 7, name: 'legacy email task', delivery: 'email:ops@example.com' }),
]

function registerTaskMounts(opts?: {
  tasks?: TaskFixture[]
  capturePatch?: (id: string, body: { delivery?: string }) => void
  patchResponse?: () => Response | object
}) {
  const rows = opts?.tasks ?? DELIVERY_TASKS
  registerEndpoint('/api/tasks', () => rows)
  registerEndpoint('/api/tasks/stats', () => ({
    runsToday: 0, successRate: null, avgDurationMs: null,
    pendingCount: rows.length, runningCount: 0, failedCount: 0,
  }))
  registerEndpoint('/api/task-runs/recent', () => [])
  // The expander lazy-loads run history on first expand.
  for (const r of rows) {
    registerEndpoint(`/api/tasks/${r.id}/runs`, () => [])
    registerEndpoint(`/api/tasks/${r.id}`, {
      method: 'PATCH',
      handler: async (event) => {
        const body = await readBody(event) as { delivery?: string }
        opts?.capturePatch?.(String(r.id), body)
        return opts?.patchResponse ? opts.patchResponse() : { id: r.id }
      },
    })
  }
}

describe('Tasks page — JCLAW-420 delivery label (3-state grammar)', () => {
  beforeEach(() => clearNuxtData())

  it('renders the Channel column with the 3-state label for each delivery value', async () => {
    registerTaskMounts()
    const component = await mountSuspended(Tasks)
    await flushPromises()
    const text = component.text()

    // tool:<name> → bare tool name (prefix stripped).
    expect(text).toContain('send_gmail_message')
    expect(text).not.toContain('tool:send_gmail_message')
    // null / blank / literal "none" → "none".
    expect(text).toContain('none')
    // channel forms → channel name only.
    expect(text).toContain('telegram')
    expect(text).toContain('web')
    // legacy email: row keeps the email channel name.
    expect(text).toContain('email')
  })

  it('has a Channel column header', async () => {
    registerTaskMounts()
    const component = await mountSuspended(Tasks)
    await flushPromises()
    const headers = component.findAll('th').map(h => h.text())
    expect(headers).toContain('Channel')
  })
})

describe('Tasks page — JCLAW-420 inline channel editor → PATCH', () => {
  beforeEach(() => clearNuxtData())

  it('PATCHes /api/tasks/:id with {delivery} when the operator edits the channel', async () => {
    const captured: Array<{ id: string, body: { delivery?: string } }> = []
    registerTaskMounts({
      tasks: [task({ id: 1, name: 'tool task', delivery: 'tool:send_gmail_message' })],
      capturePatch: (id, body) => captured.push({ id, body }),
    })
    const component = await mountSuspended(Tasks)
    await flushPromises()

    // Expand the row so the Channel editor section renders.
    const expand = component.find('button[aria-label="Toggle details for tool task"]')
    expect(expand.exists()).toBe(true)
    await expand.trigger('click')
    await flushPromises()

    // The Channel section's Edit button reveals the input.
    const editBtn = component.findAll('button').find(b => b.text() === 'Edit'
      && b.element.closest('section')?.textContent?.includes('Channel'))
    expect(editBtn).toBeTruthy()
    await editBtn!.trigger('click')
    await flushPromises()

    const input = component.find('input[aria-label="Delivery channel"]')
    expect(input.exists()).toBe(true)
    await input.setValue('telegram:999')

    const saveBtn = component.findAll('button').find(b => b.text().includes('Save')
      && b.element.closest('section')?.textContent?.includes('Channel'))
    expect(saveBtn).toBeTruthy()
    await saveBtn!.trigger('click')
    await flushPromises()

    expect(captured.length).toBe(1)
    expect(captured[0]!.id).toBe('1')
    expect(captured[0]!.body).toEqual({ delivery: 'telegram:999' })
  })

  // JCLAW-1218: this mocked { error: "..." }, a body the backend never sends, so it passed while
  // the real page showed "Failed to save channel". Mock the canonical envelope it actually returns.
  it('surfaces the backend 400 error message when the value is invalid', async () => {
    registerTaskMounts({
      tasks: [task({ id: 1, name: 'tool task', delivery: 'tool:send_gmail_message' })],
      patchResponse: () => new Response(
        JSON.stringify({ type: 'error', code: 'invalid_request', message: 'Unknown channel: carrierpigeon' }),
        { status: 400, headers: { 'content-type': 'application/json' } },
      ),
    })
    const component = await mountSuspended(Tasks)
    await flushPromises()

    const expand = component.find('button[aria-label="Toggle details for tool task"]')
    await expand.trigger('click')
    await flushPromises()

    const editBtn = component.findAll('button').find(b => b.text() === 'Edit'
      && b.element.closest('section')?.textContent?.includes('Channel'))
    await editBtn!.trigger('click')
    await flushPromises()

    await component.find('input[aria-label="Delivery channel"]').setValue('carrierpigeon:1')
    const saveBtn = component.findAll('button').find(b => b.text().includes('Save')
      && b.element.closest('section')?.textContent?.includes('Channel'))
    await saveBtn!.trigger('click')
    await flushPromises()

    expect(component.text()).toContain('Unknown channel: carrierpigeon')
  })
})

describe('Tasks page — a channel save that fails without the error envelope (JCLAW-1220)', () => {
  beforeEach(() => clearNuxtData())
  afterEach(() => unreachable.clear())

  async function saveChannel(value: string) {
    const component = await mountSuspended(Tasks)
    await flushPromises()
    await component.find('button[aria-label="Toggle details for tool task"]').trigger('click')
    await flushPromises()
    await component.findAll('button').find(b => b.text() === 'Edit'
      && b.element.closest('section')?.textContent?.includes('Channel'))!.trigger('click')
    await flushPromises()
    await component.find('input[aria-label="Delivery channel"]').setValue(value)
    await component.findAll('button').find(b => b.text().includes('Save')
      && b.element.closest('section')?.textContent?.includes('Channel'))!.trigger('click')
    await flushPromises()
    return component.findAll('section p.text-red-700').find(p => p.element.closest('section')?.textContent?.includes('Channel'))
  }

  it('names the request when the save gets no response at all', async () => {
    registerTaskMounts({ tasks: [task({ id: 1, name: 'tool task', delivery: 'tool:send_gmail_message' })] })
    unreachable.add('/api/tasks/1')

    const shown = (await saveChannel('telegram:999'))!.text()
    expect(shown).toContain('/api/tasks/1')
    expect(shown).toContain('<no response>')
    expect(shown).not.toBe('Failed to save channel')
  })

  it('names the request when a proxy answers with a page instead of the error envelope', async () => {
    registerTaskMounts({
      tasks: [task({ id: 1, name: 'tool task', delivery: 'tool:send_gmail_message' })],
      patchResponse: () => new Response('<html><body>Bad Gateway</body></html>',
        { status: 502, headers: { 'content-type': 'text/html' } }),
    })

    const shown = (await saveChannel('telegram:999'))!.text()
    expect(shown).toContain('/api/tasks/1')
    expect(shown).toContain('502')
    expect(shown).not.toBe('Failed to save channel')
  })
})

describe('Reminders page — JCLAW-420 tool: unwrap (web-auto preserved)', () => {
  beforeEach(() => clearNuxtData())

  function registerReminders(rows: TaskFixture[]) {
    registerEndpoint('/api/tasks', () => rows)
  }

  it('renders a tool: delivery as the tool name, and keeps null → "web (auto)"', async () => {
    registerReminders([
      task({ id: 1, name: 'remind tool', delivery: 'tool:send_gmail_message' }),
      task({ id: 2, name: 'remind auto', delivery: null }),
      task({ id: 3, name: 'remind tg', delivery: 'telegram:1' }),
    ])
    const component = await mountSuspended(Reminders)
    await flushPromises()
    const text = component.text()

    expect(text).toContain('send_gmail_message')
    expect(text).not.toContain('tool:send_gmail_message')
    // Reminders' null is "auto-route to the calling chat" — NOT "none".
    expect(text).toContain('web (auto)')
    expect(text).toContain('telegram')
  })
})
