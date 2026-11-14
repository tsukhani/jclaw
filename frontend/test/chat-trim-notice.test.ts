import { describe, it, expect } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import ChatTrimNotice from '~/components/chat/ChatTrimNotice.vue'

describe('ChatTrimNotice', () => {
  it('says how many earlier messages the agent did not read and that they are still saved', async () => {
    const w = await mountSuspended(ChatTrimNotice, { props: { dropped: 12 } })
    expect(w.get('[data-testid="trim-notice-title"]').text()).toBe('Earlier messages left out')
    expect(w.get('[data-testid="trim-notice-detail"]').text()).toBe('12 earlier messages were left out of what the '
      + 'agent read for this reply, to fit the model\'s context window. They are still saved in this conversation.')
    expect(w.get('[role="status"]').attributes('aria-live')).toBe('polite')
  })

  it('speaks of one message in the singular', async () => {
    const w = await mountSuspended(ChatTrimNotice, { props: { dropped: 1 } })
    expect(w.get('[data-testid="trim-notice-detail"]').text()).toBe('1 earlier message was left out of what the '
      + 'agent read for this reply, to fit the model\'s context window. It is still saved in this conversation.')
  })

  it('speaks of two messages in the plural', async () => {
    const w = await mountSuspended(ChatTrimNotice, { props: { dropped: 2 } })
    expect(w.get('[data-testid="trim-notice-detail"]').text()).toBe('2 earlier messages were left out of what the '
      + 'agent read for this reply, to fit the model\'s context window. They are still saved in this conversation.')
  })
})
