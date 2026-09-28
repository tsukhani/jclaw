/**
 * Proxy provider presets for Settings > Proxy Providers (JCLAW-1323).
 *
 * The four web_scrape.proxy.* keys stay the only stored state: a preset composes the URL and
 * username from its own fields and parses them back, recognizing its provider from the URL's host,
 * so the backend and every scrape rung read the same keys they always did.
 */

export type ProxyProviderId = 'none' | 'dataimpulse' | 'manual'
export type ProxyRotation = 'rotating' | 'sticky'

/** The stored keys a save writes, named by their suffix under web_scrape.proxy. */
export type ProxyField = 'url' | 'username' | 'password' | 'enabled'

// HTTP only: ScrapeProxy refuses credentials on socks5, and an HTTP proxy serves all three rungs.
export const DATAIMPULSE = {
  host: 'gw.dataimpulse.com',
  rotatingPort: 823,
  stickyPortMin: 10000,
  stickyPortMax: 20000,
  sessionMinutesMin: 1,
  sessionMinutesMax: 120,
  sessionMinutesDefault: 30,
  dashboardUrl: 'https://app.dataimpulse.com/',
} as const

export interface DataImpulseFields {
  login: string
  /** Comma-separated two-letter country codes; empty targets any country. */
  countries: string
  rotation: ProxyRotation
  /** Minutes a sticky IP is kept, as typed; empty leaves DataImpulse's own 30-minute default. */
  sessionMinutes: string
  /** Kept from a stored config, since each sticky port holds its own IP. */
  stickyPort: number
  /** Username parameters the panel does not model, kept verbatim and in order. */
  extraParams: string[]
}

export interface ManualProxyFields {
  url: string
  username: string
}

export interface ParsedProxy {
  provider: ProxyProviderId
  dataimpulse: DataImpulseFields
  manual: ManualProxyFields
}

/** What a save writes. A null username leaves the stored one as it is. */
export interface ComposedProxy {
  url: string
  username: string | null
}

export type DataImpulseErrors = Partial<Record<'login' | 'countries' | 'sessionMinutes', string>>
export type ManualErrors = Partial<Record<'url' | 'username' | 'password', string>>

// DataImpulse puts targeting after the login: login__cr.de,au;sessttl.30
const PARAMS_SEPARATOR = '__'
const COUNTRY_PARAM = 'cr.'
const SESSION_PARAM = 'sessttl.'

export function emptyDataImpulse(): DataImpulseFields {
  return { login: '', countries: '', rotation: 'rotating', sessionMinutes: '', stickyPort: DATAIMPULSE.stickyPortMin, extraParams: [] }
}

function isStickyPort(port: number): boolean {
  return Number.isInteger(port) && port >= DATAIMPULSE.stickyPortMin && port <= DATAIMPULSE.stickyPortMax
}

/** The port of a URL the DataImpulse card can show, or null when the Manual card must. */
function dataImpulsePort(url: string): number | null {
  let parsed: URL
  try {
    parsed = new URL(url)
  }
  catch {
    return null
  }
  if (parsed.protocol !== 'http:' || parsed.hostname.toLowerCase() !== DATAIMPULSE.host) return null
  if (parsed.username || parsed.password || parsed.search || !['', '/'].includes(parsed.pathname)) return null
  const port = Number(parsed.port)
  return port === DATAIMPULSE.rotatingPort || isStickyPort(port) ? port : null
}

// A session length means nothing on the rotating port, so there it stays an unmodeled parameter.
function parseDataImpulseUsername(username: string, sticky: boolean): Pick<DataImpulseFields, 'login' | 'countries' | 'sessionMinutes' | 'extraParams'> {
  const trimmed = username.trim()
  const at = trimmed.indexOf(PARAMS_SEPARATOR)
  const login = at < 0 ? trimmed : trimmed.slice(0, at)
  const params = at < 0 ? [] : trimmed.slice(at + PARAMS_SEPARATOR.length).split(';').map(p => p.trim()).filter(Boolean)
  let countries = ''
  let sessionMinutes = ''
  const extraParams: string[] = []
  for (const param of params) {
    if (!countries && param.startsWith(COUNTRY_PARAM) && param.length > COUNTRY_PARAM.length) {
      countries = param.slice(COUNTRY_PARAM.length)
    }
    else if (sticky && !sessionMinutes && /^sessttl\.\d+$/.test(param)) {
      sessionMinutes = param.slice(SESSION_PARAM.length)
    }
    else {
      extraParams.push(param)
    }
  }
  return { login, countries, sessionMinutes, extraParams }
}

