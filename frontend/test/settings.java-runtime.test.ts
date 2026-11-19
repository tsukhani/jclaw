import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { mockNuxtImport, mountSuspended, registerEndpoint } from '@nuxt/test-utils/runtime'
import { flushPromises, type DOMWrapper } from '@vue/test-utils'
import { defineComponent, h } from 'vue'
import { clearNuxtData } from '#app'
import { readBody, setResponseStatus } from 'h3'
import SettingsJavaRuntimePanel from '~/components/settings/SettingsJavaRuntimePanel.vue'
import ConfirmDialog from '~/components/ConfirmDialog.vue'

/**
 * Settings → Maintenance → Java runtime. One case per row of the panel's matrix: what it says for
 * each kind of runtime, that only the installer's own Java is offered a download, the switches between
 * Zulu and Temurin and the reason given for each it does not offer, that a cancelled dialog sends
 * nothing, and that a staged runtime offers the restart and the cancel.
 *
 * The restart's reconnect poll is the Restart panel's, shared through useRestartAndReconnect and
 * covered there; here it is driven once, for the phases a staged runtime adds.
 */

const { reloads } = vi.hoisted(() => ({ reloads: { count: 0 } }))
mockNuxtImport('reloadNuxtApp', () => () => {
  reloads.count += 1
})

const Harness = defineComponent({
  setup() {
    return () => h('div', [h(SettingsJavaRuntimePanel), h(ConfirmDialog)])
  },
})

let runtime: Record<string, unknown>
let getQueries: Record<string, unknown>[] = []
let stagePosts = 0
let stageBodies: unknown[] = []
let stageReply: { status: number, body: Record<string, unknown> }
let cancels = 0
let restartPosts = 0
let restartPreflight: Record<string, unknown>

registerEndpoint('/api/system/java-runtime', {
  method: 'GET',
  handler: (event) => {
    const url = new URL(String(event.node?.req?.url ?? event.path ?? ''), 'http://localhost')
    getQueries.push(Object.fromEntries(url.searchParams))
    return runtime
  },
})
registerEndpoint('/api/system/java-runtime', {
  method: 'POST',
  handler: async (event) => {
    stagePosts += 1
    const body = (await readBody(event)) as { vendor?: string } | undefined
    stageBodies.push(body ?? null)
    if (stageReply.status !== 202) {
      setResponseStatus(event, stageReply.status)
      return stageReply.body
    }
    // As the backend does: a vendor is the switch it names, none the running vendor's newest patch.
    const asked = (runtime.switches as { vendor: string, version: string }[]).find(s => s.vendor === body?.vendor)
    runtime = { ...runtime, downloading: true, downloadPercent: 40,
      downloadingVersion: asked ? asked.version : runtime.latestVersion,
      downloadingVendor: asked ? asked.vendor : runtime.distribution }
    return stageReply.body
  },
})
registerEndpoint('/api/system/java-runtime', {
  method: 'DELETE',
  handler: () => {
    cancels += 1
    runtime = { ...runtime, stagedVersion: null }
    return { status: 'ok' }
  },
})
registerEndpoint('/api/system/restart', {
  method: 'GET',
  handler: () => restartPreflight,
})
registerEndpoint('/api/system/restart', {
  method: 'POST',
  handler: () => {
    restartPosts += 1
    return { status: 'ok', mode: 'PROD', backendOnly: false, rebuildExpected: false }
  },
})

let backendUp = true
let progress: Record<string, unknown> | null = null

registerEndpoint('/api/status', {
  method: 'GET',
  handler: (event) => {
    if (backendUp) return { status: 'ok' }
    setResponseStatus(event, 503)
    return { type: 'error', code: 'restarting', message: 'JClaw is restarting' }
  },
})
registerEndpoint('/api/system/restart/progress', {
  method: 'GET',
  handler: (event) => {
    if (progress) return progress
    setResponseStatus(event, 503)
    return { type: 'error', code: 'restarting', message: 'JClaw is restarting' }
  },
})

function builtIn(over: Record<string, unknown> = {}) {
  return {
    kind: 'built-in',
    currentVersion: '25.0.4',
    currentBuild: '25.36.15',
    vendor: 'Azul Systems, Inc.',
    distribution: 'Zulu',
    latestVersion: '25.0.5',
    latestBuild: '25.38.107',
    releaseType: 'a security and bug-fix release',
    releaseNotesUrl: 'https://docs.azul.com/core/release-notes',
    releaseNotesLabel: 'Azul\'s latest release notes',
    updateAvailable: true,
    stagedVersion: null,
    stagedBuild: null,
    stagedVendor: null,
    downloading: false,
    downloadingVersion: null,
    downloadingVendor: null,
    downloadPercent: null,
    downloadError: null,
    lastStart: null,
    lastStartAt: null,
    rebuildsStartupCache: true,
    switches: [],
    ...over,
  }
}

