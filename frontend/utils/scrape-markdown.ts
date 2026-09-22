import { Marked, Renderer, type Tokens } from 'marked'
import DOMPurify from 'dompurify'

/**
 * Renders a scraped page for the Scrapes viewer (JCLAW-1273) without loading anything from the site
 * it came from: opening a page in the viewer must not contact that site from the operator's
 * browser, and the operator's browser does not go through the scrape proxy.
 *
 * Images and embedded media become links, in Markdown and in raw HTML alike, and what could still
 * fetch — styles, frames, SVG, forms — is stripped. Its own Marked and DOMPurify instances: the
 * chat's DOMPurify keeps {@code /api/} image sources on purpose, which a scraped page must not get.
 */

const EMBEDS = ['img', 'picture', 'video', 'audio', 'source', 'track', 'iframe', 'frame', 'embed', 'object']

const purifier = DOMPurify(window)
purifier.addHook('afterSanitizeAttributes', (node) => {
  if (node.tagName === 'A') {
    node.setAttribute('target', '_blank')
    node.setAttribute('rel', 'noopener noreferrer nofollow')
  }
})

function escapeHtml(text: string): string {
  return text.replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;').replaceAll('"', '&quot;')
}

function mediaLink(href: string, label: string): string {
  return `<a href="${escapeHtml(href)}">${escapeHtml(label)}</a>`
}

function imageLabel(text: string): string {
  return `${text || 'Image'} (image)`
}

const renderer = new Renderer()
renderer.image = ({ href, text }: Tokens.Image) => mediaLink(href, imageLabel(text))
// A linked image cannot be a link of its own: the parser splits nested anchors and leaves the outer one nameless.
renderer.link = function (this: Renderer, { href, title, tokens }: Tokens.Link) {
  const inner = tokens.map(token => token.type === 'image'
    ? escapeHtml(imageLabel((token as Tokens.Image).text))
    : this.parser.parseInline([token])).join('')
  return `<a href="${escapeHtml(href)}"${title ? ` title="${escapeHtml(title)}"` : ''}>${inner}</a>`
}

const markdown = new Marked({ gfm: true, breaks: false, renderer })

/**
 * Swap each embed in {@code html} for a link to its source, and re-level the page's headings so they
 * start beneath the viewer's own h2. Parsed into a {@code <template>}, whose content is inert, so
 * nothing loads while this happens.
 */
function tame(html: string): string {
  const template = document.createElement('template')
  template.innerHTML = html
  for (const element of Array.from(template.content.querySelectorAll(EMBEDS.join(',')))) {
    const tag = element.tagName.toLowerCase()
    const kind = tag === 'img' ? 'image' : tag
    const label = `${element.getAttribute('alt') || element.getAttribute('title') || kind} (${kind})`
    const source = element.getAttribute('src') ?? element.getAttribute('data') ?? element.getAttribute('srcset')?.split(/\s/)[0]
    if (element.closest('a')) {
      // Inside a link already: its own link would nest, and leave the outer one without a name.
      element.replaceWith(document.createTextNode(label))
      continue
    }
    if (!source) {
      element.remove()
      continue
    }
    const link = document.createElement('a')
    link.setAttribute('href', source)
    link.textContent = label
    element.replaceWith(link)
  }
  // Re-levelled as an outline, so a site that jumps from h1 to h3 does not carry the skip across.
  const outline: Array<{ from: number, to: number }> = []
  for (const heading of Array.from(template.content.querySelectorAll('h1, h2, h3, h4, h5, h6'))) {
    const from = Number(heading.tagName[1])
    while (outline.length && outline.at(-1)!.from >= from) outline.pop()
    const to = Math.min(6, (outline.at(-1)?.to ?? 2) + 1)
    outline.push({ from, to })
    const relevelled = document.createElement(`h${to}`)
    relevelled.append(...heading.childNodes)
    heading.replaceWith(relevelled)
  }
  return template.innerHTML
}

export function renderScrapedMarkdown(text: string): string {
  if (!text) return ''
  const html = markdown.parse(text) as string
  return purifier.sanitize(tame(html), {
    USE_PROFILES: { html: true },
    FORBID_TAGS: [...EMBEDS, 'link', 'style', 'meta', 'base', 'form', 'input', 'button', 'textarea', 'select'],
    FORBID_ATTR: ['style', 'src', 'srcset', 'poster', 'background', 'ping', 'action', 'formaction'],
  })
}

/** A JSON page record laid out for reading; the text as it came when it does not parse. */
export function prettyJson(text: string): string {
  try {
    return JSON.stringify(JSON.parse(text), null, 2)
  }
  catch {
    return text
  }
}
