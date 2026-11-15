/**
 * v-youtube-embeds: puts a player under each YouTube video a v-html container links, so the video plays in place.
 * A player starts as the video's thumbnail and a play button, and YouTube's iframe loads only on the click: a
 * transcript holding many links loads no player until one is played.
 */
import type { Directive, DirectiveBinding } from 'vue'

export interface YoutubeVideo { id: string, start: number, short: boolean }

const VIDEO_ID = /^[\w-]{11}$/
const YOUTUBE_HOSTS = new Set([
  'youtube.com', 'www.youtube.com', 'm.youtube.com', 'music.youtube.com', 'youtube-nocookie.com', 'www.youtube-nocookie.com',
])
const ID_IN_PATH = new Set(['embed', 'live', 'shorts', 'v'])
const START_TIME = /^(?:(\d+)h)?(?:(\d+)m)?(?:(\d+)s?)?$/

function startSeconds(url: URL): number {
  const raw = url.searchParams.get('t') ?? url.searchParams.get('start') ?? new URLSearchParams(url.hash.slice(1)).get('t')
  const m = raw ? START_TIME.exec(raw) : null
  if (!m) return 0
  return Number(m[1] ?? 0) * 3600 + Number(m[2] ?? 0) * 60 + Number(m[3] ?? 0)
}

/** The video a link plays, or null for anything else: a channel, a playlist, another host. */
export function parseYoutubeLink(href: string): YoutubeVideo | null {
  let url: URL
  try {
    url = new URL(href)
  }
  catch {
    return null
  }
  if (url.protocol !== 'https:' && url.protocol !== 'http:') return null
  const [route, second] = url.pathname.split('/').filter(Boolean)
  let id: string | null | undefined
  if (url.hostname === 'youtu.be') id = route
  else if (!YOUTUBE_HOSTS.has(url.hostname)) return null
  else if (route === 'watch') id = url.searchParams.get('v')
  else if (route && ID_IN_PATH.has(route)) id = second
  // embed/videoseries is a playlist route, and the word happens to be 11 characters long.
  if (!id || !VIDEO_ID.test(id) || id === 'videoseries') return null
  return { id, start: startSeconds(url), short: route === 'shorts' }
}

// The privacy-enhanced host: YouTube documents that a view there does not influence the viewer's browsing on YouTube.
export function youtubeEmbedSrc(video: YoutubeVideo): string {
  const params = new URLSearchParams({ autoplay: '1', playsinline: '1', rel: '0' })
  if (video.start > 0) params.set('start', String(video.start))
  return `https://www.youtube-nocookie.com/embed/${video.id}?${params}`
}

export function youtubeThumbnailSrc(video: YoutubeVideo): string {
  return `https://i.ytimg.com/vi/${video.id}/hqdefault.jpg`
}

// A bare link's text is its URL, which names nothing.
function linkTitle(anchor: HTMLAnchorElement): string {
  const text = anchor.textContent?.trim() ?? ''
  return /^(?:https?:\/\/|www\.)/i.test(text) ? '' : text
}

function play(box: HTMLElement, video: YoutubeVideo, label: string) {
  const frame = document.createElement('iframe')
  frame.setAttribute('src', youtubeEmbedSrc(video))
  frame.setAttribute('title', label)
  frame.setAttribute('allow', 'accelerometer; autoplay; clipboard-write; encrypted-media; gyroscope; picture-in-picture; web-share')
  frame.setAttribute('allowfullscreen', '')
  // YouTube's player refuses to play (error 153) when the request carries no Referer naming the embedding site.
  frame.setAttribute('referrerpolicy', 'strict-origin-when-cross-origin')
  box.replaceChildren(frame)
  frame.focus()
}

function player(video: YoutubeVideo, title: string): HTMLElement {
  const label = title ? `YouTube video: ${title}` : 'YouTube video'
  const box = document.createElement('div')
  box.className = video.short ? 'youtube-embed youtube-short' : 'youtube-embed'
  box.dataset.videoId = video.id
  const button = document.createElement('button')
  button.type = 'button'
  button.className = 'youtube-play'
  button.setAttribute('aria-label', `Play ${label}`)
  const thumbnail = document.createElement('img')
  thumbnail.setAttribute('src', youtubeThumbnailSrc(video))
  thumbnail.setAttribute('alt', '')
  thumbnail.setAttribute('loading', 'lazy')
  const icon = document.createElement('span')
  icon.className = 'youtube-play-icon'
  button.append(thumbnail, icon)
  button.addEventListener('click', () => play(box, video, label), { once: true })
  box.append(button)
  return box
}

// After the link's paragraph or heading; inside its list item, since a list holds nothing but items.
function place(root: HTMLElement, anchor: HTMLAnchorElement, box: HTMLElement, tails: Map<Element, Element>) {
  const found = anchor.closest('p, li, h1, h2, h3, h4, h5, h6')
  const block = found && found !== root && root.contains(found) ? found : anchor
  const tail = tails.get(block)
  if (tail) tail.after(box)
  else if (block.tagName === 'LI') {
    const sublist = block.querySelector(':scope > ul, :scope > ol')
    if (sublist) sublist.before(box)
    else block.append(box)
  }
  else block.after(box)
  tails.set(block, box)
}

const scanned = new WeakSet<Element>()

/** Adds a player for each video the container links that has none yet. A video linked twice gets one player. */
export function embedYoutubeLinks(root: HTMLElement): void {
  const embedded = new Set([...root.querySelectorAll<HTMLElement>('.youtube-embed')].map(box => box.dataset.videoId))
  const tails = new Map<Element, Element>()
  for (const anchor of root.querySelectorAll<HTMLAnchorElement>('a[href]')) {
    if (scanned.has(anchor)) continue
    scanned.add(anchor)
    // A table cell is too narrow for a player; the link alone stays.
    const table = anchor.closest('table')
    if (table && root.contains(table)) continue
    const video = parseYoutubeLink(anchor.getAttribute('href') ?? '')
    if (!video || embedded.has(video.id)) continue
    embedded.add(video.id)
    place(root, anchor, player(video, linkTitle(anchor)), tails)
  }
}

function scan(el: HTMLElement, binding: DirectiveBinding<boolean | undefined>) {
  if (binding.value !== false) embedYoutubeLinks(el)
}

/** Pass false while the container is streaming: each streamed render replaces its nodes, a playing video included. */
export const vYoutubeEmbeds: Directive<HTMLElement, boolean | undefined> = {
  mounted: scan,
  updated: scan,
}
