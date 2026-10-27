import { describe, it, expect } from 'vitest'
import { lineDiff } from '~/utils/line-diff'

describe('lineDiff', () => {
  it('keeps the lines both texts share and marks the rest, a removal before the addition that replaces it', () => {
    expect(lineDiff('# Soul\nBe kind.\nSign off.', '# Soul\nBe curious.\nSign off.\nP.S.')).toEqual([
      { kind: 'same', text: '# Soul' },
      { kind: 'removed', text: 'Be kind.' },
      { kind: 'added', text: 'Be curious.' },
      { kind: 'same', text: 'Sign off.' },
      { kind: 'added', text: 'P.S.' },
    ])
  })

  it('shows a file that was empty as all added, and one emptied as all removed', () => {
    expect(lineDiff('', 'Name: Ada\nLikes tea.')).toEqual([
      { kind: 'added', text: 'Name: Ada' },
      { kind: 'added', text: 'Likes tea.' },
    ])
    expect(lineDiff('Name: Ada', '')).toEqual([{ kind: 'removed', text: 'Name: Ada' }])
  })

  it('is all kept lines for equal texts', () => {
    expect(lineDiff('a\nb', 'a\nb')).toEqual([{ kind: 'same', text: 'a' }, { kind: 'same', text: 'b' }])
  })
})
