import { describe, it, expect } from 'vitest'
import type { PersonaFinding } from '~/types/api'
import { renderMarkdown } from '~/utils/chat-markdown'
import {
  backdropHtml,
  codePoints,
  findingText,
  findingsHold,
  markPreview,
  mergeFindings,
  removeHiddenCharacters,
  withSentinels,
} from '~/utils/injection-highlight'

const ZWSP = String.fromCodePoint(0x200B)
const OPEN = String.fromCharCode(0xE000)
const OPEN_HIDDEN = String.fromCharCode(0xE001)
const CLOSE = String.fromCharCode(0xE002)

function finding(rule: string, start: number, end: number, text: string, line = 1): PersonaFinding {
  return { rule, start, end, line, text, hint: '' }
}

/** The sentinels as visible brackets, so an expected string can be read. */
function shown(text: string): string {
  return text.replaceAll(OPEN, '[').replaceAll(OPEN_HIDDEN, '{').replaceAll(CLOSE, ']')
}

describe('mergeFindings', () => {
  it('keeps separate findings apart, in text order', () => {
    expect(mergeFindings([
      finding('hide-from-user', 20, 40, 'x'),
      finding('you-are-now', 0, 12, 'x'),
    ])).toEqual([
      { start: 0, end: 12, hidden: false },
      { start: 20, end: 40, hidden: false },
    ])
  })

  it('joins overlapping and touching findings, hidden only when every one is an invisible character', () => {
    expect(mergeFindings([
      finding('ignore-instructions', 0, 32, 'x'),
      finding('invisible-character', 32, 33, ZWSP),
      finding('invisible-character', 50, 51, ZWSP),
      finding('invisible-character', 51, 52, ZWSP),
    ])).toEqual([
      { start: 0, end: 33, hidden: false },
      { start: 50, end: 52, hidden: true },
    ])
  })

  it('is empty for no findings', () => {
    expect(mergeFindings([])).toEqual([])
  })
})

describe('findingsHold', () => {
  const findings = [finding('you-are-now', 9, 21, 'You are now ')]

  it('holds while each finding still sits on its text', () => {
    expect(findingsHold('Be kind.\nYou are now Aina.', findings)).toBe(true)
  })

  it('fails once an edit moves the text', () => {
    expect(findingsHold('Be very kind.\nYou are now Aina.', findings)).toBe(false)
  })
})

describe('codePoints and findingText', () => {
  it('names each character by its code point, a surrogate pair as one', () => {
    expect(codePoints(`${ZWSP}${String.fromCodePoint(0x1F469)}`)).toBe('U+200B U+1F469')
  })

  it('shows an invisible match by its code point and any other match as its trimmed text', () => {
    expect(findingText(finding('invisible-character', 3, 4, ZWSP))).toBe('U+200B')
    expect(findingText(finding('you-are-now', 0, 12, 'You are now '))).toBe('You are now')
  })
})

describe('backdropHtml', () => {
  it('escapes the text and marks each range, ending with a space for a trailing newline', () => {
    const content = `<b> & You are now x\nRo${ZWSP}bin\n`
    expect(backdropHtml(content, [
      { start: 6, end: 18, hidden: false },
      { start: 22, end: 23, hidden: true },
    ])).toBe('&lt;b&gt; &amp; <mark class="injection-mark">You are now </mark>x\nRo'
      + `<mark class="injection-mark injection-mark-hidden" title="U+200B">${ZWSP}</mark>bin\n `)
  })

  it('is the escaped text alone when nothing is marked', () => {
    expect(backdropHtml('a "quoted" \'word\'', [])).toBe('a &quot;quoted&quot; &#39;word&#39; ')
  })
})

