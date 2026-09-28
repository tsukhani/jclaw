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
import { marked, Marked, Renderer, type Tokens, type TokenizerAndRendererExtension } from 'marked'
import DOMPurify from 'dompurify'
import katex, { type KatexOptions } from 'katex'
// mhchem adds \ce / \pu, so chemical formulas, equations and units typeset through the same KaTeX path as math.
import 'katex/contrib/mhchem'
import { ensureLewisLoaded, isLewisLoaded, lewisVersion, renderLewis } from '~/utils/lewis'
import { parseStructureFence } from '~/utils/structure/fence'
import { rewriteWorkspaceLinks } from '~/utils/markdown-links'
import type { MessageUsage } from '~/utils/usage-cost'

// Configure marked for safe rendering
marked.setOptions({
  breaks: true,
  gfm: true,
})

// Fenced code blocks get a copy button, emitted as part of the markdown output
// rather than injected after mount: every consumer renders through v-html, and
// the streaming bubble re-renders at ~12.5 fps, which a post-render DOM walk
// would race. Click handling is delegated in plugins/code-copy.client.ts —
// DOMPurify strips inline handlers, so the markup cannot carry its own.
//
// The wrapper div exists to be the positioning context: `pre` is
// overflow-x:auto, and an absolute button inside a scrolling box slides out of
// view with the content. A per-call renderer (not marked.setOptions) keeps the
// button off pages/skills/[[name]].vue, which parses through the same shared instance.
const chatRenderer = new Renderer()
const renderCodeBlock = chatRenderer.code.bind(chatRenderer)
const codeBlock = (token: Tokens.Code) =>
  `<div class="code-block">`
  + `<button type="button" class="code-copy">Copy</button>`
  + renderCodeBlock(token)
  + `</div>`

// A fence still streaming runs to the end of the text, so its raw has no closing line of its own.
function fenceIsClosed(raw: string): boolean {
  const lines = raw.trimEnd().split('\n')
  const open = /^ {0,3}(`{3,}|~{3,})/.exec(lines[0] ?? '')?.[1]
  const close = lines.length > 1 ? /^ {0,3}(`{3,}|~{3,})[ \t]*$/.exec(lines.at(-1)!)?.[1] : undefined
  return !!open && !!close && close.startsWith(open)
}

function escapeHtml(text: string): string {
  return text.replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;')
}

// Until SmilesDrawer has loaded, a lewis fence stays a code block; the load bumps lewisVersion, which re-renders it.
function lewisBlock(token: Tokens.Code): string {
  if (!isLewisLoaded()) {
    void ensureLewisLoaded()
    return codeBlock(token)
  }
  // OpenSMILES ends a SMILES at whitespace, so a trailing name such as "[H]O[H] water" is not parsed.
  const result = renderLewis(token.text.trim().split(/\s/, 1)[0]!)
  return 'svg' in result
    ? `<figure class="lewis">${result.svg}</figure>`
    : `${codeBlock(token)}<p class="lewis-error">${escapeHtml(result.error)}</p>`
}

// Set while the streaming bubble parses: its innerHTML is replaced on every token, which would tear down a live viewer.
let parsingStream = false

// The figure keeps the code block: utils/structure/directive.ts reads the structure from it and hides it once a viewer mounts.
function structureBlock(token: Tokens.Code): string {
  const spec = parseStructureFence(token.lang ?? '', token.text)
  if ('error' in spec) return `${codeBlock(token)}<p class="structure-error">${escapeHtml(spec.error)}</p>`
  const caption = spec.caption ? `<figcaption>${escapeHtml(spec.caption)}</figcaption>` : ''
  return parsingStream
    ? `<figure class="structure-view structure-pending">${codeBlock(token)}${caption}<p class="structure-note">The 3D view appears when the reply is complete.</p></figure>`
    : `<figure class="structure-view" data-info="${escapeHtml(token.lang ?? '').replaceAll('"', '&quot;')}">${codeBlock(token)}${caption}</figure>`
}

// Marked keeps the whole info string in `lang`, so ```Lewis water still names the lewis fence.
chatRenderer.code = (token: Tokens.Code) => {
  const hint = (token.lang ?? '').split(/\s/, 1)[0]!.toLowerCase()
  if (!fenceIsClosed(token.raw)) return codeBlock(token)
  if (hint === 'lewis') return lewisBlock(token)
  if (hint === 'structure') return structureBlock(token)
  return codeBlock(token)
}

