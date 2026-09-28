import { beforeAll, describe, expect, it, vi } from 'vitest'
import type { GLViewer } from '3dmol'
import { cellMatrix, mulv, type Cell, type Site, type Vec3 } from '~/utils/structure/crystal'
import { buildScene, load3Dmol, type ViewerOptions } from '~/utils/structure/viewer'

const JMOL = { Na: 0xab5cf2, Cl: 0x1ff01f, Co: 0xf090a0, Ag: 0xc0c0c0, N: 0x3050f8 }
vi.mock('3dmol/build/3Dmol.es6-min.js', () => ({ elementColors: { Jmol: JMOL } }))

interface Point { x: number, y: number, z: number }
// One recorder for every shape method; each call fills only the fields its method takes.
interface ShapeArg { vertexArr: Point[], normalArr: Point[], faceArr: number[], start: Point, end: Point, center: Point }
interface ShapeCall { color: number, opacity: number, method: string, arg: ShapeArg }

function fakeViewer(sites: Site[], cryst: Cell | null) {
  const calls: ShapeCall[] = []
  const model = {
    selectedAtoms: () => sites.map(s => ({ elem: s.elem, x: s.pos[0], y: s.pos[1], z: s.pos[2] })),
    getCrystData: () => cryst,
  }
  const viewer = {
    clear: vi.fn(), setBackgroundColor: vi.fn(), addModel: vi.fn(() => model), addUnitCell: vi.fn(),
    replicateUnitCell: vi.fn(), setStyle: vi.fn(), zoomTo: vi.fn(), zoom: vi.fn(), render: vi.fn(),
    addShape: vi.fn(({ color, opacity }: { color: number, opacity: number }) => {
      const record = (method: string) => (arg: unknown) => calls.push({ color, opacity, method, arg: arg as ShapeArg })
      return {
        addCustom: record('addCustom'), addCylinder: record('addCylinder'),
        addDashedCylinder: record('addDashedCylinder'), addSphere: record('addSphere'),
      }
    }),
  }
  return { viewer, model, calls, of: (method: string) => calls.filter(c => c.method === method) }
}

const colors = { ink: '#111111', background: '#ffffff' }
const options = (o: Partial<ViewerOptions> = {}): ViewerOptions => ({ style: 'ball', cellBox: true, cells: 1, polyhedra: true, ...o })
const draw = (fake: ReturnType<typeof fakeViewer>, format: 'cif' | 'xyz', o?: Partial<ViewerOptions>) =>
  buildScene(fake.viewer as unknown as GLViewer, format, 'data', options(o), colors)
const key = (p: Vec3) => p.map(x => Math.round(x * 100)).join(',')

const ROCK_SALT: Cell = { a: 5.64, b: 5.64, c: 5.64, alpha: 90, beta: 90, gamma: 90 }
const rockSalt: Site[] = ([
  ['Na', [0, 0, 0]], ['Na', [0, 0.5, 0.5]], ['Na', [0.5, 0, 0.5]], ['Na', [0.5, 0.5, 0]],
  ['Cl', [0.5, 0.5, 0.5]], ['Cl', [0.5, 0, 0]], ['Cl', [0, 0.5, 0]], ['Cl', [0, 0, 0.5]],
] as [string, Vec3][]).map(([elem, f]) => ({ elem, pos: mulv(cellMatrix(ROCK_SALT), f) }))