/** Which card the stored keys belong to, with each card's fields filled from them. */
export function parseProxy(url: string, username: string): ParsedProxy {
  const storedUrl = url.trim()
  const manual = { url: storedUrl, username: username.trim() }
  const port = storedUrl ? dataImpulsePort(storedUrl) : null
  if (storedUrl && port === null) return { provider: 'manual', dataimpulse: emptyDataImpulse(), manual }
  // With no URL the credentials stay stored but unused, so either card may take them back up.
  const sticky = port !== null && port !== DATAIMPULSE.rotatingPort
  const dataimpulse: DataImpulseFields = {
    ...parseDataImpulseUsername(username, sticky),
    rotation: sticky ? 'sticky' : 'rotating',
    stickyPort: sticky ? port : DATAIMPULSE.stickyPortMin,
  }
  return { provider: storedUrl ? 'dataimpulse' : 'none', dataimpulse, manual }
}

function normalizeCountries(countries: string): string {
  return countries.split(',').map(c => c.trim().toLowerCase()).filter(Boolean).join(',')
}

export function composeDataImpulse(fields: DataImpulseFields): { url: string, username: string } {
  const sticky = fields.rotation === 'sticky'
  const port = !sticky ? DATAIMPULSE.rotatingPort : isStickyPort(fields.stickyPort) ? fields.stickyPort : DATAIMPULSE.stickyPortMin
  const params: string[] = []
  const countries = normalizeCountries(fields.countries)
  if (countries) params.push(COUNTRY_PARAM + countries)
  const minutes = sticky ? fields.sessionMinutes.trim() : ''
  if (minutes) params.push(SESSION_PARAM + Number(minutes))
  // The session field wins over a length kept from a rotating config.
  params.push(...fields.extraParams.filter(p => !(minutes && p.startsWith(SESSION_PARAM))))
  const login = fields.login.trim()
  return {
    url: `http://${DATAIMPULSE.host}:${port}`,
    username: params.length ? login + PARAMS_SEPARATOR + params.join(';') : login,
  }
}

export function composeProxy(provider: ProxyProviderId, fields: Omit<ParsedProxy, 'provider'>): ComposedProxy {
  switch (provider) {
    case 'none':
      return { url: '', username: null }
    case 'dataimpulse':
      return composeDataImpulse(fields.dataimpulse)
    case 'manual':
      return { url: fields.manual.url.trim(), username: fields.manual.username.trim() }
  }
}

/** Checks the fields the DataImpulse card composes; an empty result means it can be saved. */
export function validateDataImpulse(fields: DataImpulseFields): DataImpulseErrors {
  const errors: DataImpulseErrors = {}
  const login = fields.login.trim()
  if (!login) errors.login = 'Enter your DataImpulse proxy login.'
  else if (login.includes(PARAMS_SEPARATOR) || /[\s:;@\p{Cc}]/u.test(login)) {
    errors.login = 'Enter the login alone, as DataImpulse shows it; the country and session have their own fields.'
  }
  // abc_ would compose as abc___cr.de, which reads back as the login abc.
  else if (login.endsWith('_')) errors.login = 'A DataImpulse login cannot end in an underscore.'
  const codes = fields.countries.split(',').map(c => c.trim()).filter(Boolean)
  if (codes.some(c => !/^[a-z]{2}$/i.test(c)) || (fields.countries.trim() && !codes.length)) {
    errors.countries = 'Use two-letter country codes separated by commas, such as de or de,au.'
  }
  const minutes = fields.sessionMinutes.trim()
  if (fields.rotation === 'sticky' && minutes) {
    const n = Number(minutes)
    if (!/^\d+$/.test(minutes) || n < DATAIMPULSE.sessionMinutesMin || n > DATAIMPULSE.sessionMinutesMax) {
      errors.sessionMinutes = `Keep an IP for a whole number of minutes from ${DATAIMPULSE.sessionMinutesMin} to ${DATAIMPULSE.sessionMinutesMax}.`
    }
  }
  return errors
}

