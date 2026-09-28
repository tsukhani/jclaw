/**
 * 3Dmol for the chat's structure viewer (JCLAW-1321): the lazy load, a fixed pool of viewers, and the scene build.
 *
 * 3Dmol has no dispose: each viewer binds window and document listeners it never removes and starts observers it
 * never disconnects, so a viewer made per figure would leak one scene per figure ever shown. Viewers are pooled
 * instead and their containers move between figures, which also bounds live WebGL contexts (browsers cap them near 16).
 */
import type { GLModel, GLShape, GLViewer } from '3dmol'
import { cellMatrix, faceNormal, hullFaces, invert3, isPolyhedronCenter, ligandShell, type Site, type Vec3 } from './crystal'
import type { StructureFormat } from './fence'

type ThreeDmol = typeof import('3dmol')

export interface ViewerOptions { style: 'ball' | 'space', cellBox: boolean, cells: 1 | 2 | 3, polyhedra: boolean }
export interface SceneInfo { crystal: boolean, polyhedra: number, maxCells: 1 | 2 | 3 }
export interface Lease { viewer: GLViewer, release(): void }

const POOL_SIZE = 4
const MAX_ATOMS = 20_000
const MAX_FILE_CHARS = 5_000_000
const FILE_CACHE_MAX = 20

let loading: Promise<ThreeDmol> | null = null
let lib: ThreeDmol | null = null

/** Imports 3Dmol once. A failed import is not retried, so its fences stay code blocks until a reload. */
export function load3Dmol(): Promise<ThreeDmol> {
  loading ??= import('3dmol/build/3Dmol.es6-min.js').then((module) => {
    lib = module
    return module
  })
  return loading
}

interface Slot { viewer: GLViewer, container: HTMLDivElement, owner: object | null, onEvict: () => void, since: number }
const slots: Slot[] = []
let clock = 0

/** Lends a viewer, drawn into `host`. When the pool is full the longest-held lease is taken back and `onEvict` tells its holder. */
export async function acquireViewer(host: HTMLElement, onEvict: () => void): Promise<Lease> {
  const $3Dmol = await load3Dmol()
  let slot = slots.find(s => !s.owner)
  if (!slot && slots.length < POOL_SIZE) {
    const container = document.createElement('div')
    container.className = 'structure-gl'
    // Attached before creation so the viewer measures a real size.
    host.append(container)
    const viewer = $3Dmol.createViewer(container)
    slot = { viewer, container, owner: null, onEvict: () => {}, since: 0 }
    slots.push(slot)
  }
  if (!slot) {
    slot = slots.reduce((oldest, s) => (s.since < oldest.since ? s : oldest))
    slot.onEvict()
  }
  const owner = {}
  const held = slot
  Object.assign(held, { owner, onEvict, since: ++clock })
  host.append(held.container)
  held.viewer.resize()
  return {
    viewer: held.viewer,
    release() {
      if (held.owner !== owner) return
      held.viewer.clear()
      held.container.remove()
      held.owner = null
    },
  }
}

const fileCache = new Map<string, Promise<string>>()

/** Memoized, so a figure scrolled back into view does not fetch its file again. */
export function fetchStructureFile(url: string, path: string): Promise<string> {
  const cached = fileCache.get(url)
  if (cached) return cached
  const text = fetch(url, { credentials: 'same-origin' }).then(async (res) => {
    if (!res.ok) throw new Error(`Could not load ${path}: HTTP ${res.status}.`)
    const body = await res.text()
    if (body.length > MAX_FILE_CHARS) throw new Error(`${path} is too large to draw: over ${MAX_FILE_CHARS} characters.`)
    return body
  })
  // A failure is not cached, so a file the agent writes later still loads.
  text.catch(() => fileCache.delete(url))
  if (fileCache.size >= FILE_CACHE_MAX) fileCache.delete(fileCache.keys().next().value!)
  fileCache.set(url, text)
  return text
}

// 3Dmol applies symmetry only from the legacy tag (CIF.ts:378), so a CIF using the modern one would draw its asymmetric unit alone.
function cifFor3Dmol(text: string): string {
  return /_symmetry_equiv_pos_as_xyz/i.test(text)
    ? text
    : text.replaceAll(/_space_group_symop_operation_xyz/gi, '_symmetry_equiv_pos_as_xyz')
}

function jmolColor(elem: string): number {
  return (lib?.elementColors.Jmol[elem] as number | undefined) ?? 0xff1493
}

// Integer hundredths: toFixed(2) prints float noise below zero as "-0.00", splitting one position into two keys.
const key = (p: Vec3) => p.map(x => Math.round(x * 100)).join(',')

