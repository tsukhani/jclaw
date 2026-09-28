import { describe, expect, it } from 'vitest'
import { renderMarkdown, renderMarkdownStreaming } from '~/utils/chat-markdown'

const XYZ = '3\nwater\nO 0.000 0.000 0.117\nH 0.000 0.757 -0.470\nH 0.000 -0.757 -0.470'
const parse = (html: string) => new DOMParser().parseFromString(html, 'text/html')

describe('renderMarkdown structure fence (JCLAW-1321)', () => {
  it('wraps a finished fence in a figure that keeps its code block and info string through DOMPurify', () => {
    const doc = parse(renderMarkdown(`Here:\n\n\`\`\`structure xyz Water "bent"\n${XYZ}\n\`\`\`\n`))
    const figure = doc.querySelector('figure.structure-view')!
    expect(figure.classList.contains('structure-pending')).toBe(false)
    expect(figure.getAttribute('data-info')).toBe('structure xyz Water "bent"')
    // Marked ends a code block's text with a newline; the fence parser trims it.
    expect(figure.querySelector(':scope > .code-block code')!.textContent).toBe(`${XYZ}\n`)
    expect(figure.querySelector('figcaption')!.textContent).toBe('Water "bent"')
  })

  it('keeps a streaming reply\'s finished fence pending, with nothing for the viewer to claim', () => {
    const doc = parse(renderMarkdownStreaming(`\`\`\`structure\n${XYZ}\n\`\`\`\n\nMore text`))
    const figure = doc.querySelector('figure.structure-view')!
    expect(figure.classList.contains('structure-pending')).toBe(true)
    expect(figure.hasAttribute('data-info')).toBe(false)
    expect(doc.querySelector('.structure-note')!.textContent).toContain('when the reply is complete')
  })

  it('leaves a fence that is still streaming as a plain code block', () => {
    const doc = parse(renderMarkdownStreaming(`\`\`\`structure\n${XYZ}`))
    expect(doc.querySelector('figure')).toBeNull()
    expect(doc.querySelector('code.language-structure')).not.toBeNull()
  })

  it('shows a refused path as a code block with the reason', () => {
    const doc = parse(renderMarkdown('```structure\n../secrets/x.cif\n```\n'))
    expect(doc.querySelector('figure')).toBeNull()
    expect(doc.querySelector('code.language-structure')!.textContent).toBe('../secrets/x.cif\n')
    expect(doc.querySelector('.structure-error')!.textContent).toContain('leaves the workspace')
  })

  it('does not treat other fences as structures', () => {
    const doc = parse(renderMarkdown(`\`\`\`xyz\n${XYZ}\n\`\`\`\n`))
    expect(doc.querySelector('figure')).toBeNull()
    expect(doc.querySelector('code.language-xyz')).not.toBeNull()
  })
})