function dialogButton(label: string): HTMLButtonElement | null {
  const buttons = [...document.querySelectorAll('[role="dialog"] button')]
  return (buttons.find(b => (b.textContent ?? '').trim() === label) ?? null) as HTMLButtonElement | null
}

function dialogText(): string {
  return document.querySelector('[role="dialog"]')?.textContent ?? ''
}

type Mounted = Awaited<ReturnType<typeof mountSuspended>>

interface Button {
  text: () => string
  trigger: (e: string) => Promise<void>
  attributes: (key: string) => string | undefined
}

function panelButton(c: { findAll: (s: string) => Button[] }, label: string) {
  return c.findAll('button').find(b => b.text().trim() === label)
}

function summary(c: Mounted): string {
  return c.find('[data-testid="java-runtime-summary"]').text()
}

async function mounted(): Promise<Mounted> {
  const c = await mountSuspended(Harness)
  await flushPromises()
  return c
}

beforeEach(() => {
  clearNuxtData()
  runtime = builtIn()
  getQueries = []
  stagePosts = 0
  stageBodies = []
  stageReply = { status: 202, body: { status: 'ok', version: '25.0.5' } }
  cancels = 0
  restartPosts = 0
  restartPreflight = {
    available: true, unavailableReason: null, mode: 'PROD', backendOnly: false, rebuildExpected: false,
    runningTasks: 0, activeSubagentRuns: 0, aotCacheEnabled: true, aotCacheStatus: null, aotCacheStatusAt: null,
  }
  document.body.innerHTML = ''
  backendUp = true
  progress = null
  reloads.count = 0
})

afterEach(() => {
  vi.useRealTimers()
})

describe('SettingsJavaRuntimePanel — what it reports', () => {
  it('offers the newest patch for download on the Java the installer set up', async () => {
    const c = await mounted()

    expect(c.text()).toContain('Java 25.0.4')
    expect(c.find('[data-testid="java-runtime-vendor"]').text()).toBe('Zulu')
    expect(summary(c)).toBe('Java 25.0.5 is available.')
    expect(panelButton(c, 'Download Java 25.0.5')).toBeTruthy()
  })

  it('says up to date and offers nothing when the catalog has no newer patch', async () => {
    runtime = builtIn({ latestVersion: '25.0.4', updateAvailable: false })
    const c = await mounted()

    expect(summary(c)).toBe('Up to date.')
    expect(c.findAll('button')).toHaveLength(0)
  })

  it('points an external JDK at its vendor without a download', async () => {
    runtime = builtIn({ kind: 'external', distribution: null, vendor: 'Ubuntu' })
    const c = await mounted()

    expect(summary(c)).toBe('Java 25.0.5 is available from your JDK\'s vendor.')
    expect(c.find('[data-testid="java-runtime-vendor"]').text()).toBe('Ubuntu')
    expect(c.findAll('button')).toHaveLength(0)
  })

  it('says a container gets its Java from the image, without a download', async () => {
    runtime = builtIn({ kind: 'container' })
    const c = await mounted()

    expect(summary(c)).toBe('Java 25.0.5 is available. The runtime comes with the Docker image.')
    expect(c.findAll('button')).toHaveLength(0)
  })

  it('says it could not check, and checks again past the cache on request', async () => {
    runtime = builtIn({ latestVersion: null, updateAvailable: false })
    const c = await mounted()

    expect(summary(c)).toBe('Couldn\'t check for a newer Java.')
    expect(panelButton(c, 'Download Java 25.0.5')).toBeUndefined()

    runtime = builtIn()
    await panelButton(c, 'Check again')!.trigger('click')
    await flushPromises()

    expect(getQueries.at(-1)).toEqual({ refresh: 'true' })
    expect(summary(c)).toBe('Java 25.0.5 is available.')
  })

  it('says what kind of release the newer patch is and links Azul\'s latest release notes', async () => {
    const c = await mounted()

    const release = c.find('[data-testid="java-runtime-release"]')
    expect(release.text()).toBe('Java 25.0.5 is a security and bug-fix release. Azul\'s latest release notes')
    const link = release.find('a')
    expect(link.attributes('href')).toBe('https://docs.azul.com/core/release-notes')
    expect(link.attributes('target')).toBe('_blank')
    expect(link.attributes('rel')).toBe('noopener noreferrer')
  })

  it('links Azul\'s latest release notes alone when the catalog gives no release type', async () => {
    runtime = builtIn({ releaseType: null })
    const c = await mounted()

    expect(c.find('[data-testid="java-runtime-release"]').text()).toBe('Azul\'s latest release notes')
  })

  it('says nothing about a release when there is no newer patch', async () => {
    runtime = builtIn({ latestVersion: '25.0.4', updateAvailable: false })
    const c = await mounted()

    expect(c.find('[data-testid="java-runtime-release"]').exists()).toBe(false)
  })

  it('shows what the last start did with a staged runtime', async () => {
    runtime = builtIn({ lastStart: 'upgraded 25.0.3 → 25.0.4', lastStartAt: '2026-10-25T09:00:00Z' })
    const c = await mounted()

    expect(c.find('[data-testid="java-runtime-last-start"]').text())
      .toContain('Last start: upgraded 25.0.3 → 25.0.4')
  })

  it('says nothing about a last start before any start wrote one', async () => {
    const c = await mounted()

    expect(c.find('[data-testid="java-runtime-last-start"]').exists()).toBe(false)
  })

  it('shows why the last download failed and offers it again', async () => {
    runtime = builtIn({ downloadError: 'Java 25.0.5 could not be downloaded: checksum mismatch' })
    const c = await mounted()

    expect(c.find('[role="status"]').text()).toBe('Java 25.0.5 could not be downloaded: checksum mismatch')
    expect(panelButton(c, 'Download Java 25.0.5')).toBeTruthy()
  })
})

