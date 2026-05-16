<script setup lang="ts">
/**
 * Renders one User Guide section from a raw markdown string.
 *
 * Markdown is the single source of truth — files live under
 * `docs/user-guide/*.md` and are imported into the SPA via Vite's `?raw`
 * query (see `sections.ts`). Operator-facing copy is edited in those .md
 * files; this component handles only presentation.
 *
 * Transformations applied to the source:
 *
 *   - `:::tip [Title] / body / :::`  →  emerald callout block.
 *   - `:::gotcha [Title] / body / :::` → amber callout block.
 *   - `:::note [Title] / body / :::`  →  neutral callout block.
 *   - `## Heading {#explicit-id}`     →  anchor id `<sectionId>-explicit-id`.
 *   - Other h2/h3/h4 headings         →  anchor id `<sectionId>-<slug>`
 *                                         derived from heading text.
 *   - Internal links (href starting `/`) — intercepted on click and
 *     pushed through the Nuxt router so we stay an SPA.
 *
 * Per-section anchor namespacing keeps deep links stable across sections:
 * an `h2 "Tips"` in subagents.md generates `#subagents-tips`, distinct
 * from an `h2 "Tips"` in any other section.
 */
import { computed } from 'vue'
import { marked, Renderer, type Tokens } from 'marked'
import DOMPurify from 'dompurify'
import { useRouter } from '#imports'

interface Props {
  /** Stable section id used as the prefix for every heading anchor in this body. */
  sectionId: string
  /** Raw markdown content (typically a `?raw` import of a .md file). */
  content: string
}

const props = defineProps<Props>()
const router = useRouter()

