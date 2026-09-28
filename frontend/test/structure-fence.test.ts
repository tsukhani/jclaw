import { describe, expect, it } from 'vitest'
import { MAX_INLINE_CHARS, parseStructureFence, sniffFormat, workspaceFileUrl } from '~/utils/structure/fence'

const CIF = 'data_nacl\n_cell_length_a 5.64\nloop_\n_atom_site_label\nNa1\n'
const XYZ = '3\nwater\nO 0.000 0.000 0.117\nH 0.000 0.757 -0.470\nH 0.000 -0.757 -0.470\n'

describe('parseStructureFence (JCLAW-1321)', () => {
  it('takes inline text and sniffs its format', () => {
    expect(parseStructureFence('structure', CIF)).toEqual({ kind: 'inline', format: 'cif', text: CIF.trim(), caption: '' })
  })

  it('reads a named format and a caption from the info string', () => {
    expect(parseStructureFence('structure xyz Water molecule', XYZ))
      .toMatchObject({ kind: 'inline', format: 'xyz', caption: 'Water molecule' })
  })

  it('treats words after the hint as a caption when none names a format', () => {
    expect(parseStructureFence('structure Rock salt', CIF)).toMatchObject({ format: 'cif', caption: 'Rock salt' })
  })

  it('takes a single-line body ending in a structure extension as a workspace path', () => {
    expect(parseStructureFence('structure Sodium formate', 'crystals/sodium formate.CIF\n'))
      .toEqual({ kind: 'file', format: 'cif', path: 'crystals/sodium formate.CIF', caption: 'Sodium formate' })
  })

  it.each([
    ['a URL', 'https://example.com/x.cif', 'is a URL'],
    ['a file URL', 'file:///etc/x.cif', 'is a URL'],
    ['an absolute path', '/etc/x.cif', 'not a relative workspace path'],
    ['a backslash', 'a\\x.cif', 'not a relative workspace path'],
    ['a parent segment', 'a/../../x.cif', 'leaves the workspace'],
  ])('refuses %s instead of fetching it', (_, path, message) => {
    const spec = parseStructureFence('structure', path)
    expect('error' in spec && spec.error).toContain(message)
  })

  it('reports an empty block, an unknown format and oversized text', () => {
    expect(parseStructureFence('structure', '  \n')).toEqual({ error: 'The structure block is empty.' })
    expect(parseStructureFence('structure', 'hello\nworld')).toMatchObject({ error: expect.stringContaining('format') })
    const big = `data_x\n${'x'.repeat(MAX_INLINE_CHARS)}`
    expect(parseStructureFence('structure', big)).toMatchObject({ error: expect.stringContaining('Too large') })
  })

  it.each([
    ['cif', 'data_x\n_cell_length_b 3\n'],
    ['cif', '_cell_length_a 5.64\n'],
    ['pdb', 'CRYST1   10.000\nATOM      1  O   HOH A   1\n'],
    ['sdf', 'water\n  RDKit\n\n  3  2  0  0  0  0  0  0  0  0999 V2000\nM  END\n'],
    ['mol2', '@<TRIPOS>MOLECULE\nwater\n'],
    ['xyz', XYZ],
  ])('sniffs %s', (format, text) => {
    expect(sniffFormat(text)).toBe(format)
  })

  it('encodes each path segment of a workspace file URL', () => {
    expect(workspaceFileUrl(7, 'crystals/sodium formate#1.cif')).toBe('/api/agents/7/files/crystals/sodium%20formate%231.cif')
  })
})