describe('SettingsJavaRuntimePanel — download', () => {
  it('sends nothing when the confirmation is cancelled', async () => {
    const c = await mounted()

    await panelButton(c, 'Download Java 25.0.5')!.trigger('click')
    await flushPromises()
    expect(dialogText()).toContain('Java 25.0.5 will be downloaded from Azul and checked against its SHA-256 '
      + 'while JClaw keeps running on Java 25.0.4.')
    dialogButton('Cancel')!.click()
    await flushPromises()

    expect(stagePosts).toBe(0)
    expect(summary(c)).toBe('Java 25.0.5 is available.')
  })

  it('asks for no vendor, so the backend stages the running vendor\'s patch', async () => {
    const c = await mounted()

    await panelButton(c, 'Download Java 25.0.5')!.trigger('click')
    await flushPromises()
    dialogButton('Download')!.click()
    await flushPromises()

    // The stub reads the body before it answers, so wait for the answer before reading what it got.
    await vi.waitFor(() => expect(summary(c)).toBe('Downloading Java 25.0.5…'))
    expect(stageBodies).toEqual([null])
    c.unmount()
  })

  it('posts once confirmed, follows the download, then offers the restart', async () => {
    const c = await mounted()

    await panelButton(c, 'Download Java 25.0.5')!.trigger('click')
    await flushPromises()
    dialogButton('Download')!.click()
    await flushPromises()

    expect(stagePosts).toBe(1)
    await vi.waitFor(() => expect(c.find('[data-testid="java-runtime-progress"]').exists()).toBe(true))
    expect(c.find('[role="progressbar"]').attributes('aria-valuenow')).toBe('40')
    expect(summary(c)).toBe('Downloading Java 25.0.5…')
    expect(panelButton(c, 'Download Java 25.0.5')).toBeUndefined()

    runtime = builtIn({ stagedVersion: '25.0.5' })
    await vi.waitFor(() => expect(summary(c)).toBe('Java 25.0.5 will be installed at the next restart. '
      + 'The first start on it rebuilds the startup cache, so it takes longer than usual.'), { timeout: 3000 })
    expect(c.find('[data-testid="java-runtime-progress"]').exists()).toBe(false)
    expect(panelButton(c, 'Restart now')).toBeTruthy()
    expect(panelButton(c, 'Cancel')).toBeTruthy()
    // Opt-in: the download never restarts anything on its own.
    expect(restartPosts).toBe(0)
    c.unmount()
  })

  it('shows the refusal when the backend will not download', async () => {
    stageReply = { status: 409, body: { status: 'error', code: 'conflict', message: 'A newer Java is already downloading.' } }
    const c = await mounted()

    await panelButton(c, 'Download Java 25.0.5')!.trigger('click')
    await flushPromises()
    dialogButton('Download')!.click()
    await flushPromises()

    expect(stagePosts).toBe(1)
    await vi.waitFor(() => expect(c.find('[role="status"]').text()).toBe('A newer Java is already downloading.'))
  })
})

