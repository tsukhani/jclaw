import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { mountSuspended, registerEndpoint } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import type { DOMWrapper } from '@vue/test-utils'
import { clearNuxtData } from '#app'
import { defineComponent, h, nextTick } from 'vue'
import Slack from '~/pages/channels/slack.vue'
import ConfirmDialog from '~/components/ConfirmDialog.vue'

/**
 * pages/channels/slack.vue flow coverage. slack.page.test.ts pins the
 * structural rendering (setup guidance, Request URL display, owner
 * requirement, transport switch); slack-scope-warning.test.ts pins the
 * JCLAW-458 banner. This spec drives the remaining CRUD + interaction flows:
 *
 *   - Create: field-by-field canSave gating (agent → botToken →
 *     signingSecret → owner) and the exact POST body.
 *   - Socket-mode create: appToken replaces signingSecret; webhookBaseUrl
 *     sent blank; POST body shape.
 *   - Save failure (500) surfaces the error line and keeps the form open.
 *   - Card enable/disable switch PUTs { enabled }.
 *   - Delete: ConfirmDialog cancel skips, confirm fires DELETE.
 *   - Agent autocomplete: query filtering, selection via mousedown,
 *     stale-id invalidation on edit + "pick from dropdown" warning.
 *   - copyText: clipboard write + Copied state handoff between buttons,
 *     and the denied-clipboard no-op.
 *   - Form URL preview branches: amber prompt with no base, "generated
 *     when you save" with a base but no id, full URLs when editing.
 */

const AGENTS = [
  { id: 1, name: 'main', enabled: true, isMain: true, modelProvider: 'openrouter', modelId: 'gpt-4.1' },
  { id: 2, name: 'helper', enabled: true, isMain: false, modelProvider: 'ollama-cloud', modelId: 'kimi-k2.5' },
]

function binding(overrides: Record<string, unknown> = {}) {
  return {
    id: 7,
    agentId: 1,
    agentName: 'main',
    ownerUserId: 'U-OWNER',
    transport: 'HTTP',
    webhookBaseUrl: 'https://jclaw.tnet.ts.net',
    effectiveRequestUrl: null,
    hasSigningSecret: true,
    hasAppToken: false,
    botUserId: null,
    teamId: null,
    enabled: true,
    replyToMode: null,
    createdAt: null,
    updatedAt: null,
    ...overrides,
  }
}

const Harness = defineComponent({
  setup() {
    return () => h('div', [h(Slack), h(ConfirmDialog)])
  },
})

let bindingsResponse: unknown[] = []
let tailscaleResponse: Record<string, unknown> = {}
let postBody: Record<string, unknown> | null = null
let putBody: Record<string, unknown> | null = null
let putFails = false
let deleted = false

registerEndpoint('/api/agents', () => AGENTS)
registerEndpoint('/api/channels/slack/bindings', () => bindingsResponse)
registerEndpoint('/api/tailscale', () => tailscaleResponse)
registerEndpoint('/api/channels/slack/bindings', {
  method: 'POST',
  handler: async (event) => {
    const { readBody } = await import('h3')
    postBody = await readBody(event) as Record<string, unknown>
    return binding({ id: 9, ...postBody })
  },
})
registerEndpoint('/api/channels/slack/bindings/7', {
  method: 'PUT',
  handler: async (event) => {
    const { readBody, createError } = await import('h3')
    if (putFails) throw createError({ statusCode: 500, statusMessage: 'boom' })
    putBody = await readBody(event) as Record<string, unknown>
    return binding(putBody)
  },
})
registerEndpoint('/api/channels/slack/bindings/7', {
  method: 'DELETE',
  handler: () => {
    deleted = true
    return { status: 'ok' }
  },
})

const writeTextMock = vi.fn(async (_text: string) => {})