// Its own instance keeps math off the skills page and the guide. Dollar math follows Pandoc's rule — the
// opening $ is followed by a non-space, the closing one follows a non-space and precedes no digit — so
// "$5 and $10" stays prose and "(none can be $2$)" still closes. `\(…\)`/`\[…\]` need a rule of their
// own because Marked reads them as escaped brackets.
const KATEX_OPTIONS: KatexOptions = { throwOnError: false, strict: 'ignore' }

// Each rule is anchored and captures the TeX in group 1; the first that matches wins, as in an alternation.
function mathExtension(name: string, level: 'block' | 'inline', rules: RegExp[],
  startAt: RegExp): TokenizerAndRendererExtension {
  return {
    name,
    level,
    start(src) {
      const i = src.search(startAt)
      return i < 0 ? undefined : i
    },
    tokenizer(src) {
      for (const rule of rules) {
        const m = rule.exec(src)
        if (m?.[1]) {
          return { type: name, raw: m[0], text: m[1].trim(), displayMode: level === 'block' || m[0].startsWith('$$') || m[0].startsWith(String.raw`\[`) }
        }
      }
      return undefined
    },
    renderer(token) {
      return katex.renderToString(token.text as string, { ...KATEX_OPTIONS, displayMode: token.displayMode as boolean })
    },
  }
}

const chatMarked = new Marked({ breaks: true, gfm: true }, {
  extensions: [
    mathExtension('mathBlock', 'block', [/^\$\$([\s\S]+?)\$\$[ \t]*(?:\n+|$)/], /^\$\$/m),
    mathExtension('mathInline', 'inline', [
      /^\$\$((?:\\.|[^\\$])+?)\$\$/,
      /^\$(?!\s)((?:\\.|[^\\\n$])+?)(?<!\s)\$(?!\d)/,
      /^\\\(([\s\S]+?)\\\)/,
      /^\\\[([\s\S]+?)\\\]/,
    ], /\$|\\[([]/),
  ],
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

// A link destination is either angle-bracketed — which may hold spaces and
// parentheses — or bare, where CommonMark still permits balanced parens. Both
// shapes must be matched whole: a pattern that stopped at the first `)` truncated
// `[x](<Book (123)/x.epub>)` mid-destination and then re-wrapped the fragment,
// turning a valid link into literal text (JCLAW-1098).
const MARKDOWN_LINK = /\[([^\]\n]+)\]\((<[^<>\n]*>|[^()\n]*(?:\([^()\n]*\)[^()\n]*)*)\)/g

// Marked (CommonMark) only allows whitespace in link destinations when they
// are wrapped in angle brackets. LLMs routinely emit bare filenames with
// spaces like `[file.docx](file.docx)`, which silently fall through as plain
// text. Wrap such destinations in <...> so they parse into real anchors.
export function normalizeMarkdownLinks(text: string): string {
  return text.replaceAll(MARKDOWN_LINK, (match, label, dest) => {
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
let cachedLewisVersion = 0

function renderMarkdownInner(text: string, agentId: number | null): string {
  const html = chatMarked.parse(normalizeMarkdownLinks(text), { renderer: chatRenderer }) as string
  const sanitized = DOMPurify.sanitize(html, {
    // semantics/annotation: KaTeX's MathML carries its TeX there; stripped, the TeX leaks into what a screen reader reads.
    ADD_TAGS: ['img', 'audio', 'video', 'source', 'semantics', 'annotation'],
    ADD_ATTR: ['src', 'controls', 'autoplay', 'download', 'target', 'encoding'],
  })
  return agentId == null ? sanitized : rewriteWorkspaceLinks(sanitized, agentId)
}

export function renderMarkdown(text: string, agentId: number | null = null): string {
  if (!text) return ''
  // Read reactively, so a caller's render re-runs once SmilesDrawer loads and its lewis fences can draw.
  const version = lewisVersion.value
  // The cache stops adding at its bound rather than evicting, so entries from before the load would hold slots forever.
  if (version !== cachedLewisVersion) {
    markdownCache.clear()
    cachedLewisVersion = version
  }
  const cacheKey = `${version}:${agentId}:${text}`
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
  parsingStream = true
  try {
    return renderMarkdownInner(text, agentId)
  }
  finally {
    parsingStream = false
  }
}