describe('SettingsJavaRuntimePanel — the switch from a host Java', () => {
  const both = [{ vendor: 'Zulu', version: '25.0.5', refusal: null }, { vendor: 'Temurin', version: '25.0.5', refusal: null }]

  function hostJava(over: Record<string, unknown> = {}) {
    return builtIn({ kind: 'external', currentVersion: '25.0.3', switches: both, ...over })
  }

  it('offers a bundle install on a Java it did not install the switch to one of its own, from either vendor', async () => {
    runtime = hostJava()
    const c = await mounted()

    expect(summary(c)).toBe('Java 25.0.5 is available from your JDK\'s vendor.')
    expect(c.find('[data-testid="java-runtime-switch"]').text()).toBe('JClaw cannot patch a Java it did not install. '
      + 'Switch to Zulu 25.0.5 from Azul or Temurin 25.0.5 from Adoptium, which JClaw installs beside itself and '
      + 'keeps patched.')
    expect(c.findAll('button').map((b: DOMWrapper<Element>) => b.text().trim()))
      .toEqual(['Switch to Zulu', 'Switch to Temurin'])
  })

  it('shows no switch on a source checkout or in a container', async () => {
    runtime = hostJava({ switches: [] })
    const checkout = await mounted()
    expect(checkout.find('[data-testid="java-runtime-switch"]').exists()).toBe(false)
    expect(checkout.findAll('button')).toHaveLength(0)
    checkout.unmount()

    clearNuxtData()
    runtime = hostJava({ kind: 'container', switches: [] })
    const container = await mounted()
    expect(container.find('[data-testid="java-runtime-switch"]').exists()).toBe(false)
    expect(container.findAll('button')).toHaveLength(0)
  })

  it('shows no switch, and says why, when the catalogs could not say which Java they would install', async () => {
    runtime = hostJava({ latestVersion: null, updateAvailable: false, switches: [
      { vendor: 'Zulu', version: null, refusal: 'Couldn\'t check Azul\'s catalog for a newer Java.' },
      { vendor: 'Temurin', version: null, refusal: 'Couldn\'t check Adoptium\'s catalog for a newer Java.' }] })
    const c = await mounted()

    expect(c.find('[data-testid="java-runtime-switch"]').exists()).toBe(false)
    expect(c.findAll('[data-testid="java-runtime-switch-refused"]').map((d: DOMWrapper<Element>) => d.text())).toEqual([
      'No switch to Zulu. Couldn\'t check Azul\'s catalog for a newer Java.',
      'No switch to Temurin. Couldn\'t check Adoptium\'s catalog for a newer Java.'])
    expect(c.findAll('button').map((b: DOMWrapper<Element>) => b.text().trim())).toEqual(['Check again'])
  })

  it('sends nothing when the switch is cancelled', async () => {
    runtime = hostJava()
    const c = await mounted()

    await panelButton(c, 'Switch to Zulu')!.trigger('click')
    await flushPromises()
    expect(dialogText()).toContain('Zulu 25.0.5 will be downloaded from Azul and checked against its SHA-256 while JClaw '
      + 'keeps running on Java 25.0.3. The next time JClaw starts, it installs it in the jre folder beside JClaw and '
      + 'runs on it from then on, and this panel keeps it patched. Java 25.0.3 stays installed, unused by JClaw.')
    dialogButton('Cancel')!.click()
    await flushPromises()

    expect(stagePosts).toBe(0)
    expect(panelButton(c, 'Switch to Zulu')).toBeTruthy()
  })

  it('switches a host Java of another major to Temurin once confirmed, follows the download, then offers the restart', async () => {
    runtime = hostJava({ currentVersion: '27.0.1', latestVersion: '27.0.2', distribution: 'Temurin', vendor: 'Eclipse Adoptium' })
    const c = await mounted()

    await panelButton(c, 'Switch to Temurin')!.trigger('click')
    await flushPromises()
    expect(dialogText()).toContain('Temurin 25.0.5 will be downloaded from Adoptium and checked against its SHA-256')
    dialogButton('Switch')!.click()
    await flushPromises()

    await vi.waitFor(() => expect(c.find('[data-testid="java-runtime-progress"]').exists()).toBe(true))
    expect(stagePosts).toBe(1)
    expect(stageBodies).toEqual([{ vendor: 'Temurin' }])
    expect(summary(c)).toBe('Downloading Temurin 25.0.5…')
    expect(c.find('[role="progressbar"]').attributes('aria-label')).toBe('Downloading Temurin 25.0.5')
    expect(panelButton(c, 'Switch to Temurin')).toBeUndefined()

    runtime = hostJava({ currentVersion: '27.0.1', latestVersion: '27.0.2', distribution: 'Temurin',
      stagedVersion: '25.0.5', stagedVendor: 'Temurin' })
    await vi.waitFor(() => expect(summary(c)).toBe('Temurin 25.0.5 will be installed at the next restart. '
      + 'The first start on it rebuilds the startup cache, so it takes longer than usual.'), { timeout: 3000 })
    expect(c.find('[data-testid="java-runtime-switch"]').exists()).toBe(false)
    expect(c.findAll('button').map((b: DOMWrapper<Element>) => b.text().trim())).toEqual(['Cancel', 'Restart now'])
    expect(restartPosts).toBe(0)
    c.unmount()
  })

  it('shows the refusal when the backend will not switch', async () => {
    runtime = hostJava()
    stageReply = { status: 409, body: { status: 'error', code: 'conflict',
      message: '/opt/jclaw is not writable, so a new Java cannot be staged beside /opt/jclaw/jre.' } }
    const c = await mounted()

    await panelButton(c, 'Switch to Zulu')!.trigger('click')
    await flushPromises()
    dialogButton('Switch')!.click()
    await flushPromises()

    await vi.waitFor(() => expect(c.find('[role="status"]').text())
      .toBe('/opt/jclaw is not writable, so a new Java cannot be staged beside /opt/jclaw/jre.'))
    expect(stagePosts).toBe(1)
    expect(stageBodies).toEqual([{ vendor: 'Zulu' }])
  })
})

