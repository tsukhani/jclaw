// Module-level lock so concurrent navigations don't fire parallel checkAuth requests
let checkInProgress: Promise<boolean> | null = null

export function useAuth() {
  const authenticated = useState('auth:authenticated', () => false)
  const username = useState<string | null>('auth:username', () => null)

  async function login(user: string, pass: string): Promise<boolean> {
    try {
      await $fetch('/api/auth/login', {
        method: 'POST',
        body: { username: user, password: pass },
      })
      authenticated.value = true
      username.value = user
      return true
    }
    catch {
      return false
    }
  }

  async function logout() {
    try {
      await $fetch('/api/auth/logout', { method: 'POST' })
    }
    catch {
      // Ignore errors on logout
    }
    authenticated.value = false
    username.value = null
    navigateTo('/login')
  }

  async function checkAuth(): Promise<boolean> {
    if (checkInProgress) return checkInProgress
    checkInProgress = (async () => {
      try {
        await $fetch('/api/config')
        authenticated.value = true
        return true
      }
      catch {
        authenticated.value = false
        return false
      }
    })().finally(() => { checkInProgress = null })
    return checkInProgress
  }

  /**
   * Whether the admin password has been set in the DB. Drives the routing
   * between /login (password set) and /setup-password (fresh install or
   * deliberately cleared password). Unauthenticated endpoint — safe to
   * call from the login and setup pages.
   */
  async function checkPasswordSet(): Promise<boolean> {
    try {
      const r = await $fetch<{ passwordSet: boolean }>('/api/auth/status')
      return !!r?.passwordSet
    }
    catch {
      // On network error, assume password is set — safer than routing to
      // the setup screen and letting an attacker mid-MITM claim "it's a
      // fresh install, set the password please."
      return true
    }
  }

  /** Submit the first-time password. Server rejects with 409 if a password
   *  is already configured, which the caller handles as "go back to /login." */
  async function setupPassword(pass: string): Promise<{ ok: boolean, error?: string }> {
    try {
      await $fetch('/api/auth/setup', {
        method: 'POST',
        body: { password: pass },
      })
      return { ok: true }
    }
    catch (e: unknown) {
      const err = e as { data?: { code?: string, message?: string }, status?: number }
      const code = err?.data?.code
      if (err?.status === 409 || code === 'already_set') {
        return { ok: false, error: 'already_set' }
      }
      if (code === 'password_too_short') {
        return { ok: false, error: 'password_too_short' }
      }
      return { ok: false, error: 'network' }
    }
  }

  /**
   * Wipe the admin password hash from the DB and sign the user out. Used
   * by the Settings → Password section; the server clears the session
   * atomically with the config delete, so the next navigation hits the
   * middleware's unset-password path and routes to /setup-password.
   */
  async function resetPassword(): Promise<boolean> {
    try {
      await $fetch('/api/auth/reset-password', { method: 'POST' })
      authenticated.value = false
      username.value = null
      return true
    }
    catch {
      return false
    }
  }

  return {
    authenticated: readonly(authenticated),
    username: readonly(username),
    login,
    logout,
    checkAuth,
    checkPasswordSet,
    setupPassword,
    resetPassword,
  }
}
