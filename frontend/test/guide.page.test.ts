import { describe, it, expect, afterEach, vi } from 'vitest'
import { mountSuspended, registerEndpoint } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import Guide from '~/pages/guide.vue'
import GuideRenderer from '~/components/guide/GuideRenderer.vue'
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
 * jsdom doesn't provide IntersectionObserver, so the page's guard skips
 * observing and the active highlight defaults to the first registered
 * section — the same cold-load behavior the operator sees.
 */

// The page itself calls no API, but the cases below navigate, and any
// navigation runs the global auth middleware — which probes /api/config and
// redirects to /login (dropping the fragment) when it cannot reach it. Merely
// registering the endpoint causes no request, so the "no API calls" case that
// mounts without navigating is unaffected.
registerEndpoint('/api/config', () => ({ entries: [] }))

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

  it('writes the section into the URL when a TOC entry is picked', async () => {
    const component = await mountSuspended(Guide, { route: '/guide' })
    await flushPromises()
    const router = useRouter()

    await component.find('[data-testid="guide-toc-item-prompts"]').trigger('click')

    // Without this the address bar stays at a bare /guide however far the
    // reader navigates, so there is nothing to copy or bookmark.
    await vi.waitFor(() => expect(router.currentRoute.value.hash).toBe('#prompts'))
  })

  it('clears the fragment when the reader jumps back to the top', async () => {
    const component = await mountSuspended(Guide, { route: '/guide#tasks' })
    await flushPromises()
    const router = useRouter()

    // The mascot in the right gutter is the back-to-top control; the image and
    // its hint share one clickable wrapper.
    await component.find('img[src="/clawdia-reading.webp"]').trigger('click')

    // A URL still naming a section is wrong to copy once the reader is at the
    // top of the guide.
    await vi.waitFor(() => expect(router.currentRoute.value.hash).toBe(''))
    expect(router.currentRoute.value.path).toBe('/guide')
  })

  it('highlights the owning section when the URL names a heading inside it', async () => {
    const component = await mountSuspended(Guide, { route: '/guide#subagents-async-yield' })
    await flushPromises()

    const active = component.findAll('[aria-current="true"]').map(el => el.attributes('data-testid'))
    expect(active).toEqual(['guide-toc-item-subagents'])
  })

  it('resolves a fragment to the longest matching section id, not the first', async () => {
    // `subagents-tasks-reminders` is prefixed by `subagents`, which sorts
    // earlier, so first-prefix-wins highlighted `subagents` instead. Asserted
    // on the highlight rather than the URL: nothing rewrites the fragment, so a
    // URL assertion would hold whether or not the resolution is right.
    const component = await mountSuspended(Guide, { route: '/guide#subagents-tasks-reminders' })
    await flushPromises()

    const active = component.findAll('[aria-current="true"]').map(el => el.attributes('data-testid'))
    expect(active).toEqual(['guide-toc-item-subagents-tasks-reminders'])
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

  it('fades the "Scroll to the top" hint in only when the reader has left the top', async () => {
    // The guide's scroll container is the layout <main> (overflow-auto in
    // default.vue). Component tests don't mount the layout, so provide a
    // <main> for the page's onMounted to bind its scroll listener to, then
    // drive its scrollTop to simulate the reader moving down and back up.
    const main = document.createElement('main')
    document.body.appendChild(main)
    try {
      const component = await mountSuspended(Guide)
      await flushPromises()

      // At the top: the mascot is present but the hint text is not. (The
      // button's aria-label is an attribute, so .text() excludes it — the
      // only way "Scroll to the top" lands in text content is the visible
      // hint span.)
      expect(component.text()).not.toContain('Scroll to the top')

      // Reader scrolls down → the hint fades in.
      main.scrollTop = 500
      main.dispatchEvent(new Event('scroll'))
      await flushPromises()
      expect(component.text()).toContain('Scroll to the top')

      // Returned to the top → the hint disappears.
      main.scrollTop = 0
      main.dispatchEvent(new Event('scroll'))
      await flushPromises()
      expect(component.text()).not.toContain('Scroll to the top')
    }
    finally {
      main.remove()
    }
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
// Every test mounts the whole Guide page with its bundled markdown — the slowest
// in the suite (~500ms). The pre-push hook runs them beside the backend suite,
// where one hit 5.9s against the 5s default. Headroom for contention, not a budget.
}, 20_000)

describe('User Guide tables', () => {
  const PROSE = 'How many tool rounds one turn may run before the agent has to stop and answer.'
  const render = (content: string) => mountSuspended(GuideRenderer, { props: { sectionId: 't', content } })
  const held = (component: Awaited<ReturnType<typeof render>>) =>
    component.findAll('th, td').map(cell => cell.find('.guide-cell-held').exists())

  it('holds the short columns of a table and leaves its prose column to wrap', async () => {
    const component = await render(`| Key | Default | Meaning |\n|---|---|---|\n| \`chat.maxToolRounds\` | \`10\` | ${PROSE} |\n`)

    expect(held(component)).toEqual([true, true, false, true, true, false])
    expect(component.find('.guide-table > table').exists()).toBe(true)
    expect(component.find('.guide-table').classes()).not.toContain('guide-table-two-prose')
  })

  it('lets a held config key break after a dot', async () => {
    const component = await render(`| Key | Meaning |\n|---|---|\n| \`chat.maxToolRounds\` | ${PROSE} \`chat.other\` |\n`)

    // Asserted after DOMPurify, which would drop a tag it does not allow.
    const [key, inProse] = component.findAll('td code').map(code => code.element.innerHTML)
    expect(key).toBe('chat.<wbr>maxToolRounds')
    expect(inProse).toBe('chat.other')
  })

  it('gives a table with two prose columns the larger reserve', async () => {
    const component = await render(`| Icon | Appears when | Effect |\n|---|---|---|\n| Run once | ${PROSE} | ${PROSE} More. |\n`)

    expect(held(component)).toEqual([true, false, false, true, false, false])
    expect(component.find('.guide-table').classes()).toContain('guide-table-two-prose')
  })

  it('holds nothing in a table with three prose columns', async () => {
    const component = await render(`| | A | B | C |\n|---|---|---|---|\n| Who decides | ${PROSE} | ${PROSE} | ${PROSE} More. |\n`)

    expect(component.find('.guide-cell-held').exists()).toBe(false)
  })

  it('holds every Field cell of the app manifest table', async () => {
    // The reported case: `version`, `creator` and `description` each broke across two lines.
    const component = await mountSuspended(Guide)
    await flushPromises()

    const rows = component.find('[data-section-id="apps"] table').findAll('tr')
    expect(rows.length).toBeGreaterThan(8)
    for (const row of rows) expect(row.find(':first-child > .guide-cell-held').exists()).toBe(true)
  })

  it('sizes a held cell to its content', async () => {
    // jsdom lays nothing out, so this pins the declaration that keeps the cell on one line.
    const rendererSrc = (await import('../components/guide/GuideRenderer.vue?raw')).default as string
    expect(rendererSrc).toMatch(/\.guide-cell-held\s*\{[^}]*width:\s*max-content/)
  })
})
