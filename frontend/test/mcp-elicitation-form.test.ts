import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mountSuspended, registerEndpoint } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import { defineComponent, h, ref } from 'vue'
import { readBody, setResponseStatus } from 'h3'
import McpElicitationForm from '~/components/chat/McpElicitationForm.vue'
import { useMcpElicitations } from '~/composables/useMcpElicitations'
import type { McpElicitation } from '~/types/api'

const bus = vi.hoisted(() => ({
  handlers: [] as Array<{ type: string, handler: (data: unknown, type: string) => void }>,
  openHandlers: [] as Array<() => void>,
}))

vi.mock('~/composables/useEventBus', () => ({
  useEventBus: () => ({
    on: vi.fn(),
    off: vi.fn(),
    onEvent: (type: string, handler: (data: unknown, type: string) => void) => {
      bus.handlers.push({ type, handler })
    },
    onOpen: (handler: () => void) => {
      bus.openHandlers.push(handler)
    },
  }),
}))

function emitBus(type: string, data: unknown) {
  for (const entry of bus.handlers) {
    if (entry.type === type) entry.handler(data, type)
  }
}

function question(id: string, conversationId = 5, overrides: Partial<McpElicitation> = {}): McpElicitation {
  return {
    id,
    conversationId,
    server: 'crm',
    message: 'Which contact should I use?\nSee https://evil.example/login for details.',
    requestedSchema: {
      type: 'object',
      properties: {
        email: { type: 'string', title: 'Email', format: 'email', description: 'Where the receipt goes' },
        age: { type: 'integer', title: 'Age', minimum: 18, maximum: 120, default: 30 },
        plan: { type: 'string', title: 'Plan', oneOf: [{ const: 'free', title: 'Free' }, { const: 'pro', title: 'Pro' }], default: 'pro' },
        tags: { type: 'array', title: 'Tags', items: { anyOf: [{ const: 'a', title: 'Alpha' }, { const: 'b', title: 'Beta' }] }, maxItems: 1 },
        subscribe: { type: 'boolean', title: 'Subscribe', default: true },
        nick: { type: 'string', minLength: 2 },
        color: { type: 'string', enum: ['red', 'green'] },
        at: { type: 'string', title: 'When', format: 'date-time', default: '2026-10-25T10:00:00Z' },
      },
      required: ['email', 'plan'],
    },
    expiresAt: '2026-10-25T12:05:00Z',
    ...overrides,
  }
}

/** Serves the answer endpoint for {@code id}, recording each body; {@code reply} chooses the response. */
function serveAnswer(id: string, reply: (event: Parameters<typeof setResponseStatus>[0]) => unknown = () => ({ status: 'ok' })) {
  const bodies: unknown[] = []
  registerEndpoint(`/api/mcp-servers/elicitations/${id}`, {
    method: 'POST',
    handler: async (event) => {
      bodies.push(await readBody(event))
      return reply(event)
    },
  })
  return bodies
}

const mounted: Array<{ unmount: () => void }> = []

async function mountForm(elicitation: McpElicitation) {
  const component = await mountSuspended(McpElicitationForm, { props: { elicitation } })
  mounted.push(component)
  return component
}

afterEach(() => {
  while (mounted.length) mounted.pop()!.unmount()
})

