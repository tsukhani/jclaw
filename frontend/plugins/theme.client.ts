// Apply the persisted theme (or system preference) to <html> before any
// component mounts, so every page — including /login, which bypasses the
// default layout and therefore never calls useTheme() — renders in the
// correct mode from the first paint. System preference is the default when
// nothing is stored; useTheme() in the default layout is still the sole
// writer of localStorage and takes over once a user logs in.
export default defineNuxtPlugin(() => {
  const saved = localStorage.getItem('jclaw-theme') as 'system' | 'light' | 'dark' | null
  const mode = saved ?? 'system'
  const prefersDark =
    mode === 'dark' ||
    (mode === 'system' && window.matchMedia('(prefers-color-scheme: dark)').matches)
  document.documentElement.classList.toggle('dark', prefersDark)
})
