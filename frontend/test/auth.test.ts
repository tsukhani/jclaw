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

  // The "Leave a star!" nudge is scoped to a login session, not to a browser:
  // signing back in has to surface it again rather than it being spent forever
  // on whichever load happened to fire it first.
  it('login re-arms the star nudge for the new session', async () => {
    registerEndpoint('/api/auth/login', {
      method: 'POST',
      handler: () => ({ status: 'ok', username: 'admin' }),
    })
    localStorage.setItem('jclaw-star-nudge-seen', '1')

    await useAuth().login('admin', 'password')

    expect(localStorage.getItem('jclaw-star-nudge-seen')).toBeNull()
  })

  it('a failed login leaves the star-nudge flag alone', async () => {
    registerEndpoint('/api/auth/login', {
      method: 'POST',
      handler: () => { throw createError({ statusCode: 401 }) },
    })
    localStorage.setItem('jclaw-star-nudge-seen', '1')

    await useAuth().login('admin', 'wrong')

    expect(localStorage.getItem('jclaw-star-nudge-seen')).toBe('1')
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

  it('logout signs the client out and is on the login page once it resolves', async () => {
    registerEndpoint('/api/auth/login', {
      method: 'POST',
      handler: () => ({ status: 'ok', username: 'admin' }),
    })
    registerEndpoint('/api/auth/logout', { method: 'POST', handler: () => ({ status: 'ok' }) })
    const { authenticated, username, login, logout } = useAuth()
    await login('admin', 'password')
    await useRouter().push('/chat')

    await logout()

    expect(authenticated.value).toBe(false)
    expect(username.value).toBeNull()
    expect(useRouter().currentRoute.value.path).toBe('/login')
  })

  it('a refused logout still signs the client out and lands on the login page', async () => {
    registerEndpoint('/api/auth/login', {
      method: 'POST',
      handler: () => ({ status: 'ok', username: 'admin' }),
    })
    registerEndpoint('/api/auth/logout', {
      method: 'POST',
      handler: () => { throw createError({ statusCode: 500 }) },
    })
    const { authenticated, username, login, logout } = useAuth()
    await login('admin', 'password')
    await useRouter().push('/chat')

    await logout()

    expect(authenticated.value).toBe(false)
    expect(username.value).toBeNull()
    expect(useRouter().currentRoute.value.path).toBe('/login')
  })
})
