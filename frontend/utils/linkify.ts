/**
 * Turn bare http(s) URLs inside a plain-text string into clickable anchors,
 * leaving every other character verbatim.
 *
 * This is deliberately a linkify-only pass, NOT a markdown render: reminder
 * descriptions are shown as-is (the page promises "the description is what
 * you'll see") with `whitespace-pre-wrap`, so a full `marked` pass would
 * reinterpret `#`, `*`, `1.` etc. and mangle the verbatim text.
 *
 * Safety ordering (the result is fed to `v-html`):
 *   1. HTML-escape the whole string, so any markup in the reminder text is
 *      rendered inert rather than executed.
 *   2. Splice in our own controlled <a> tags. Escaping first also turns `&`
 *      into `&amp;`, which is the correct encoding inside an href attribute —
 *      so a `?api=1&destination=…` query string round-trips intact.
 *   3. DOMPurify as a defence-in-depth guard, restricted to anchors.
 */
import DOMPurify from 'dompurify'

// An http/https URL runs up to the first whitespace or `<`; the trailing class
// trims one terminal punctuation char so a sentence-final ".", ")", etc. stays
// out of the link. Runs against the already-escaped string, so `&amp;` in the
// middle of a query string is matched as ordinary URL text.
const URL_RE = /\bhttps?:\/\/[^\s<]+[^\s<.,:;!?"')\]}]/gi

function escapeHtml(s: string): string {
  return s
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;')
    .replaceAll('\'', '&#39;')
}

export function linkify(text: string): string {
  if (!text) return ''
  const html = escapeHtml(text).replaceAll(
    URL_RE,
    url => `<a href="${url}" target="_blank" rel="noopener noreferrer">${url}</a>`,
  )
  return DOMPurify.sanitize(html, { ALLOWED_TAGS: ['a'], ALLOWED_ATTR: ['href', 'target', 'rel'] })
}
