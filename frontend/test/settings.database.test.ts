import { describe, it, expect, beforeEach } from 'vitest'
import { mountSuspended, registerEndpoint } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import { defineComponent, h } from 'vue'
import { readBody } from 'h3'
import { clearNuxtData } from '#app'
import SettingsDatabasePanel from '~/components/settings/SettingsDatabasePanel.vue'
import ConfirmDialog from '~/components/ConfirmDialog.vue'
import { sectionGroups } from '~/components/settings/sections'

/**
 * Settings → Database (JCLAW-1165). What is covered is the gate around each destructive
 * action and what the strip says: the verdict and its reason are shown as the backend
 * gave them, a restore is confirmed by name and cancelled cleanly, cleanup is dead with
 * its reason until the backend says otherwise, and settings go out as config writes.
 * The reconnect poll is a wall-clock interval against a genuinely restarting backend
 * and is not driven here.
 */

/** Mount beside ConfirmDialog so confirm() renders — it reads useConfirm()'s singleton. */
const Harness = defineComponent({
  setup() {
    return () => h('div', [h(SettingsDatabasePanel), h(ConfirmDialog)])
  },
})

let status: Record<string, unknown>
let posts: Array<{ url: string, body: unknown }> = []

function healthy(over: Record<string, unknown> = {}) {
  return {
    verdict: 'HEALTHY',
    reason: 'Queries answer and the trace file shows no read failures in 7 days.',
    dataFileBytes: 192_626_688,
    traceFileBytes: 17_618,
    preRestoreBytes: 0,
    intermediateBytes: 0,
    freeBytes: 52 * 1024 * 1024 * 1024,
    h2Version: '2.5.250 (2026-03-02)',
    probeMs: 1,
    pool: { active: 1, idle: 4, total: 5, awaiting: 0, max: 64 },
    corruption24h: 0,
    corruption7d: 0,
    firstCorruptionAt: null,
    lastBackupAt: '2026-09-09T12:00:00Z',
    lastBackupAgeSeconds: 7200,
    backups: [
      { id: 'jclaw-20260909T120000Z.zip', bytes: 61_000_000, createdAt: '2026-09-09T12:00:00Z' },
      { id: 'jclaw-20260908T120000Z.zip', bytes: 60_000_000, createdAt: '2026-09-08T12:00:00Z' },
    ],
    backupsDir: '/srv/jclaw/data/backups',
    retention: 7,
    schedule: null,
    scheduledBackupAt: null,
    scheduledBackupError: null,
    repair: null,
    cleanupAvailable: false,
    cleanupUnavailableReason: 'No repair has left files behind.',
    lastOperation: null,
    maintenanceAvailable: true,
    maintenanceUnavailableReason: null,
    ...over,
  }
}

const repairManifest = {
  stamp: '20260909T221800Z',
  ok: true,
  summary: '60 tables, 174802 rows restored',
  files: [
    { name: 'jclaw.mv.db.damaged-20260909T221800Z', bytes: 193_000_000 },
    { name: 'jclaw.h2.sql', bytes: 133_000_000 },
  ],
  tables: [
    { name: 'PUBLIC.PROMPT', expected: 20, staged: 20, restored: 20 },
    { name: 'PUBLIC.MESSAGE', expected: 170_000, staged: 170_000, restored: 170_000 },
  ],
  enumCastTables: ['PUBLIC.PROMPT'],
  referenceAvailable: true,
  referenceError: null,
  unrecoveredRows: 0,
  scriptErrors: 0,
  failures: [],
}

registerEndpoint('/api/system/database', { method: 'GET', handler: () => status })
for (const url of ['/api/system/database/backups', '/api/system/database/restore', '/api/system/database/repair', '/api/system/database/repair/clean', '/api/config']) {
  registerEndpoint(url, {
    method: 'POST',
    handler: async (event) => {
      const body: unknown = await readBody(event).catch(() => null)
      posts.push({ url, body })
      if (url.endsWith('/backups')) return { id: 'jclaw-20260909T140000Z.zip', bytes: 62_000_000, createdAt: '2026-09-09T14:00:00Z' }
      if (url.endsWith('/clean')) return { ok: true, reason: '', deleted: [{}, {}], reclaimedBytes: 326_000_000 }
      return { status: 'ok', rebuildExpected: false }
    },
  })
}

function dialogButton(label: string): HTMLButtonElement | null {
  const buttons = [...document.querySelectorAll('[role="dialog"] button')]
  return (buttons.find(b => (b.textContent ?? '').trim() === label) ?? null) as HTMLButtonElement | null
}

/** The mocked endpoints answer asynchronously; a click needs a few ticks to land. */
async function settle() {
  for (let i = 0; i < 4; i++) await flushPromises()
}

async function mount() {
  const c = await mountSuspended(Harness)
  await settle()
  return c
}

beforeEach(() => {
  clearNuxtData()
  posts = []
  status = healthy()
  document.body.innerHTML = ''
})

