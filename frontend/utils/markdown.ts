import { marked } from 'marked'
import DOMPurify from 'dompurify'
import { rewriteWorkspaceLinks } from '~/utils/markdown-links'

// Module-level marked + DOMPurify configuration. Module evaluation runs once
// per app session, so the addHook call below registers exactly one hook even
// if multiple pages import this file. (DOMPurify.addHook is additive — calling
// it twice would fire the hook twice per sanitize call, slowing the hot path.)
marked.setOptions({
  breaks: true,
  gfm: true,
})

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

/**
 * Marked (CommonMark) only allows whitespace in link destinations when they
 * are wrapped in angle brackets. LLMs routinely emit bare filenames with
 * spaces like `[file.docx](file.docx)`, which silently fall through as plain
 * text. Wrap such destinations in `<...>` so they parse into real anchors.
 */
export function normalizeMarkdownLinks(text: string): string {
  return text.replace(/\[([^\]\n]+)\]\(([^)\n]+)\)/g, (match, label, dest) => {
    const trimmed = dest.trim()
    if (trimmed.startsWith('<') && trimmed.endsWith('>')) return match
    if (!/\s/.test(trimmed)) return match
    return `[${label}](<${trimmed}>)`
  })
}

/**
 * Render markdown to sanitized HTML, optionally rewriting workspace-relative
 * paths into agent-scoped download URLs. Cache-bypassing — call this from
 * the streaming hot path where the content changes per token, or from any
 * caller that already has its own memoization layer.
 */
export function renderMarkdownUncached(text: string, agentId: number | null = null): string {
  if (!text) return ''
  const html = marked.parse(normalizeMarkdownLinks(text)) as string
  const sanitized = DOMPurify.sanitize(html, {
    ADD_TAGS: ['img', 'audio', 'video', 'source'],
    ADD_ATTR: ['src', 'controls', 'autoplay', 'download', 'target'],
  })
  return agentId != null ? rewriteWorkspaceLinks(sanitized, agentId) : sanitized
}

// Memoized markdown rendering — avoids re-parsing unchanged messages on
// re-render. Cache is keyed by `agentId:text` so the same body rendered
// against two different agents (different workspace-link rewrites) doesn't
// poison the other's slot. Bound at MARKDOWN_CACHE_MAX entries to prevent
// unbounded growth during long sessions; once full, new misses fall through
// to renderMarkdownUncached without inserting.
const markdownCache = new Map<string, string>()
const MARKDOWN_CACHE_MAX = 200

/**
 * Render markdown to sanitized HTML with LRU memoization. Use this for any
 * content that doesn't change between renders (historical messages, settled
 * post-stream content). Streaming token bubbles should use
 * {@link renderMarkdownUncached} instead so per-token mutations don't
 * thrash the cache.
 */
export function renderMarkdown(text: string, agentId: number | null = null): string {
  if (!text) return ''
  const cacheKey = `${agentId}:${text}`
  const cached = markdownCache.get(cacheKey)
  if (cached) return cached

  const result = renderMarkdownUncached(text, agentId)
  if (markdownCache.size < MARKDOWN_CACHE_MAX) {
    markdownCache.set(cacheKey, result)
  }
  return result
}
