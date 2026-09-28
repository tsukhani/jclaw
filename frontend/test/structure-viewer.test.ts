import { beforeEach, describe, expect, it, vi, type Mock } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import type { StructureSource } from '~/utils/structure/fence'
import type { Lease, SceneInfo, ViewerOptions } from '~/utils/structure/viewer'

const viewer = vi.hoisted(() => ({
  acquireViewer: vi.fn(),
  buildScene: vi.fn(),
  fetchStructureFile: vi.fn(),
}))
vi.mock('~/utils/structure/viewer', () => viewer)

const StructureViewer = (await import('~/components/chat/StructureViewer.vue')).default

const XYZ = '3\nwater\nO 0 0 0.117\nH 0 0.757 -0.47\nH 0 -0.757 -0.47'
const inline: StructureSource = { kind: 'inline', format: 'xyz', text: XYZ, caption: 'Water' }
const file: StructureSource = { kind: 'file', format: 'cif', path: 'crystals/nacl.cif', caption: '' }
const initial: ViewerOptions = { style: 'ball', cellBox: true, cells: 1, polyhedra: true }

let evict: () => void
let release: Mock<() => void>

function scene(info: Partial<SceneInfo> = {}): SceneInfo {
  return { crystal: false, polyhedra: 0, maxCells: 1, ...info }
}

function mountViewer(source: StructureSource, agentId: number | null = 7) {
  return mount(StructureViewer, { props: { source, agentId, initial } })
}

describe('StructureViewer (JCLAW-1321)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    release = vi.fn<() => void>()
    viewer.acquireViewer.mockImplementation(async (_host: HTMLElement, onEvict: () => void): Promise<Lease> => {
      evict = onEvict
      return { viewer: {} as Lease['viewer'], release }
    })
    viewer.buildScene.mockReturnValue(scene())
  })

  it('draws inline text into a borrowed viewer and rebuilds when a control changes', async () => {
    const c = mountViewer(inline)
    await flushPromises()
    expect(viewer.acquireViewer).toHaveBeenCalledWith(c.find('.structure-gl-host').element, expect.any(Function))
    expect(viewer.buildScene).toHaveBeenCalledWith(expect.anything(), 'xyz', XYZ, initial,
      { ink: expect.stringMatching(/^#[0-9a-f]{6}$/), background: expect.stringMatching(/^#[0-9a-f]{6}$/) })
    expect(c.find('[role="img"]').attributes('aria-label')).toBe('3D structure of Water, drag to rotate')

    await c.get('button[aria-pressed="false"]').trigger('click')
    expect(viewer.buildScene).toHaveBeenLastCalledWith(expect.anything(), 'xyz', XYZ, { ...initial, style: 'space' }, expect.anything())
    expect(c.emitted('change')![0]).toEqual([{ ...initial, style: 'space' }])
    // A molecule gets no crystal controls.
    expect(c.text()).not.toContain('2×2×2')
  })

  it('offers only the supercells under the atom cap, and the polyhedra toggle when there are any', async () => {
    viewer.buildScene.mockReturnValue(scene({ crystal: true, polyhedra: 4, maxCells: 2 }))
    const c = mountViewer(inline)
    await flushPromises()
    const cells = c.findAll('[aria-label="Cells shown"] button')
    expect(cells.map(b => b.attributes('disabled') !== undefined)).toEqual([false, false, true])
    expect(c.text()).toContain('Coordination polyhedra')
  })

  it('loads a workspace file through the agent\'s file route and links it', async () => {
    viewer.fetchStructureFile.mockResolvedValue(XYZ)
    const c = mountViewer(file)
    await flushPromises()
    expect(viewer.fetchStructureFile).toHaveBeenCalledWith('/api/agents/7/files/crystals/nacl.cif', 'crystals/nacl.cif')
    expect(c.get('a.workspace-file').attributes('href')).toBe('/api/agents/7/files/crystals/nacl.cif')
  })

  it('reports a file it cannot read, and a structure 3Dmol cannot draw', async () => {
    const noAgent = mountViewer(file, null)
    await flushPromises()
    expect(noAgent.emitted('failed')![0]![0]).toContain('no agent to read it from')

    viewer.buildScene.mockImplementation(() => {
      throw new Error('No atoms found in this structure.')
    })
    const broken = mountViewer(inline)
    await flushPromises()
    expect(broken.emitted('failed')![0]).toEqual(['No atoms found in this structure.'])
    expect(release).toHaveBeenCalled()
  })

  it('offers to draw again after the pool takes its viewer back, and returns the viewer on unmount', async () => {
    const c = mountViewer(inline)
    await flushPromises()
    evict()
    await flushPromises()
    await c.get('button').trigger('click')
    await flushPromises()
    expect(viewer.acquireViewer).toHaveBeenCalledTimes(2)
    c.unmount()
    expect(release).toHaveBeenCalledTimes(1)
  })
})
