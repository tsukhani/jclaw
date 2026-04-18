import { describe, it, expect } from 'vitest'
import { rewriteWorkspaceLinks } from '~/utils/markdown-links'

// jsdom is the default Vitest env — DOMParser is available.

function parse(html: string): Document {
  return new DOMParser().parseFromString(`<!doctype html><body>${html}</body>`, 'text/html')
}
function firstAnchor(html: string): Element | null {
  return parse(html).querySelector('a')
}

describe('rewriteWorkspaceLinks', () => {
  const AGENT_ID = 1

  describe('absolute workspace URLs (tool-emitted, e.g. PlaywrightBrowserTool screenshot link)', () => {
    it('marks the link as a download and applies the workspace-file class', () => {
      // The browser tool's formatScreenshotResult now emits
      // `[screenshot](/api/agents/{id}/files/screenshot-N.png)` so the LLM can
      // echo a clickable link. The chat renderer must promote that link to
      // the same styled pill-button affordance used by other file links.
      const input = '<a href="/api/agents/1/files/screenshot-1000.png">screenshot</a>'
      const result = rewriteWorkspaceLinks(input, AGENT_ID)
      const a = firstAnchor(result)!
      expect(a.getAttribute('href')).toBe('/api/agents/1/files/screenshot-1000.png')
      expect(a.hasAttribute('download')).toBe(true)
      expect(a.classList.contains('workspace-file')).toBe(true)
    })

    it('does not touch workspace URLs for a different agent id', () => {
      // Safety guard: agent-scoped URLs should only be styled for the current
      // agent. A link pointing to a different agent's workspace must remain
      // a plain absolute href (no download affordance) — cross-agent file
      // access is an authorization decision that belongs in the backend.
      const input = '<a href="/api/agents/99/files/other.png">other</a>'
      const result = rewriteWorkspaceLinks(input, AGENT_ID)
      const a = firstAnchor(result)!
      expect(a.getAttribute('href')).toBe('/api/agents/99/files/other.png')
      expect(a.hasAttribute('download')).toBe(false)
      expect(a.classList.contains('workspace-file')).toBe(false)
    })

    it('leaves unrelated /api/ links alone', () => {
      const input = '<a href="/api/status">status</a>'
      const result = rewriteWorkspaceLinks(input, AGENT_ID)
      const a = firstAnchor(result)!
      expect(a.hasAttribute('download')).toBe(false)
      expect(a.classList.contains('workspace-file')).toBe(false)
    })
  })

  describe('relative hrefs', () => {
    it('rewrites to workspace endpoint, adds download + class', () => {
      const input = '<a href="report.pdf">report</a>'
      const result = rewriteWorkspaceLinks(input, AGENT_ID)
      const a = firstAnchor(result)!
      expect(a.getAttribute('href')).toBe('/api/agents/1/files/report.pdf')
      expect(a.hasAttribute('download')).toBe(true)
      expect(a.classList.contains('workspace-file')).toBe(true)
    })

    it('preserves spaces-in-filenames by round-tripping encodeURIComponent', () => {
      // marked.parse URL-encodes spaces to %20 in the href; encoding again
      // would produce %2520 and 404 against a filename with real spaces.
      const input = '<a href="my%20notes.md">notes</a>'
      const result = rewriteWorkspaceLinks(input, AGENT_ID)
      const a = firstAnchor(result)!
      expect(a.getAttribute('href')).toBe('/api/agents/1/files/my%20notes.md')
    })
  })

  describe('external and non-workspace links', () => {
    it('opens http(s) links in a new tab', () => {
      const input = '<a href="https://example.com">ex</a>'
      const result = rewriteWorkspaceLinks(input, AGENT_ID)
      const a = firstAnchor(result)!
      expect(a.getAttribute('target')).toBe('_blank')
      expect(a.getAttribute('rel')).toBe('noopener noreferrer')
      expect(a.hasAttribute('download')).toBe(false)
    })

    it('leaves anchors (#foo) alone', () => {
      const input = '<a href="#section">jump</a>'
      const result = rewriteWorkspaceLinks(input, AGENT_ID)
      const a = firstAnchor(result)!
      expect(a.getAttribute('href')).toBe('#section')
      expect(a.hasAttribute('download')).toBe(false)
    })

    it('leaves mailto / tel / data / javascript alone', () => {
      for (const scheme of ['mailto:a@b', 'tel:+1', 'data:text/plain,hi', 'javascript:void(0)']) {
        const a = firstAnchor(rewriteWorkspaceLinks(`<a href="${scheme}">x</a>`, AGENT_ID))!
        expect(a.getAttribute('href')).toBe(scheme)
        expect(a.hasAttribute('download')).toBe(false)
        expect(a.hasAttribute('target')).toBe(false)
      }
    })
  })

  describe('edge cases', () => {
    it('returns the input unchanged when there are no anchors', () => {
      const input = '<p>no links here</p>'
      expect(rewriteWorkspaceLinks(input, AGENT_ID)).toBe(input)
    })

    it('handles multiple mixed links in one pass', () => {
      const input = [
        '<a href="/api/agents/1/files/a.png">a</a>',
        '<a href="https://example.com">b</a>',
        '<a href="relative.txt">c</a>',
      ].join(' ')
      const doc = parse(rewriteWorkspaceLinks(input, AGENT_ID))
      const anchors = doc.querySelectorAll('a')
      expect(anchors.length).toBe(3)
      const workspaceAbs = anchors[0]!
      const external = anchors[1]!
      const relative = anchors[2]!
      expect(workspaceAbs.classList.contains('workspace-file')).toBe(true)
      expect(workspaceAbs.hasAttribute('download')).toBe(true)
      expect(external.getAttribute('target')).toBe('_blank')
      expect(relative.getAttribute('href')).toBe('/api/agents/1/files/relative.txt')
      expect(relative.hasAttribute('download')).toBe(true)
    })
  })
})
