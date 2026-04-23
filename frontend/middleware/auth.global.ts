export default defineNuxtRouteMiddleware(async (to) => {
  const { authenticated, checkAuth, checkPasswordSet } = useAuth()

  // On /login and /setup-password, run the password-set check so we can
  // route between them. Both pages are unauthenticated targets, but they
  // are mutually exclusive: no-password → /setup-password, has-password
  // → /login.
  if (to.path === '/login' || to.path === '/setup-password') {
    const passwordSet = await checkPasswordSet()
    if (!passwordSet && to.path === '/login') return navigateTo('/setup-password')
    if (passwordSet && to.path === '/setup-password') return navigateTo('/login')
    return
  }

  if (!authenticated.value) {
    const isValid = await checkAuth()
    if (!isValid) {
      // Prefer the setup screen on a cold install so the user doesn't
      // hit /login only to get bounced one hop later.
      const passwordSet = await checkPasswordSet()
      return navigateTo(passwordSet ? '/login' : '/setup-password')
    }
  }
})
