type ThemeMode = 'system' | 'light' | 'dark'

const themeMode = ref<ThemeMode>('dark')

function applyTheme(mode: ThemeMode) {
  if (import.meta.server) return

  const root = document.documentElement
  if (mode === 'system') {
    const prefersDark = window.matchMedia('(prefers-color-scheme: dark)').matches
    root.classList.toggle('dark', prefersDark)
    root.classList.toggle('light-mode', !prefersDark)
  } else if (mode === 'light') {
    root.classList.remove('dark')
    root.classList.add('light-mode')
  } else {
    root.classList.add('dark')
    root.classList.remove('light-mode')
  }
}

export function useTheme() {
  onMounted(() => {
    const saved = localStorage.getItem('jclaw-theme') as ThemeMode | null
    if (saved) themeMode.value = saved
    applyTheme(themeMode.value)

    // Listen for system theme changes when in system mode
    const mq = window.matchMedia('(prefers-color-scheme: dark)')
    const handler = () => { if (themeMode.value === 'system') applyTheme('system') }
    mq.addEventListener('change', handler)
    onUnmounted(() => mq.removeEventListener('change', handler))
  })

  function setTheme(mode: ThemeMode) {
    themeMode.value = mode
    localStorage.setItem('jclaw-theme', mode)
    applyTheme(mode)
  }

  return { themeMode: readonly(themeMode), setTheme }
}