/** Slugify a heading title into a URL-fragment-safe id. */
function slugify(input: string): string {
  return input
    .toLowerCase()
    // Strip backticks; we want `mode=session` → `mode-session`, not `mode=session-`.
    .replace(/`/g, '')
    .replace(/[^\w\s-]/g, ' ')
    .trim()
    .replace(/\s+/g, '-')
    .replace(/-+/g, '-')
    .replace(/^-|-$/g, '')
}

/**
 * Translate `:::variant [Title]\nbody\n:::` fences into the equivalent
 * HTML before marked sees the source. Done as a raw string substitution
 * (not a marked extension) because the embedded body still needs to be
 * marked-parsed — letting marked see the inner-block markdown means
 * lists/code/links inside a callout work without extra plumbing.
 *
 * The opening fence accepts an optional title:
 *   `:::tip`             → defaults to "Tip"
 *   `:::tip Truncation`  → uses "Truncation"
 */
const VARIANT_LABEL: Record<string, string> = {
  tip: 'Tip',
  gotcha: 'Heads up',
  note: 'Note',
}

function transformCallouts(md: string): string {
  // Closing-fence whitespace is intentionally `[ \t]*` (horizontal only),
  // not `\s*`. `\s` matches `\n`, which means a greedy `\s*` here would
  // consume the blank line that separates the callout from whatever
  // follows. Without that blank line marked treats the next heading as
  // continuation of the callout's HTML block — rendering "## OCR" as
  // literal text instead of an <h2>. Keeping horizontal-only whitespace
  // preserves the original blank line in the surrounding source.
  return md.replace(
    /^:::(tip|gotcha|note)([^\n]*)\n([\s\S]*?)^:::[ \t]*$/gm,
    (_match, variant: 'tip' | 'gotcha' | 'note', titleArg: string, body: string) => {
      const title = titleArg.trim() || VARIANT_LABEL[variant] || 'Note'
      // Pre-render the body's markdown to HTML so the surrounding aside
      // can be emitted as a single line. A multi-line aside with internal
      // blank lines triggers CommonMark's HTML-block termination rule
      // (type 6: ends at the first blank line), which makes the body
      // bleed back into markdown parsing and breaks the next heading.
      // Inlining keeps the entire callout as one opaque HTML block.
      const bodyHtml = marked.parse(body.trim(), { gfm: true, breaks: false }) as string
      return (
        `<aside class="guide-callout guide-callout-${variant}" data-testid="guide-callout-${variant}">`
        + `<div class="guide-callout-title">${escapeHtml(title)}</div>`
        + `<div class="guide-callout-body">${bodyHtml}</div>`
        + `</aside>`
      )
    },
  )
}

function escapeHtml(s: string): string {
  return s
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
}

/**
 * Marked heading renderer override:
 *   - Strips a trailing `{#explicit-id}` from the heading text and uses
 *     it (namespaced) as the anchor id.
 *   - Otherwise auto-slugs the heading text, also namespaced.
 *
 * Only h2/h3/h4 get anchors — h1 is the section title and lives on the
 * outer `<section data-section-id="...">` wrapper in pages/guide.vue.
 */
function buildRenderer(sectionId: string) {
  const renderer = new Renderer()
  // Track ids we've emitted in this body so repeated headings get -2, -3
  // suffixes rather than colliding.
  const seen = new Map<string, number>()

  renderer.heading = function ({ tokens, depth, text }: Tokens.Heading) {
    // Pull an explicit `{#id}` out of the raw heading source if present.
    // We accept and strip it from the displayed text in both the slug
    // derivation and the rendered inline output.
    const explicitMatch = text.match(/\s*\{#([\w-]+)\}\s*$/)
    const explicit = explicitMatch ? explicitMatch[1] : null
    const displayText = explicit
      ? text.slice(0, text.length - explicitMatch![0].length).trimEnd()
      : text

    // Render inline children. parseInline handles `code`, **bold**, etc.
    const inner = explicit
      ? marked.parseInline(displayText)
      : this.parser.parseInline(tokens)

    let slug = explicit ?? slugify(displayText)
    if (!slug) slug = `heading-${depth}`
    const namespaced = `${sectionId}-${slug}`
    const dedup = seen.get(namespaced) ?? 0
    seen.set(namespaced, dedup + 1)
    const finalId = dedup === 0 ? namespaced : `${namespaced}-${dedup + 1}`

    return `<h${depth} id="${finalId}">${inner}</h${depth}>\n`
  }
  return renderer
}

const renderedHtml = computed(() => {
  if (!props.content) return ''
  const preprocessed = transformCallouts(props.content)
  const renderer = buildRenderer(props.sectionId)
  const html = marked.parse(preprocessed, {
    gfm: true,
    breaks: false, // GFM breaks make markdown line-wraps confusing here
    renderer,
  }) as string
  // DOMPurify keeps the callout aside + class attrs but strips scripts /
  // event handlers / javascript: URLs. Allow our anchor scheme on headings.
  return DOMPurify.sanitize(html, {
    ADD_TAGS: ['aside'],
    ADD_ATTR: ['id', 'data-testid'],
  })
})

/**
 * Intercept clicks on internal-app links so they navigate via the Nuxt
 * router rather than a full page reload. External links and in-page
 * fragment links fall through untouched.
 */
function onClick(event: MouseEvent) {
  const target = event.target as HTMLElement | null
  if (!target) return
  const anchor = target.closest('a')
  if (!anchor) return
  const href = anchor.getAttribute('href')
  if (!href) return
  // Modifier-click / middle-click / new-tab intent — let the browser handle it.
  if (event.metaKey || event.ctrlKey || event.shiftKey || event.altKey || event.button !== 0) return
  // In-page fragment: let the browser scroll naturally.
  if (href.startsWith('#')) return
  // External / protocol-prefixed: let the browser navigate.
  if (/^(https?:|mailto:|tel:)/i.test(href)) return
  // Internal — keep it SPA.
  if (href.startsWith('/')) {
    event.preventDefault()
    router.push(href)
  }
}
</script>

<template>
  <!-- Click handler intercepts internal links to keep navigation SPA;
       there is no keyboard equivalent because the same anchors can be
       reached via the page's TOC and the browser's native focus + Enter
       on the anchor element itself (focus is preserved on the <a>).
       eslint-disable-next-line for v-html is intentional: renderedHtml
       is DOMPurify-sanitized in the script above. -->
  <!-- eslint-disable vue/no-v-html, vuejs-accessibility/click-events-have-key-events, vuejs-accessibility/no-static-element-interactions -->
  <article
    class="text-fg-primary guide-section"
    @click="onClick"
    v-html="renderedHtml"
  />
  <!-- eslint-enable vue/no-v-html, vuejs-accessibility/click-events-have-key-events, vuejs-accessibility/no-static-element-interactions -->
</template>

<style>
/*
 * Typographic + callout styles for User Guide markdown output.
 *
 * Plain CSS (not @apply) because the project's scoped-style flow in
 * Tailwind 4 can't see utility-class macros from inside a scoped block.
 * Mirrors the convention pages/chat.vue uses for `.prose-chat` — write
 * the rules against the design tokens (CSS variables) directly. The
 * `.guide-section` class namespace keeps these from leaking onto any
 * other markdown output (the chat surface keeps its own prose-chat).
 */
.guide-section { overflow-wrap: anywhere; }

.guide-section h1 {
  font-size: 1.25rem;
  font-weight: 600;
  color: var(--fg-strong);
  margin: 0 0 0.5rem;
}

.guide-section h2 {
  font-size: 1rem;
  font-weight: 600;
  color: var(--fg-strong);
  margin: 2rem 0 0.5rem;
}

.guide-section h3 {
  font-size: 0.875rem;
  font-weight: 600;
  color: var(--fg-strong);
  margin: 1.5rem 0 0.5rem;
}

.guide-section h4 {
  font-size: 0.875rem;
  font-weight: 500;
  color: var(--fg-strong);
  margin: 1rem 0 0.25rem;
}

.guide-section p {
  font-size: 0.875rem;
  line-height: 1.6;
  margin: 0.75rem 0;
}

.guide-section ul, .guide-section ol {
  font-size: 0.875rem;
  margin: 0.75rem 0;
  padding-left: 1.5rem;
}
.guide-section ul { list-style: disc; }
.guide-section ol { list-style: decimal; }
.guide-section li { line-height: 1.6; margin: 0.25rem 0; }

/* Bold every link so inline-paragraph references read with the same
   weight as ones nested inside `**...**` emphasis (e.g. numbered list
   items in getting-started.md). Without this, a link's visual weight
   depended on whether its surrounding sentence was bolded — visibly
   inconsistent across the guide. */
.guide-section a {
  color: hsl(160 84% 30%);
  font-weight: 700;
  text-decoration: none;
}
.guide-section a:hover { text-decoration: underline; }
html.dark .guide-section a { color: hsl(152 76% 60%); }

.guide-section code {
  font-family: ui-monospace, monospace;
  font-size: 0.8em;
  padding: 0.15em 0.4em;
  border-radius: 0.25rem;
  background: hsl(0 0% 90% / 60%);
  color: var(--fg-strong);
}
html.dark .guide-section code { background: hsl(0 0% 18% / 60%); }

.guide-section pre {
  font-size: 0.8rem;
  margin: 0.75rem 0;
  padding: 0.75rem;
  border-radius: 0.4rem;
  background: hsl(0 0% 92% / 60%);
  overflow-x: auto;
}
html.dark .guide-section pre { background: hsl(0 0% 16% / 60%); }
.guide-section pre code { padding: 0; background: transparent; }

.guide-section blockquote {
  margin: 0.75rem 0;
  padding: 0.5rem 0.75rem;
  border-left: 2px solid hsl(160 84% 60% / 60%);
  background: hsl(152 76% 95% / 40%);
  font-style: italic;
  color: var(--fg-strong);
  font-size: 0.875rem;
}

html.dark .guide-section blockquote {
  background: hsl(152 76% 14% / 25%);
}

.guide-section table {
  width: 100%;
  font-size: 0.875rem;
  margin: 1rem 0;
  border-collapse: collapse;
  table-layout: auto;
}
.guide-section thead tr { border-bottom: 1px solid var(--border); }
.guide-section tbody tr { border-bottom: 1px solid hsl(0 0% 80% / 40%); }
.guide-section tbody tr:last-child { border-bottom: 0; }

.guide-section th {
  text-align: left;
  padding: 0.5rem 0.75rem 0.5rem 0;
  font-weight: 500;
  color: var(--fg-strong);
}

.guide-section td {
  padding: 0.5rem 0.75rem 0.5rem 0;
  vertical-align: top;
}

/* Override the wrapper's `overflow-wrap: anywhere` for table cells.
   `anywhere` lets the auto-layout algorithm shrink columns below the
   longest-word width, breaking short labels ("Admin", "Ops") into
   "Admi/n" / "Op/s" inside dense tables. `break-word` keeps long-URL
   wrapping (content still wraps when it would overflow) but pins the
   min-content size to the longest word, so cells size to the labels
   they actually carry. */
.guide-section th,
.guide-section td {
  overflow-wrap: break-word;
}

.guide-section kbd {
  font-family: ui-monospace, monospace;
  font-size: 0.65rem;
  padding: 0.1rem 0.35rem;
  border-radius: 0.25rem;
  border: 1px solid var(--border);
  background: var(--surface-elevated, hsl(0 0% 98%));
}

/* Callout chips. Three variants matching the chat-side palette. */
.guide-section .guide-callout {
  margin: 1rem 0;
  padding: 0.6rem 0.75rem;
  border-radius: 0.5rem;
  border: 1px solid;
}

.guide-section .guide-callout-tip {
  border-color: hsl(152 76% 80%);
  background: hsl(152 76% 95% / 50%);
}

.guide-section .guide-callout-gotcha {
  border-color: hsl(40 96% 78%);
  background: hsl(40 96% 95% / 50%);
}

.guide-section .guide-callout-note {
  border-color: hsl(0 0% 80%);
  background: var(--surface-elevated, hsl(0 0% 98%));
}

html.dark .guide-section .guide-callout-tip {
  border-color: hsl(152 76% 25% / 50%);
  background: hsl(152 76% 14% / 20%);
}

html.dark .guide-section .guide-callout-gotcha {
  border-color: hsl(40 50% 25% / 50%);
  background: hsl(40 50% 14% / 20%);
}

html.dark .guide-section .guide-callout-note {
  border-color: hsl(0 0% 25%);
  background: var(--surface-elevated, hsl(0 0% 14%));
}

.guide-section .guide-callout-title {
  font-size: 0.7rem;
  font-weight: 500;
  text-transform: uppercase;
  letter-spacing: 0.05em;
  margin-bottom: 0.25rem;
}
.guide-section .guide-callout-tip .guide-callout-title { color: hsl(160 84% 30%); }
.guide-section .guide-callout-gotcha .guide-callout-title { color: hsl(35 85% 35%); }
.guide-section .guide-callout-note .guide-callout-title { color: var(--fg-muted); }
html.dark .guide-section .guide-callout-tip .guide-callout-title { color: hsl(152 76% 60%); }
html.dark .guide-section .guide-callout-gotcha .guide-callout-title { color: hsl(40 96% 65%); }

.guide-section .guide-callout-body {
  font-size: 0.875rem;
  color: var(--fg-strong);
}
.guide-section .guide-callout-body > :first-child { margin-top: 0; }
.guide-section .guide-callout-body > :last-child { margin-bottom: 0; }
</style>