describe('McpElicitationForm — what the operator sees', () => {
  it('names the server, shows its message as plain text with no link, and each field with its default filled in', async () => {
    const component = await mountForm(question('q1'))
    expect(component.find('[data-testid="mcp-elicitation-server"]').text()).toBe('crm')
    const message = component.find('[data-testid="mcp-elicitation-message"]')
    expect(message.text()).toBe('Which contact should I use?\nSee https://evil.example/login for details.')
    expect(component.findAll('a')).toHaveLength(0)

    const field = (key: string) => component.find(`[data-testid="mcp-elicitation-field-${key}"]`)
    expect(field('email').find('label').text()).toBe('Email *')
    expect((field('email').find('input').element as HTMLInputElement).type).toBe('email')
    expect(field('email').text()).toContain('Where the receipt goes')
    expect((field('age').find('input').element as HTMLInputElement).value).toBe('30')
    expect((field('plan').find('select').element as HTMLSelectElement).value).toBe('pro')
    expect(field('plan').findAll('option').map(o => o.text())).toEqual(['Choose…', 'Free', 'Pro'])
    expect(field('color').findAll('option').map(o => o.text())).toEqual(['None', 'red', 'green'])
    expect(field('tags').findAll('label').map(l => l.text())).toEqual(['Alpha', 'Beta'])
    expect((field('subscribe').find('input').element as HTMLInputElement).checked).toBe(true)
    expect(field('nick').find('label').text()).toBe('nick')
    const when = field('at').find('input').element as HTMLInputElement
    expect(when.type).toBe('datetime-local')
    expect(new Date(when.value).toISOString()).toBe('2026-10-25T10:00:00.000Z')
    expect(component.text()).toContain('If you do not answer by')
  })
})

describe('McpElicitationForm — what an unchanged form sends', () => {
  it('announces the server politely without taking focus', async () => {
    const component = await mountSuspended(McpElicitationForm, { props: { elicitation: question('a1') }, attachTo: document.body })
    mounted.push(component)
    await flushPromises()
    const status = component.find('[data-testid="mcp-elicitation-status"]')
    expect(status.attributes('role')).toBe('status')
    expect(status.text()).toBe('MCP server crm is asking a question.')
    expect(component.element.contains(document.activeElement)).toBe(false)
  })

  it('sends a yes-or-no field only when it is required, has a default, or was changed', async () => {
    const bodies = serveAnswer('b1')
    const component = await mountForm(question('b1', 5, { requestedSchema: { type: 'object', properties: {
      untouched: { type: 'boolean' },
      needed: { type: 'boolean' },
      preset: { type: 'boolean', default: false },
      ticked: { type: 'boolean' },
    }, required: ['needed'] } }))
    await component.find('[data-testid="mcp-elicitation-field-ticked"] input').setValue(true)
    await component.find('form').trigger('submit')
    await vi.waitFor(() => expect(bodies).toEqual([{ action: 'accept', content: { needed: false, preset: false, ticked: true } }]))
  })

  it('keeps a date and time default to the second, so sending it unchanged sends the same instant', async () => {
    const bodies = serveAnswer('t1')
    const component = await mountForm(question('t1', 5, { requestedSchema: { type: 'object', properties: {
      at: { type: 'string', format: 'date-time', default: '2026-10-25T10:00:30Z' } } } }))
    const input = component.find('[data-testid="mcp-elicitation-field-at"] input')
    expect(input.attributes('step')).toBe('1')
    expect((input.element as HTMLInputElement).value.endsWith(':30')).toBe(true)
    await component.find('form').trigger('submit')
    await vi.waitFor(() => expect(bodies).toEqual([{ action: 'accept', content: { at: '2026-10-25T10:00:30.000Z' } }]))
  })
})

describe('McpElicitationForm — a server\'s own field names', () => {
  it('labels and describes a field whose key has a space, and sends it under that key', async () => {
    const bodies = serveAnswer('k1')
    const component = await mountForm(question('k1', 5, { requestedSchema: { type: 'object', properties: {
      'full name': { type: 'string', description: 'As on the card', minLength: 2 } } } }))
    const field = component.find('[data-testid="mcp-elicitation-field-full name"]')
    const input = field.find('input')
    expect(field.find('label').attributes('for')).toBe(input.attributes('id'))
    expect(input.attributes('id')).not.toContain(' ')
    await input.setValue('x')
    await component.find('form').trigger('submit')
    await flushPromises()
    expect(input.attributes('aria-describedby')!.split(' ').map(id => component.find(`[id="${id}"]`).text()))
      .toEqual(['As on the card', 'Must be at least 2 characters.'])
    await input.setValue('Ada Lovelace')
    await component.find('form').trigger('submit')
    await vi.waitFor(() => expect(bodies).toEqual([{ action: 'accept', content: { 'full name': 'Ada Lovelace' } }]))
  })
})

