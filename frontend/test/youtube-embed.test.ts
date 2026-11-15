import { afterEach, describe, expect, it } from 'vitest'
import { defineComponent, h, nextTick, ref, withDirectives } from 'vue'
import { mount } from '@vue/test-utils'
import { renderMarkdown } from '~/utils/chat-markdown'
import { parseYoutubeLink, vYoutubeEmbeds, youtubeEmbedSrc } from '~/utils/youtube-embed'

const ID = 'dQw4w9WgXcQ'

function mountWith(initialHtml: string, initialEnabled?: boolean) {
  const html = ref(initialHtml)
  const enabled = ref<boolean | undefined>(initialEnabled)
  const tick = ref(0)
  const wrapper = mount(defineComponent({
    setup: () => () => withDirectives(
      h('div', { 'innerHTML': html.value, 'data-tick': tick.value }),
      [[vYoutubeEmbeds, enabled.value]],
    ),
  }), { attachTo: document.body })
  const root = wrapper.element as HTMLElement
  return {
    html,
    enabled,
    tick,
    root,
    players: () => [...root.querySelectorAll<HTMLElement>('.youtube-embed')],
  }
}

const attributes = (el: Element) => Object.fromEntries([...el.attributes].map(a => [a.name, a.value]))

describe('parseYoutubeLink', () => {
  it.each([
    ['https://www.youtube.com/watch?v=dQw4w9WgXcQ', 0],
    ['https://youtube.com/watch?v=dQw4w9WgXcQ&list=PL123&index=2', 0],
    ['https://m.youtube.com/watch?v=dQw4w9WgXcQ', 0],
    ['https://music.youtube.com/watch?v=dQw4w9WgXcQ', 0],
    ['http://www.youtube.com/watch?v=dQw4w9WgXcQ', 0],
    ['https://youtu.be/dQw4w9WgXcQ?si=Xy12', 0],
    ['https://youtu.be/dQw4w9WgXcQ?t=42', 42],
    ['https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=90s', 90],
    ['https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=1h2m3s', 3723],
    ['https://www.youtube.com/watch?v=dQw4w9WgXcQ#t=2m', 120],
    ['https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=soon', 0],
    ['https://www.youtube.com/embed/dQw4w9WgXcQ?start=30', 30],
    ['https://www.youtube-nocookie.com/embed/dQw4w9WgXcQ', 0],
    ['https://www.youtube.com/live/dQw4w9WgXcQ', 0],
  ])('reads %s as the video and its start', (href, start) => {
    expect(parseYoutubeLink(href)).toEqual({ id: ID, start, short: false })
  })

  it('marks a Short, which plays upright', () => {
    expect(parseYoutubeLink('https://www.youtube.com/shorts/aBcDeFgHiJk')).toEqual({ id: 'aBcDeFgHiJk', start: 0, short: true })
  })

  it.each([
    'https://www.youtube.com/watch?v=dQw4w9WgXc',
    'https://www.youtube.com/watch?v=dQw4w9WgXcQQ',
    'https://www.youtube.com/watch?v=dQw4w9WgX%3C',
    'https://www.youtube.com/watch',
    'https://youtu.be/',
    'https://www.youtube.com/@SomeChannel',
    'https://www.youtube.com/playlist?list=PLabc',
    'https://www.youtube.com/embed/videoseries?list=PLabc',
    'https://youtube.com.example.org/watch?v=dQw4w9WgXcQ',
    'https://example.org/watch?v=dQw4w9WgXcQ',
    'ftp://youtu.be/dQw4w9WgXcQ',
    'javascript:alert(1)//youtu.be/dQw4w9WgXcQ',
    'watch?v=dQw4w9WgXcQ',
    '',
  ])('plays nothing for %s', (href) => {
    expect(parseYoutubeLink(href)).toBeNull()
  })

  it('builds the privacy-enhanced player URL, with the start only when there is one', () => {
    expect(youtubeEmbedSrc({ id: ID, start: 0, short: false }))
      .toBe('https://www.youtube-nocookie.com/embed/dQw4w9WgXcQ?autoplay=1&playsinline=1&rel=0')
    expect(youtubeEmbedSrc({ id: ID, start: 42, short: false }))
      .toBe('https://www.youtube-nocookie.com/embed/dQw4w9WgXcQ?autoplay=1&playsinline=1&rel=0&start=42')
  })
})

