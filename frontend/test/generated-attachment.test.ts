import { describe, it, expect } from 'vitest'
import { generatedImageLabel } from '~/utils/generated-attachment'
import type { MessageAttachment } from '~/types/api'

function att(o: Partial<MessageAttachment> = {}): MessageAttachment {
  return { uuid: 'u1', kind: 'IMAGE', sizeBytes: 10, originalFilename: 'generated-20260101.png', mimeType: 'image/png', ...o }
}

describe('generatedImageLabel (JCLAW-228)', () => {
  it('returns the prompt parsed from generationMetadata', () => {
    expect(generatedImageLabel(att({ generationMetadata: JSON.stringify({ prompt: 'a red bicycle' }) }))).toBe('a red bicycle')
  })
  it('falls back to the filename when metadata is absent', () => {
    expect(generatedImageLabel(att({ generationMetadata: undefined }))).toBe('generated-20260101.png')
  })
  it('falls back to the filename when metadata is unparseable', () => {
    expect(generatedImageLabel(att({ generationMetadata: '{not json' }))).toBe('generated-20260101.png')
  })
  it('falls back when the prompt is blank/whitespace', () => {
    expect(generatedImageLabel(att({ generationMetadata: JSON.stringify({ prompt: '   ' }) }))).toBe('generated-20260101.png')
  })
})
