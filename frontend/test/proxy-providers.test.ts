import { describe, it, expect } from 'vitest'
import {
  composeDataImpulse,
  composeProxy,
  credentialError,
  emptyDataImpulse,
  keepsStoredPassword,
  manualUrlError,
  emptyStoredPlans,
  parseProxy,
  planProxyWrites,
  planRecord,
  planUsable,
  proxyKey,
  validateDataImpulse,
  validateManual,
  type DataImpulseFields,
  type StoredPlans,
} from '~/utils/proxy-providers'

function dataImpulse(over: Partial<DataImpulseFields> = {}): DataImpulseFields {
  return { ...emptyDataImpulse(), plan: 'residential', logins: planRecord(p => (p === 'residential' ? 'abc' : '')), ...over }
}

function plans(over: Partial<StoredPlans> = {}): StoredPlans {
  return { ...emptyStoredPlans(), plan: 'residential', logins: planRecord(p => (p === 'residential' ? 'abc' : '')), ...over }
}

describe('proxyKey', () => {
  it('keeps the generic keys and puts the plan keys under the DataImpulse prefix', () => {
    expect(proxyKey('url')).toBe('web_scrape.proxy.url')
    expect(proxyKey('password')).toBe('web_scrape.proxy.password')
    expect(proxyKey('plan')).toBe('web_scrape.proxy.dataimpulse.plan')
    expect(proxyKey('targeting')).toBe('web_scrape.proxy.dataimpulse.targeting')
    expect(proxyKey('premium-residential.password')).toBe('web_scrape.proxy.dataimpulse.premium-residential.password')
  })
})

describe('composeDataImpulse', () => {
  it('rotating with any country is the active plan\'s login alone on port 823', () => {
    expect(composeDataImpulse(dataImpulse()))
      .toEqual({ url: 'http://gw.dataimpulse.com:823', targeting: '', username: 'abc' })
  })

  it('uses the chosen plan\'s login, not another plan\'s', () => {
    const logins = planRecord(p => `${p}-login`)
    expect(composeDataImpulse(dataImpulse({ plan: 'mobile', logins, countries: 'de' })).username).toBe('mobile-login__cr.de')
    expect(composeDataImpulse(dataImpulse({ plan: '', logins, countries: 'de' })))
      .toMatchObject({ targeting: 'cr.de', username: '' })
  })

  it('puts the countries in the targeting, lower-cased and without spaces', () => {
    expect(composeDataImpulse(dataImpulse({ countries: 'DE, au' }))).toMatchObject({ targeting: 'cr.de,au', username: 'abc__cr.de,au' })
  })

  it('sticky goes to port 10000 with the session length', () => {
    expect(composeDataImpulse(dataImpulse({ countries: 'us', rotation: 'sticky', sessionMinutes: '45' })))
      .toEqual({ url: 'http://gw.dataimpulse.com:10000', targeting: 'cr.us;sessttl.45', username: 'abc__cr.us;sessttl.45' })
  })

  it('sticky with no session length leaves DataImpulse its default', () => {
    expect(composeDataImpulse(dataImpulse({ rotation: 'sticky' })).targeting).toBe('')
  })

  it('rotating drops a session length, which only a sticky IP has', () => {
    expect(composeDataImpulse(dataImpulse({ sessionMinutes: '45' })).targeting).toBe('')
  })

  it('keeps a stored sticky port, and falls back to 10000 outside the sticky range', () => {
    expect(composeDataImpulse(dataImpulse({ rotation: 'sticky', stickyPort: 12345 })).url)
      .toBe('http://gw.dataimpulse.com:12345')
    expect(composeDataImpulse(dataImpulse({ rotation: 'sticky', stickyPort: 9000 })).url)
      .toBe('http://gw.dataimpulse.com:10000')
  })

  it('connects to the IP gateway on the same ports when it is chosen', () => {
    expect(composeDataImpulse(dataImpulse({ gateway: 'ip' })).url).toBe('http://74.81.81.81:823')
    expect(composeDataImpulse(dataImpulse({ gateway: 'ip', rotation: 'sticky', stickyPort: 12345 })).url)
      .toBe('http://74.81.81.81:12345')
  })
})

