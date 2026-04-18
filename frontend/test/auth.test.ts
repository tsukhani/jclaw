import { describe, it, expect } from 'vitest'
import { registerEndpoint } from '@nuxt/test-utils/runtime'

describe('useAuth', () => {
  it('starts unauthenticated', () => {
    const { authenticated, username } = useAuth()
    expect(authenticated.value).toBe(false)
    expect(username.value).toBeNull()
  })

  it('login sets authenticated state on success', async () => {
    registerEndpoint('/api/auth/login', {
      method: 'POST',
      handler: () => ({ status: 'ok', username: 'admin' }),
    })

    const { authenticated, username, login } = useAuth()
    const result = await login('admin', 'password')

    expect(result).toBe(true)
    expect(authenticated.value).toBe(true)
    expect(username.value).toBe('admin')
  })

  it('login returns false on failure', async () => {
    registerEndpoint('/api/auth/login', {
      method: 'POST',
      handler: () => { throw createError({ statusCode: 401 }) },
    })

    // Reset state from previous test
    useState('auth:authenticated').value = false
    useState('auth:username').value = null

    const { authenticated, login } = useAuth()
    const result = await login('admin', 'wrong')

    expect(result).toBe(false)
    expect(authenticated.value).toBe(false)
  })

  it('checkAuth returns true when API responds', async () => {
    registerEndpoint('/api/config', {
      method: 'GET',
      handler: () => ({ entries: [] }),
    })

    const { authenticated, checkAuth } = useAuth()
    const result = await checkAuth()

    expect(result).toBe(true)
    expect(authenticated.value).toBe(true)
  })

  it('checkAuth returns false when API rejects', async () => {
    registerEndpoint('/api/config', {
      method: 'GET',
      handler: () => { throw createError({ statusCode: 401 }) },
    })

    const { checkAuth } = useAuth()
    const result = await checkAuth()

    expect(result).toBe(false)
  })
})
