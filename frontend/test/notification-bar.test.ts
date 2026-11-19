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

describe('NotificationBar — a notice JClaw raises itself (JF-536)', () => {
  async function mountWithNotice(linkUrl: string) {
    unregister.push(registerEndpoint('/api/notifications', () => [
      {
        id: 9, agentId: 1, agentName: 'main', content: 'Java 25.0.5.1 is downloaded and installs the next time JClaw restarts.',
        sourceTaskRunId: null, sourceTaskId: null, createdAt: new Date().toISOString(), acknowledgedAt: null,
        title: 'Java runtime', linkUrl, linkLabel: 'Azul\'s latest release notes',
      },
    ]))
    await mountSuspended(NotificationBar)
    await vi.waitFor(() => expect(document.body.textContent).toContain('Java 25.0.5.1 is downloaded'))
  }

  it('is headed by its own title, links its page, and offers no reply in chat', async () => {
    await mountWithNotice('https://docs.azul.com/core/release-notes')

    const toast = document.body.querySelector('article')!
    expect(toast.querySelector('span')!.textContent!.trim()).toBe('Java runtime')
    const link = toast.querySelector('a')!
    expect(link.textContent!.trim()).toBe('Azul\'s latest release notes')
    expect(link.getAttribute('href')).toBe('https://docs.azul.com/core/release-notes')
    expect(link.getAttribute('target')).toBe('_blank')
    expect(link.getAttribute('rel')).toBe('noopener noreferrer')
    expect(document.body.querySelector('[data-testid="reminder-reply"]')).toBeNull()
    expect(button('Delete notification')).toBeTruthy()
  })

  it('deletes only the notification, since no reminder stands behind it', async () => {
    let tasksDeleted = 0
    let notificationDeleted = false
    stub('/api/tasks/null', 'DELETE', () => {
      tasksDeleted++
      return { status: 'ok' }
    })
    stub('/api/notifications/9', 'DELETE', () => {
      notificationDeleted = true
      return { status: 'ok' }
    })
    await mountWithNotice('https://docs.azul.com/core/release-notes')

    button('Delete notification').click()

    await vi.waitFor(() => expect(document.body.textContent).not.toContain('Java 25.0.5.1 is downloaded'))
    expect(notificationDeleted).toBe(true)
    expect(tasksDeleted).toBe(0)
  })

  it('leaves out a link that is not https, label and all', async () => {
    await mountWithNotice('javascript:alert(1)')

    const toast = document.body.querySelector('article')!
    expect(toast.querySelector('a')).toBeNull()
    expect(toast.textContent).not.toContain('release notes')
    expect(toast.textContent).not.toContain('javascript:')
  })
})

describe('NotificationBar — a reminder keeps its own heading', () => {
  it('is headed Reminder and carries no link', async () => {
    await mountWithReminder()

    const toast = document.body.querySelector('article')!
    expect(toast.querySelector('span')!.textContent!.trim()).toBe('Reminder')
    expect(toast.querySelector('a')).toBeNull()
    expect(button('Delete reminder')).toBeTruthy()
  })
})
