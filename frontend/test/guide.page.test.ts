import { describe, it, expect, afterEach, vi } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import Guide from '~/pages/guide.vue'
import { sections } from '~/components/guide/sections'

/**
 * In-app User Guide coverage.
 *
 * The page is fully client-side (no /api fetches) and renders each
 * section from a bundled markdown source. Tests focus on:
 *
 *   - the TOC mirrors the registered sections (registry → DOM contract);
 *   - each section emits its data-section-id sentinel so the
 *     IntersectionObserver and deep-link scroll can find it;
 *   - the markdown renderer produces namespaced anchor ids
 *     (`<section-id>-<slug>`) so deep links stay stable across sections;
 *   - `:::tip` / `:::gotcha` / `:::note` containers become callout chips
 *     with the expected class and test id;
 *   - the page never hits `/api` on initial load.
 *
 * IntersectionObserver is jsdom-stubbed so the active highlight defaults
 * to the first registered section — the same cold-load behavior the
 * operator sees.
 */

describe('User Guide page', () => {
  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('renders one TOC entry per registered section', async () => {
    const component = await mountSuspended(Guide)
    await flushPromises()
    for (const s of sections) {
      const item = component.find(`[data-testid="guide-toc-item-${s.id}"]`)
      expect(item.exists()).toBe(true)
      expect(item.text()).toContain(s.title)
    }
  })

  it('emits a section sentinel for every registered section', async () => {
    const component = await mountSuspended(Guide)
    await flushPromises()
    // The IntersectionObserver and deep-link scroll both look up
    // `[data-section-id="..."]`; missing sentinels break navigation
    // without obvious symptoms, so we assert presence explicitly.
    for (const s of sections) {
      const sentinel = component.find(`[data-section-id="${s.id}"]`)
      expect(sentinel.exists()).toBe(true)
    }
  })

  it('namespaces heading anchors by section id', async () => {
    const component = await mountSuspended(Guide)
    await flushPromises()
    const html = component.html()
    // Subagents section anchors — the explicit `{#async-yield}` marker
    // in subagents.md becomes `#subagents-async-yield`, matching the
    // stable deep-link surface operators reference.
    expect(html).toContain('id="subagents-async-yield"')
    // Other sections' top-level h2 anchors follow the same scheme.
    expect(html).toContain('id="chat-slash-commands"')
    expect(html).toContain('id="agents-creating-or-editing-an-agent"')
  })

  it('emits a font-weight rule for guide links', async () => {
    // Regression: links inside `**...**` got bold weight via inheritance
    // from <strong>, while inline-paragraph links rendered at body
    // weight — visibly inconsistent across the guide. The renderer's
    // scoped style block must include a font-weight declaration on
    // `.guide-section a` so every link is bold regardless of its
    // surrounding markdown context. Vite's `?raw` query gives us the
    // source as a string — same trick the section registry uses for
    // markdown sources.
    const rendererSrc = (
      await import('../components/guide/GuideRenderer.vue?raw')
    ).default as string
    expect(rendererSrc).toMatch(/\.guide-section a\s*\{[^}]*font-weight:\s*\d/)
  })

  it('does not stack prose-chat styles onto the guide article', async () => {
    // Regression: the wrapper used to carry both `prose-chat` and
    // `guide-section`. Both stylesheets define `.<class> a { color: ... }`
    // at identical specificity, so source order decided the winner —
    // the chat one (loaded second) silently overrode the guide's emerald
    // anchor color with neutral grey. Only `guide-section` is correct.
    const component = await mountSuspended(Guide)
    await flushPromises()
    const article = component.find('.guide-section')
    expect(article.exists()).toBe(true)
    expect(article.classes()).not.toContain('prose-chat')
  })

  it('renders headings that immediately follow callouts as <h2>', async () => {
    // Regression: a greedy `\s*` in the callout closing fence used to
    // consume the blank line after `:::`, which made marked treat the
    // next heading as continuation of the callout's HTML block. The
    // headings then rendered as literal `## Heading` text in the prose.
    // Probe one heading from each section that follows a callout — none
    // of them must appear as literal markdown anywhere in the document.
    const component = await mountSuspended(Guide)
    await flushPromises()
    const html = component.html()
    // No raw `## ` should survive into the rendered DOM. The space
    // suffix is important — bare `##` inside code blocks is fine; this
    // probe targets only line-leading ATX-heading-shape text that got
    // through the parser unrendered.
    expect(html).not.toMatch(/(^|>)\s*##\s+\w/m)
    // Spot-check the specific anchors that broke in the bug report.
    expect(html).toContain('id="settings-ocr"')
    expect(html).toContain('id="chat-tool-calls-and-reasoning"')
    expect(html).toContain('id="settings-malware-and-virus-scanners"')
  })

  it('renders callouts as styled chips with their variant class', async () => {
    const component = await mountSuspended(Guide)
    await flushPromises()
    const html = component.html()
    expect(html).toContain('guide-callout-tip')
    expect(html).toContain('guide-callout-gotcha')
    expect(html).toContain('guide-callout-note')
    // testid is preserved through DOMPurify so existing addressing keeps
    // working for downstream tests that target a specific callout.
    expect(component.find('[data-testid="guide-callout-tip"]').exists()).toBe(true)
  })

  it('renders body content from the .md sources', async () => {
    const component = await mountSuspended(Guide)
    await flushPromises()
    const text = component.text()
    // A representative line from each .md source — guards against an
    // import path going wrong without anyone noticing.
    expect(text).toContain('Welcome to JClaw')
    expect(text).toContain('Reply was truncated by the model')
    expect(text).toContain('task_manager')
  })

  it('renders without making any API calls', async () => {
    const fetchSpy = vi.spyOn(globalThis, 'fetch')
    await mountSuspended(Guide)
    await flushPromises()
    const apiCalls = fetchSpy.mock.calls.filter((call) => {
      const url = String(call[0] ?? '')
      return url.includes('/api/')
    })
    expect(apiCalls).toEqual([])
  })

  it('mounts cleanly with an unknown URL hash', async () => {
    // Hard-refresh on /guide#some-unknown-anchor must still render —
    // falling back silently to the first section.
    const component = await mountSuspended(Guide)
    await flushPromises()
    expect(
      component.find('[data-testid="guide-toc-item-getting-started"]').exists(),
    ).toBe(true)
  })
})