describe('McpElicitationForm — answering', () => {
  beforeEach(() => {
    clearNuxtData()
  })

  it('submits the values the operator reviewed, typed as the schema says, and leaves blank optional fields out', async () => {
    const bodies = serveAnswer('q2')
    const component = await mountForm(question('q2'))
    await component.find('[data-testid="mcp-elicitation-field-email"] input').setValue('ops@example.com')
    await component.find('[data-testid="mcp-elicitation-field-age"] input').setValue('42')
    await component.findAll('[data-testid="mcp-elicitation-field-tags"] input')[1]!.setValue(true)
    await component.find('[data-testid="mcp-elicitation-field-subscribe"] input').setValue(false)
    await component.find('[data-testid="mcp-elicitation-field-at"] input').setValue('2026-10-26T09:30')
    await component.find('form').trigger('submit')
    await flushPromises()

    expect(bodies).toEqual([{
      action: 'accept',
      content: {
        email: 'ops@example.com', age: 42, plan: 'pro', tags: ['b'], subscribe: false,
        at: new Date('2026-10-26T09:30').toISOString(),
      },
    }])
    await vi.waitFor(() => expect(component.emitted('done')).toEqual([['q2']]))
  })

  it('shows each problem under its field and sends nothing until they are fixed', async () => {
    const bodies = serveAnswer('q3')
    const component = await mountForm(question('q3'))
    await component.find('[data-testid="mcp-elicitation-field-email"] input').setValue('ops@example')
    await component.find('[data-testid="mcp-elicitation-field-age"] input').setValue('121')
    await component.find('[data-testid="mcp-elicitation-field-plan"] select').setValue('')
    for (const box of component.findAll('[data-testid="mcp-elicitation-field-tags"] input')) await box.setValue(true)
    await component.find('[data-testid="mcp-elicitation-field-nick"] input').setValue('t')
    await component.find('form').trigger('submit')
    await flushPromises()

    const problem = (key: string) => component.find(`[data-testid="mcp-elicitation-field-${key}"] [data-testid="mcp-elicitation-problem"]`)
    expect(problem('email').text()).toBe('Must be an email address.')
    expect(problem('age').text()).toBe('Must be at most 120.')
    expect(problem('plan').text()).toBe('Required.')
    expect(problem('tags').text()).toBe('Choose at most 1.')
    expect(problem('nick').text()).toBe('Must be at least 2 characters.')
    expect(problem('color').exists()).toBe(false)
    const email = component.find('[data-testid="mcp-elicitation-field-email"] input')
    expect(email.attributes('aria-invalid')).toBe('true')
    expect(email.attributes('aria-describedby')).toContain(problem('email').attributes('id'))
    expect(bodies).toEqual([])
    expect(component.emitted('done')).toBeUndefined()

    await component.find('[data-testid="mcp-elicitation-field-email"] input').setValue('ops@example.com')
    await component.find('[data-testid="mcp-elicitation-field-age"] input').setValue('18')
    await component.find('[data-testid="mcp-elicitation-field-plan"] select').setValue('free')
    await component.findAll('[data-testid="mcp-elicitation-field-tags"] input')[0]!.setValue(false)
    await component.find('[data-testid="mcp-elicitation-field-nick"] input').setValue('tj')
    await component.find('form').trigger('submit')
    await flushPromises()
    await vi.waitFor(() => expect(component.emitted('done')).toEqual([['q3']]))
    expect(bodies).toHaveLength(1)
  })

  it('clears a field\'s problem once its value changes, leaves the others, and Submit checks again', async () => {
    const bodies = serveAnswer('q3b')
    const component = await mountForm(question('q3b'))
    for (const box of component.findAll('[data-testid="mcp-elicitation-field-tags"] input')) await box.setValue(true)
    await component.find('[data-testid="mcp-elicitation-field-nick"] input').setValue('t')
    await component.find('form').trigger('submit')
    await flushPromises()

    const problem = (key: string) => component.find(`[data-testid="mcp-elicitation-field-${key}"] [data-testid="mcp-elicitation-problem"]`)
    const email = () => component.find('[data-testid="mcp-elicitation-field-email"] input')
    expect(problem('email').text()).toBe('Required.')
    expect(problem('tags').text()).toBe('Choose at most 1.')
    expect(problem('nick').text()).toBe('Must be at least 2 characters.')

    await email().setValue('ops@example')
    expect(problem('email').exists()).toBe(false)
    expect(email().attributes('aria-invalid')).toBeUndefined()
    expect(email().attributes('aria-describedby')).toBe(`${email().attributes('id')}-help`)
    const tags = component.find('[data-testid="mcp-elicitation-field-tags"] fieldset')
    expect(tags.attributes('aria-invalid')).toBe('true')
    await component.findAll('[data-testid="mcp-elicitation-field-tags"] input')[0]!.setValue(false)
    expect(problem('tags').exists()).toBe(false)
    expect(tags.attributes('aria-invalid')).toBeUndefined()
    expect(problem('nick').text()).toBe('Must be at least 2 characters.')

    await component.find('form').trigger('submit')
    await flushPromises()
    expect(problem('email').text()).toBe('Must be an email address.')
    expect(problem('nick').text()).toBe('Must be at least 2 characters.')
    expect(bodies).toEqual([])
  })

  it('declines or cancels with the action alone, whatever the fields hold', async () => {
    for (const action of ['decline', 'cancel'] as const) {
      const bodies = serveAnswer(`q-${action}`)
      const component = await mountForm(question(`q-${action}`))
      await component.find('[data-testid="mcp-elicitation-field-email"] input').setValue('not an email')
      await component.find(`[data-testid="mcp-elicitation-${action}"]`).trigger('click')
      await flushPromises()
      expect(bodies).toEqual([{ action }])
      await vi.waitFor(() => expect(component.emitted('done')).toEqual([[`q-${action}`]]))
    }
  })

  it('keeps the form open and marks the field the server refused', async () => {
    serveAnswer('q4', (event) => {
      setResponseStatus(event, 400)
      return { type: 'error', code: 'invalid_request', message: 'Field \'email\' must be an email address', field: 'email',
        template: { whatBroke: 'x', whatToCheck: 'y', howToRetry: 'z' } }
    })
    const component = await mountForm(question('q4'))
    await component.find('[data-testid="mcp-elicitation-field-email"] input').setValue('ops@example.com')
    await component.find('form').trigger('submit')
    await vi.waitFor(() => expect(component.find('[data-testid="mcp-elicitation-field-email"] [data-testid="mcp-elicitation-problem"]')
      .text()).toBe('Field \'email\' must be an email address'))
    expect(component.emitted('done')).toBeUndefined()

    await component.find('[data-testid="mcp-elicitation-field-email"] input').setValue('billing@example.com')
    expect(component.find('[data-testid="mcp-elicitation-field-email"] [data-testid="mcp-elicitation-problem"]').exists()).toBe(false)
  })

  it('shows a refusal that names no field of the form above the buttons', async () => {
    serveAnswer('q5', (event) => {
      setResponseStatus(event, 400)
      return { type: 'error', code: 'invalid_request', message: 'Field \'extra\' is not in the form', field: 'extra',
        template: { whatBroke: 'x', whatToCheck: 'y', howToRetry: 'z' } }
    })
    const component = await mountForm(question('q5'))
    await component.find('[data-testid="mcp-elicitation-field-email"] input').setValue('ops@example.com')
    await component.find('form').trigger('submit')
    await vi.waitFor(() => expect(component.find('[data-testid="mcp-elicitation-error"]').text())
      .toBe('Field \'extra\' is not in the form'))
    expect(component.emitted('done')).toBeUndefined()
  })

  it('drops a form that is no longer waiting', async () => {
    serveAnswer('q6', (event) => {
      setResponseStatus(event, 404)
      return { type: 'error', code: 'not_found', message: 'This question is no longer waiting for an answer.',
        template: { whatBroke: 'x', whatToCheck: 'y', howToRetry: 'z' } }
    })
    const component = await mountForm(question('q6'))
    await component.find('[data-testid="mcp-elicitation-cancel"]').trigger('click')
    await flushPromises()
    await vi.waitFor(() => expect(component.emitted('done')).toEqual([['q6']]))
  })
})

