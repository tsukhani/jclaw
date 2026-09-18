import { describe, it, expect } from 'vitest'
import { useLatestRequest } from '~/composables/useLatestRequest'

describe('useLatestRequest', () => {
  it('treats the newest begin() as current and every earlier one as superseded', () => {
    const latest = useLatestRequest()
    const first = latest.begin()
    expect(latest.isCurrent(first)).toBe(true)
    const second = latest.begin()
    expect(latest.isCurrent(first)).toBe(false)
    expect(latest.isCurrent(second)).toBe(true)
  })

  it('lets a slow older response be dropped while the newer one is applied', async () => {
    const latest = useLatestRequest()
    let shown = ''
    async function load(name: string, delayMs: number) {
      const request = latest.begin()
      await new Promise(resolve => setTimeout(resolve, delayMs))
      if (!latest.isCurrent(request)) return
      shown = name
    }
    await Promise.all([load('old', 20), load('new', 1)])
    expect(shown).toBe('new')
  })
})