describe('parseProxy', () => {
  it('reads a rotating DataImpulse config back into its card, with every plan\'s login', () => {
    const logins = planRecord(p => `${p}-login`)
    const parsed = parseProxy('http://gw.dataimpulse.com:823', '', plans({ plan: 'mobile', logins, targeting: 'cr.de,au' }))
    expect(parsed.provider).toBe('dataimpulse')
    expect(parsed.dataimpulse).toMatchObject({ plan: 'mobile', logins, countries: 'de,au', rotation: 'rotating', extraParams: [] })
  })

  it('reads a sticky DataImpulse config back, with its port and session length', () => {
    const parsed = parseProxy('http://gw.dataimpulse.com:10000', '', plans({ targeting: 'cr.us;sessttl.45' }))
    expect(parsed.provider).toBe('dataimpulse')
    expect(parsed.dataimpulse).toMatchObject({ countries: 'us', rotation: 'sticky', sessionMinutes: '45', stickyPort: 10000 })
  })

  it('recognizes the gateway whatever the host\'s case', () => {
    expect(parseProxy('http://GW.DataImpulse.com:823/', '').provider).toBe('dataimpulse')
  })

  it('reads a config on the IP gateway back into the DataImpulse card', () => {
    const parsed = parseProxy('http://74.81.81.81:823', '', plans({ targeting: 'cr.de' }))
    expect(parsed.provider).toBe('dataimpulse')
    expect(parsed.dataimpulse).toMatchObject({ gateway: 'ip', countries: 'de', rotation: 'rotating' })
    expect(parseProxy('http://gw.dataimpulse.com:823', '').dataimpulse.gateway).toBe('hostname')
  })

  it('shows any other proxy in the Manual card with its raw fields, keeping the plans for the DataImpulse card', () => {
    const parsed = parseProxy('http://proxy.example:3128', 'scraper', plans({ plan: 'mobile', targeting: 'cr.de;anon.1' }))
    expect(parsed.provider).toBe('manual')
    expect(parsed.manual).toEqual({ url: 'http://proxy.example:3128', username: 'scraper' })
    expect(parsed.dataimpulse).toMatchObject({ plan: 'mobile', logins: { residential: 'abc' }, countries: 'de', extraParams: ['anon.1'] })
  })

  it('leaves a DataImpulse URL the card cannot write to the Manual card', () => {
    // socks5 would lose its scheme, and 824 or 9999 their port, on the next save.
    for (const url of ['socks5://gw.dataimpulse.com:824', 'http://gw.dataimpulse.com:824', 'http://gw.dataimpulse.com:9999']) {
      expect(parseProxy(url, '').provider, url).toBe('manual')
    }
  })

  it('an empty URL is None, with the unused credentials still in the cards', () => {
    const parsed = parseProxy('', 'abc__cr.de', plans())
    expect(parsed.provider).toBe('none')
    expect(parsed.dataimpulse.logins.residential).toBe('abc')
    expect(parsed.manual.username).toBe('abc__cr.de')
  })
})

describe('parse and compose round-trip', () => {
  const stored: Array<[string, string]> = [
    ['http://gw.dataimpulse.com:823', ''],
    ['http://gw.dataimpulse.com:823', 'cr.de,au'],
    ['http://gw.dataimpulse.com:10000', 'cr.us;sessttl.45'],
    ['http://gw.dataimpulse.com:15000', 'sessttl.5'],
    ['http://gw.dataimpulse.com:823', 'cr.us;anon.1'],
    // A session length on the rotating port is kept as it is, not dropped as an unused field.
    ['http://gw.dataimpulse.com:823', 'cr.us;sessttl.45'],
    ['http://74.81.81.81:10000', 'cr.us;sessttl.45'],
  ]
  for (const [url, targeting] of stored) {
    it(`${url} ${targeting}`, () => {
      const parsed = parseProxy(url, '', plans({ targeting }))
      expect(composeProxy(parsed.provider, parsed)).toEqual({
        url, username: null, dataimpulse: { plan: 'residential', targeting, logins: plans().logins },
      })
    })
  }

  it('keeps targeting parameters the panel does not model when a field changes', () => {
    const parsed = parseProxy('http://gw.dataimpulse.com:823', '', plans({ targeting: 'cr.us;anon.1;state.ny' }))
    expect(parsed.dataimpulse.extraParams).toEqual(['anon.1', 'state.ny'])
    parsed.dataimpulse.countries = 'de'
    expect(composeProxy('dataimpulse', parsed).dataimpulse?.targeting).toBe('cr.de;anon.1;state.ny')
  })

  it('a session length typed for a sticky IP replaces one kept from a rotating config', () => {
    const parsed = parseProxy('http://gw.dataimpulse.com:823', '', plans({ targeting: 'sessttl.45' }))
    expect(parsed.dataimpulse).toMatchObject({ sessionMinutes: '', extraParams: ['sessttl.45'] })
    Object.assign(parsed.dataimpulse, { rotation: 'sticky', sessionMinutes: '10' })
    expect(composeProxy('dataimpulse', parsed).dataimpulse?.targeting).toBe('sessttl.10')
  })

  it('None clears the URL and leaves the credentials alone', () => {
    expect(composeProxy('none', parseProxy('http://gw.dataimpulse.com:823', '', plans()))).toEqual({ url: '', username: null })
  })
})