describe('useMcpElicitations — which form shows', () => {
  beforeEach(() => {
    bus.handlers.length = 0
    bus.openHandlers.length = 0
  })

  /** Serves the pending list per conversation, recording each conversation asked for. */
  function servePending(byConversation: Record<number, McpElicitation[]>) {
    const asked: number[] = []
    registerEndpoint('/api/mcp-servers/elicitations', (event) => {
      const url = new URL(String(event.node?.req?.url ?? event.path ?? ''), 'http://localhost')
      const id = Number(url.searchParams.get('conversationId'))
      asked.push(id)
      return byConversation[id] ?? []
    })
    return asked
  }

  async function mountPending(convoId: number | null) {
    const selected = ref<number | null>(convoId)
    let api!: ReturnType<typeof useMcpElicitations>
    mounted.push(await mountSuspended(defineComponent({
      setup() {
        api = useMcpElicitations(selected)
        return () => h('div')
      },
    })))
    await flushPromises()
    return { selected, api: () => api }
  }

  it('fetches the forms waiting in the open conversation, so a reloaded page shows them again', async () => {
    const asked = servePending({ 5: [question('w1'), question('w2')] })
    const { api } = await mountPending(5)
    await vi.waitFor(() => expect(api().current.value?.id).toBe('w1'))
    expect(asked).toEqual([5])
    api().remove('w1')
    expect(api().current.value?.id).toBe('w2')
  })

  it('shows a form announced for the open conversation and drops it when it closes', async () => {
    servePending({})
    const { api } = await mountPending(5)
    expect(api().current.value).toBeNull()
    emitBus('mcp.elicitation', question('e1', 9))
    expect(api().current.value).toBeNull()
    emitBus('mcp.elicitation', question('e2', 5))
    emitBus('mcp.elicitation', question('e2', 5))
    expect(api().current.value?.id).toBe('e2')
    emitBus('mcp.elicitation.closed', { id: 'e2', conversationId: 5, action: 'cancel' })
    expect(api().current.value).toBeNull()
    emitBus('mcp.elicitation', question('e2', 5))
    expect(api().current.value).toBeNull()
  })

  it('keeps a form announced while the fetch was in flight, and drops one that closed meanwhile', async () => {
    let answer!: (rows: McpElicitation[]) => void
    registerEndpoint('/api/mcp-servers/elicitations', () => new Promise<McpElicitation[]>((resolve) => {
      answer = resolve
    }))
    const { api } = await mountPending(5)
    await vi.waitFor(() => expect(answer).toBeTypeOf('function'))
    emitBus('mcp.elicitation', question('late', 5))
    emitBus('mcp.elicitation.closed', { id: 'gone', conversationId: 5, action: 'decline' })
    answer([question('gone'), question('early')])
    await vi.waitFor(() => expect(api().current.value?.id).toBe('early'))
    api().remove('early')
    expect(api().current.value?.id).toBe('late')
  })

  it('fetches again when the event stream reconnects, since it replays nothing', async () => {
    const waiting: Record<number, McpElicitation[]> = {}
    const asked = servePending(waiting)
    const { api } = await mountPending(5)
    waiting[5] = [question('r1')]
    bus.openHandlers.forEach(handler => handler())
    await vi.waitFor(() => expect(api().current.value?.id).toBe('r1'))
    expect(asked).toEqual([5, 5])
  })

  it('switching conversation shows only the new one\'s forms', async () => {
    const asked = servePending({ 5: [question('s1')], 6: [question('s2', 6)] })
    const { selected, api } = await mountPending(5)
    await vi.waitFor(() => expect(api().current.value?.id).toBe('s1'))
    selected.value = 6
    await vi.waitFor(() => expect(api().current.value?.id).toBe('s2'))
    expect(asked).toEqual([5, 6])
    selected.value = null
    await flushPromises()
    expect(api().current.value).toBeNull()
    expect(asked).toEqual([5, 6])
  })
})
