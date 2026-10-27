import type { PersonaFinding } from '~/types/api'
import { sanitizeRenderedHtml } from '~/utils/chat-markdown'

/** The rule the backend names for a zero-width or bidi character, which shows as nothing on screen. */
export const INVISIBLE_RULE = 'invisible-character'

// Private-use characters carry the marks through the markdown renderer, which passes them as text.
const OPEN = String.fromCharCode(0xE000)
const OPEN_HIDDEN = String.fromCharCode(0xE001)
const CLOSE = String.fromCharCode(0xE002)
const SENTINEL = new RegExp(`[${OPEN}${OPEN_HIDDEN}${CLOSE}]`)
const SENTINELS = new RegExp(SENTINEL.source, 'g')
// Same length, so a marker character the file already holds keeps every offset after it.
const NEUTRAL = String.fromCodePoint(0xFFFD)

/** A stretch of a file to mark; `hidden` when every match in it is an invisible character. */
export interface MarkRange {
  start: number
  end: number
  hidden: boolean
}

/** Overlapping or touching findings become one range, so one stretch of text gets one mark. */
export function mergeFindings(findings: readonly PersonaFinding[]): MarkRange[] {
  const sorted = [...findings].sort((a, b) => a.start - b.start || a.end - b.end)
  const ranges: MarkRange[] = []
  for (const f of sorted) {
    const hidden = f.rule === INVISIBLE_RULE
    const last = ranges.at(-1)
    if (last && f.start <= last.end) {
      last.end = Math.max(last.end, f.end)
      last.hidden = last.hidden && hidden
    }
    else {
      ranges.push({ start: f.start, end: f.end, hidden })
    }
  }
  return ranges
}

/** Whether each finding still sits on the text it reported: an edit or a write since the check moves them. */
export function findingsHold(content: string, findings: readonly PersonaFinding[]): boolean {
  return findings.every(f => content.slice(f.start, f.end) === f.text)
}

/** The file without the invisible characters the findings name, each only while it still sits where reported. */
export function removeHiddenCharacters(content: string, findings: readonly PersonaFinding[]): string {
  return findings
    .filter(f => f.rule === INVISIBLE_RULE && content.slice(f.start, f.end) === f.text)
    .sort((a, b) => b.start - a.start)
    .reduce((text, f) => text.slice(0, f.start) + text.slice(f.end), content)
}

/** `U+200B` for each character, which is how the page names text that shows as nothing. */
export function codePoints(text: string): string {
  return [...text].map(c => `U+${c.codePointAt(0)!.toString(16).toUpperCase().padStart(4, '0')}`).join(' ')
}

/** A finding's text as the findings list shows it. */
export function findingText(f: PersonaFinding): string {
  return f.rule === INVISIBLE_RULE ? codePoints(f.text) : f.text.trim()
}

function escapeHtml(text: string): string {
  return text.replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;').replaceAll('\'', '&#39;')
}

function markHtml(text: string, hidden: boolean): string {
  return hidden
    ? `<mark class="injection-mark injection-mark-hidden" title="${codePoints(text)}">${escapeHtml(text)}</mark>`
    : `<mark class="injection-mark">${escapeHtml(text)}</mark>`
}

/**
 * The layer under the editor: the file's text, escaped, with each range in a mark. Its text is transparent
 * and laid out exactly as the textarea above it, so only the marks show, under the characters they cover.
 */
export function backdropHtml(content: string, ranges: readonly MarkRange[]): string {
  let html = ''
  let at = 0
  for (const r of ranges) {
    html += escapeHtml(content.slice(at, r.start)) + markHtml(content.slice(r.start, r.end), r.hidden)
    at = r.end
  }
  // A textarea shows a line after a trailing newline that a div collapses; the space keeps their heights equal.
  return `${html}${escapeHtml(content.slice(at))} `
}

/**
 * The file with each range between sentinels, for the markdown renderer. Sentinels the file already holds become
 * U+FFFD, so the only ones left are these. A visible range loses its edge whitespace first: a match can end on a
 * newline, and a sentinel after it would start the next markdown line.
 */
export function withSentinels(file: string, ranges: readonly MarkRange[]): string {
  const content = file.replaceAll(SENTINELS, NEUTRAL)
  let out = ''
  let at = 0
  for (const r of ranges) {
    let start = r.start
    let end = r.end
    if (!r.hidden) {
      while (start < end && /\s/.test(content[start]!)) start++
      while (end > start && /\s/.test(content[end - 1]!)) end--
    }
    if (start === end) continue
    out += content.slice(at, start) + (r.hidden ? OPEN_HIDDEN : OPEN) + content.slice(start, end) + CLOSE
    at = end
  }
  return out + content.slice(at)
}

function markElement(text: string, hidden: boolean): HTMLElement {
  const mark = document.createElement('mark')
  mark.className = hidden ? 'injection-mark injection-mark-hidden' : 'injection-mark'
  if (hidden) mark.title = codePoints(text)
  mark.textContent = text
  return mark
}

/**
 * Rendered HTML with each sentinel pair turned into a mark. A range the renderer split across elements, such as
 * a match running into bold text, is marked piece by piece. Sentinels that landed in an attribute, such as a
 * link's address, are dropped there, and the result is sanitized again: an attribute changed after DOMPurify
 * checked it could otherwise become an address it would have refused.
 */
export function markPreview(html: string): string {
  if (!SENTINEL.test(html)) return html
  const template = document.createElement('template')
  template.innerHTML = html
  for (const element of template.content.querySelectorAll('*')) {
    for (const attr of [...element.attributes]) {
      if (SENTINEL.test(attr.value)) element.setAttribute(attr.name, attr.value.replaceAll(SENTINELS, ''))
    }
  }
  const walker = document.createTreeWalker(template.content, NodeFilter.SHOW_TEXT)
  const texts: Text[] = []
  while (walker.nextNode()) texts.push(walker.currentNode as Text)
  let open: string | null = null
  for (const node of texts) {
    if (open === null && !SENTINEL.test(node.data)) continue
    const parts = document.createDocumentFragment()
    let run = ''
    const flush = () => {
      if (run) parts.append(open === null ? run : markElement(run, open === OPEN_HIDDEN))
      run = ''
    }
    for (const ch of node.data) {
      if (ch === OPEN || ch === OPEN_HIDDEN) {
        flush()
        open = ch
      }
      else if (ch === CLOSE) {
        flush()
        open = null
      }
      else {
        run += ch
      }
    }
    flush()
    node.replaceWith(parts)
  }
  return sanitizeRenderedHtml(template.innerHTML)
}
