import type { MessageAttachment } from '~/types/api'

/**
 * The human-readable label for a generated image/video attachment: the original
 * generation prompt parsed out of `generationMetadata`, falling back to the
 * filename if the metadata is missing or unparseable. Shared by the generated
 * image and video cards (and the in-flight image-gen progress row).
 */
export function generatedImageLabel(att: MessageAttachment): string {
  if (att.generationMetadata) {
    try {
      const meta = JSON.parse(att.generationMetadata) as { prompt?: unknown }
      if (typeof meta.prompt === 'string' && meta.prompt.trim()) return meta.prompt
    }
    catch { /* fall through to the filename */ }
  }
  return att.originalFilename
}