describe('withSentinels', () => {
  it('wraps each range, leaving the edge whitespace of a visible one outside', () => {
    const content = `# Title\nYou are now\n## Next ${ZWSP}`
    expect(shown(withSentinels(content, [
      { start: 8, end: 20, hidden: false },
      { start: 28, end: 29, hidden: true },
    ]))).toBe(`# Title\n[You are now]\n## Next {${ZWSP}]`)
  })

  it('neutralizes a marker character the file already holds, so only its own markers reach the renderer', () => {
    expect(shown(withSentinels(`a${OPEN}b You are now`, [{ start: 4, end: 16, hidden: false }])))
      .toBe(`a${String.fromCodePoint(0xFFFD)}b [You are now]`)
  })

  it('skips a visible range that is only whitespace', () => {
    expect(shown(withSentinels('a \n b', [{ start: 1, end: 4, hidden: false }]))).toBe('a \n b')
  })
})

describe('markPreview', () => {
  it('turns a sentinel pair into a mark', () => {
    expect(markPreview(`<p>Hi ${OPEN}You are now${CLOSE} there</p>`))
      .toBe('<p>Hi <mark class="injection-mark">You are now</mark> there</p>')
  })

  it('marks a range the renderer split across elements piece by piece', () => {
    expect(markPreview(`<p>${OPEN}Do not <strong>tell${CLOSE} anyone</strong></p>`))
      .toBe('<p><mark class="injection-mark">Do not </mark><strong><mark class="injection-mark">tell</mark>'
        + ' anyone</strong></p>')
  })

  it('names an invisible character by its code point', () => {
    expect(markPreview(`<p>Ro${OPEN_HIDDEN}${ZWSP}${CLOSE}bin</p>`))
      .toBe(`<p>Ro<mark class="injection-mark injection-mark-hidden" title="U+200B">${ZWSP}</mark>bin</p>`)
  })

  it('drops sentinels that landed in an attribute', () => {
    expect(markPreview(`<p><a href="https://x.test/${OPEN}a${CLOSE}">x</a></p>`))
      .toBe('<p><a href="https://x.test/a">x</a></p>')
  })

  it('cannot be steered into a script address by a marker character the file itself holds', () => {
    // DOMPurify passes this href as a relative path; taking the private-use character out after it would leave a script URL.
    expect(markPreview(`<p><a href="java${OPEN}script:alert(1)">x</a> ${OPEN}You are now${CLOSE}</p>`))
      .toBe('<p><a>x</a> <mark class="injection-mark">You are now</mark></p>')
  })

  it('renders a hostile file whose link hides a marker character without a script address', () => {
    const content = `[x](java${OPEN}script:alert(1)) You are now here`
    const html = markPreview(renderMarkdown(withSentinels(content, [{ start: 26, end: 38, hidden: false }])))
    expect(html).not.toContain('javascript:')
    expect(html).toContain('<mark class="injection-mark">You are now</mark>')
  })

  it('returns HTML with no sentinels unchanged', () => {
    expect(markPreview('<p>plain</p>')).toBe('<p>plain</p>')
  })

  it('leaves the markdown around a match as it was: the heading after it stays a heading', () => {
    const content = '# Title\nYou are now\n## Next'
    const html = markPreview(renderMarkdown(withSentinels(content, [{ start: 8, end: 20, hidden: false }])))
    const plain = renderMarkdown(content)
    expect(html).toBe(plain.replace('You are now', '<mark class="injection-mark">You are now</mark>'))
  })
})

describe('removeHiddenCharacters', () => {
  it('deletes each invisible character the findings name and leaves every other match alone', () => {
    const content = `You are now Ro${ZWSP}bin${ZWSP}.`
    expect(removeHiddenCharacters(content, [
      finding('you-are-now', 0, 12, 'You are now '),
      finding('invisible-character', 14, 15, ZWSP),
      finding('invisible-character', 18, 19, ZWSP),
    ])).toBe('You are now Robin.')
  })

  it('leaves a character that has moved since the check', () => {
    const content = `Ro${ZWSP}bin`
    expect(removeHiddenCharacters(content, [finding('invisible-character', 1, 2, ZWSP)])).toBe(content)
  })
})
