import { describe, it, expect, afterEach, vi } from 'vitest'
import { mountSuspended, registerEndpoint, mockNuxtImport } from '@nuxt/test-utils/runtime'
import { enableAutoUnmount } from '@vue/test-utils'
import { setResponseStatus, type H3Event } from 'h3'
import NotificationBar from '~/components/NotificationBar.vue'

enableAutoUnmount(afterEach)

const { navigateToMock } = vi.hoisted(() => ({ navigateToMock: vi.fn().mockResolvedValue(undefined) }))
mockNuxtImport('navigateTo', () => navigateToMock)

let unregister: Array<() => void> = []

afterEach(() => {
  unregister.forEach(off => off())
  unregister = []
  document.body.innerHTML = ''
})

function stub(url: string, method: 'POST' | 'DELETE', handler: (event: H3Event) => unknown) {
  unregister.push(registerEndpoint(url, { method, handler }))
}

function status(code: number) {
  return (event: H3Event) => {
    setResponseStatus(event, code)
    return code === 404 ? { type: 'error', code: 'not_found', message: 'Not found' } : '<html><body>Bad Gateway</body></html>'
  }
}

async function mountWithReminder() {
  unregister.push(registerEndpoint('/api/notifications', () => [
    { id: 7, agentId: 1, agentName: 'main', content: 'Call the dentist', sourceTaskRunId: 3, sourceTaskId: 40, createdAt: new Date().toISOString(), acknowledgedAt: null },
  ]))
  await mountSuspended(NotificationBar)
  await vi.waitFor(() => expect(document.body.textContent).toContain('Call the dentist'))
}

const button = (label: string) =>
  [...document.querySelectorAll<HTMLButtonElement>('button')].find(b => b.textContent?.trim() === label || b.getAttribute('aria-label') === label)!

describe('NotificationBar — a reminder toast only leaves once the server has let it go (JCLAW-1221)', () => {
  it('keeps the toast and names the request when marking it seen fails', async () => {
    stub('/api/notifications/7/ack', 'POST', status(502))
    await mountWithReminder()

    button('Mark as seen').click()
    await vi.waitFor(() => expect(document.body.querySelector('[data-testid="api-error"]')).not.toBeNull())

    expect(document.body.querySelector('[data-testid="api-error"]')!.textContent).toContain('/api/notifications/7/ack')
    expect(document.body.textContent).toContain('Call the dentist')
  })

  it('removes the toast when the notification is already gone', async () => {
    stub('/api/notifications/7/ack', 'POST', status(404))
    await mountWithReminder()

    button('Mark as seen').click()

    await vi.waitFor(() => expect(document.body.textContent).not.toContain('Call the dentist'))
    expect(document.body.querySelector('[data-testid="api-error"]')).toBeNull()
  })

  it('keeps the toast when deleting the notification fails, and lets a retry finish the job', async () => {
    let notificationDeletes = 0
    let taskGone = false
    stub('/api/tasks/40', 'DELETE', (event) => {
      if (taskGone) return status(404)(event)
      taskGone = true
      return { status: 'ok' }
    })
    stub('/api/notifications/7', 'DELETE', (event) => {
      notificationDeletes++
      return notificationDeletes === 1 ? status(502)(event) : { status: 'ok' }
    })
    await mountWithReminder()

    button('Delete reminder').click()
    await vi.waitFor(() => expect(document.body.querySelector('[data-testid="api-error"]')).not.toBeNull())
    expect(document.body.textContent).toContain('Call the dentist')

    button('Delete reminder').click()
    await vi.waitFor(() => expect(document.body.textContent).not.toContain('Call the dentist'))
    expect(notificationDeletes).toBe(2)
  })
})

describe('NotificationBar — Reply in chat (JCLAW-1299)', () => {
  it('marks the reminder seen and opens its agent\'s chat with the reminder quoted', async () => {
    let acked = false
    stub('/api/notifications/7/ack', 'POST', () => {
      acked = true
      return { status: 'ok' }
    })
    await mountWithReminder()

    button('Reply in chat').click()

    await vi.waitFor(() => expect(navigateToMock).toHaveBeenCalledWith('/chat'))
    expect(acked).toBe(true)
    expect(useChatReplyHandoff().value).toEqual({ agentId: 1, quote: { kind: 'reminder', text: 'Call the dentist' } })
    expect(document.body.textContent).not.toContain('Call the dentist')
    useChatReplyHandoff().value = null
  })
})
