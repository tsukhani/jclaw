import { describe, it, expect } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import ChatGeneratedVideo from '~/components/chat/ChatGeneratedVideo.vue'
import type { MessageAttachment } from '~/types/api'
import type { VideoJobStatus } from '~/utils/video-job'

function att(o: Partial<MessageAttachment> = {}): MessageAttachment {
  return {
    uuid: 'vid1', kind: 'VIDEO', sizeBytes: 0, originalFilename: 'clip.mp4', mimeType: 'video/mp4',
    generated: true, generationJobId: 7, generationMetadata: JSON.stringify({ prompt: 'a spinning top' }), ...o,
  }
}

describe('ChatGeneratedVideo (JCLAW-234)', () => {
  it('shows a determinate progress bar while generating with a percent', async () => {
    const jobStatus: VideoJobStatus = { id: 7, state: 'RUNNING', percent: 42 }
    const c = await mountSuspended(ChatGeneratedVideo, { props: { att: att(), jobStatus, deleted: false } })
    expect(c.text()).toContain('Generating video')
    const bar = c.find('[role="progressbar"]')
    expect(bar.exists()).toBe(true)
    expect(bar.attributes('aria-valuenow')).toBe('42')
  })
  it('renders the inline player and prompt once ready', async () => {
    const jobStatus: VideoJobStatus = { id: 7, state: 'SUCCEEDED', resultAttachmentUuid: 'vid1' }
    const c = await mountSuspended(ChatGeneratedVideo, { props: { att: att({ sizeBytes: 999 }), jobStatus, deleted: false } })
    expect(c.find('video').exists()).toBe(true)
    expect(c.text()).toContain('a spinning top')
  })
  it('shows the failure card with the error message', async () => {
    const jobStatus: VideoJobStatus = { id: 7, state: 'FAILED', errorMessage: 'sidecar died' }
    const c = await mountSuspended(ChatGeneratedVideo, { props: { att: att(), jobStatus, deleted: false } })
    expect(c.text()).toContain('Video generation failed')
    expect(c.text()).toContain('sidecar died')
  })
  it('emits delete from the ready card', async () => {
    const jobStatus: VideoJobStatus = { id: 7, state: 'SUCCEEDED', resultAttachmentUuid: 'vid1' }
    const c = await mountSuspended(ChatGeneratedVideo, { props: { att: att({ sizeBytes: 999 }), jobStatus, deleted: false } })
    await c.find('[title="Delete video from workspace"]').trigger('click')
    expect(c.emitted('delete')).toBeTruthy()
  })
})
