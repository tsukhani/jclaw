import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mountSuspended, registerEndpoint } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import { defineComponent, h } from 'vue'
import { clearNuxtData } from '#app'
import { setResponseStatus } from 'h3'
import SettingsJavaRuntimePanel from '~/components/settings/SettingsJavaRuntimePanel.vue'
import ConfirmDialog from '~/components/ConfirmDialog.vue'

/**
 * Settings → Maintenance → Java runtime. One case per row of the panel's matrix: what it says for
 * each kind of runtime, that only the installer's own Java is offered a download, that a cancelled
 * dialog sends nothing, and that a staged runtime offers the restart and the cancel.
 *
 * The restart's reconnect poll is the Restart panel's, shared through useRestartAndReconnect and
 * not driven here.
 */

const Harness = defineComponent({
  setup() {
    return () => h('div', [h(SettingsJavaRuntimePanel), h(ConfirmDialog)])
  },
})

let runtime: Record<string, unknown>
let getQueries: Record<string, unknown>[] = []
let stagePosts = 0
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
  handler: (event) => {
    stagePosts += 1
    if (stageReply.status !== 202) {
      setResponseStatus(event, stageReply.status)
      return stageReply.body
    }
    runtime = { ...runtime, downloading: true, downloadPercent: 40 }
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

function builtIn(over: Record<string, unknown> = {}) {
  return {
    kind: 'built-in',
    currentVersion: '25.0.4',
    vendor: 'Azul Systems, Inc.',
    latestVersion: '25.0.5',
    updateAvailable: true,
    stagedVersion: null,
    downloading: false,
    downloadPercent: null,
    downloadError: null,
    lastStart: null,
    lastStartAt: null,
    rebuildsStartupCache: true,
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
  stageReply = { status: 202, body: { status: 'ok', version: '25.0.5' } }
  cancels = 0
  restartPosts = 0
  restartPreflight = {
    available: true, unavailableReason: null, mode: 'PROD', backendOnly: false, rebuildExpected: false,
    runningTasks: 0, activeSubagentRuns: 0, aotCacheEnabled: true, aotCacheStatus: null, aotCacheStatusAt: null,
  }
  document.body.innerHTML = ''
})

describe('SettingsJavaRuntimePanel — what it reports', () => {
  it('offers the newest patch for download on the Java the installer set up', async () => {
    const c = await mounted()

    expect(c.text()).toContain('Java 25.0.4')
    expect(c.text()).toContain('Azul Systems, Inc.')
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
    runtime = builtIn({ kind: 'external' })
    const c = await mounted()

    expect(summary(c)).toBe('Java 25.0.5 is available from your JDK\'s vendor.')
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
