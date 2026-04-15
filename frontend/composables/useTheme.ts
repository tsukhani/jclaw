type ThemeMode = 'system' | 'light' | 'dark'

const themeMode = ref<ThemeMode>('dark')

function applyTheme(mode: ThemeMode) {
  if (import.meta.server) return

  const root = document.documentElement
  const prefersDark =
    mode === 'dark' ||
    (mode === 'system' && window.matchMedia('(prefers-color-scheme: dark)').matches)
  root.classList.toggle('dark', prefersDark)
}

export function useTheme() {
  let cleanup: (() => void) | null = null

  onMounted(() => {
    const saved = localStorage.getItem('jclaw-theme') as ThemeMode | null
    if (saved) themeMode.value = saved
    applyTheme(themeMode.value)

    // Listen for system theme changes when in system mode
    const mq = window.matchMedia('(prefers-color-scheme: dark)')
    const handler = () => { if (themeMode.value === 'system') applyTheme('system') }
    mq.addEventListener('change', handler)
    cleanup = () => mq.removeEventListener('change', handler)
  })

  onUnmounted(() => cleanup?.())

  function setTheme(mode: ThemeMode) {
    themeMode.value = mode
    localStorage.setItem('jclaw-theme', mode)
    applyTheme(mode)
  }

  return { themeMode: readonly(themeMode), setTheme }
}