beforeEach(() => {
  clearNuxtData()
  bindingsResponse = []
  tailscaleResponse = { enabled: true, available: true, publicUrl: 'https://jclaw.tnet.ts.net', error: null }
  postBody = null
  putBody = null
  putFails = false
  deleted = false
  writeTextMock.mockClear()
  writeTextMock.mockImplementation(async () => {})
  // jsdom ships no navigator.clipboard — install a capture stub.
  Object.defineProperty(navigator, 'clipboard', {
    value: { writeText: writeTextMock },
    configurable: true,
  })
})

afterEach(() => {
  document.body.querySelectorAll('[role="dialog"]').forEach(el => el.remove())
})

/** Open the create form and pick an agent from the autocomplete dropdown. */
async function openCreateAndPickAgent(c: Awaited<ReturnType<typeof mountSuspended>>, query: string, optionText: string) {
  await c.findAll('button').find((b: DOMWrapper<Element>) => b.text() === '+ New binding')!.trigger('click')
  await nextTick()
  const agentInput = c.find('#binding-agent')
  await agentInput.setValue(query)
  await agentInput.trigger('input')
  await nextTick()
  const option = c.findAll('ul button').find((b: DOMWrapper<Element>) => b.text().includes(optionText))!
  expect(option).toBeDefined()
  await option.trigger('mousedown')
  await nextTick()
}

describe('slack bindings — create flow (canSave gating + POST body)', () => {
  it('unlocks Save field by field and POSTs the trimmed HTTP-transport payload', async () => {
    const c = await mountSuspended(Slack)
    await flushPromises()

    await c.findAll('button').find((b: DOMWrapper<Element>) => b.text() === '+ New binding')!.trigger('click')
    await nextTick()
    const saveBtn = c.findAll('button').find(b => b.text() === 'Save')!

    // No agent yet → locked.
    expect(saveBtn.attributes('disabled')).toBeDefined()

    // Autocomplete narrows on the query: 'ma' matches only 'main'.
    const agentInput = c.find('#binding-agent')
    await agentInput.setValue('ma')
    await agentInput.trigger('input')
    await nextTick()
    const options = c.findAll('ul button')
    expect(options.length).toBe(1)
    expect(options[0]!.text()).toContain('main')
    await options[0]!.trigger('mousedown')
    await nextTick()
    expect((agentInput.element as HTMLInputElement).value).toBe('main')

    // Agent picked, but botToken is required on create.
    expect(saveBtn.attributes('disabled')).toBeDefined()
    await c.find('#binding-bot-token').setValue('xoxb-1')
    await nextTick()

    // HTTP transport also needs the signing secret.
    expect(saveBtn.attributes('disabled')).toBeDefined()
    await c.find('#binding-signing-secret').setValue('shh')
    await nextTick()

    // main agent → owner-locked until an owner id is set.
    expect(saveBtn.attributes('disabled')).toBeDefined()
    await c.find('#binding-owner-user-id').setValue('U1')
    await nextTick()
    expect(saveBtn.attributes('disabled')).toBeUndefined()

    await saveBtn.trigger('click')
    // The POST stub is async (dynamic h3 import) — wait for the capture
    // rather than assuming one flushPromises spans the whole mutate chain.
    await vi.waitFor(() => expect(postBody).not.toBeNull())

    expect(postBody).toEqual({
      agentId: 1,
      transport: 'HTTP',
      webhookBaseUrl: 'https://jclaw.tnet.ts.net', // pre-filled from the funnel
      ownerUserId: 'U1',
      enabled: true,
      botToken: 'xoxb-1',
      signingSecret: 'shh',
    })
    // Form closed after a successful save — closeModal() runs only after the
    // async mutate resolves, so wait for the form field to drop out of the
    // DOM. (The "+ New binding" header button always contains the phrase,
    // so assert on a form field.)
    await vi.waitFor(() => expect(c.find('#binding-bot-token').exists()).toBe(false))
  })

  it('Socket Mode requires the app token instead and sends a blank webhook base', async () => {
    const c = await mountSuspended(Slack)
    await flushPromises()

    // helper is not the main agent → no owner requirement in play.
    await openCreateAndPickAgent(c, 'help', 'helper')
    await c.find('#binding-transport').setValue('SOCKET')
    await nextTick()
    await c.find('#binding-bot-token').setValue('xoxb-2')
    await nextTick()

    // botToken alone is not enough on Socket Mode — the app token gates.
    const saveBtn = c.findAll('button').find(b => b.text() === 'Save')!
    expect(saveBtn.attributes('disabled')).toBeDefined()
    await c.find('#binding-app-token').setValue('xapp-1')
    await nextTick()
    expect(saveBtn.attributes('disabled')).toBeUndefined()

    await saveBtn.trigger('click')
    await flushPromises()

    expect(postBody).toEqual({
      agentId: 2,
      transport: 'SOCKET',
      webhookBaseUrl: '', // Socket Mode ignores the public base
      ownerUserId: null, // blank owner clears to null
      enabled: true,
      botToken: 'xoxb-2',
      appToken: 'xapp-1',
    })
  })
})

