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

const renderer = new Renderer()
renderer.image = ({ href, text }: Tokens.Image) => mediaLink(href, `${text || 'Image'} (image)`)

const markdown = new Marked({ gfm: true, breaks: false, renderer })

/**
 * Swap each embed in {@code html} for a link to its source. Parsed into a {@code <template>}, whose
 * content is inert, so nothing loads while the swap happens.
 */
function linkEmbeds(html: string): string {
  const template = document.createElement('template')
  template.innerHTML = html
  for (const element of Array.from(template.content.querySelectorAll(EMBEDS.join(',')))) {
    const source = element.getAttribute('src') ?? element.getAttribute('data') ?? element.getAttribute('srcset')?.split(/\s/)[0]
    if (!source) {
      element.remove()
      continue
    }
    const link = document.createElement('a')
    link.setAttribute('href', source)
    link.textContent = `${element.getAttribute('alt') || element.getAttribute('title') || element.tagName.toLowerCase()} (${element.tagName.toLowerCase()})`
    element.replaceWith(link)
  }
  return template.innerHTML
}

export function renderScrapedMarkdown(text: string): string {
  if (!text) return ''
  const html = markdown.parse(text) as string
  return purifier.sanitize(linkEmbeds(html), {
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
