import { describe, it, expect } from 'vitest'
import { estimatePasswordStrength, MIN_PASSWORD_LENGTH } from '../utils/passwordStrength'

describe('estimatePasswordStrength', () => {
  it('returns score 0 and an empty label for an empty password', () => {
    expect(estimatePasswordStrength('')).toEqual({ score: 0, label: '' })
  })

  it('flags a too-short password', () => {
    const r = estimatePasswordStrength('short') // 5 chars
    expect(r.score).toBe(0)
    expect(r.label).toBe('Too short')
  })

  it('rates a 12-char lowercase password at least Fair', () => {
    expect(estimatePasswordStrength('abcdefghijkl').score).toBeGreaterThanOrEqual(2)
  })

  it('rates a long, character-varied password Strong', () => {
    const r = estimatePasswordStrength('Abcd1234!xyzQ_longer') // 20 chars, 4 classes
    expect(r.score).toBe(4)
    expect(r.label).toBe('Strong')
  })

  it('favours length — a long lowercase passphrase still scores well', () => {
    // NIST 800-63B: length beats composition. No symbols/digits, but long.
    expect(estimatePasswordStrength('correcthorsebatterystaple').score).toBeGreaterThanOrEqual(3)
  })

  it('exposes a 12-character minimum', () => {
    expect(MIN_PASSWORD_LENGTH).toBe(12)
  })
})
