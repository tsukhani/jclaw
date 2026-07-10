import { describe, it, expect } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import ChatAudioAttachment from '~/components/chat/ChatAudioAttachment.vue'
import type { MessageAttachment } from '~/types/api'

function att(o: Partial<MessageAttachment> = {}): MessageAttachment {
  return { uuid: 'aud1', kind: 'AUDIO', sizeBytes: 4096, originalFilename: 'lineup.wav', mimeType: 'audio/wav', ...o }
}

describe('ChatAudioAttachment (JCLAW-562)', () => {
  it('renders an inline audio player pointing at the attachment', async () => {
    const c = await mountSuspended(ChatAudioAttachment, { props: { att: att() } })
    const audio = c.find('audio')
    expect(audio.exists()).toBe(true)
    expect(audio.attributes('src')).toBe('/api/attachments/aud1')
  })
  it('renders the filename chip below the player', async () => {
    const c = await mountSuspended(ChatAudioAttachment, { props: { att: att() } })
    expect(c.text()).toContain('lineup.wav')
  })
  it('marks AI-generated audio with a gen badge', async () => {
    const c = await mountSuspended(ChatAudioAttachment, { props: { att: att({ generated: true }) } })
    expect(c.text()).toContain('gen')
  })
})
