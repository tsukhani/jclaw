import { describe, it, expect } from 'vitest'
import { maskedAsRead } from './config-mask'

// ConfigServiceTest's maskValue cases, so a panel test is served what the API serves.
describe('maskedAsRead', () => {
  it.each(['ts-secret-123', 'abcde', 'abcd', 'a'])('reads a saved secret %s back as the mask alone', (value) => {
    expect(maskedAsRead('decision.jev.apiKey', value)).toBe('****')
  })

  it('reads an unset secret back as blank', () => {
    expect(maskedAsRead('decision.jev.apiKey', '')).toBe('')
  })

  it.each([
    'decision.laya.apiKey', 'decision.baseten.apiKey', 'web_scrape.proxy.password',
    'web_scrape.proxy.dataimpulse.residential.password', 'telegram.bot.token',
  ])('masks %s, a key the API masks', (key) => {
    expect(maskedAsRead(key, 'abcdef-secret')).toBe('****')
  })

  it.each([
    ['decision.laya.baseUrl', 'http://localhost:8000'],
    ['web_scrape.proxy.username', 'abc'],
    ['web_scrape.proxy.dataimpulse.residential.login', 'abc'],
    ['telegram.keyboardScope', 'group'],
  ])('reads %s back as stored', (key, value) => {
    expect(maskedAsRead(key, value)).toBe(value)
  })
})
