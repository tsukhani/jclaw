import { describe, it, expect } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import ChatAttachmentChip from '~/components/chat/ChatAttachmentChip.vue'
import type { MessageAttachment } from '~/types/api'

function att(o: Partial<MessageAttachment> = {}): MessageAttachment {
  return { uuid: 'abc', kind: 'FILE', sizeBytes: 2048, originalFilename: 'report.pdf', mimeType: 'application/pdf', ...o }
}

describe('ChatAttachmentChip (JCLAW-279)', () => {
  it('renders a download link with the filename', async () => {
    const c = await mountSuspended(ChatAttachmentChip, { props: { att: att() } })
    expect(c.find('a').attributes('href')).toBe('/api/attachments/abc')
    expect(c.text()).toContain('report.pdf')
  })
  it('shows the gen corner-mark for tool-produced files', async () => {
    const c = await mountSuspended(ChatAttachmentChip, { props: { att: att({ generated: true }) } })
    expect(c.text()).toContain('gen')
  })
  it('omits the gen mark for user uploads', async () => {
    const c = await mountSuspended(ChatAttachmentChip, { props: { att: att({ generated: false }) } })
    expect(c.text()).not.toContain('gen')
  })
})