describe('SettingsJavaRuntimePanel — the switch between Zulu and Temurin', () => {
  const toTemurin = { vendor: 'Temurin', version: '25.0.5.1', refusal: null }

  function zulu(over: Record<string, unknown> = {}) {
    return builtIn({ currentVersion: '25.0.5.1', currentBuild: '25.38.201', latestVersion: '25.0.5.1',
      latestBuild: '25.38.201', updateAvailable: false, switches: [toTemurin], ...over })
  }

  function temurin(over: Record<string, unknown> = {}) {
    return builtIn({ currentVersion: '25.0.5', currentBuild: null, vendor: 'Eclipse Adoptium', distribution: 'Temurin',
      latestVersion: '25.0.6', latestBuild: null, releaseType: null,
      releaseNotesUrl: 'https://adoptium.net/temurin/release-notes/?version=jdk-25.0.6%2B7',
      releaseNotesLabel: 'Release notes for Temurin 25.0.6',
      switches: [{ vendor: 'Zulu', version: '25.0.6', refusal: null }], ...over })
  }

  it('shows the vendor and offers the built-in Zulu the switch to Temurin at the same version', async () => {
    runtime = zulu()
    const c = await mounted()

    expect(c.find('[data-testid="java-runtime-vendor"]').text()).toBe('Zulu')
    expect(summary(c)).toBe('Up to date.')
    expect(c.find('[data-testid="java-runtime-switch"]').text())
      .toBe('JClaw can run on Temurin 25.0.5.1 from Adoptium instead: a build of the same OpenJDK from another vendor.')
    expect(c.findAll('button').map((b: DOMWrapper<Element>) => b.text().trim())).toEqual(['Switch to Temurin'])
  })

  it('sends nothing when the switch is cancelled', async () => {
    runtime = zulu()
    const c = await mounted()

    await panelButton(c, 'Switch to Temurin')!.trigger('click')
    await flushPromises()
    expect(dialogText()).toContain('Temurin 25.0.5.1 will be downloaded from Adoptium and checked against its SHA-256 '
      + 'while JClaw keeps running on Zulu 25.0.5.1. It is installed the next time JClaw starts, and Zulu 25.0.5.1 is '
      + 'kept as jre.previous. From then on this panel keeps it patched from Adoptium.')
    dialogButton('Cancel')!.click()
    await flushPromises()

    expect(stagePosts).toBe(0)
    expect(panelButton(c, 'Switch to Temurin')).toBeTruthy()
  })

  it('posts the vendor once confirmed, follows the download, then names it in the restart', async () => {
    runtime = zulu()
    const c = await mounted()

    await panelButton(c, 'Switch to Temurin')!.trigger('click')
    await flushPromises()
    dialogButton('Switch')!.click()
    await flushPromises()

    await vi.waitFor(() => expect(summary(c)).toBe('Downloading Temurin 25.0.5.1…'))
    expect(stageBodies).toEqual([{ vendor: 'Temurin' }])
    expect(c.find('[data-testid="java-runtime-switch"]').exists()).toBe(false)

    runtime = zulu({ stagedVersion: '25.0.5.1', stagedVendor: 'Temurin', rebuildsStartupCache: false })
    await vi.waitFor(() => expect(summary(c)).toBe('Temurin 25.0.5.1 will be installed at the next restart.'),
      { timeout: 3000 })
    expect(c.findAll('button').map((b: DOMWrapper<Element>) => b.text().trim())).toEqual(['Cancel', 'Restart now'])
    await panelButton(c, 'Restart now')!.trigger('click')
    await flushPromises()
    expect(dialogText()).toContain('Temurin 25.0.5.1 is installed as JClaw starts again.')
    dialogButton('Cancel')!.click()
    await flushPromises()
    expect(restartPosts).toBe(0)
    c.unmount()
  })

  it('shows what the start after the switch did', async () => {
    runtime = temurin({ lastStart: 'switched Zulu 25.0.5.1 → Temurin 25.0.5.1', lastStartAt: '2026-11-20T09:00:00Z' })
    const c = await mounted()

    expect(c.find('[data-testid="java-runtime-last-start"]').text())
      .toContain('Last start: switched Zulu 25.0.5.1 → Temurin 25.0.5.1')
  })

  it('on Temurin offers Adoptium\'s patch with that release\'s notes, and the switch back to Zulu', async () => {
    runtime = temurin()
    const c = await mounted()

    expect(c.find('[data-testid="java-runtime-vendor"]').text()).toBe('Temurin')
    expect(summary(c)).toBe('Java 25.0.6 is available.')
    const release = c.find('[data-testid="java-runtime-release"]')
    expect(release.text()).toBe('Release notes for Temurin 25.0.6')
    expect(release.find('a').attributes('href')).toBe('https://adoptium.net/temurin/release-notes/?version=jdk-25.0.6%2B7')
    expect(c.findAll('button').map((b: DOMWrapper<Element>) => b.text().trim()))
      .toEqual(['Download Java 25.0.6', 'Switch to Zulu'])

    await panelButton(c, 'Download Java 25.0.6')!.trigger('click')
    await flushPromises()
    expect(dialogText()).toContain('Java 25.0.6 will be downloaded from Adoptium and checked against its SHA-256 '
      + 'while JClaw keeps running on Java 25.0.5.')
    dialogButton('Download')!.click()
    await flushPromises()
    await vi.waitFor(() => expect(summary(c)).toBe('Downloading Java 25.0.6…'))
    expect(stageBodies).toEqual([null])
    c.unmount()
  })

  it('says why it offers no switch where Adoptium has no JRE, or where it would go back to an older Java', async () => {
    runtime = zulu({ switches: [{ vendor: 'Temurin', version: null,
      refusal: 'Adoptium\'s catalog lists no Java 25 runtime for windows/aarch64.' }] })
    const none = await mounted()
    expect(none.find('[data-testid="java-runtime-switch"]').exists()).toBe(false)
    expect(none.find('[data-testid="java-runtime-switch-refused"]').text())
      .toBe('No switch to Temurin. Adoptium\'s catalog lists no Java 25 runtime for windows/aarch64.')
    expect(none.findAll('button')).toHaveLength(0)
    none.unmount()

    clearNuxtData()
    runtime = zulu({ switches: [{ vendor: 'Temurin', version: '25.0.5',
      refusal: 'Temurin 25.0.5 is older than the Zulu 25.0.5.1 JClaw runs on, so switching to it would undo fixes.' }] })
    const older = await mounted()
    expect(older.find('[data-testid="java-runtime-switch-refused"]').text()).toBe('No switch to Temurin. Temurin 25.0.5 '
      + 'is older than the Zulu 25.0.5.1 JClaw runs on, so switching to it would undo fixes.')
    expect(older.findAll('button')).toHaveLength(0)
  })

  it('shows the refusal when the backend will not switch', async () => {
    runtime = zulu()
    stageReply = { status: 409, body: { status: 'error', code: 'conflict', message: 'A newer Java is already downloading.' } }
    const c = await mounted()

    await panelButton(c, 'Switch to Temurin')!.trigger('click')
    await flushPromises()
    dialogButton('Switch')!.click()
    await flushPromises()

    await vi.waitFor(() => expect(c.find('[role="status"]').text()).toBe('A newer Java is already downloading.'))
    expect(stageBodies).toEqual([{ vendor: 'Temurin' }])
  })
})

