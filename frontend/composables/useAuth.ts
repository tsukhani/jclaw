// Module-level lock so concurrent navigations don't fire parallel checkAuth requests
let checkInProgress: Promise<boolean> | null = null

/**
 * Whether the admin password has been set in the DB. Drives the routing
 * between /login (password set) and /setup-password (fresh install or
 * deliberately cleared password). Unauthenticated endpoint — safe to
 * call from the login and setup pages.
 *
 * Stateless (no closure over useAuth refs), so it lives at module scope.
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
 *  is already configured, which the caller handles as "go back to /login."
 *
 *  Stateless (no closure over useAuth refs), so it lives at module scope.
 */
async function setupPassword(pass: string): Promise<{ ok: boolean, error?: string }> {
  const { saveError, attempt } = useSaveAttempt()
  const ok = await attempt(() => $fetch('/api/auth/setup', {
    method: 'POST',
    body: { password: pass },
  }))
  if (ok) return { ok: true }
  const code = saveError.value?.code
  if (code === 'already_set') {
    return { ok: false, error: 'already_set' }
  }
  if (code === 'password_too_short' || code === 'password_too_long' || code === 'password_breached') {
    return { ok: false, error: code }
  }
  return { ok: false, error: 'network' }
}

export function useAuth() {
  const authenticated = useState('auth:authenticated', () => false)
  const username = useState<string | null>('auth:username', () => null)
  const { saveError, attempt } = useSaveAttempt()

  async function login(user: string, pass: string): Promise<boolean> {
    const ok = await attempt(() => $fetch('/api/auth/login', {
      method: 'POST',
      body: { username: user, password: pass },
    }))
    if (!ok) return false
    authenticated.value = true
    username.value = user
    // A new session re-arms the first-run nudges. The "Leave a star!"
    // pointer is scoped to a login, not to a browser, so signing back in
    // surfaces it again rather than it being spent forever on whichever
    // load happened to fire it first.
    resetStarNudge()
    return true
  }

  async function logout() {
    // A refused logout still signs the client out.
    await attempt(() => $fetch('/api/auth/logout', { method: 'POST' }))
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
   * Wipe the admin password hash from the DB and sign the user out. Used
   * by the Settings → Password section; the server clears the session
   * atomically with the config delete, so the next navigation hits the
   * middleware's unset-password path and routes to /setup-password. Throws
   * when the server does not reset, so the caller can say why.
   */
  async function resetPassword(): Promise<void> {
    const ok = await attempt(() => $fetch('/api/auth/reset-password', { method: 'POST' }))
    // The panel reads the cause back through apiErrorDetails, which takes it from `data`.
    if (!ok) throw Object.assign(new Error(saveError.value!.message), { data: saveError.value })
    authenticated.value = false
    username.value = null
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
