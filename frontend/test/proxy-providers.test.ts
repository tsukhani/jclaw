import { describe, it, expect } from 'vitest'
import {
  composeDataImpulse,
  composeProxy,
  credentialError,
  emptyDataImpulse,
  keepsStoredPassword,
  manualUrlError,
  parseProxy,
  planProxyWrites,
  validateDataImpulse,
  validateManual,
  type DataImpulseFields,
} from '~/utils/proxy-providers'

function dataImpulse(over: Partial<DataImpulseFields>): DataImpulseFields {
  return { ...emptyDataImpulse(), ...over }
}

describe('composeDataImpulse', () => {
  it('rotating with any country is the login alone on port 823', () => {
    expect(composeDataImpulse(dataImpulse({ login: 'abc' })))
      .toEqual({ url: 'http://gw.dataimpulse.com:823', username: 'abc' })
  })

  it('puts the countries after the login, lower-cased and without spaces', () => {
    expect(composeDataImpulse(dataImpulse({ login: 'abc', countries: 'DE, au' })).username).toBe('abc__cr.de,au')
  })

  it('sticky goes to port 10000 with the session length', () => {
    expect(composeDataImpulse(dataImpulse({ login: 'abc', countries: 'us', rotation: 'sticky', sessionMinutes: '45' })))
      .toEqual({ url: 'http://gw.dataimpulse.com:10000', username: 'abc__cr.us;sessttl.45' })
  })

  it('sticky with no session length leaves DataImpulse its default', () => {
    expect(composeDataImpulse(dataImpulse({ login: 'abc', rotation: 'sticky' })).username).toBe('abc')
  })

  it('rotating drops a session length, which only a sticky IP has', () => {
    expect(composeDataImpulse(dataImpulse({ login: 'abc', sessionMinutes: '45' })).username).toBe('abc')
  })

  it('keeps a stored sticky port, and falls back to 10000 outside the sticky range', () => {
    expect(composeDataImpulse(dataImpulse({ login: 'abc', rotation: 'sticky', stickyPort: 12345 })).url)
      .toBe('http://gw.dataimpulse.com:12345')
    expect(composeDataImpulse(dataImpulse({ login: 'abc', rotation: 'sticky', stickyPort: 9000 })).url)
      .toBe('http://gw.dataimpulse.com:10000')
  })

  it('connects to the IP gateway on the same ports when it is chosen', () => {
    expect(composeDataImpulse(dataImpulse({ login: 'abc', gateway: 'ip' })).url).toBe('http://74.81.81.81:823')
    expect(composeDataImpulse(dataImpulse({ login: 'abc', gateway: 'ip', rotation: 'sticky', stickyPort: 12345 })).url)
      .toBe('http://74.81.81.81:12345')
  })
})

describe('parseProxy', () => {
  it('reads a rotating DataImpulse config back into its card', () => {
    const parsed = parseProxy('http://gw.dataimpulse.com:823', 'abc__cr.de,au')
    expect(parsed.provider).toBe('dataimpulse')
    expect(parsed.dataimpulse).toMatchObject({ login: 'abc', countries: 'de,au', rotation: 'rotating', extraParams: [] })
  })

  it('reads a sticky DataImpulse config back, with its port and session length', () => {
    const parsed = parseProxy('http://gw.dataimpulse.com:10000', 'abc__cr.us;sessttl.45')
    expect(parsed.provider).toBe('dataimpulse')
    expect(parsed.dataimpulse).toMatchObject({ login: 'abc', countries: 'us', rotation: 'sticky', sessionMinutes: '45', stickyPort: 10000 })
  })

  it('recognizes the gateway whatever the host\'s case', () => {
    expect(parseProxy('http://GW.DataImpulse.com:823/', 'abc').provider).toBe('dataimpulse')
  })

  it('reads a config on the IP gateway back into the DataImpulse card', () => {
    const parsed = parseProxy('http://74.81.81.81:823', 'abc__cr.de')
    expect(parsed.provider).toBe('dataimpulse')
    expect(parsed.dataimpulse).toMatchObject({ gateway: 'ip', login: 'abc', countries: 'de', rotation: 'rotating' })
    expect(parseProxy('http://gw.dataimpulse.com:823', 'abc').dataimpulse.gateway).toBe('hostname')
  })

  it('shows any other proxy in the Manual card with its raw fields', () => {
    const parsed = parseProxy('http://proxy.example:3128', 'scraper')
    expect(parsed.provider).toBe('manual')
    expect(parsed.manual).toEqual({ url: 'http://proxy.example:3128', username: 'scraper' })
    expect(parsed.dataimpulse.login).toBe('')
  })

  it('leaves a DataImpulse URL the card cannot write to the Manual card', () => {
    // socks5 would lose its scheme, and 824 or 9999 their port, on the next save.
    for (const url of ['socks5://gw.dataimpulse.com:824', 'http://gw.dataimpulse.com:824', 'http://gw.dataimpulse.com:9999']) {
      expect(parseProxy(url, '').provider, url).toBe('manual')
    }
  })

  it('an empty URL is None, with the unused credentials still in the cards', () => {
    const parsed = parseProxy('', 'abc__cr.de')
    expect(parsed.provider).toBe('none')
    expect(parsed.dataimpulse.login).toBe('abc')
    expect(parsed.manual.username).toBe('abc__cr.de')
  })
})

