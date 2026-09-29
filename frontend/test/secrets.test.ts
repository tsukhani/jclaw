import { describe, it, expect } from 'vitest'
import { isSensitiveKey } from '~/utils/secrets'

// JCLAW-1332: the same two lists as ConfigServiceTest, so the page and the API mask the same keys.
describe('isSensitiveKey', () => {
  it.each([
    'provider.openai.apiKey', 'search.exa.apiKey', 'scanner.malwarebazaar.authKey', 'scanner.virustotal.apiKey',
    'imagegen.local.hfToken', 'diarize.hfToken', 'decision.jev.apiKey', 'web_scrape.proxy.password',
    'otel.exporter.secretHeaders', 'jclaw.skills.catalog.github.token', 'http.proxyPassword', 'auth.internal.apiToken',
    'application.secret', 'certificate.password', 'OPENAI_API_KEY', 'GITHUB_TOKEN', 'AWS_SECRET_ACCESS_KEY',
    'API_KEYS', 'webhookSecret',
  ])('%s is a credential', (key) => {
    expect(isSensitiveKey(key)).toBe(true)
  })

  it.each([
    'telegram.keyboardScope', 'chat.stream.token_coalesce_chars', 'chat.compactionReserveTokens',
    'memory.autocapture.maxTokens', 'auth.password.breach-check.enabled', 'certificate.key.file',
    'web_scrape.proxy.username', 'TOKENIZERS_PARALLELISM', 'NODE_ENV', '',
  ])('%s is not', (key) => {
    expect(isSensitiveKey(key)).toBe(false)
  })
})
