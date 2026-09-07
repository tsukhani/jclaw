/**
 * Reserve a panel's last-known height until its data arrives, so content landing
 * later doesn't shove the rest of the page down (CLS).
 *
 * The dashboard's data panels render header-only until their fetch resolves, then
 * expand — Chat Cost measured 137px -> 1349px, moving the three panels below it.
 * Reserving the height the panel had last time makes that expansion a no-op: the
 * space is already the right size, so nothing moves. `GithubStarsButton` does the
 * same thing by hand for its star count.
 *
 * The reservation is a floor, not a fixed size, and it is dropped the moment
 * `ready` flips — so a panel whose data shrank (a narrower window filter, say)
 * settles to its true height rather than keeping a stale gap. The cost of a wrong
 * stored value is therefore one shift, which is what an unreserved panel does on
 * every load anyway.
 *
 * @param key      storage suffix, unique per panel
 * @param fallback height to reserve when nothing is stored — the first-ever visit
 * @param ready    flips true once the panel's data has rendered. It MUST read
 *                 false on its first evaluation or nothing is ever reserved —
 *                 a lazy `useFetch` sits at status 'idle' with `pending` false
 *                 before it starts, so watch `status`, not `pending`.
 */
export function useStableHeight(key: string, fallback: number, ready: Ref<boolean>) {
  const storageKey = `jclaw-panel-height-${key}`
  const el = ref<HTMLElement | null>(null)
  const released = ref(false)
  const stored = ref(fallback)

  // Private-mode localStorage throws on access, and a hand-mangled entry parses
  // to NaN. Either way the fallback stands.
  try {
    const raw = localStorage.getItem(storageKey)
    const n = raw ? Number.parseInt(raw, 10) : Number.NaN
    if (Number.isFinite(n) && n > 0) stored.value = n
  }
  catch { /* no stored height */ }

  /** Bound to the panel body's style; undefined once released so the box sizes to content. */
  const reservedHeight = computed(() =>
    released.value ? undefined : `${stored.value}px`)

  const stop = watch(ready, async (isReady) => {
    if (!isReady || released.value) return
    released.value = true
    // Measure after the release lands, or min-height would inflate the reading
    // and ratchet the stored value up on every visit.
    await nextTick()
    const h = Math.round(el.value?.getBoundingClientRect().height ?? 0)
    if (h > 0) {
      try {
        localStorage.setItem(storageKey, String(h))
      }
      catch { /* not persisted; next load uses the fallback */ }
    }
    stop()
  }, { immediate: true })

  return { reservedHeight, el }
}