describe('parse and compose round-trip', () => {
  const stored: Array<[string, string]> = [
    ['http://gw.dataimpulse.com:823', 'abc'],
    ['http://gw.dataimpulse.com:823', 'abc__cr.de,au'],
    ['http://gw.dataimpulse.com:10000', 'abc__cr.us;sessttl.45'],
    ['http://gw.dataimpulse.com:15000', 'abc__sessttl.5'],
    ['http://gw.dataimpulse.com:823', 'abc__cr.us;anon.1'],
    // A session length on the rotating port is kept as it is, not dropped as an unused field.
    ['http://gw.dataimpulse.com:823', 'abc__cr.us;sessttl.45'],
    ['http://74.81.81.81:10000', 'abc__cr.us;sessttl.45'],
  ]
  for (const [url, username] of stored) {
    it(`${url} ${username}`, () => {
      const parsed = parseProxy(url, username)
      expect(composeProxy(parsed.provider, parsed)).toEqual({ url, username })
    })
  }

  it('keeps username parameters the panel does not model when a field changes', () => {
    const parsed = parseProxy('http://gw.dataimpulse.com:823', 'abc__cr.us;anon.1;state.ny')
    expect(parsed.dataimpulse.extraParams).toEqual(['anon.1', 'state.ny'])
    parsed.dataimpulse.countries = 'de'
    expect(composeProxy('dataimpulse', parsed).username).toBe('abc__cr.de;anon.1;state.ny')
  })

  it('a session length typed for a sticky IP replaces one kept from a rotating config', () => {
    const parsed = parseProxy('http://gw.dataimpulse.com:823', 'abc__sessttl.45')
    expect(parsed.dataimpulse).toMatchObject({ sessionMinutes: '', extraParams: ['sessttl.45'] })
    Object.assign(parsed.dataimpulse, { rotation: 'sticky', sessionMinutes: '10' })
    expect(composeProxy('dataimpulse', parsed).username).toBe('abc__sessttl.10')
  })

  it('None clears the URL and leaves the credentials alone', () => {
    expect(composeProxy('none', parseProxy('http://gw.dataimpulse.com:823', 'abc'))).toEqual({ url: '', username: null })
  })
})

describe('validateDataImpulse', () => {
  it('accepts a login and a country', () => {
    expect(validateDataImpulse(dataImpulse({ login: 'abc', countries: 'de,au' }))).toEqual({})
  })

  it('names each bad field', () => {
    expect(validateDataImpulse(dataImpulse({ login: '', countries: 'germany' }))).toEqual({
      login: expect.any(String),
      countries: expect.any(String),
    })
  })

  it('refuses a session length outside 1 to 120 minutes on a sticky IP', () => {
    for (const minutes of ['0', '200', '1.5', 'ten']) {
      expect(validateDataImpulse(dataImpulse({ login: 'abc', rotation: 'sticky', sessionMinutes: minutes })).sessionMinutes, minutes)
        .toBeDefined()
    }
    for (const minutes of ['', '1', '120']) {
      expect(validateDataImpulse(dataImpulse({ login: 'abc', rotation: 'sticky', sessionMinutes: minutes })), minutes).toEqual({})
    }
  })

  it('refuses a login with parameters already in it, which would be composed twice', () => {
    expect(validateDataImpulse(dataImpulse({ login: 'abc__cr.de' })).login).toBeDefined()
    expect(validateDataImpulse(dataImpulse({ login: 'abc def' })).login).toBeDefined()
  })

  it('refuses a login ending in an underscore, which would read back as a different login', () => {
    expect(validateDataImpulse(dataImpulse({ login: 'abc_', countries: 'de' })).login).toBeDefined()
    expect(validateDataImpulse(dataImpulse({ login: 'a_bc' }))).toEqual({})
  })
})