describe('buildScene (JCLAW-1321)', () => {
  beforeAll(async () => {
    await load3Dmol()
  })

  it('draws rock salt\'s four Na octahedra, each edge once, with a stand-in for every corner outside the cell', () => {
    const fake = fakeViewer(rockSalt, ROCK_SALT)
    expect(draw(fake, 'cif')).toEqual({ crystal: true, polyhedra: 4, maxCells: 3 })

    const hulls = fake.of('addCustom')
    expect(hulls).toHaveLength(4)
    for (const { color, opacity, arg } of hulls) {
      expect({ color, opacity }).toEqual({ color: JMOL.Na, opacity: 0.3 })
      expect(arg.vertexArr).toHaveLength(24)
      expect(arg.normalArr).toHaveLength(24)
      expect(arg.faceArr).toEqual([...Array(24).keys()])
    }
    const edges = fake.of('addCylinder')
    expect(edges).toHaveLength(48)
    expect(edges.every(e => e.color === JMOL.Na && e.opacity === 1)).toBe(true)
    expect(fake.of('addDashedCylinder')).toHaveLength(0)

    // Each Na at a face or corner has three Cl neighbours in the next cell over.
    const ghosts = fake.of('addSphere')
    const ghostKeys = ghosts.map(g => key([g.arg.center.x, g.arg.center.y, g.arg.center.z]))
    const shown = new Set(rockSalt.map(s => key(s.pos)))
    expect(ghosts).toHaveLength(12)
    expect(new Set(ghostKeys).size).toBe(12)
    expect(ghostKeys.some(k => shown.has(k))).toBe(false)
    expect(ghosts.every(g => g.color === JMOL.Cl && g.opacity === 0.4)).toBe(true)
  })

  it('strokes a shell under four ligands as dashed spokes, and gives a non-CIF structure no cell', () => {
    const at = (elem: string, x: number, y: number, z: number): Site => ({ elem, pos: [x, y, z] })
    const fake = fakeViewer([
      at('Co', 0, 0, 0), at('N', 1.95, 0, 0), at('N', -1.95, 0, 0), at('N', 0, 1.95, 0),
      at('N', 0, -1.95, 0), at('N', 0, 0, 1.95), at('N', 0, 0, -1.95),
      at('Ag', 10, 0, 0), at('N', 8, 0, 0), at('N', 12, 0, 0),
    ], ROCK_SALT)
    expect(draw(fake, 'xyz', { cells: 3 })).toEqual({ crystal: false, polyhedra: 1, maxCells: 1 })

    expect(fake.of('addCustom').map(c => [c.color, c.arg.vertexArr.length])).toEqual([[JMOL.Co, 24]])
    expect(fake.of('addCylinder')).toHaveLength(12)
    const spokes = fake.of('addDashedCylinder')
    expect(spokes.map(s => [s.color, s.arg.start.x, s.arg.end.x])).toEqual([[JMOL.Ag, 10, 8], [JMOL.Ag, 10, 12]])
    expect(fake.of('addSphere')).toHaveLength(0)
    expect(fake.viewer.addUnitCell).not.toHaveBeenCalled()
    expect(fake.viewer.replicateUnitCell).not.toHaveBeenCalled()
  })

  it('caps the supercell by atom count, and pulls back only for a single boxed cell', () => {
    const carbons: Site[] = Array.from({ length: 1000 }, (_, i) => ({ elem: 'C', pos: [i * 0.01, 0, 0] }))
    const big = fakeViewer(carbons, ROCK_SALT)
    expect(draw(big, 'cif', { cells: 3 })).toEqual({ crystal: true, polyhedra: 0, maxCells: 2 })
    expect(big.viewer.replicateUnitCell).toHaveBeenCalledWith(2, 2, 2, expect.anything(), false)
    expect(big.viewer.addUnitCell).toHaveBeenCalledOnce()
    expect(big.viewer.zoom).not.toHaveBeenCalled()

    const one = fakeViewer(rockSalt, ROCK_SALT)
    draw(one, 'cif', { cells: 1 })
    expect(one.viewer.zoom).toHaveBeenCalledWith(0.85)
    expect(one.viewer.replicateUnitCell).not.toHaveBeenCalled()

    const bare = fakeViewer(rockSalt, ROCK_SALT)
    draw(bare, 'cif', { cells: 1, cellBox: false })
    expect(bare.viewer.addUnitCell).not.toHaveBeenCalled()
    expect(bare.viewer.zoom).not.toHaveBeenCalled()
  })

  it('refuses a structure with no atoms', () => {
    expect(() => draw(fakeViewer([], null), 'xyz')).toThrow('No atoms found in this structure.')
  })
})
