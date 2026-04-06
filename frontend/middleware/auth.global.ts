export default defineNuxtRouteMiddleware(async (to) => {
  if (to.path === '/login') return

  const { authenticated, checkAuth } = useAuth()

  if (!authenticated.value) {
    const isValid = await checkAuth()
    if (!isValid) {
      return navigateTo('/login')
    }
  }
})
