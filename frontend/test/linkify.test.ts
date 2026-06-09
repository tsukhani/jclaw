import { describe, it, expect } from 'vitest'
import { linkify } from '~/utils/linkify'

describe('linkify', () => {
  it('returns empty string for empty input', () => {
    expect(linkify('')).toBe('')
    expect(linkify(null as unknown as string)).toBe('')
    expect(linkify(undefined as unknown as string)).toBe('')
  })

  it('leaves plain text untouched (no anchor)', () => {
    expect(linkify('just a plain reminder')).toBe('just a plain reminder')
  })

  it('wraps a bare https URL in a blank-target, noopener anchor', () => {
    const out = linkify('see https://example.com now')
    expect(out).toContain('<a href="https://example.com"')
    expect(out).toContain('target="_blank"')
    expect(out).toContain('rel="noopener noreferrer"')
    expect(out).toContain('>https://example.com</a>')
  })

  it('preserves a query string with & inside the href (the Google Maps case)', () => {
    const url = 'https://www.google.com/maps/dir/?api=1&destination=3.1547903,101.718559'
    const out = linkify(`Maps: ${url}`)
    // `&` is HTML-escaped to `&amp;` — the correct encoding inside an attribute,
    // and the comma in the coordinates stays part of the link.
    expect(out).toContain('href="https://www.google.com/maps/dir/?api=1&amp;destination=3.1547903,101.718559"')
  })

  it('does not swallow a trailing sentence period into the link', () => {
    const out = linkify('Go to https://example.com.')
    expect(out).toContain('>https://example.com</a>.')
  })

  it('escapes HTML so embedded markup cannot execute', () => {
    const out = linkify('<img src=x onerror=alert(1)> https://example.com')
    expect(out).not.toContain('<img')
    expect(out).toContain('&lt;img')
    expect(out).toContain('<a href="https://example.com"')
  })
})
