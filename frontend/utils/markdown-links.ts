/**
 * Rewrite / annotate anchor hrefs inside sanitized markdown HTML so they
 * render as styled workspace-file download links.
 *
 * Three link shapes are handled distinctly:
 *  - Relative paths (e.g. `summary.docx`, `uploads/123/report.pdf`) — rewritten
 *    to `/api/agents/{agentId}/files/{encoded}` and given the `download`
 *    attribute and `workspace-file` CSS class so they render as pill buttons.
 *  - Absolute workspace URLs (`/api/agents/{agentId}/files/...`) — already
 *    correctly addressed, so the href is left alone; the `download` attribute
 *    and `workspace-file` class are still applied so tool-emitted links (e.g.
 *    the screenshot URL from `PlaywrightBrowserTool`) render the same way.
 *  - External and unrelated absolute URLs — `target="_blank"` for http(s),
 *    otherwise left untouched.
 *
 * Extracted from `chat.vue` so the behavior is unit-testable outside a
 * component mount; the component re-exports it via its normal import path.
 */
export function rewriteWorkspaceLinks(html: string, agentId: number): string {
  if (typeof DOMParser === 'undefined') return html
  const parser = new DOMParser()
  const doc = parser.parseFromString(`<div id="__root">${html}</div>`, 'text/html')
  const root = doc.getElementById('__root')
  if (!root) return html
  const workspacePrefix = `/api/agents/${agentId}/files/`
  root.querySelectorAll('a[href]').forEach((a) => {
    const href = a.getAttribute('href') || ''
    if (!href) return
    if (href.startsWith('#')) return
    if (href.startsWith(workspacePrefix)) {
      a.setAttribute('download', '')
      a.classList.add('workspace-file')
      return
    }
    if (href.startsWith('/')) return
    if (/^https?:/i.test(href)) {
      a.setAttribute('target', '_blank')
      a.setAttribute('rel', 'noopener noreferrer')
      return
    }
    if (/^(mailto|tel|ftp|data|javascript):/i.test(href)) return
    // Decode first: marked.parse already URL-encodes the href (spaces → %20),
    // so a raw encodeURIComponent would double-encode (%20 → %2520), producing
    // a URL that 404s because the filename on disk has real spaces, not "%20".
    const encoded = href.split('/').filter(Boolean).map(s => encodeURIComponent(decodeURIComponent(s))).join('/')
    a.setAttribute('href', `/api/agents/${agentId}/files/${encoded}`)
    a.setAttribute('download', '')
    a.classList.add('workspace-file')
  })
  return root.innerHTML
}
