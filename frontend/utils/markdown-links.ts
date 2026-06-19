/**
 * Rewrite / annotate anchor hrefs and image sources inside sanitized
 * markdown HTML so they render as styled workspace-file links and
 * resolve to the correct workspace-file URL respectively.
 *
 * Three URL shapes are handled distinctly for both {@code a[href]} and
 * {@code img[src]}:
 *  - Relative paths (e.g. `summary.docx`, `uploads/123/report.pdf`, or the
 *    bare-filename `screenshot-1776781604684.png` the LLM sometimes emits in
 *    a markdown image) — rewritten to `/api/agents/{agentId}/files/{encoded}`.
 *    Anchors additionally get the `download` attribute and `workspace-file`
 *    CSS class so they render as pill buttons; images get only the src fix.
 *  - Absolute workspace URLs (`/api/agents/{agentId}/files/...`) — already
 *    correctly addressed, so the URL is left alone; the anchor `download`
 *    attribute and `workspace-file` class are still applied so tool-emitted
 *    links render the same way.
 *  - External and unrelated absolute URLs — anchors get `target="_blank"`
 *    for http(s); images are left untouched.
 *
 * Extracted from `chat.vue` so the behavior is unit-testable outside a
 * component mount; the component re-exports it via its normal import path.
 */

/**
 * Normalize a link destination that arrived angle-bracket-wrapped. Some models wrap
 * a URL in angle brackets (a markdown habit) and — notably JSON-escaping-prone models
 * like deepseek — emit those brackets as the escape-SEQUENCE text (backslash-u-003c /
 * backslash-u-003e) rather than real angle-bracket characters. `marked` strips a real
 * angle-bracket wrapper but not the escaped form, and it URL-encodes the stray
 * backslash to %5C — so by the time the rewriter sees the href it looks like
 * `%5Cu003c…/jclaw-explainer.html%5Cu003e`. Left alone, the workspace URL we build
 * 404s ("File wasn't available on site").
 *
 * Decode both the raw (backslash) and marked-encoded (%5C) escape forms back to angle
 * brackets, then strip a leading/trailing wrapper. A real angle bracket is never valid
 * raw in a URL or path, so this is a no-op for clean links (and for %-encoded spaces)
 * and safe for absolute / external URLs alike.
 */
function stripAngleWrapping(url: string): string {
  // The model wraps with a single <…>, so single-char anchored strips are enough — and
  // they avoid the `>+$` backtracking shape Sonar flags (S5852). replaceAll() is the
  // preferred form for the global decode regexes (S7781).
  return url
    .replaceAll(/(?:\\|%5c)u003c/gi, '<')
    .replaceAll(/(?:\\|%5c)u003e/gi, '>')
    .replace(/^</, '')
    .replace(/>$/, '')
}

export function rewriteWorkspaceLinks(html: string, agentId: number): string {
  if (typeof DOMParser === 'undefined') return html
  const parser = new DOMParser()
  const doc = parser.parseFromString(`<div id="__root">${html}</div>`, 'text/html')
  const root = doc.getElementById('__root')
  if (!root) return html
  const workspacePrefix = `/api/agents/${agentId}/files/`
  root.querySelectorAll('a[href]').forEach((a) => {
    const raw = a.getAttribute('href') || ''
    if (!raw) return
    // Defend against angle-bracket-wrapped / escaped destinations (see
    // stripAngleWrapping) before any branch decides what to do with the href.
    const href = stripAngleWrapping(raw)
    if (href !== raw) a.setAttribute('href', href)
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
  // JCLAW-124: same rewrite rules for img src. The LLM sometimes emits a
  // markdown image with a bare-filename src (e.g. `![Screenshot](screenshot-X.png)`
  // instead of `![Screenshot](/api/agents/1/files/screenshot-X.png)`), which
  // the browser resolves against the page URL `/chat` → `/screenshot-X.png` →
  // 404 → broken-image placeholder in the bubble. Rewriting to the absolute
  // workspace URL makes these resolve to the real file.
  root.querySelectorAll('img[src]').forEach((img) => {
    const raw = img.getAttribute('src') || ''
    if (!raw) return
    const src = stripAngleWrapping(raw)
    if (src !== raw) img.setAttribute('src', src)
    if (!src) return
    if (src.startsWith('#')) return
    if (src.startsWith(workspacePrefix)) return
    if (src.startsWith('/')) return
    if (/^https?:/i.test(src)) return
    if (/^(mailto|tel|ftp|data|javascript):/i.test(src)) return
    const encoded = src.split('/').filter(Boolean).map(s => encodeURIComponent(decodeURIComponent(s))).join('/')
    img.setAttribute('src', `/api/agents/${agentId}/files/${encoded}`)
  })
  return root.innerHTML
}