describe('slack bindings — save failure + card actions', () => {
  it('shows the save-failed line and keeps the form open when the server 500s', async () => {
    bindingsResponse = [binding()]
    putFails = true
    const c = await mountSuspended(Slack)
    await flushPromises()

    await c.find('[aria-label="Edit binding"]').trigger('click')
    await nextTick()
    await c.findAll('button').find(b => b.text() === 'Save')!.trigger('click')
    await flushPromises()

    expect(c.text()).toContain('Save failed — check server logs for details.')
    expect(c.text()).toContain('Edit binding') // form did not close
  })

  it('the card switch PUTs the flipped enabled flag', async () => {
    bindingsResponse = [binding({ enabled: true })]
    const c = await mountSuspended(Slack)
    await flushPromises()

    await c.find('button[role="switch"]').trigger('click')
    await vi.waitFor(() => expect(putBody).not.toBeNull())

    expect(putBody).toEqual({ enabled: false })
  })

  it('cancel on the delete dialog skips the DELETE', async () => {
    bindingsResponse = [binding()]
    const c = await mountSuspended(Harness)
    await flushPromises()

    await c.find('[aria-label="Delete binding"]').trigger('click')
    await flushPromises()

    const dialog = document.body.querySelector('[role="dialog"]')
    expect(dialog?.textContent).toContain('Delete the Slack binding for main')
    const cancelBtn = Array.from(document.body.querySelectorAll<HTMLButtonElement>('[role="dialog"] button'))
      .find(b => (b.textContent ?? '').trim() === 'Cancel')
    expect(cancelBtn).toBeTruthy()
    cancelBtn!.click()
    await flushPromises()
    await flushPromises()

    expect(deleted).toBe(false)
  })

  it('confirm on the delete dialog fires DELETE for the binding', async () => {
    bindingsResponse = [binding()]
    const c = await mountSuspended(Harness)
    await flushPromises()

    await c.find('[aria-label="Delete binding"]').trigger('click')
    await flushPromises()

    const confirmBtn = Array.from(document.body.querySelectorAll<HTMLButtonElement>('[role="dialog"] button'))
      .find(b => (b.textContent ?? '').trim() === 'Delete')
    expect(confirmBtn).toBeTruthy()
    confirmBtn!.click()
    await vi.waitFor(() => expect(deleted).toBe(true))
  })
})

describe('slack bindings — agent autocomplete edge cases', () => {
  it('editing past the match invalidates the selected id and warns after blur', async () => {
    const c = await mountSuspended(Slack)
    await flushPromises()

    await openCreateAndPickAgent(c, 'help', 'helper')
    const agentInput = c.find('#binding-agent')

    // Typo past the match: id invalidated, dropdown has nothing to offer.
    await agentInput.setValue('helperX')
    await agentInput.trigger('input')
    await nextTick()
    expect(c.find('ul').exists()).toBe(false) // no matching agents

    await agentInput.trigger('blur')
    await nextTick()
    expect(c.text()).toContain('Select an agent from the dropdown before saving.')

    // Save is locked again — the stale id did not survive the typo.
    const saveBtn = c.findAll('button').find(b => b.text() === 'Save')!
    expect(saveBtn.attributes('disabled')).toBeDefined()
  })
})

