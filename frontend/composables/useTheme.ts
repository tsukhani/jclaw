type ThemeMode = 'system' | 'light' | 'dark'

const themeMode = ref<ThemeMode>('system')

function applyTheme(mode: ThemeMode) {
  if (import.meta.server) return

  const root = document.documentElement
  const prefersDark
    = mode === 'dark'
      || (mode === 'system' && window.matchMedia('(prefers-color-scheme: dark)').matches)
  root.classList.toggle('dark', prefersDark)
}

// View Transitions API isn't in lib.dom yet (Apr 2026: Chromium-only stable;
// Firefox still flagged). Keep the typing local so call sites are forced to
// feature-detect via the optional chain rather than trusting an ambient
// declaration that lies on Firefox.
type ViewTransition = { ready: Promise<void>, finished: Promise<void> }
type DocumentWithTransition = Document & {
  startViewTransition?: (cb: () => void) => ViewTransition
}

function prefersReducedMotion() {
  return window.matchMedia('(prefers-reduced-motion: reduce)').matches
}

async function runWithCircularReveal(
  commit: () => void,
  anchor: Element,
  doc: DocumentWithTransition,
) {
  const transition = doc.startViewTransition!(commit)
  try {
    await transition.ready
  }
  catch {
    return
  }

  const rect = anchor.getBoundingClientRect()
  // Inset the visible center of the circle by 60px from any viewport edge.
  // The toggle button is in the header's top-right corner; a clip-path
  // circle anchored exactly there expands as a quarter-arc, and on wide
  // aspect ratios the arc's leading edge sweeps near-vertically across
  // the screen — the user perceives a horizontal wipe, not a radial reveal.
  // Clamping by a small fixed inset brings the circle's center on-screen
  // so the curvature is visible during the early phase, while still feeling
  // anchored to the click.
  const EDGE_INSET = 60
  const cx = rect.left + rect.width / 2
  const cy = rect.top + rect.height / 2
  const x = Math.max(EDGE_INSET, Math.min(cx, window.innerWidth - EDGE_INSET))
  const y = Math.max(EDGE_INSET, Math.min(cy, window.innerHeight - EDGE_INSET))
  // Distance from the anchor's center to the farthest viewport corner —
  // guarantees the circle covers everything regardless of where the toggle
  // sits.
  const maxRadius = Math.hypot(
    Math.max(x, window.innerWidth - x),
    Math.max(y, window.innerHeight - y),
  )

  document.documentElement.animate(
    {
      clipPath: [
        `circle(0px at ${x}px ${y}px)`,
        `circle(${maxRadius}px at ${x}px ${y}px)`,
      ],
    },
    {
      duration: 550,
      easing: 'cubic-bezier(0.65, 0, 0.35, 1)',
      pseudoElement: '::view-transition-new(root)',
    },
  )
}

export function useTheme() {
  let cleanup: (() => void) | null = null

  onMounted(() => {
    const saved = localStorage.getItem('jclaw-theme') as ThemeMode | null
    if (saved) themeMode.value = saved
    applyTheme(themeMode.value)

    // Listen for system theme changes when in system mode
    const mq = window.matchMedia('(prefers-color-scheme: dark)')
    const handler = () => {
      if (themeMode.value === 'system') applyTheme('system')
    }
    mq.addEventListener('change', handler)
    cleanup = () => mq.removeEventListener('change', handler)
  })

  onUnmounted(() => cleanup?.())

  function setTheme(mode: ThemeMode, anchor?: Element | EventTarget | null) {
    const commit = () => {
      themeMode.value = mode
      localStorage.setItem('jclaw-theme', mode)
      applyTheme(mode)
    }

    const doc = document as DocumentWithTransition
    const el = anchor instanceof Element ? anchor : null
    if (!el || !doc.startViewTransition || prefersReducedMotion()) {
      commit()
      return
    }

    void runWithCircularReveal(commit, el, doc)
  }

  return { themeMode: readonly(themeMode), setTheme }
}
