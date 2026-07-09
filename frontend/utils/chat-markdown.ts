/**
 * Chat markdown render pipeline (pure).
 *
 * Configures Marked (CommonMark + GFM) and DOMPurify once at module import,
 * then exposes the memoized markdown → safe-HTML conversion the chat transcript
 * renders through. Kept out of the page SFC so the sanitizer allow-list,
 * angle-bracket link normalization, and the LRU cache are unit-testable without
 * mounting the page.
 *
 * The `marked.setOptions` + `DOMPurify.addHook` calls below are deliberate
 * module-level side effects: they must run exactly once, and the
 * `markdownCache` must stay module-scoped so the LRU is shared across every
 * caller. Do not move these into a factory or per-call path — the /api/ src+href
 * allow-list and the cache bound both depend on the single-instance semantics.
 */
import { marked } from 'marked'
import DOMPurify from 'dompurify'
import { rewriteWorkspaceLinks } from '~/utils/markdown-links'
import type { MessageUsage } from '~/utils/usage-cost'

// Configure marked for safe rendering
marked.setOptions({
  breaks: true,
  gfm: true,
})

export function formatTokensPerSec(usage: MessageUsage): string | null {
  if (!usage.durationMs || usage.durationMs <= 0 || !usage.completion) return null
  const tps = (usage.completion / usage.durationMs) * 1000
  return tps.toFixed(1) + ' tok/s'
}

// Configure DOMPurify to allow images, audio, video, and download links
DOMPurify.addHook('uponSanitizeAttribute', (node, data) => {
  // Allow src attributes on img/audio/video/source that point to our API
  if (data.attrName === 'src' && data.attrValue?.startsWith('/api/')) {
    data.forceKeepAttr = true
  }
  // Allow href for download links to our API
  if (data.attrName === 'href' && data.attrValue?.startsWith('/api/')) {
    data.forceKeepAttr = true
  }
})

// Marked (CommonMark) only allows whitespace in link destinations when they
// are wrapped in angle brackets. LLMs routinely emit bare filenames with
// spaces like `[file.docx](file.docx)`, which silently fall through as plain
// text. Wrap such destinations in <...> so they parse into real anchors.
export function normalizeMarkdownLinks(text: string): string {
  return text.replaceAll(/\[([^\]\n]+)\]\(([^)\n]+)\)/g, (match, label, dest) => {
    const trimmed = dest.trim()
    if (trimmed.startsWith('<') && trimmed.endsWith('>')) return match
    if (!/\s/.test(trimmed)) return match
    return `[${label}](<${trimmed}>)`
  })
}

// Memoized markdown rendering — avoids re-parsing unchanged messages on re-render.
// Cache is keyed by (text + agentId); the streaming message renders through
// renderMarkdownStreaming() which bypasses the cache entirely (its content
// changes every token, so caching every intermediate state would thrash both
// the cache and the LRU bound).
const markdownCache = new Map<string, string>()
const MARKDOWN_CACHE_MAX = 200

function renderMarkdownInner(text: string, agentId: number | null): string {
  const html = marked.parse(normalizeMarkdownLinks(text)) as string
  const sanitized = DOMPurify.sanitize(html, {
    ADD_TAGS: ['img', 'audio', 'video', 'source'],
    ADD_ATTR: ['src', 'controls', 'autoplay', 'download', 'target'],
  })
  return agentId == null ? sanitized : rewriteWorkspaceLinks(sanitized, agentId)
}

export function renderMarkdown(text: string, agentId: number | null = null): string {
  if (!text) return ''
  const cacheKey = `${agentId}:${text}`
  const cached = markdownCache.get(cacheKey)
  if (cached) return cached

  const result = renderMarkdownInner(text, agentId)
  // Only cache if under limit (prevents unbounded growth during long sessions)
  if (markdownCache.size < MARKDOWN_CACHE_MAX) {
    markdownCache.set(cacheKey, result)
  }
  return result
}

// Cache-bypassing variant for the in-flight streaming bubble. The content
// changes every token; caching every intermediate string would saturate the
// 200-entry LRU before the cache helped any historical message.
export function renderMarkdownStreaming(text: string, agentId: number | null = null): string {
  if (!text) return ''
  return renderMarkdownInner(text, agentId)
}