describe('SettingsJavaRuntimePanel — a new build of the same Java version', () => {
  const respin = { currentVersion: '25.0.5', currentBuild: '25.38.105', latestVersion: '25.0.5', latestBuild: '25.38.107' }

  it('names both Zulu builds, and sends nothing when the download is cancelled', async () => {
    runtime = builtIn(respin)
    const c = await mounted()

    expect(summary(c)).toBe('Zulu 25.38.107 is available to replace Zulu 25.38.105 (Java 25.0.5).')
    expect(c.find('[data-testid="java-runtime-release"]').text())
      .toBe('Zulu 25.38.107 is a security and bug-fix release. Azul\'s latest release notes')
    await panelButton(c, 'Download Zulu 25.38.107')!.trigger('click')
    await flushPromises()
    expect(dialogText()).toContain('Zulu 25.38.107 will be downloaded from Azul and checked against its SHA-256 '
      + 'while JClaw keeps running on Zulu 25.38.105.')
    dialogButton('Cancel')!.click()
    await flushPromises()

    expect(stagePosts).toBe(0)
  })

  it('names the staged build in the summary and the restart dialog', async () => {
    runtime = builtIn({ ...respin, stagedVersion: '25.0.5', stagedBuild: '25.38.107', rebuildsStartupCache: false })
    const c = await mounted()

    expect(summary(c)).toBe('Zulu 25.38.107 will be installed at the next restart.')
    await panelButton(c, 'Restart now')!.trigger('click')
    await flushPromises()
    expect(dialogText()).toContain('Zulu 25.38.107 is installed as JClaw starts again.')
    dialogButton('Cancel')!.click()
    await flushPromises()
    expect(restartPosts).toBe(0)
  })

  it('names a new Java version by its version, whatever its build', async () => {
    runtime = builtIn({ currentBuild: '25.38.105', latestVersion: '25.0.6', latestBuild: '25.36.1' })
    const c = await mounted()

    expect(summary(c)).toBe('Java 25.0.6 is available.')
    expect(panelButton(c, 'Download Java 25.0.6')).toBeTruthy()
  })

  it('names the version when the running build is unknown', async () => {
    runtime = builtIn({ ...respin, currentBuild: null, stagedVersion: '25.0.5', stagedBuild: '25.38.107', rebuildsStartupCache: false })
    const c = await mounted()

    expect(summary(c)).toBe('Java 25.0.5 will be installed at the next restart.')
  })
})

