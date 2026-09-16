import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { registerEndpoint } from '@nuxt/test-utils/runtime'
import { setResponseStatus } from 'h3'
import { apiErrorDetails, useApiMutation } from '~/composables/useApiMutation'

// JCLAW-1131: the composable gained `errorDetails` additively — `error` stays the nullable
// string all 18 consumers destructure.
describe('apiErrorDetails', () => {
  it('reads the canonical envelope', () => {
    const details = apiErrorDetails({
      data: {
        type: 'error',
        code: 'not_found',
        message: 'no such thing',
        template: { whatBroke: 'gone', whatToCheck: 'the id', howToRetry: 'go back' },
      },
    })

    expect(details.code).toBe('not_found')
    expect(details.message).toBe('no such thing')
    expect(details.template?.howToRetry).toBe('go back')
  })

  it('leaves code and template null when the failure produced no envelope', () => {
    const details = apiErrorDetails(new Error('[GET] "/api/x": 502 Bad Gateway'))

    expect(details.code).toBeNull()
    expect(details.template).toBeNull()
    expect(details.message).toBe('[GET] "/api/x": 502 Bad Gateway')
  })

  it('prefers a supplied fallback over the raw status text, but never over the envelope', () => {
    expect(apiErrorDetails(new Error('502 Bad Gateway'), 'Save failed').message)
      .toBe('Save failed')
    expect(apiErrorDetails({ data: { code: 'forbidden', message: 'reserved key' } }, 'Save failed').message)
      .toBe('reserved key')
  })
})

describe('useApiMutation', () => {
  beforeEach(() => {
    vi.spyOn(console, 'error').mockImplementation(() => {})
    registerEndpoint('/api/mutation-ok', { method: 'POST', handler: () => ({ status: 'ok' }) })
    registerEndpoint('/api/mutation-refused', {
      method: 'POST',
      handler: (event) => {
        setResponseStatus(event, 403)
        return {
          type: 'error',
          code: 'forbidden',
          message: 'reserved key',
          template: { whatBroke: 'not allowed', whatToCheck: 'operator-only?', howToRetry: null },
        }
      },
    })
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('leaves both error refs null on success', async () => {
    const { mutate, error, errorDetails } = useApiMutation()

    expect(await mutate('/api/mutation-ok', { method: 'POST' })).toEqual({ status: 'ok' })
    expect(error.value).toBeNull()
    expect(errorDetails.value).toBeNull()
  })

  it('keeps error as the message string and carries the parts alongside it', async () => {
    const { mutate, error, errorDetails } = useApiMutation()

    expect(await mutate('/api/mutation-refused', { method: 'POST' })).toBeNull()
    expect(error.value).toBe('reserved key')
    expect(errorDetails.value?.code).toBe('forbidden')
    expect(errorDetails.value?.template?.whatToCheck).toBe('operator-only?')
    expect(errorDetails.value?.template?.howToRetry).toBeNull()
  })

  it('clears a previous failure when the next call succeeds', async () => {
    const { mutate, error, errorDetails } = useApiMutation()

    await mutate('/api/mutation-refused', { method: 'POST' })
    await mutate('/api/mutation-ok', { method: 'POST' })

    expect(error.value).toBeNull()
    expect(errorDetails.value).toBeNull()
  })
})
