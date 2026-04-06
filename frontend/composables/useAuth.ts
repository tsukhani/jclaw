export function useAuth() {
  const authenticated = useState('auth:authenticated', () => false)
  const username = useState<string | null>('auth:username', () => null)

  async function login(user: string, pass: string): Promise<boolean> {
    try {
      await $fetch<any>('/api/auth/login', {
        method: 'POST',
        body: { username: user, password: pass }
      })
      authenticated.value = true
      username.value = user
      return true
    } catch {
      return false
    }
  }

  async function logout() {
    try {
      await $fetch('/api/auth/logout', { method: 'POST' })
    } catch {
      // Ignore errors on logout
    }
    authenticated.value = false
    username.value = null
    navigateTo('/login')
  }

  async function checkAuth(): Promise<boolean> {
    try {
      await $fetch('/api/config')
      authenticated.value = true
      return true
    } catch {
      authenticated.value = false
      return false
    }
  }

  return {
    authenticated: readonly(authenticated),
    username: readonly(username),
    login,
    logout,
    checkAuth
  }
}
