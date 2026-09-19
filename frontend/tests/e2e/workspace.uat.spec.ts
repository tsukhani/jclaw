import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { test, expect, gotoPage, uniqueName, borrowModelConfig } from './helpers'

/**
 * UAT — Agent workspace manager (JCLAW-1246).
 *
 * A throwaway agent owns the fixture; its workspace is materialised on create with the five
 * Standing Orders files, and everything else the spec seeds it removes in afterAll. Text files
 * are written through the workspace API. Folders, a nested file and a symlink cannot be created
 * through any API, so they are seeded on disk when the instance is co-located with this checkout
 * and the folder-dependent cases skip otherwise — the file, filter, backup and protection cases
 * run everywhere.
 *
 * Serial because every step shares the one fixture agent.
 */
test.describe.configure({ mode: 'serial' })

const HERE = path.dirname(fileURLToPath(import.meta.url))
const WORKSPACE_ROOT = path.resolve(HERE, '../../../workspace')

/** Entry names from a zip's local file headers, enough to assert membership without a zip library. */
function zipEntryNames(buf: Buffer): string[] {
  const names: string[] = []
  for (let i = 0; i + 30 <= buf.length; i++) {
    if (buf[i] === 0x50 && buf[i + 1] === 0x4b && buf[i + 2] === 0x03 && buf[i + 3] === 0x04) {
      const n = buf.readUInt16LE(i + 26)
      names.push(buf.subarray(i + 30, i + 30 + n).toString('utf8'))
      i += 29 + n
    }
  }
  return names
}

