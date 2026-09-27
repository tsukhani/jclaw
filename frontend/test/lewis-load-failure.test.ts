import { describe, it, expect, vi } from 'vitest'
import { renderMarkdown } from '~/utils/chat-markdown'
import { ensureLewisLoaded, isLewisLoaded, lewisVersion } from '~/utils/lewis'

// A module whose import throws stands in for a chunk that fails to load, offline or after a redeploy.
vi.mock('smiles-drawer', () => {
  throw new Error('chunk failed to load')
})

describe('lewis fence when SmilesDrawer fails to load', () => {
  it('keeps the code block and never bumps lewisVersion', async () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {})
    try {
      const fence = '```lewis\n[H]O[H]\n```\n'
      expect(renderMarkdown(fence)).toContain('<code class="language-lewis">')
      await ensureLewisLoaded()
      expect(isLewisLoaded()).toBe(false)
      expect(lewisVersion.value).toBe(0)
      expect(warn).toHaveBeenCalledWith('[lewis] SmilesDrawer failed to load', expect.anything())
      const after = renderMarkdown(fence)
      expect(after).toContain('<code class="language-lewis">')
      expect(after).not.toContain('<svg')
    }
    finally {
      warn.mockRestore()
    }
  })
})
