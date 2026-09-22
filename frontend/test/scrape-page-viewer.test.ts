import { describe, it, expect } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import ScrapePageViewer from '~/components/scrapes/ScrapePageViewer.vue'
import { renderScrapedMarkdown } from '~/utils/scrape-markdown'

/**
 * JCLAW-1273: a scraped page is shown without loading anything from the site it came from, and
 * without running anything it carries.
 */
describe('scraped page viewer', () => {
  it('turns Markdown images into links rather than loading them', () => {
    const html = renderScrapedMarkdown('Intro\n\n![Site logo](https://tracker.example.test/logo.png)')

    expect(html).not.toContain('<img')
    expect(html).toContain('href="https://tracker.example.test/logo.png"')
    expect(html).toContain('Site logo (image)')
  })

  it('turns raw HTML embeds into links and strips what could still fetch or run', () => {
    const html = renderScrapedMarkdown([
      '<p style="background:url(https://tracker.example.test/bg.png)">Styled</p>',
      '<img src="https://tracker.example.test/pixel.gif" alt="pixel">',
      '<iframe src="https://tracker.example.test/frame"></iframe>',
      '<video src="https://tracker.example.test/clip.mp4"></video>',
      '<picture><source srcset="https://tracker.example.test/a.webp 1x"></picture>',
      '<svg><image href="https://tracker.example.test/svg.png"/></svg>',
      '<style>body { background: url(https://tracker.example.test/css.png) }</style>',
      '<script>window.pwned = true</script>',
      '<a href="javascript:alert(1)">bad</a>',
    ].join('\n\n'))

    for (const tag of ['<img', '<iframe', '<video', '<source', '<picture', '<svg', '<image', '<style', '<script']) {
      expect(html, tag).not.toContain(tag)
    }
    expect(html).not.toContain('style=')
    expect(html).not.toContain('src=')
    expect(html).not.toContain('javascript:')
    expect(html).toContain('href="https://tracker.example.test/pixel.gif"')
    expect(html).toContain('href="https://tracker.example.test/frame"')
    expect(html).toContain('href="https://tracker.example.test/clip.mp4"')
  })

  it('keeps a linked image one named link rather than a link inside a link', () => {
    const fromMarkdown = renderScrapedMarkdown('[![Tipping the Velvet](https://x.test/cover.jpg)](https://x.test/book/1)')
    const fromHtml = renderScrapedMarkdown('<a href="https://x.test/book/2"><img src="https://x.test/c2.jpg" alt="Soumission"></a>')

    const holder = document.createElement('div')
    for (const html of [fromMarkdown, fromHtml]) {
      holder.innerHTML = html
      const links = [...holder.querySelectorAll('a')]
      expect(links, html).toHaveLength(1)
      expect(links[0]!.textContent?.trim()).toMatch(/\(image\)$/)
    }
    expect(fromMarkdown).toContain('href="https://x.test/book/1"')
    expect(fromMarkdown).toContain('Tipping the Velvet (image)')
    expect(fromHtml).toContain('Soumission (image)')
  })

  it('moves the page\'s headings beneath the viewer\'s own, one level at a time', () => {
    const html = renderScrapedMarkdown('# Title\n\n### Skipped a level\n\n### Sibling\n\n## Back up\n\n<h5>Raw</h5>')

    const levels = [...html.matchAll(/<h(\d)>/g)].map(m => Number(m[1]))
    expect(levels).toEqual([3, 4, 4, 4, 5])
    expect(html).toContain('<h3>Title</h3>')
    expect(html).toContain('<h5>Raw</h5>')
  })

  it('opens links in a new tab without handing the site a referrer or the opener', () => {
    const html = renderScrapedMarkdown('[Docs](https://docs.example.test/)')

    expect(html).toContain('target="_blank"')
    expect(html).toContain('rel="noopener noreferrer nofollow"')
  })

  it('shows plain text as text and JSON laid out', async () => {
    const text = await mountSuspended(ScrapePageViewer, {
      props: { page: { id: 1, url: 'https://x.test/', format: 'text', content: '<b>not bold</b>' } },
    })
    expect(text.find('[data-testid="scrape-page-text"]').text()).toBe('<b>not bold</b>')
    expect(text.find('b').exists()).toBe(false)

    const json = await mountSuspended(ScrapePageViewer, {
      props: { page: { id: 2, url: 'https://x.test/', format: 'json', content: '{"url":"https://x.test/","fields":{"price":["£1"]}}' } },
    })
    expect(json.find('[data-testid="scrape-page-json"]').text()).toContain('"fields": {\n    "price": [')
  })
})