describe('validateDataImpulse', () => {
  const saved = { residential: true }

  it('accepts a chosen plan with a login and a password, and a country', () => {
    expect(validateDataImpulse(dataImpulse({ countries: 'de,au' }), saved)).toEqual({})
  })

  it('names each bad field', () => {
    expect(validateDataImpulse(dataImpulse({ plan: '', countries: 'germany' }), saved)).toEqual({
      plan: expect.any(String),
      countries: expect.any(String),
    })
  })

  it('refuses a plan without both a login and a password, naming it', () => {
    expect(validateDataImpulse(dataImpulse(), {}).plan).toContain('Residential')
    expect(validateDataImpulse(dataImpulse({ logins: planRecord(() => '') }), saved).plan).toContain('Residential')
    expect(planUsable(dataImpulse(), 'residential', saved)).toBe(true)
    expect(planUsable(dataImpulse(), 'residential', {})).toBe(false)
    expect(planUsable(dataImpulse(), 'mobile', { mobile: true })).toBe(false)
  })

  it('refuses a session length outside 1 to 120 minutes on a sticky IP', () => {
    for (const minutes of ['0', '200', '1.5', 'ten']) {
      expect(validateDataImpulse(dataImpulse({ rotation: 'sticky', sessionMinutes: minutes }), saved).sessionMinutes, minutes)
        .toBeDefined()
    }
    for (const minutes of ['', '1', '120']) {
      expect(validateDataImpulse(dataImpulse({ rotation: 'sticky', sessionMinutes: minutes }), saved), minutes).toEqual({})
    }
  })

  it('checks every plan\'s login, in use or not', () => {
    for (const bad of ['abc__cr.de', 'abc def', 'a:b', 'a;b', 'a@b', 'abc_', 'a\u0007b']) {
      const logins = { ...plans().logins, datacenter: bad }
      expect(validateDataImpulse(dataImpulse({ logins }), saved)['datacenter.login'], bad).toBeDefined()
    }
    for (const ok of ['a_bc', '_abc', 'abc-1', 'a.b', '']) {
      const logins = { ...plans().logins, datacenter: ok }
      expect(validateDataImpulse(dataImpulse({ logins }), saved), ok).toEqual({})
    }
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
  const gateway = 'http://gw.dataimpulse.com:823'
  const withPlans = (over: Partial<StoredPlans> = {}) => ({ url: gateway, username: '', hasPassword: false, enabled: true, dataimpulse: plans({ hasPassword: planRecord(p => p === 'residential'), ...over }) })
  const target = (over: Partial<StoredPlans> = {}) => {
    const p = plans(over)
    return { url: gateway, username: null, dataimpulse: { plan: p.plan, targeting: p.targeting, logins: p.logins } }
  }

  it('writes the plan credentials first, then the URL, targeting, plan and switch', () => {
    const off = { url: '', username: '', hasPassword: false, enabled: false, dataimpulse: emptyStoredPlans() }
    expect(planProxyWrites(target({ plan: 'mobile', targeting: 'cr.de', logins: planRecord(p => (p === 'mobile' ? 'm' : '')) }), '', off, { mobile: 'mp' })).toEqual([
      { field: 'mobile.login', value: 'm' },
      { field: 'mobile.password', value: 'mp' },
      { field: 'url', value: gateway },
      { field: 'targeting', value: 'cr.de' },
      { field: 'plan', value: 'mobile' },
      { field: 'enabled', value: 'true' },
    ])
  })

  it('an untouched save writes nothing, and a blank plan password keeps the stored one', () => {
    expect(planProxyWrites(target(), '', withPlans(), { residential: '  ' })).toEqual([])
  })

  it('switching the plan writes the plan and nothing else', () => {
    const logins = planRecord(p => `${p}-login`)
    expect(planProxyWrites(target({ plan: 'datacenter', logins }), '', withPlans({ logins }))).toEqual([
      { field: 'plan', value: 'datacenter' },
    ])
  })

  it('never writes the generic username for DataImpulse', () => {
    const stored = { ...withPlans(), username: 'legacy' }
    expect(planProxyWrites(target(), '', stored).map(w => w.field)).not.toContain('username')
  })

  it('a Manual save leaves the plan keys alone', () => {
    const writes = planProxyWrites({ url: 'http://proxy.example:3128', username: 'scraper' }, 'pw', withPlans())
    expect(writes.map(w => w.field)).toEqual(['url', 'username', 'password'])
  })
})