test.describe('UAT workspace manager', () => {
  let agentId: number | null = null
  let agentName: string
  let workspaceDir: string
  let coLocated = false

  test.afterAll(async ({ playwright }) => {
    if (agentId === null) return
    const ctx = await playwright.request.newContext({
      baseURL: process.env.JCLAW_E2E_BASE_URL || 'http://localhost:3000',
      storageState: './tests/e2e/.auth/admin.json',
    })
    await ctx.delete(`/api/agents/${agentId}`)
    await ctx.dispose()
    if (coLocated && fs.existsSync(workspaceDir)) fs.rmSync(workspaceDir, { recursive: true, force: true })
  })

  test('create the fixture agent and seed its workspace', async ({ request }) => {
    const { modelProvider, modelId } = await borrowModelConfig(request)
    agentName = uniqueName('ws')
    const res = await request.post('/api/agents', {
      data: { name: agentName, modelProvider, modelId, description: 'Workspace fixture for the JClaw UAT suite.' },
    })
    expect(res.status(), await res.text()).toBe(200)
    agentId = (await res.json()).id

    const put = await request.put(`/api/agents/${agentId}/workspace/e2e-note.txt`, { data: { content: 'hello from the uat suite\n' } })
    expect(put.status(), await put.text()).toBe(200)

    workspaceDir = path.join(WORKSPACE_ROOT, agentName)
    coLocated = fs.existsSync(path.join(workspaceDir, 'AGENT.md'))
    if (coLocated) {
      fs.mkdirSync(path.join(workspaceDir, 'scratch', 'nested'), { recursive: true })
      fs.writeFileSync(path.join(workspaceDir, 'scratch', 'nested', 'b.txt'), 'nested bytes\n')
      fs.writeFileSync(path.join(workspaceDir, 'scratch', 'photo.png'), Buffer.from([0x89, 0x50, 0x4e, 0x47]))
      fs.symlinkSync('nested', path.join(workspaceDir, 'scratch', 'link'))
    }
  })

  test('the tree lists files with sizes, a total, and the Standing Orders as protected', async ({ page }) => {
    await gotoPage(page, `/agents/${agentName}`)
    const manager = page.getByTestId('workspace-manager')
    await expect(manager).toBeVisible()
    await expect(manager.getByTestId('ws-row-e2e-note.txt')).toBeVisible({ timeout: 15_000 })
    await expect(manager.getByTestId('ws-row-e2e-note.txt')).toContainText('B')
    await expect(manager.getByTestId('workspace-total')).toContainText('Total')

    for (const name of ['SOUL.md', 'IDENTITY.md', 'USER.md', 'BOOTSTRAP.md', 'AGENT.md']) {
      await expect(manager.getByTestId(`ws-protected-${name}`)).toBeVisible()
      await expect(manager.getByTestId(`ws-download-${name}`)).toBeVisible()
      await expect(manager.getByTestId(`ws-delete-${name}`)).toHaveCount(0)
    }
    // Colour by kind: the name span carries the kind it is coloured for.
    await expect(manager.getByTestId('ws-row-SOUL.md').locator('[data-kind="text"]')).toBeVisible()
    if (coLocated) {
      await expect(manager.getByTestId('ws-row-scratch').locator('[data-kind="dir"]')).toBeVisible()
      await manager.getByTestId('ws-row-scratch').getByRole('button').first().click()
      await expect(manager.getByTestId('ws-row-scratch/photo.png').locator('[data-kind="binary"]')).toBeVisible()
    }
  })

  test('the filter narrows the tree to matching names and clears back', async ({ page }) => {
    await gotoPage(page, `/agents/${agentName}`)
    const manager = page.getByTestId('workspace-manager')
    await expect(manager.getByTestId('ws-row-e2e-note.txt')).toBeVisible({ timeout: 15_000 })

    await manager.getByTestId('workspace-filter').fill('e2e-note')
    await expect(manager.getByTestId('ws-row-e2e-note.txt')).toBeVisible()
    await expect(manager.getByTestId('ws-row-AGENT.md')).toHaveCount(0)

    await manager.getByTestId('workspace-filter').fill('nothing-here-at-all')
    await expect(manager.getByTestId('workspace-empty')).toContainText('Nothing matches')

    await manager.getByTestId('workspace-filter-clear').click()
    await expect(manager.getByTestId('ws-row-AGENT.md')).toBeVisible()
  })

  test('a file downloads as itself, a folder as a zip, and the backup zip holds the persona', async ({ request }) => {
    const file = await request.get(`/api/agents/${agentId}/workspace-download/e2e-note.txt`)
    expect(file.status()).toBe(200)
    expect(file.headers()['content-disposition']).toContain('attachment')
    expect(file.headers()['content-disposition']).toContain('e2e-note.txt')
    expect(await file.text()).toContain('hello from the uat suite')

    if (coLocated) {
      const folder = await request.get(`/api/agents/${agentId}/workspace-download/scratch`)
      expect(folder.status()).toBe(200)
      expect(folder.headers()['content-disposition']).toContain('scratch.zip')
      const names = zipEntryNames(await folder.body())
      expect(names).toContain('nested/b.txt')
      expect(names).toContain('photo.png')
      expect(names.some(n => n.startsWith('link'))).toBe(false)
    }

    const backup = await request.get(`/api/agents/${agentId}/workspace-backup`)
    expect(backup.status()).toBe(200)
    expect(backup.headers()['content-disposition']).toContain(`${agentName}-workspace.zip`)
    const names = zipEntryNames(await backup.body())
    expect(names).toContain('AGENT.md')
    expect(names).toContain('e2e-note.txt')
  })

  test('the server refuses to delete the Standing Orders and the root', async ({ request }) => {
    for (const target of ['AGENT.md', 'SOUL.md', '']) {
      const res = await request.delete(`/api/agents/${agentId}/workspace-tree/${target}`)
      expect(res.status(), target || '<root>').toBe(403)
    }
    const stillThere = await request.get(`/api/agents/${agentId}/workspace/AGENT.md`)
    expect(stillThere.status()).toBe(200)
  })

  test('delete asks for confirmation, then removes the row and the file', async ({ page, request }) => {
    await gotoPage(page, `/agents/${agentName}`)
    const manager = page.getByTestId('workspace-manager')
    await expect(manager.getByTestId('ws-row-e2e-note.txt')).toBeVisible({ timeout: 15_000 })

    await manager.getByTestId('ws-delete-e2e-note.txt').click()
    await manager.getByTestId('ws-delete-cancel-e2e-note.txt').click()
    await expect(manager.getByTestId('ws-row-e2e-note.txt')).toBeVisible()

    await manager.getByTestId('ws-delete-e2e-note.txt').click()
    await manager.getByTestId('ws-delete-confirm-e2e-note.txt').click()
    await expect(manager.getByTestId('ws-row-e2e-note.txt')).toHaveCount(0)
    const gone = await request.get(`/api/agents/${agentId}/workspace/e2e-note.txt`)
    expect(gone.status()).toBe(404)
  })

  test('deleting a symlink unlinks it, and deleting a folder removes its subtree', async ({ page }) => {
    test.skip(!coLocated, 'folders and symlinks can only be seeded on a co-located instance')
    await gotoPage(page, `/agents/${agentName}`)
    const manager = page.getByTestId('workspace-manager')
    await expect(manager.getByTestId('ws-row-scratch')).toBeVisible({ timeout: 15_000 })
    await manager.getByTestId('ws-row-scratch').getByRole('button').first().click()

    await manager.getByTestId('ws-delete-scratch/link').click()
    await manager.getByTestId('ws-delete-confirm-scratch/link').click()
    await expect(manager.getByTestId('ws-row-scratch/link')).toHaveCount(0)
    expect(fs.existsSync(path.join(workspaceDir, 'scratch', 'nested', 'b.txt'))).toBe(true)

    await manager.getByTestId('ws-delete-scratch').click()
    await manager.getByTestId('ws-delete-confirm-scratch').click()
    await expect(manager.getByTestId('ws-row-scratch')).toHaveCount(0)
    expect(fs.existsSync(path.join(workspaceDir, 'scratch'))).toBe(false)
  })
})
