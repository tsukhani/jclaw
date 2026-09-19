import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import ErrorPage from '~/error.vue'

/**
 * error.vue replaces Nuxt's stock error page for an unmatched route and for an unhandled error,
 * so both read as JClaw and offer a way back into the app.
 */
const clearErrorMock = vi.hoisted(() => vi.fn())
mockNuxtImport('clearError', () => clearErrorMock)

function errorOf(statusCode: number, message: string, statusMessage?: string) {
  return { statusCode, message, statusMessage, url: '/whatever', fatal: false, unhandled: false, toJSON: () => ({}) } as never
}

describe('error page', () => {
  beforeEach(() => {
    clearErrorMock.mockReset()
  })

  it('renders a branded not-found page with the address and a way home', async () => {
    const page = await mountSuspended(ErrorPage, { props: { error: errorOf(404, 'Page not found: /no-such-page') } })
    expect(page.find('h1').text()).toBe('Page not found')
    expect(page.find('[data-testid="error-status"]').exists()).toBe(false)
    expect(page.find('[data-testid="error-detail"]').text()).toContain('nothing at this address')
    expect(page.find('[data-testid="error-back"]').exists()).toBe(true)
    expect(page.find('[data-testid="error-retry"]').exists()).toBe(false)

    await page.find('[data-testid="error-home"]').trigger('click')
    expect(clearErrorMock).toHaveBeenCalledWith({ redirect: '/' })
    page.unmount()
  })

  it('renders a runtime error with its message and a retry that clears the error in place', async () => {
    const page = await mountSuspended(ErrorPage, { props: { error: errorOf(500, 'boom', 'The listing walk failed') } })
    expect(page.find('h1').text()).toBe('Something went wrong')
    expect(page.find('[data-testid="error-status"]').text()).toBe('500')
    expect(page.find('[data-testid="error-detail"]').text()).toBe('The listing walk failed')
    expect(page.find('[data-testid="error-path"]').exists()).toBe(false)
    expect(page.find('[data-testid="error-back"]').exists()).toBe(false)

    await page.find('[data-testid="error-retry"]').trigger('click')
    expect(clearErrorMock).toHaveBeenCalledTimes(1)
    expect(clearErrorMock.mock.calls[0]![0]).toHaveProperty('redirect')
    page.unmount()
  })

  it('falls back to a generic sentence when the error carries no message', async () => {
    const page = await mountSuspended(ErrorPage, { props: { error: errorOf(503, '') } })
    expect(page.find('[data-testid="error-detail"]').text()).toBe('An unexpected error interrupted the page.')
    page.unmount()
  })
})