describe('slack bindings — clipboard copy', () => {
  it('copies the Request URL, shows Copied, and hands the state to the next copy', async () => {
    bindingsResponse = [binding({ effectiveRequestUrl: 'https://jclaw.tnet.ts.net/api/webhooks/slack/7' })]
    const c = await mountSuspended(Slack)
    await flushPromises()

    const requestCopy = c.findAll('button')
      .find(b => b.attributes('aria-label') === 'Copy request URL')!
    expect(requestCopy).toBeDefined()
    await requestCopy.trigger('click')
    await flushPromises()

    expect(writeTextMock).toHaveBeenCalledWith('https://jclaw.tnet.ts.net/api/webhooks/slack/7')
    expect(requestCopy.attributes('aria-label')).toBe('Copied')

    // Copying the interactivity URL moves the Copied state over.
    const interactiveCopy = c.findAll('button')
      .find(b => b.attributes('aria-label') === 'Copy interactivity URL')!
    await interactiveCopy.trigger('click')
    await flushPromises()

    expect(writeTextMock).toHaveBeenCalledWith('https://jclaw.tnet.ts.net/api/webhooks/slack/7/interactive')
    expect(interactiveCopy.attributes('aria-label')).toBe('Copied')
    expect(requestCopy.attributes('aria-label')).toBe('Copy request URL')
  })

  it('a denied clipboard is a silent no-op — no Copied state, no crash', async () => {
    writeTextMock.mockRejectedValueOnce(new Error('denied'))
    bindingsResponse = [binding({ effectiveRequestUrl: 'https://jclaw.tnet.ts.net/api/webhooks/slack/7' })]
    const c = await mountSuspended(Slack)
    await flushPromises()

    const requestCopy = c.findAll('button')
      .find(b => b.attributes('aria-label') === 'Copy request URL')!
    await requestCopy.trigger('click')
    await flushPromises()

    expect(requestCopy.attributes('aria-label')).toBe('Copy request URL')
  })
})

describe('slack bindings — form Request-URL preview branches', () => {
  it('prompts for a public URL with no base, then explains the id-on-save gap once a base is typed', async () => {
    // Funnel off + jsdom localhost origin → nothing to pre-fill.
    tailscaleResponse = { enabled: false, available: false, publicUrl: null, error: null }
    const c = await mountSuspended(Slack)
    await flushPromises()

    await c.findAll('button').find((b: DOMWrapper<Element>) => b.text() === '+ New binding')!.trigger('click')
    await nextTick()
    expect(c.text()).toContain('Enter your public HTTPS URL')

    await c.find('#binding-webhook-base').setValue('https://x.example.com')
    await nextTick()
    // A base without a binding id: the full URL only exists after save.
    expect(c.text()).not.toContain('Enter your public HTTPS URL')
    expect(c.text()).toContain('The full Request URL is generated when you save')
  })

  it('builds both request URLs from the edited base when editing, with working copy buttons', async () => {
    // effectiveRequestUrl stays null so the card section is absent — the
    // form's own preview (and its copy buttons) are the only match targets.
    bindingsResponse = [binding({ webhookBaseUrl: 'https://ex.example.com', effectiveRequestUrl: null })]
    const c = await mountSuspended(Slack)
    await flushPromises()

    await c.find('[aria-label="Edit binding"]').trigger('click')
    await nextTick()

    expect(c.text()).toContain('https://ex.example.com/api/webhooks/slack/7')
    expect(c.text()).toContain('https://ex.example.com/api/webhooks/slack/7/interactive')

    await c.findAll('button').find(b => b.attributes('aria-label') === 'Copy interactivity URL')!
      .trigger('click')
    await flushPromises()
    expect(writeTextMock).toHaveBeenCalledWith('https://ex.example.com/api/webhooks/slack/7/interactive')
  })
})
