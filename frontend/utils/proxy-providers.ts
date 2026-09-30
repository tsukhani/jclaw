/**
 * Proxy provider presets for Settings > Proxy Providers (JCLAW-1323).
 *
 * The web_scrape.proxy.* keys stay the only stored state: a preset composes the URL from its own
 * fields and parses it back, recognizing its provider from the URL's host. DataImpulse keeps a login
 * and password per plan type under web_scrape.proxy.dataimpulse.* (JCLAW-1334), and the backend
 * resolves the active plan's credentials itself, so every scrape rung still reads one place.
 */

export type ProxyProviderId = 'none' | 'dataimpulse' | 'manual'
export type ProxyRotation = 'rotating' | 'sticky'
export type DataImpulseGateway = 'hostname' | 'ip'

export type DataImpulsePlanId = 'residential' | 'premium-residential' | 'mobile' | 'datacenter'

/** DataImpulse's plan types, in its dashboard's order; each has its own login and password. */
export const DATAIMPULSE_PLANS: ReadonlyArray<{ id: DataImpulsePlanId, label: string }> = [
  { id: 'residential', label: 'Residential' },
  { id: 'premium-residential', label: 'Premium Residential' },
  { id: 'mobile', label: 'Mobile' },
  { id: 'datacenter', label: 'Datacenter' },
]

type PlanRecord<T> = Record<DataImpulsePlanId, T>
export type PlanCredentialField = `${DataImpulsePlanId}.${'login' | 'password'}`

/** The stored keys a save writes, named by their suffix under web_scrape.proxy, or under web_scrape.proxy.dataimpulse for a plan's. */
export type ProxyField = 'url' | 'username' | 'password' | 'enabled' | 'plan' | 'targeting' | PlanCredentialField

const GENERIC_FIELDS: ReadonlySet<ProxyField> = new Set(['url', 'username', 'password', 'enabled'])

export function proxyKey(field: ProxyField): string {
  return GENERIC_FIELDS.has(field) ? `web_scrape.proxy.${field}` : `web_scrape.proxy.dataimpulse.${field}`
}

export function planRecord<T>(value: (plan: DataImpulsePlanId) => T): PlanRecord<T> {
  return Object.fromEntries(DATAIMPULSE_PLANS.map(p => [p.id, value(p.id)])) as PlanRecord<T>
}

// HTTP only: ScrapeProxy refuses credentials on socks5, and an HTTP proxy serves all three rungs.
export const DATAIMPULSE = {
  host: 'gw.dataimpulse.com',
  // DataImpulse's Connection Hosts page offers it for networks that cannot use the hostname, and warns it may change.
  ipHost: '74.81.81.81',
  rotatingPort: 823,
  stickyPortMin: 10000,
  stickyPortMax: 20000,
  sessionMinutesMin: 1,
  sessionMinutesMax: 120,
  sessionMinutesDefault: 30,
  dashboardUrl: 'https://app.dataimpulse.com/',
} as const

export interface DataImpulseFields {
  gateway: DataImpulseGateway
  /** The plan whose credentials the proxy uses; '' until one is chosen. */
  plan: DataImpulsePlanId | ''
  logins: PlanRecord<string>
  /** Comma-separated two-letter country codes; empty targets any country. */
  countries: string
  rotation: ProxyRotation
  /** Minutes a sticky IP is kept, as typed; empty leaves DataImpulse's own 30-minute default. */
  sessionMinutes: string
  /** Kept from a stored config, since each sticky port holds its own IP. */
  stickyPort: number
  /** Targeting parameters the panel does not model, kept verbatim and in order. */
  extraParams: string[]
}