describe('SettingsJavaRuntimePanel — a runtime waiting for the next start', () => {
  it('cancels it, and offers the download again', async () => {
    runtime = builtIn({ stagedVersion: '25.0.5' })
    const c = await mounted()

    await panelButton(c, 'Cancel')!.trigger('click')
    await flushPromises()

    expect(cancels).toBe(1)
    await vi.waitFor(() => expect(summary(c)).toBe('Java 25.0.5 is available.'))
    expect(panelButton(c, 'Download Java 25.0.5')).toBeTruthy()
    expect(restartPosts).toBe(0)
  })

  it('names the interrupted work and the slower first start, and sends nothing when the restart is cancelled', async () => {
    runtime = builtIn({ stagedVersion: '25.0.5' })
    restartPreflight = { ...restartPreflight, runningTasks: 2 }
    const c = await mounted()

    await panelButton(c, 'Restart now')!.trigger('click')
    await flushPromises()

    expect(dialogText()).toContain('2 task runs currently running will be interrupted. Java 25.0.5 is installed '
      + 'as JClaw starts again. The first start on it rebuilds the startup cache, so it takes longer than usual.')
    dialogButton('Cancel')!.click()
    await flushPromises()
    expect(restartPosts).toBe(0)
    expect(cancels).toBe(0)
  })

  it('restarts once confirmed and waits for the backend', async () => {
    runtime = builtIn({ stagedVersion: '25.0.5' })
    const c = await mounted()

    await panelButton(c, 'Restart now')!.trigger('click')
    await flushPromises()
    dialogButton('Restart')!.click()
    await flushPromises()

    expect(restartPosts).toBe(1)
    await vi.waitFor(() => expect(c.text()).toContain('Waiting for the backend'))
    expect(panelButton(c, 'Restarting…')!.attributes('disabled')).toBeDefined()
    c.unmount()
  })

  it('leaves out the startup cache note where there is no cache, as on Windows', async () => {
    runtime = builtIn({ stagedVersion: '25.0.5', rebuildsStartupCache: false })
    const c = await mounted()

    expect(summary(c)).toBe('Java 25.0.5 will be installed at the next restart.')
    await panelButton(c, 'Restart now')!.trigger('click')
    await flushPromises()
    expect(dialogText()).not.toContain('startup cache')
    dialogButton('Cancel')!.click()
  })

  it('says why when this install cannot restart itself', async () => {
    runtime = builtIn({ stagedVersion: '25.0.5' })
    restartPreflight = { ...restartPreflight, available: false, unavailableReason: 'jclaw.sh not found at /srv/jclaw/jclaw.sh.' }
    const c = await mounted()

    await panelButton(c, 'Restart now')!.trigger('click')
    await flushPromises()

    expect(document.querySelector('[role="dialog"]')).toBeNull()
    expect(c.find('[role="status"]').text()).toBe('jclaw.sh not found at /srv/jclaw/jclaw.sh.')
    expect(restartPosts).toBe(0)
  })
})

