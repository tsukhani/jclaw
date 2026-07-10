import { describe, it, expect } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import ChatGeneratedImage from '~/components/chat/ChatGeneratedImage.vue'
import type { MessageAttachment } from '~/types/api'

function att(o: Partial<MessageAttachment> = {}): MessageAttachment {
  return {
    uuid: 'img1', kind: 'IMAGE', sizeBytes: 12345, originalFilename: 'generated.png', mimeType: 'image/png',
    generated: true, generationMetadata: JSON.stringify({ prompt: 'a red bicycle' }), ...o,
  }
}

describe('ChatGeneratedImage (JCLAW-227/228)', () => {
  it('renders the inline preview and prompt while the bytes exist', async () => {
    const c = await mountSuspended(ChatGeneratedImage, { props: { att: att(), deleted: false } })
    expect(c.find('img').attributes('src')).toBe('/api/attachments/img1')
    expect(c.text()).toContain('a red bicycle')
    expect(c.find('[title="Delete image from workspace"]').exists()).toBe(true)
  })
  it('collapses to the deletion marker when deleted', async () => {
    const c = await mountSuspended(ChatGeneratedImage, { props: { att: att(), deleted: true } })
    expect(c.find('img').exists()).toBe(false)
    expect(c.text()).toContain('deleted from workspace')
    expect(c.find('[title="Delete image from workspace"]').exists()).toBe(false)
  })
  it('emits delete when the trash button is clicked', async () => {
    const c = await mountSuspended(ChatGeneratedImage, { props: { att: att(), deleted: false } })
    await c.find('[title="Delete image from workspace"]').trigger('click')
    expect(c.emitted('delete')).toBeTruthy()
  })
})