/** What is stored for the plans; a password reads back masked, so only whether one is saved is known. */
export interface StoredPlans {
  plan: DataImpulsePlanId | ''
  targeting: string
  logins: PlanRecord<string>
  hasPassword: PlanRecord<boolean>
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

/** What a save writes. A null username leaves the stored one as it is; dataimpulse carries the plan keys. */
export interface ComposedProxy {
  url: string
  username: string | null
  dataimpulse?: { plan: DataImpulsePlanId | '', targeting: string, logins: PlanRecord<string> }
}

export type DataImpulseErrors = Partial<Record<'plan' | 'countries' | 'sessionMinutes' | PlanCredentialField, string>>
export type ManualErrors = Partial<Record<'url' | 'username' | 'password', string>>

// DataImpulse puts targeting after the login: login__cr.de,au;sessttl.30
const PARAMS_SEPARATOR = '__'
const COUNTRY_PARAM = 'cr.'
const SESSION_PARAM = 'sessttl.'

export function emptyDataImpulse(): DataImpulseFields {
  return { gateway: 'hostname', plan: '', logins: planRecord(() => ''), countries: '', rotation: 'rotating', sessionMinutes: '', stickyPort: DATAIMPULSE.stickyPortMin, extraParams: [] }
}

export function emptyStoredPlans(): StoredPlans {
  return { plan: '', targeting: '', logins: planRecord(() => ''), hasPassword: planRecord(() => false) }
}

export function isPlanId(value: string): value is DataImpulsePlanId {
  return DATAIMPULSE_PLANS.some(p => p.id === value)
}

function isStickyPort(port: number): boolean {
  return Number.isInteger(port) && port >= DATAIMPULSE.stickyPortMin && port <= DATAIMPULSE.stickyPortMax
}

function gatewayHost(gateway: DataImpulseGateway): string {
  return gateway === 'ip' ? DATAIMPULSE.ipHost : DATAIMPULSE.host
}

/** The gateway and port of a URL the DataImpulse card can show, or null when the Manual card must. */
function dataImpulseEndpoint(url: string): { gateway: DataImpulseGateway, port: number } | null {
  let parsed: URL
  try {
    parsed = new URL(url)
  }
  catch {
    return null
  }
  const host = parsed.hostname.toLowerCase()
  const gateway = (['hostname', 'ip'] as const).find(g => gatewayHost(g) === host) ?? null
  if (parsed.protocol !== 'http:' || gateway === null) return null
  if (parsed.username || parsed.password || parsed.search || !['', '/'].includes(parsed.pathname)) return null
  const port = Number(parsed.port)
  return port === DATAIMPULSE.rotatingPort || isStickyPort(port) ? { gateway, port } : null
}

// A session length means nothing on the rotating port, so there it stays an unmodeled parameter.
function parseTargeting(targeting: string, sticky: boolean): Pick<DataImpulseFields, 'countries' | 'sessionMinutes' | 'extraParams'> {
  const params = targeting.split(';').map(p => p.trim()).filter(Boolean)
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
  return { countries, sessionMinutes, extraParams }
}

/** Which card the stored keys belong to, with each card's fields filled from them. */
export function parseProxy(url: string, username: string, plans: StoredPlans = emptyStoredPlans()): ParsedProxy {
  const storedUrl = url.trim()
  const manual = { url: storedUrl, username: username.trim() }
  const endpoint = storedUrl ? dataImpulseEndpoint(storedUrl) : null
  // Under None or Manual the plans and targeting stay stored but unused, so the DataImpulse card takes them back up.
  const sticky = endpoint !== null && endpoint.port !== DATAIMPULSE.rotatingPort
  const dataimpulse: DataImpulseFields = {
    ...parseTargeting(plans.targeting, sticky),
    plan: plans.plan,
    logins: { ...plans.logins },
    gateway: endpoint?.gateway ?? 'hostname',
    rotation: sticky ? 'sticky' : 'rotating',
    stickyPort: sticky ? endpoint.port : DATAIMPULSE.stickyPortMin,
  }
  if (!storedUrl) return { provider: 'none', dataimpulse, manual }
  return { provider: endpoint === null ? 'manual' : 'dataimpulse', dataimpulse, manual }
}

function normalizeCountries(countries: string): string {
  return countries.split(',').map(c => c.trim().toLowerCase()).filter(Boolean).join(',')
}

/** The gateway URL, the shared targeting, and the username the active plan connects with. */
export function composeDataImpulse(fields: DataImpulseFields): { url: string, targeting: string, username: string } {
  const sticky = fields.rotation === 'sticky'
  const stickyPort = isStickyPort(fields.stickyPort) ? fields.stickyPort : DATAIMPULSE.stickyPortMin
  const port = sticky ? stickyPort : DATAIMPULSE.rotatingPort
  const params: string[] = []
  const countries = normalizeCountries(fields.countries)
  if (countries) params.push(COUNTRY_PARAM + countries)
  const minutes = sticky ? fields.sessionMinutes.trim() : ''
  if (minutes) params.push(SESSION_PARAM + Number(minutes))
  // The session field wins over a length kept from a rotating config.
  params.push(...fields.extraParams.filter(p => !(minutes && p.startsWith(SESSION_PARAM))))
  const targeting = params.join(';')
  const login = fields.plan ? fields.logins[fields.plan].trim() : ''
  return {
    url: `http://${gatewayHost(fields.gateway)}:${port}`,
    targeting,
    username: targeting && login ? login + PARAMS_SEPARATOR + targeting : login,
  }
}

export function composeProxy(provider: ProxyProviderId, fields: Omit<ParsedProxy, 'provider'>): ComposedProxy {
  switch (provider) {
    case 'none':
      return { url: '', username: null }
    case 'dataimpulse': {
      const { url, targeting } = composeDataImpulse(fields.dataimpulse)
      const { plan, logins } = fields.dataimpulse
      return { url, username: null, dataimpulse: { plan, targeting, logins: planRecord(p => logins[p].trim()) } }
    }
    case 'manual':
      return { url: fields.manual.url.trim(), username: fields.manual.username.trim() }
  }
}

function planLoginError(login: string): string | null {
  if (login.includes(PARAMS_SEPARATOR) || /[\s:;@\p{Cc}]/u.test(login)) {
    return 'Enter the login alone, as DataImpulse shows it; the country and session have their own fields.'
  }
  // abc_ would compose as abc___cr.de, which DataImpulse reads as the login abc.
  return login.endsWith('_') ? 'A DataImpulse login cannot end in an underscore.' : null
}

/** Whether a plan can be the one in use: it has a login, and a password saved or typed. */
export function planUsable(fields: DataImpulseFields, plan: DataImpulsePlanId, hasPassword: Partial<PlanRecord<boolean>>): boolean {
  return fields.logins[plan].trim() !== '' && hasPassword[plan] === true
}

/**
 * Checks the fields the DataImpulse card composes; an empty result means it can be saved.
 * `hasPassword` says which plans have a password saved or typed.
 */
export function validateDataImpulse(fields: DataImpulseFields, hasPassword: Partial<PlanRecord<boolean>> = {}): DataImpulseErrors {
  const errors: DataImpulseErrors = {}
  for (const { id } of DATAIMPULSE_PLANS) {
    const login = planLoginError(fields.logins[id].trim())
    if (login) errors[`${id}.login`] = login
  }
  const active = DATAIMPULSE_PLANS.find(p => p.id === fields.plan)
  if (!active) errors.plan = 'Choose the plan the proxy uses.'
  else if (!planUsable(fields, active.id, hasPassword)) errors.plan = `Enter the ${active.label} plan's login and password to use it.`
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
  dataimpulse?: StoredPlans
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

interface ProxyWrite {
  field: ProxyField
  value: string
}

type ComposedPlans = NonNullable<ComposedProxy['dataimpulse']>

/**
 * The config writes that take the stored keys to `target`, in an order the backend's cross-key checks
 * accept and that never hands one host another's credentials: the stored credentials are cleared
 * first for a socks5 URL, which ScrapeProxy refuses while any are stored, and for a URL on another
 * host; otherwise the URL goes before its credentials. A blank `password` keeps the stored one on the
 * same host. DataImpulse plan credentials go first, since the backend refuses a plan without them, and
 * the targeting and plan follow the URL; a blank plan password keeps the stored one. A save that leaves
 * a proxy set switches it on, last, once its credentials are in place.
 */
export function planProxyWrites(
  target: ComposedProxy,
  password: string,
  stored: StoredProxy,
  planPasswords: Partial<PlanRecord<string>> = {},
): ProxyWrite[] {
  const url = target.url.trim()
  const socks = /^socks5:/i.test(url)
  const hostChanges = url !== '' && proxyHost(url) !== proxyHost(stored.url)
  const storedUsername = hostChanges ? '' : stored.username.trim()
  const plans = target.dataimpulse
  const storedPlans = stored.dataimpulse ?? emptyStoredPlans()
  const writes = plans ? planCredentialWrites(plans, storedPlans, planPasswords) : []
  if (socks || hostChanges) writes.push(...clearedCredentialWrites(stored))
  if (url !== stored.url.trim()) writes.push({ field: 'url', value: url })
  if (!socks) writes.push(...credentialWrites(target.username?.trim() ?? null, password, storedUsername))
  if (plans) writes.push(...planChoiceWrites(plans, storedPlans))
  if (url && !stored.enabled) writes.push({ field: 'enabled', value: 'true' })
  return writes
}

function planCredentialWrites(plans: ComposedPlans, stored: StoredPlans, passwords: Partial<PlanRecord<string>>): ProxyWrite[] {
  const writes: ProxyWrite[] = []
  for (const { id } of DATAIMPULSE_PLANS) {
    if (plans.logins[id] !== stored.logins[id].trim()) writes.push({ field: `${id}.login`, value: plans.logins[id] })
    const planPassword = passwords[id] ?? ''
    if (planPassword.trim()) writes.push({ field: `${id}.password`, value: planPassword })
  }
  return writes
}

function clearedCredentialWrites(stored: StoredProxy): ProxyWrite[] {
  const writes: ProxyWrite[] = []
  if (stored.username.trim()) writes.push({ field: 'username', value: '' })
  if (stored.hasPassword) writes.push({ field: 'password', value: '' })
  return writes
}

function credentialWrites(username: string | null, password: string, storedUsername: string): ProxyWrite[] {
  const writes: ProxyWrite[] = []
  if (username !== null && username !== storedUsername) writes.push({ field: 'username', value: username })
  if (password.trim()) writes.push({ field: 'password', value: password })
  return writes
}

function planChoiceWrites(plans: ComposedPlans, stored: StoredPlans): ProxyWrite[] {
  const writes: ProxyWrite[] = []
  if (plans.targeting !== stored.targeting.trim()) writes.push({ field: 'targeting', value: plans.targeting })
  if (plans.plan !== stored.plan) writes.push({ field: 'plan', value: plans.plan })
  return writes
}