describe('SettingsJavaRuntimePanel — the restart that installs it', () => {
  type Step = { id: string, label: string, state: string, seconds: number }
  const served = (source: string, state: string, steps: Step[], services: unknown = null) =>
    ({ source, state, failure: null, steps, services })
  const step = (id: string, label: string, state: string, seconds: number): Step => ({ id, label, state, seconds })

  function lines(c: Mounted): string[] {
    return c.findAll('[data-testid="restart-steps"] li').map((li: DOMWrapper<Element>) => {
      const spans = li.findAll('span')
      return `${spans[0]!.text()} | ${spans[1]?.text() ?? ''}`
    })
  }

  async function tick() {
    await vi.advanceTimersByTimeAsync(1000)
    for (let j = 0; j < 6; j++) await flushPromises()
  }

  it('narrates the stop, the Java install, the cache rebuild and the start in order, then reloads', async () => {
    vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval', 'Date'] })
    runtime = builtIn({ stagedVersion: '25.0.5' })
    const c = await mounted()
    await panelButton(c, 'Restart now')!.trigger('click')
    await flushPromises()
    dialogButton('Restart')!.click()
    await vi.waitFor(() => expect(restartPosts).toBe(1))
    await flushPromises()

    backendUp = false
    progress = served('narrator', 'running', [
      step('stop', 'Stopping JClaw', 'done', 5), step('java', 'Installing Java 25.0.5', 'running', 2)])
    await tick()
    expect(lines(c)).toEqual(['Stopping JClaw | 5s', 'Installing Java 25.0.5… | 2s'])

    progress = served('narrator', 'running', [
      step('stop', 'Stopping JClaw', 'done', 5), step('java', 'Installing Java 25.0.5', 'done', 7),
      step('aot', 'Rebuilding the startup cache', 'running', 3)])
    await tick()
    expect(lines(c)).toEqual(['Stopping JClaw | 5s', 'Installing Java 25.0.5 | 7s', 'Rebuilding the startup cache… | 3s'])

    progress = null
    await tick()
    expect(lines(c)).toEqual(['Stopping JClaw | 5s', 'Installing Java 25.0.5 | 7s', 'Rebuilding the startup cache | 3s',
      'Starting JClaw (connecting channels)… | 0s'])

    backendUp = true
    progress = served('jclaw', 'done', [
      step('stop', 'Stopping JClaw', 'done', 5), step('java', 'Installing Java 25.0.5', 'done', 7),
      step('aot', 'Rebuilding the startup cache', 'done', 31), step('start', 'Starting JClaw (connecting channels)', 'done', 6)],
    { channels: ['whatsapp'], mcpConnected: 1, mcpConfigured: 1, settled: true })
    await tick()
    expect(lines(c)).toEqual(['Stopping JClaw | 5s', 'Installing Java 25.0.5 | 7s', 'Rebuilding the startup cache | 31s',
      'Starting JClaw (connecting channels) | 6s', 'Channels running: WhatsApp | ', 'MCP servers: 1 of 1 connected | '])
    expect(reloads.count).toBe(0)
    await tick()
    expect(reloads.count).toBe(1)
    c.unmount()
  })
})