/** A credential the backend would refuse, or a missing one the save needs; null when it can be written. */
export function credentialError(value: string, required: boolean, label: string): string | null {
  if (required && !value.trim()) return `Enter the ${label}.`
  return /\p{Cc}/u.test(value) ? `The ${label} cannot contain control characters.` : null
}

const URL_PARTS = /^([a-z][a-z0-9+.-]*):\/\/([^/?#]*)(.*)$/i
// java.net.URI finds no host in a name with an underscore, so ScrapeProxy would refuse it.
const HOST_AND_PORT = /^(?:\[[0-9a-f:.]+\]|[a-z0-9.-]+):(\d+)$/i

/** What ScrapeProxy.urlRejection would refuse in a Manual URL, checked before anything is written. */
export function manualUrlError(url: string): string | null {
  const value = url.trim()
  if (!value) return 'Enter the proxy URL, or choose None to connect directly.'
  const parts = URL_PARTS.exec(value)
  if (!parts) return 'Use http://host:port or socks5://host:port.'
  const [, scheme = '', authority = '', rest = ''] = parts
  if (!['http', 'socks5'].includes(scheme.toLowerCase())) return 'The URL must start with http:// or socks5://.'
  if (authority.includes('@')) return 'Put the credentials in the username and password, not in the URL.'
  const port = HOST_AND_PORT.exec(authority)?.[1]
  if (!port) return 'The URL needs a host and a port, such as http://proxy.example:8080.'
  if (Number(port) < 1 || Number(port) > 65535) return 'The port must be from 1 to 65535.'
  if (rest !== '' && rest !== '/') return 'The URL takes only a scheme, host and port.'
  return null
}

export function validateManual(fields: ManualProxyFields, password: string): ManualErrors {
  const errors: ManualErrors = {}
  const url = manualUrlError(fields.url)
  if (url) errors.url = url
  const username = credentialError(fields.username, false, 'username')
  if (username) errors.username = username
  const pass = credentialError(password, false, 'password')
  if (pass) errors.password = pass
  return errors
}

export interface StoredProxy {
  url: string
  username: string
  hasPassword: boolean
  enabled: boolean
}

/** The URL's host, lower-cased, or '' when it has none. */
export function proxyHost(url: string): string {
  const authority = URL_PARTS.exec(url.trim())?.[2] ?? ''
  return authority.replace(/:\d*$/, '').toLowerCase()
}

/** Whether a save to `targetUrl` keeps the stored password, which never follows the proxy to another host. */
export function keepsStoredPassword(targetUrl: string, stored: StoredProxy): boolean {
  const host = proxyHost(targetUrl)
  return stored.hasPassword && host !== '' && host === proxyHost(stored.url)
}

/**
 * The config writes that take the stored keys to `target`, in an order the backend's cross-key checks
 * accept and that never hands one host another's credentials: the stored credentials are cleared
 * first for a socks5 URL, which ScrapeProxy refuses while any are stored, and for a URL on another
 * host; otherwise the URL goes before its credentials. A blank `password` keeps the stored one on the
 * same host. A save that leaves a proxy set switches it on, last, once its credentials are in place.
 */
export function planProxyWrites(target: ComposedProxy, password: string, stored: StoredProxy): Array<{ field: ProxyField, value: string }> {
  const url = target.url.trim()
  const socks = /^socks5:/i.test(url)
  const hostChanges = url !== '' && proxyHost(url) !== proxyHost(stored.url)
  const writes: Array<{ field: ProxyField, value: string }> = []
  if (socks || hostChanges) {
    if (stored.username.trim()) writes.push({ field: 'username', value: '' })
    if (stored.hasPassword) writes.push({ field: 'password', value: '' })
  }
  if (url !== stored.url.trim()) writes.push({ field: 'url', value: url })
  if (!socks) {
    const username = target.username?.trim() ?? null
    const storedUsername = hostChanges ? '' : stored.username.trim()
    if (username !== null && username !== storedUsername) writes.push({ field: 'username', value: username })
    if (password.trim()) writes.push({ field: 'password', value: password })
  }
  if (url && !stored.enabled) writes.push({ field: 'enabled', value: 'true' })
  return writes
}