describe('v-youtube-embeds', () => {
  afterEach(() => {
    document.body.innerHTML = ''
  })

  it('puts a thumbnail player after the paragraph that links a video, and keeps the link', () => {
    const { root, players } = mountWith(renderMarkdown(`Watch [Never Gonna Give You Up](https://www.youtube.com/watch?v=${ID}) tonight.`, 1))
    expect(root.querySelector('p > a')!.getAttribute('href')).toBe(`https://www.youtube.com/watch?v=${ID}`)
    expect(players()).toHaveLength(1)
    expect(root.querySelector('p')!.nextElementSibling).toBe(players()[0])
    expect(players()[0]!.outerHTML).toBe(
      `<div class="youtube-embed" data-video-id="${ID}">`
      + '<button type="button" class="youtube-play" aria-label="Play YouTube video: Never Gonna Give You Up">'
      + `<img src="https://i.ytimg.com/vi/${ID}/hqdefault.jpg" alt="" loading="lazy">`
      + '<span class="youtube-play-icon"></span>'
      + '</button></div>',
    )
  })

  it('plays the video in place when the thumbnail is clicked', () => {
    const { players } = mountWith(renderMarkdown(`[Never Gonna Give You Up](https://youtu.be/${ID}?t=42)`, 1))
    const box = players()[0]!
    box.querySelector('button')!.click()
    expect(box.children).toHaveLength(1)
    const frame = box.querySelector('iframe')!
    expect(attributes(frame)).toEqual({
      src: `https://www.youtube-nocookie.com/embed/${ID}?autoplay=1&playsinline=1&rel=0&start=42`,
      title: 'YouTube video: Never Gonna Give You Up',
      allow: 'accelerometer; autoplay; clipboard-write; encrypted-media; gyroscope; picture-in-picture; web-share',
      allowfullscreen: '',
      referrerpolicy: 'strict-origin-when-cross-origin',
    })
    expect(document.activeElement).toBe(frame)
  })

  it('names a bare link\'s player for YouTube alone, since its text is only the URL', () => {
    const { players } = mountWith(renderMarkdown(`See https://www.youtube.com/watch?v=${ID}`, 1))
    const button = players()[0]!.querySelector('button')!
    expect(button.getAttribute('aria-label')).toBe('Play YouTube video')
    button.click()
    expect(players()[0]!.querySelector('iframe')!.getAttribute('title')).toBe('YouTube video')
  })

  it('gives each video one player, in the order the reply links them', () => {
    const { root, players } = mountWith(renderMarkdown(
      `[First](https://youtu.be/${ID}) and [Second](https://www.youtube.com/watch?v=9bZkp7q19f0), `
      + `then [First again](https://www.youtube.com/watch?v=${ID}&t=10).`,
      1,
    ))
    expect(players().map(p => p.dataset.videoId)).toEqual([ID, '9bZkp7q19f0'])
    expect(root.querySelector('p')!.nextElementSibling).toBe(players()[0])
    expect(players()[0]!.nextElementSibling).toBe(players()[1])
  })

  it('puts a list item\'s player inside the item, ahead of its sublist', () => {
    const { root } = mountWith(renderMarkdown(`- [Talk](https://youtu.be/${ID})\n  - notes\n- plain item`, 1))
    const items = root.querySelectorAll(':scope > ul > li')
    expect([...items[0]!.children].map(c => c.className || c.tagName)).toEqual(['A', 'youtube-embed', 'UL'])
    expect([...root.querySelector(':scope > ul')!.children].map(c => c.tagName)).toEqual(['LI', 'LI'])
  })

  it('puts a heading\'s player after the heading, and an upright box under a Short', () => {
    const { root, players } = mountWith(renderMarkdown('## [Clip](https://www.youtube.com/shorts/aBcDeFgHiJk)', 1))
    expect(root.querySelector('h2')!.nextElementSibling).toBe(players()[0])
    expect(players()[0]!.className).toBe('youtube-embed youtube-short')
  })

  it('adds no player for a link in a table, a playlist or another site', () => {
    const { root, players } = mountWith(renderMarkdown(
      `| Video |\n| --- |\n| [Talk](https://youtu.be/${ID}) |\n\n`
      + '[List](https://www.youtube.com/playlist?list=PLabc) and [Vimeo](https://vimeo.com/76979871)',
      1,
    ))
    expect(root.querySelectorAll('a')).toHaveLength(3)
    expect(players()).toHaveLength(0)
  })

  it('holds off while the reply streams, and adds the player once it is done', async () => {
    const { enabled, players } = mountWith(renderMarkdown(`[Talk](https://youtu.be/${ID})`, 1), false)
    expect(players()).toHaveLength(0)
    enabled.value = true
    await nextTick()
    expect(players().map(p => p.dataset.videoId)).toEqual([ID])
  })

  it('keeps a playing video through a re-render that leaves the HTML alone', async () => {
    const { tick, players } = mountWith(renderMarkdown(`[Talk](https://youtu.be/${ID})`, 1))
    const box = players()[0]!
    box.querySelector('button')!.click()
    const frame = box.querySelector('iframe')
    tick.value++
    await nextTick()
    expect(players()).toEqual([box])
    expect(box.querySelector('iframe')).toBe(frame)
  })

  it('replaces the players when the reply is rendered anew', async () => {
    const { html, players } = mountWith(renderMarkdown(`[Talk](https://youtu.be/${ID})`, 1))
    html.value = renderMarkdown('[Other](https://youtu.be/9bZkp7q19f0)', 1)
    await nextTick()
    expect(players().map(p => p.dataset.videoId)).toEqual(['9bZkp7q19f0'])
  })
})