describe('manualUrlError', () => {
  it('accepts what ScrapeProxy accepts', () => {
    for (const url of ['http://proxy.example:8080', 'socks5://127.0.0.1:1080', 'HTTP://proxy.example:80/', 'http://[::1]:8080']) {
      expect(manualUrlError(url), url).toBeNull()
    }
  })

  it('refuses what ScrapeProxy would refuse, before anything is written', () => {
    for (const url of ['', 'proxy.example:8080', 'https://proxy.example:443', 'http://proxy.example',
      'http://proxy.example:0', 'http://proxy.example:65536', 'http://u:p@proxy.example:8080',
      'http://proxy.example:8080/path', 'http://proxy.example:8080?x=1', 'http://my_proxy:8080']) {
      expect(manualUrlError(url), url).not.toBeNull()
    }
  })

  it('refuses control characters in the credentials', () => {
    expect(validateManual({ url: 'http://proxy.example:8080', username: 'a\nb' }, 'p\u0000w'))
      .toEqual({ username: expect.any(String), password: expect.any(String) })
    expect(credentialError('', true, 'password')).not.toBeNull()
  })
})

describe('planProxyWrites', () => {
  const none = { url: '', username: '', hasPassword: false, enabled: true }

  it('writes an http URL before its credentials', () => {
    expect(planProxyWrites({ url: 'http://gw.dataimpulse.com:823', username: 'abc' }, 'pw', none)).toEqual([
      { field: 'url', value: 'http://gw.dataimpulse.com:823' },
      { field: 'username', value: 'abc' },
      { field: 'password', value: 'pw' },
    ])
  })

  it('clears stored credentials before a socks5 URL, whatever was typed', () => {
    const stored = { url: 'http://gw.dataimpulse.com:823', username: 'abc', hasPassword: true, enabled: true }
    expect(planProxyWrites({ url: 'socks5://127.0.0.1:1080', username: 'typed' }, 'typed', stored)).toEqual([
      { field: 'username', value: '' },
      { field: 'password', value: '' },
      { field: 'url', value: 'socks5://127.0.0.1:1080' },
    ])
  })

  it('writes only what changed on the same host, and a blank password keeps the stored one', () => {
    const stored = { url: 'http://gw.dataimpulse.com:823', username: 'abc', hasPassword: true, enabled: true }
    expect(planProxyWrites({ url: 'http://gw.dataimpulse.com:10000', username: 'abc__cr.de' }, '', stored)).toEqual([
      { field: 'url', value: 'http://gw.dataimpulse.com:10000' },
      { field: 'username', value: 'abc__cr.de' },
    ])
    expect(keepsStoredPassword('http://gw.dataimpulse.com:10000', stored)).toBe(true)
  })

  it('never carries the stored credentials to another host: they are cleared before the URL moves', () => {
    const stored = { url: 'http://gw.dataimpulse.com:823', username: 'abc', hasPassword: true, enabled: true }
    expect(planProxyWrites({ url: 'http://proxy.example:3128', username: 'scraper' }, '', stored)).toEqual([
      { field: 'username', value: '' },
      { field: 'password', value: '' },
      { field: 'url', value: 'http://proxy.example:3128' },
      { field: 'username', value: 'scraper' },
    ])
    expect(keepsStoredPassword('http://proxy.example:3128', stored)).toBe(false)
  })

  it('treats a switch between the gateway\'s name and its address as another host', () => {
    const stored = { url: 'http://gw.dataimpulse.com:823', username: 'abc', hasPassword: true, enabled: true }
    expect(planProxyWrites({ url: 'http://74.81.81.81:823', username: 'abc' }, '', stored)).toEqual([
      { field: 'username', value: '' },
      { field: 'password', value: '' },
      { field: 'url', value: 'http://74.81.81.81:823' },
      { field: 'username', value: 'abc' },
    ])
  })

  it('writes the password as typed', () => {
    expect(planProxyWrites({ url: 'http://proxy.example:3128', username: '' }, ' p w ', none))
      .toContainEqual({ field: 'password', value: ' p w ' })
  })

  it('switches a proxy that was off back on, after its credentials', () => {
    const off = { url: 'http://proxy.example:3128', username: 'scraper', hasPassword: true, enabled: false }
    expect(planProxyWrites({ url: 'http://proxy.example:3128', username: 'scraper' }, 'new', off)).toEqual([
      { field: 'password', value: 'new' },
      { field: 'enabled', value: 'true' },
    ])
    expect(planProxyWrites({ url: '', username: null }, '', off)).toEqual([{ field: 'url', value: '' }])
  })

  it('None writes the URL alone', () => {
    const stored = { url: 'http://gw.dataimpulse.com:823', username: 'abc', hasPassword: true, enabled: true }
    expect(planProxyWrites({ url: '', username: null }, '', stored)).toEqual([{ field: 'url', value: '' }])
  })
})
