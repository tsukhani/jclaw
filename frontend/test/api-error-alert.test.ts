import { describe, it, expect } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import ApiErrorAlert from '~/components/ApiErrorAlert.vue'
import type { ApiErrorDetails } from '~/types/api'

// JCLAW-1131: one renderer for every API error — the headline plus what broke, what to
// check and how to retry.
describe('ApiErrorAlert', () => {
  const refused: ApiErrorDetails = {
    code: 'forbidden',
    message: 'voice.endpoint.baseSilenceMs must not exceed voice.endpoint.maxSilenceMs (1500).',
    template: {
      whatBroke: 'You are signed in, but not allowed to do this.',
      whatToCheck: 'Check whether the operation is operator-only.',
      howToRetry: 'Make the change yourself in the admin UI.',
    },
  }

  it('renders nothing when there is no error', async () => {
    const c = await mountSuspended(ApiErrorAlert, { props: { error: null } })
    expect(c.find('[data-testid="api-error"]').exists()).toBe(false)
  })

  it('renders the message and all three parts', async () => {
    const c = await mountSuspended(ApiErrorAlert, { props: { error: refused } })

    const text = c.find('[role="alert"]').text()
    expect(text).toContain('must not exceed')
    expect(text).toContain('What broke')
    expect(text).toContain('You are signed in, but not allowed to do this.')
    expect(text).toContain('What to check')
    expect(text).toContain('How to retry')
    expect(text).toContain('Make the change yourself in the admin UI.')
  })

  it('omits the retry section when the failure has no retry path', async () => {
    const noRetry = { ...refused, template: { ...refused.template!, howToRetry: null } }
    const c = await mountSuspended(ApiErrorAlert, { props: { error: noRetry } })

    const text = c.find('[role="alert"]').text()
    expect(text).toContain('What to check')
    expect(text).not.toContain('How to retry')
  })

  it('renders the message alone when the server sent no template', async () => {
    const c = await mountSuspended(ApiErrorAlert, {
      props: { error: { code: null, message: 'Save failed', template: null } },
    })

    const text = c.find('[role="alert"]').text()
    expect(text).toContain('Save failed')
    expect(text).not.toContain('What broke')
  })

  // JCLAW-61 tuned --danger to ≥4.5:1 on --muted and --surface-elevated in both themes;
  // the raw text-red-700/dark:text-red-400 pair most panels use is not that token.
  it('colours itself with the semantic danger token and no raw red utility', async () => {
    const c = await mountSuspended(ApiErrorAlert, { props: { error: refused } })

    expect(c.find('[data-testid="api-error"]').classes()).toContain('text-danger')
    expect(c.html()).not.toContain('text-red-')
  })

  describe('headline', () => {
    it('replaces the message and keeps the server\'s own beneath it', async () => {
      const c = await mountSuspended(ApiErrorAlert, {
        props: {
          error: { code: 'upstream_error', message: 'Provider returned HTTP 401', template: null },
          headline: 'Could not reach openrouter.',
        },
      })

      const text = c.find('[role="alert"]').text()
      expect(text).toContain('Could not reach openrouter.')
      expect(text).toContain('Provider returned HTTP 401')
    })

    // With no code the envelope never parsed, so the message is $fetch's raw status line.
    it('drops the message when the failure carried no code', async () => {
      const c = await mountSuspended(ApiErrorAlert, {
        props: {
          error: { code: null, message: '[POST] "/api/x": 502 Bad Gateway', template: null },
          headline: 'Could not reach openrouter.',
        },
      })

      const text = c.find('[role="alert"]').text()
      expect(text).toContain('Could not reach openrouter.')
      expect(text).not.toContain('502 Bad Gateway')
    })
  })
})
