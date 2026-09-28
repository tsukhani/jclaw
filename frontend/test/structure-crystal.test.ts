import { describe, expect, it } from 'vitest'
import {
  cellMatrix, contactCutoff, faceNormal, hullFaces, invert3, isPolyhedronCenter, ligandShell, mulv, type Cell, type Site, type Vec3,
} from '~/utils/structure/crystal'

function unitCell(cell: Cell, sites: [string, Vec3][], ops: ((f: Vec3) => Vec3)[]) {
  const m = cellMatrix(cell)
  const wrap = (f: Vec3) => f.map(v => v - Math.floor(v)) as Vec3
  const atoms: Site[] = sites.flatMap(([elem, f]) => ops.map(op => ({ elem, pos: mulv(m, wrap(op(f))) })))
  return { atoms, cell: { m, inv: invert3(m) } }
}

const distances = (center: Site, shell: Site[]) =>
  shell.map(s => Math.hypot(s.pos[0] - center.pos[0], s.pos[1] - center.pos[1], s.pos[2] - center.pos[2])).sort((a, b) => a - b)

describe('coordination geometry (JCLAW-1321)', () => {
  it('finds the six oxygens around Na in sodium formate dihydrate, COD 7004910, across periodic images', () => {
    const { atoms, cell } = unitCell(
      { a: 7.6852, b: 3.5113, c: 8.1103, alpha: 90, beta: 111.78, gamma: 90 },
      [['Na', [0.1830, 0.296, 0.642]], ['C', [0.081, 0.317, 0.211]], ['O', [0.014, 0.291, 0.330]],
        ['O', [0.241, 0.114, 0.246]], ['O', [0.336, 0.318, 0.949]], ['O', [0.376, 0.823, 0.605]]],
      [([x, y, z]) => [x, y, z], ([x, y, z]) => [-x, y + 0.5, -z]],
    )
    const na = atoms.find(a => a.elem === 'Na')!
    const shell = ligandShell(na, atoms, cell)
    expect(distances(na, shell).map(d => d.toFixed(3))).toEqual(['2.319', '2.327', '2.371', '2.377', '2.396', '2.459'])
    // The next oxygen sits at 3.462 Å, well past the cutoff.
    expect(contactCutoff('Na', 'O')).toBeGreaterThan(2.459)
    expect(contactCutoff('Na', 'O')).toBeLessThan(3.462)
  })

  it('finds rock salt\'s octahedron for a Na at the cell corner, and its hull has eight outward faces', () => {
    const { atoms, cell } = unitCell(
      { a: 5.64, b: 5.64, c: 5.64, alpha: 90, beta: 90, gamma: 90 },
      [['Na', [0, 0, 0]], ['Na', [0, 0.5, 0.5]], ['Na', [0.5, 0, 0.5]], ['Na', [0.5, 0.5, 0]],
        ['Cl', [0.5, 0.5, 0.5]], ['Cl', [0.5, 0, 0]], ['Cl', [0, 0.5, 0]], ['Cl', [0, 0, 0.5]]],
      [f => f],
    )
    const na = atoms[0]!
    const shell = ligandShell(na, atoms, cell)
    expect(distances(na, shell).map(d => d.toFixed(2))).toEqual(Array(6).fill('2.82'))
    const corners = shell.map(s => s.pos)
    const faces = hullFaces(corners)
    expect(faces).toHaveLength(8)
    for (const face of faces) {
      const centroid = face.map(i => corners[i]!).reduce((s, p) => [s[0] + p[0] / 3, s[1] + p[1] / 3, s[2] + p[2] / 3], [0, 0, 0])
      const n = faceNormal(corners, face)
      expect(n[0] * (centroid[0] - na.pos[0]) + n[1] * (centroid[1] - na.pos[1]) + n[2] * (centroid[2] - na.pos[2])).toBeGreaterThan(0)
    }
  })

  it('without a cell, uses only the sites given', () => {
    const co: Site = { elem: 'Co', pos: [0, 0, 0] }
    const ligands: Site[] = ([[1.95, 0, 0], [-1.95, 0, 0], [0, 1.95, 0], [0, -1.95, 0], [0, 0, 1.95], [0, 0, -1.95]] as Vec3[])
      .map(pos => ({ elem: 'N', pos }))
    const shell = ligandShell(co, [co, ...ligands, { elem: 'H', pos: [2.9, 0, 0] }])
    expect(shell).toHaveLength(6)
    expect(hullFaces(shell.map(s => s.pos))).toHaveLength(8)
  })

  it('centers polyhedra on metals only', () => {
    expect(['Na', 'Ca', 'Ti', 'Fe'].every(isPolyhedronCenter)).toBe(true)
    expect(['O', 'Cl', 'C', 'H', 'Xx'].some(isPolyhedronCenter)).toBe(false)
  })
})