function drawShell(faces: GLShape, lines: GLShape, center: Vec3, corners: Vec3[]) {
  if (corners.length < 4) {
    for (const [x, y, z] of corners) {
      lines.addDashedCylinder({ start: { x: center[0], y: center[1], z: center[2] }, end: { x, y, z }, radius: 0.03 })
    }
    return
  }
  const vertexArr: { x: number, y: number, z: number }[] = []
  const normalArr: { x: number, y: number, z: number }[] = []
  const faceArr: number[] = []
  const edges = new Set<string>()
  for (const face of hullFaces(corners)) {
    const [nx, ny, nz] = faceNormal(corners, face)
    for (const i of face) {
      faceArr.push(vertexArr.length)
      vertexArr.push({ x: corners[i]![0], y: corners[i]![1], z: corners[i]![2] })
      normalArr.push({ x: nx, y: ny, z: nz })
    }
    for (const [u, w] of [[face[0], face[1]], [face[1], face[2]], [face[2], face[0]]] as const) {
      const edge = `${Math.min(u, w)}-${Math.max(u, w)}`
      if (edges.has(edge)) continue
      edges.add(edge)
      const [a, b] = [corners[u]!, corners[w]!]
      lines.addCylinder({ start: { x: a[0], y: a[1], z: a[2] }, end: { x: b[0], y: b[1], z: b[2] }, radius: 0.02 })
    }
  }
  faces.addCustom({ vertexArr, normalArr, faceArr })
}

function drawPolyhedra(viewer: GLViewer, model: GLModel, sites: Site[], cell: Parameters<typeof ligandShell>[2]) {
  // A shape repaints every vertex in its own color when drawn (GLShape.ts:1448), so each element color gets its own shapes.
  const shapes = new Map<string, GLShape>()
  const shape = (kind: string, elem: string, opacity: number) => {
    const id = `${kind}:${elem}`
    let s = shapes.get(id)
    if (!s) {
      s = viewer.addShape({ color: jmolColor(elem), opacity })
      shapes.set(id, s)
    }
    return s
  }
  const shown = new Set(model.selectedAtoms({}).map(a => key([a.x!, a.y!, a.z!])))
  const ghosted = new Set<string>()
  for (const atom of model.selectedAtoms({})) {
    if (!isPolyhedronCenter(atom.elem!)) continue
    const center: Site = { elem: atom.elem!, pos: [atom.x!, atom.y!, atom.z!] }
    const shell = ligandShell(center, sites, cell)
    drawShell(shape('faces', center.elem, 0.3), shape('lines', center.elem, 1), center.pos, shell.map(s => s.pos))
    // A corner whose atom sits in an undisplayed cell gets a translucent stand-in, so no polyhedron floats in empty space.
    for (const site of shell) {
      const k = key(site.pos)
      if (shown.has(k) || ghosted.has(k)) continue
      ghosted.add(k)
      shape('ghost', site.elem, 0.4).addSphere({ center: { x: site.pos[0], y: site.pos[1], z: site.pos[2] }, radius: 0.28 })
    }
  }
}

function cellOf(model: GLModel, isCif: boolean) {
  const cryst = isCif ? model.getCrystData() : null
  if (!cryst) return undefined
  const m = cellMatrix(cryst)
  return { m, inv: invert3(m) }
}

/** Draws the structure; throws when 3Dmol finds no atoms. `colors` match the theme: `ink` draws the unit-cell box. */
export function buildScene(viewer: GLViewer, format: StructureFormat, text: string, options: ViewerOptions,
  { ink, background }: { ink: string, background: string }): SceneInfo {
  viewer.clear()
  // Opaque: over a transparent canvas, blending squares a translucent polyhedron's alpha and it all but vanishes.
  viewer.setBackgroundColor(background, 1)
  const isCif = format === 'cif'
  const model = viewer.addModel(isCif ? cifFor3Dmol(text) : text, format === 'mol' ? 'sdf' : format,
    // unboundCations: an ionic lattice's cation contacts are drawn as polyhedra, not as covalent sticks.
    isCif ? { doAssembly: true, duplicateAssemblyAtoms: true, normalizeAssembly: true, unboundCations: true } : {})
  const atoms = model.selectedAtoms({})
  if (!atoms.length) throw new Error('No atoms found in this structure.')
  const sites: Site[] = atoms.map(a => ({ elem: a.elem!, pos: [a.x!, a.y!, a.z!] }))
  const cell = cellOf(model, isCif)
  const maxCells = cell ? ([3, 2, 1] as const).find(n => atoms.length * n ** 3 <= MAX_ATOMS) ?? 1 : 1
  const polyhedra = sites.filter(s => isPolyhedronCenter(s.elem) && ligandShell(s, sites, cell).length >= 4).length

  if (cell && options.cellBox) {
    const axisLabel = { fontColor: ink, backgroundOpacity: 0, fontSize: 13 }
    viewer.addUnitCell(model, { box: { color: ink }, alabel: 'a', blabel: 'b', clabel: 'c',
      alabelstyle: axisLabel, blabelstyle: axisLabel, clabelstyle: axisLabel })
  }
  const n = Math.min(options.cells, maxCells)
  // addBonds=false: each copy keeps its own bonds; re-bonding would run without unboundCations.
  if (cell && n > 1) viewer.replicateUnitCell(n, n, n, model, false)
  // The default RasMol scheme paints Na blue; Jmol colors are the convention.
  const colorscheme = 'Jmol'
  viewer.setStyle({}, options.style === 'space'
    ? { sphere: { colorscheme } }
    : { sphere: { scale: 0.25, colorscheme }, stick: { radius: 0.12, colorscheme } })
  if (options.polyhedra && polyhedra) drawPolyhedra(viewer, model, sites, cell)
  viewer.zoomTo()
  // zoomTo fits the atoms only; a single cell's axis arrows reach past them.
  if (cell && options.cellBox && n === 1) viewer.zoom(0.85)
  viewer.render()
  return { crystal: !!cell, polyhedra, maxCells }
}