describe('Settings → Database', () => {
  it('sits in the System group immediately before Maintenance', () => {
    const system = sectionGroups.find(g => g.label === 'System')!
    const ids = system.sections.map(s => s.id)
    expect(ids.indexOf('database')).toBe(ids.indexOf('maintenance') - 1)
  })

  it('shows the verdict, the reason, sizes, free space, last backup age and the H2 version', async () => {
    const c = await mount()
    expect(c.find('[data-testid="db-verdict"]').text()).toBe('Healthy')
    expect(c.find('[data-testid="db-reason"]').text()).toContain('no read failures in 7 days')
    const text = c.text()
    expect(text).toContain('183.7 MB')
    expect(text).toContain('52.0 GB')
    expect(text).toContain('2 h ago')
    expect(text).toContain('2.5.250')
    expect(text).toContain('jclaw-20260909T120000Z.zip')
  })

  it('reads Attention with its reason and lifts Repair when the trace shows read failures', async () => {
    status = healthy({
      verdict: 'ATTENTION',
      reason: '3 read failures logged in the last 24 hours (9 in 7 days). Pages are going bad; back up now, then Repair.',
      corruption24h: 3,
      corruption7d: 9,
      firstCorruptionAt: '2026-09-03T02:10:00Z',
    })
    const c = await mount()
    expect(c.find('[data-testid="db-verdict"]').text()).toBe('Attention')
    expect(c.find('[data-testid="db-reason"]').text()).toContain('3 read failures')
    expect(c.find('[data-testid="db-repair-section"]').classes()).toContain('border-amber-500/70')
    expect(c.find('[data-testid="db-repair"]').classes().join(' ')).toContain('bg-amber-600')
  })

  it('backs up on demand and reports the new file', async () => {
    const c = await mount()
    await c.find('[data-testid="db-backup-now"]').trigger('click')
    await settle()
    expect(posts.map(p => p.url)).toEqual(['/api/system/database/backups'])
    expect(c.text()).toContain('Backed up to jclaw-20260909T140000Z.zip (59.1 MB)')
  })

  it('confirms a restore by naming the backup, and a cancelled dialog issues no request', async () => {
    const c = await mount()
    await c.find('[data-testid="db-restore-jclaw-20260908T120000Z.zip"]').trigger('click')
    await settle()
    const dialog = document.querySelector('[role="dialog"]')
    expect(dialog?.textContent).toContain('jclaw-20260908T120000Z.zip')
    expect(dialog?.textContent).toContain('Everything written since then is lost')
    dialogButton('Cancel')!.click()
    await settle()
    expect(posts).toEqual([])

    await c.find('[data-testid="db-restore-jclaw-20260908T120000Z.zip"]').trigger('click')
    await settle()
    dialogButton('Restore')!.click()
    await settle()
    expect(posts).toEqual([{ url: '/api/system/database/restore', body: { id: 'jclaw-20260908T120000Z.zip' } }])
    expect(c.text()).toContain('Waiting for the backend to stop')
  })

  it('disables restore and repair with the reason when the install is not managed by jclaw.sh', async () => {
    status = healthy({ maintenanceAvailable: false, maintenanceUnavailableReason: 'jclaw.sh not found at /srv/jclaw/jclaw.sh — this installation is not managed by jclaw.sh.' })
    const c = await mount()
    expect(c.find('[data-testid="db-repair"]').attributes('disabled')).toBeDefined()
    expect(c.find('[data-testid="db-restore-jclaw-20260909T120000Z.zip"]').attributes('disabled')).toBeDefined()
    expect(c.text()).toContain('not managed by jclaw.sh')
  })

  it('shows the last repair per table and keeps cleanup dead with the reason until the backend allows it', async () => {
    status = healthy({
      repair: repairManifest,
      intermediateBytes: 326_000_000,
      cleanupAvailable: false,
      cleanupUnavailableReason: 'The database is not healthy: 2 read failures logged in the last 24 hours.',
    })
    const c = await mount()
    const result = c.find('[data-testid="db-repair-result"]')
    expect(result.text()).toContain('60 tables, 174802 rows restored')
    expect(result.text()).toContain('PUBLIC.PROMPT')
    expect(result.text()).toContain('(enum cast)')
    expect(result.text()).toContain('2 files, 310.9 MB')
    expect(c.find('[data-testid="db-clean"]').attributes('disabled')).toBeDefined()
    expect(c.find('[data-testid="db-clean-reason"]').text()).toContain('not healthy')

    status = healthy({ repair: repairManifest, intermediateBytes: 326_000_000, cleanupAvailable: true, cleanupUnavailableReason: null })
    clearNuxtData()
    const c2 = await mount()
    expect(c2.find('[data-testid="db-clean"]').attributes('disabled')).toBeUndefined()
    await c2.find('[data-testid="db-clean"]').trigger('click')
    await settle()
    expect(document.querySelector('[role="dialog"]')?.textContent).toContain('2 files')
    dialogButton('Delete')!.click()
    await settle()
    expect(posts.map(p => p.url)).toEqual(['/api/system/database/repair/clean'])
    expect(c2.text()).toContain('Removed 2 files; 310.9 MB reclaimed')
  })

  it('saves retention as an ordinary config write', async () => {
    const c = await mount()
    const keep = c.findAll('button').find(b => b.text() === 'keep 7')!
    await keep.trigger('click')
    const input = c.find('input[aria-label="Backups to keep"]')
    await input.setValue('14')
    await input.trigger('keydown.enter')
    await settle()
    expect(posts).toEqual([{ url: '/api/config', body: { key: 'db.backup.retention', value: '14' } }])
  })
})
