/** Geometry for the structure viewer's coordination polyhedra (JCLAW-1321): pure, so it is testable without WebGL. */

export type Vec3 = [number, number, number]
export type Mat3 = [Vec3, Vec3, Vec3]
/** Three indices into a point list, wound so the normal points out of the hull. */
export type Face = [number, number, number]
export interface Cell { a: number, b: number, c: number, alpha: number, beta: number, gamma: number }
export interface Site { elem: string, pos: Vec3 }

// Cordero et al., Dalton Trans. 2008, 2832; Mn, Fe and Co take the mean of their low- and high-spin radii.
const COVALENT_RADII: Record<string, number> = {
  Li: 1.28, Be: 0.96, Na: 1.66, Mg: 1.41, Al: 1.21, K: 2.03, Ca: 1.76, Sc: 1.70, Ti: 1.60, V: 1.53, Cr: 1.39,
  Mn: 1.50, Fe: 1.42, Co: 1.38, Ni: 1.24, Cu: 1.32, Zn: 1.22, Ga: 1.22, Rb: 2.20, Sr: 1.95, Y: 1.90, Zr: 1.75,
  Nb: 1.64, Mo: 1.54, Ru: 1.46, Rh: 1.42, Pd: 1.39, Ag: 1.45, Cd: 1.44, In: 1.42, Sn: 1.39, Cs: 2.44, Ba: 2.15,
  La: 2.07, Hf: 1.75, W: 1.62, Pt: 1.36, Au: 1.36, Hg: 1.32, Pb: 1.46, Bi: 1.48,
  N: 0.71, O: 0.66, F: 0.57, S: 1.05, Cl: 1.02, Se: 1.20, Br: 1.20, I: 1.39,
}
const LIGANDS = new Set(['N', 'O', 'F', 'S', 'Cl', 'Se', 'Br', 'I'])
// Keeps sodium formate dihydrate's sixth O (2.46 Å) and drops the next (3.46 Å); NaCl, CaF2 and TiO2 separate as cleanly.
const CONTACT_TOLERANCE = 0.45

export function isPolyhedronCenter(elem: string): boolean {
  return elem in COVALENT_RADII && !LIGANDS.has(elem)
}

export function isLigand(elem: string): boolean {
  return LIGANDS.has(elem)
}

export function contactCutoff(center: string, ligand: string): number {
  return (COVALENT_RADII[center] ?? 0) + (COVALENT_RADII[ligand] ?? 0) + CONTACT_TOLERANCE
}

// Same fractional-to-Cartesian convention as 3Dmol's conversionMatrix3, so positions line up with the rendered model.
export function cellMatrix({ a, b, c, alpha, beta, gamma }: Cell): Mat3 {
  const r = Math.PI / 180
  const ca = Math.cos(alpha * r), cb = Math.cos(beta * r), cg = Math.cos(gamma * r), sg = Math.sin(gamma * r)
  const cz = c * Math.sqrt(1 - ca * ca - cb * cb - cg * cg + 2 * ca * cb * cg) / sg
  return [[a, b * cg, c * cb], [0, b * sg, c * (ca - cb * cg) / sg], [0, 0, cz]]
}

export function invert3(m: Mat3): Mat3 {
  const [[a, b, c], [d, e, f], [g, h, i]] = m
  const A = e * i - f * h, B = f * g - d * i, C = d * h - e * g
  const det = a * A + b * B + c * C
  const rows: Mat3 = [[A, c * h - b * i, b * f - c * e], [B, a * i - c * g, c * d - a * f], [C, b * g - a * h, a * e - b * d]]
  return rows.map(row => row.map(x => x / det)) as Mat3
}

export function mulv(m: Mat3, v: Vec3): Vec3 {
  return [0, 1, 2].map(k => m[k]![0] * v[0] + m[k]![1] * v[1] + m[k]![2] * v[2]) as Vec3
}

const sub = (p: Vec3, q: Vec3): Vec3 => [p[0] - q[0], p[1] - q[1], p[2] - q[2]]
const cross = (p: Vec3, q: Vec3): Vec3 => [p[1] * q[2] - p[2] * q[1], p[2] * q[0] - p[0] * q[2], p[0] * q[1] - p[1] * q[0]]
const dot = (p: Vec3, q: Vec3) => p[0] * q[0] + p[1] * q[1] + p[2] * q[2]

function periodicOffsets(pos: Vec3, fc: Vec3, cell: { m: Mat3, inv: Mat3 }): Vec3[] {
  const base = sub(mulv(cell.inv, pos), fc)
  const n = base.map(Math.round)
  const offsets: Vec3[] = []
  for (let i = -1; i <= 1; i++) for (let j = -1; j <= 1; j++) for (let k = -1; k <= 1; k++) {
    offsets.push(mulv(cell.m, [base[0] - n[0]! + i, base[1] - n[1]! + j, base[2] - n[2]! + k]))
  }
  return offsets
}

/**
 * Positions of the ligand atoms within contact distance of a center. With a cell, `sites` is the unit cell and every
 * periodic image counts, so a center at a cell edge still gets its whole shell; without one, only the given sites do.
 */
export function ligandShell(center: Site, sites: Site[], cell?: { m: Mat3, inv: Mat3 }): Site[] {
  const shell: Site[] = []
  const fc = cell ? mulv(cell.inv, center.pos) : null
  for (const site of sites) {
    if (!isLigand(site.elem)) continue
    const cutoff = contactCutoff(center.elem, site.elem)
    const offsets = cell && fc ? periodicOffsets(site.pos, fc, cell) : [sub(site.pos, center.pos)]
    for (const v of offsets) {
      const r = Math.hypot(...v)
      if (r > 0.1 && r <= cutoff) shell.push({ elem: site.elem, pos: [center.pos[0] + v[0], center.pos[1] + v[1], center.pos[2] + v[2]] })
    }
  }
  return shell
}

function hullFace(points: Vec3[], i: number, j: number, k: number): Face | null {
  const n = cross(sub(points[j]!, points[i]!), sub(points[k]!, points[i]!))
  if (Math.hypot(...n) < 1e-9) return null
  let above = 0, below = 0
  for (let m = 0; m < points.length; m++) {
    if (m === i || m === j || m === k) continue
    const s = dot(n, sub(points[m]!, points[i]!))
    if (s > 1e-6) above++
    else if (s < -1e-6) below++
  }
  if (above && below) return null
  return above ? [i, k, j] : [i, j, k]
}

/** Convex hull of a coordination shell by brute force: a triangle is a face when no point lies on its outer side. */
export function hullFaces(points: Vec3[]): Face[] {
  const faces: Face[] = []
  for (let i = 0; i < points.length; i++) for (let j = i + 1; j < points.length; j++) for (let k = j + 1; k < points.length; k++) {
    const face = hullFace(points, i, j, k)
    if (face) faces.push(face)
  }
  return faces
}

export function faceNormal(points: Vec3[], [a, b, c]: Face): Vec3 {
  const n = cross(sub(points[b]!, points[a]!), sub(points[c]!, points[a]!))
  const len = Math.hypot(...n)
  return [n[0] / len, n[1] / len, n[2] / len]
}
