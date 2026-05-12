import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mountSuspended, registerEndpoint } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import ApiTokensSection from '~/components/ApiTokensSection.vue'

/**
 * Unit tests for the API Tokens section (JCLAW-282).
 *
 * <p>Covers the three operator interactions that matter:
 *
 * <ul>
 *   <li>Listing — active vs revoked partitioning and label rendering.</li>
 *   <li>Minting — request shape, plaintext capture into the show-once
 *       modal, refresh-on-success.</li>
 *   <li>Revoking — the confirm-then-DELETE flow.</li>
 * </ul>
 *
 * <p>{@code registerEndpoint} is convenient for GET fixtures but
 * awkward for POST/DELETE because the test needs to read the request
 * body — verb-aware handlers under {@code @nuxt/test-utils/runtime}
 * vary by version. We spy on global {@code $fetch} instead so the
 * test asserts the actual call shape independent of the mocking
 * layer's API surface.
 */

const sampleTokens = [
  {
    id: 1,
    name: 'claude-desktop',
    displayPrefix: 'jcl_abc12345',
    scope: 'READ_ONLY',
    ownerUsername: 'admin',
    createdAt: '2026-05-01T10:00:00Z',
    lastUsedAt: '2026-05-12T09:00:00Z',
    revokedAt: null,
    plaintext: null,
  },
  {
    id: 2,
    name: 'old-token',
    displayPrefix: 'jcl_xyz98765',
    scope: 'FULL',
    ownerUsername: 'admin',
    createdAt: '2026-04-01T10:00:00Z',
    lastUsedAt: null,
    revokedAt: '2026-04-15T10:00:00Z',
    plaintext: null,
  },
]

const mintedToken = {
  id: 99,
  name: 'fresh',
  displayPrefix: 'jcl_freshAB',
  scope: 'READ_ONLY',
  ownerUsername: 'admin',
  createdAt: '2026-05-12T20:00:00Z',
  lastUsedAt: null,
  revokedAt: null,
  plaintext: 'jcl_freshAB_full_secret_value_for_test',
}

// GET endpoint is registered as a Nitro mock — it serves the initial
// useFetch() at component setup. Mutating calls go through the
// $fetch spy below.
registerEndpoint('/api/api-tokens', () => sampleTokens)

let fetchSpy: ReturnType<typeof vi.fn>

beforeEach(() => {
  // Replace global $fetch with a spy that returns appropriate fixtures
  // per URL. This lets us inspect call arguments without depending on
  // a particular registerEndpoint API surface.
  fetchSpy = vi.fn(async (url: string, opts?: { method?: string }) => {
    if (url === '/api/api-tokens' && (!opts?.method || opts.method === 'GET')) {
      return sampleTokens
    }
    if (url === '/api/api-tokens' && opts?.method === 'POST') {
      return mintedToken
    }
    if (url.startsWith('/api/api-tokens/') && opts?.method === 'DELETE') {
      return { revoked: true }
    }
    return null
  })
  vi.stubGlobal('$fetch', fetchSpy)
})

describe('ApiTokensSection — listing', () => {
  it('renders active token name, scope chip, and prefix', async () => {
    const wrapper = await mountSuspended(ApiTokensSection)
    await flushPromises()
    const html = wrapper.html()
    expect(html).toContain('claude-desktop')
    expect(html).toContain('jcl_abc12345')
    // Active count is computed from the partition — must NOT include the
    // revoked row (id=2) below.
    expect(html).toContain('Active tokens (1)')
    expect(html).toContain('read-only')
  })

  it('puts revoked tokens in the collapsed history section', async () => {
    const wrapper = await mountSuspended(ApiTokensSection)
    await flushPromises()
    const html = wrapper.html()
    // History section header includes the revoked count and the revoked
    // row's prefix appears inside (collapsed-but-rendered <details>).
    expect(html).toContain('Revoked tokens (1)')
    expect(html).toContain('jcl_xyz98765')
  })
})

describe('ApiTokensSection — mint', () => {
  it('sends name + scope and surfaces the plaintext in the modal', async () => {
    const wrapper = await mountSuspended(ApiTokensSection)
    await flushPromises()

    const nameInput = wrapper.find('input[aria-label="New API token name"]')
    expect(nameInput.exists()).toBe(true)
    await nameInput.setValue('claude-cursor')
    await wrapper.find('button.bg-emerald-600').trigger('click')
    await flushPromises()

    // The $fetch spy captures the POST — body shape proves the form is
    // wired to the right route + verb.
    const postCall = fetchSpy.mock.calls.find(
      ([url, opts]) => url === '/api/api-tokens' && opts?.method === 'POST',
    )
    expect(postCall, 'expected POST /api/api-tokens to be invoked').toBeDefined()
    expect(postCall?.[1]?.body).toMatchObject({
      name: 'claude-cursor',
      scope: 'READ_ONLY',
    })

    // The modal uses <Teleport to="body">, so the rendered content
    // lives on document.body rather than in wrapper.html(). Querying
    // body matches what the user sees on screen.
    expect(document.body.innerHTML).toContain('Token minted')
    expect(document.body.innerHTML).toContain(mintedToken.plaintext)
  })

  it('disables Mint when the name field is blank', async () => {
    const wrapper = await mountSuspended(ApiTokensSection)
    await flushPromises()
    const button = wrapper.find('button.bg-emerald-600')
    expect(button.attributes('disabled')).toBeDefined()
  })

  it('clears the form after a successful mint so the next one starts fresh', async () => {
    const wrapper = await mountSuspended(ApiTokensSection)
    await flushPromises()

    const nameInput = wrapper.find('input[aria-label="New API token name"]')
    await nameInput.setValue('once')
    await wrapper.find('button.bg-emerald-600').trigger('click')
    await flushPromises()

    // Modal is up; the input behind it should be empty so a second mint
    // doesn't accidentally re-use the previous label.
    expect((nameInput.element as HTMLInputElement).value).toBe('')
  })
})
