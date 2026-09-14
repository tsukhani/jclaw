<script setup lang="ts">
// How every Telegram binding behaves. A binding's own reply mode, error reply policy and
// notifier cooldown (JCLAW-378) override the matching row here.
import SettingsConfigField from './settings/SettingsConfigField.vue'

// The rows read the settings config context, which only the Settings page provides otherwise.
const { asyncConfig } = useProvideSettingsConfig()
await asyncConfig

function choices(...values: string[]) {
  return values.map(v => ({ value: v, label: v }))
}
</script>

<template>
  <section
    class="mt-8 max-w-3xl space-y-4"
    data-testid="telegram-channel-defaults"
  >
    <h2 class="text-sm font-medium text-fg-strong">
      Channel defaults
    </h2>
    <p class="text-xs text-fg-muted">
      How every Telegram binding behaves. A binding's own reply mode, error reply policy and
      notifier cooldown override the matching row. Changes apply from the next message.
    </p>

    <h3 class="text-[11px] font-semibold text-fg-muted uppercase tracking-wide pt-2">
      Replies &amp; Delivery
    </h3>
    <div class="bg-surface-elevated border border-border divide-y divide-border">
      <SettingsConfigField
        config-key="telegram.replyTo.mode"
        label="replyTo.mode"
        kind="select"
        fallback="first"
        :options="choices('off', 'first', 'all')"
        label-width="w-72"
        tip="Which outgoing messages quote the message being answered: off never, first only the first chunk of a reply, all every chunk."
      />
      <SettingsConfigField
        config-key="telegram.linkPreview"
        label="linkPreview"
        kind="select"
        fallback="on"
        :options="choices('on', 'off')"
        label-width="w-72"
        tip="on leaves Telegram's link preview cards on outgoing messages; off suppresses them."
      />
      <SettingsConfigField
        config-key="telegram.ackReaction"
        label="ackReaction"
        kind="select"
        fallback="off"
        :options="choices('off', 'on')"
        label-width="w-72"
        tip="on marks the message being answered with 👀 while the agent works, then ✅ or ❌ when it finishes."
      />
      <SettingsConfigField
        config-key="telegram.notifier.policy"
        label="notifier.policy"
        kind="select"
        fallback="reply"
        :options="choices('reply', 'silent')"
        label-width="w-72"
        tip="When a message still cannot be delivered after retries, reply posts a notice in the chat; silent only logs it."
      />
      <SettingsConfigField
        config-key="telegram.notifier.cooldownMs"
        label="notifier.cooldownMs"
        kind="number"
        fallback="60000"
        :min="1"
        label-width="w-72"
        tip="Minimum milliseconds between delivery-failure notices in one conversation, so an outage does not flood the chat. Minimum 1."
      />
    </div>

    <h3 class="text-[11px] font-semibold text-fg-muted uppercase tracking-wide pt-2">
      Inbound Messages
    </h3>
    <div class="bg-surface-elevated border border-border divide-y divide-border">
      <SettingsConfigField
        config-key="telegram.reactions.notify"
        label="reactions.notify"
        kind="select"
        fallback="own"
        :options="choices('off', 'own', 'all')"
        label-width="w-72"
        tip="Which message reactions the agent is told about: off none, own only reactions to the bot's messages, all every reaction."
      />
      <SettingsConfigField
        config-key="telegram.inbound.coalesce-threshold"
        label="inbound.coalesce-threshold"
        kind="number"
        fallback="4000"
        :min="1"
        label-width="w-72"
        tip="Telegram splits a long paste into several messages. A message at least this many characters long is held briefly so the pieces that follow join it as one turn; shorter messages go straight through."
      />
      <SettingsConfigField
        config-key="telegram.inbound.coalesce-window-ms"
        label="inbound.coalesce-window-ms"
        kind="number"
        fallback="750"
        :min="0"
        label-width="w-72"
        tip="Milliseconds of quiet after a held long message before the joined text goes to the agent."
      />
      <SettingsConfigField
        config-key="telegram.inbound.forward-coalesce-window-ms"
        label="inbound.forward-coalesce-window-ms"
        kind="number"
        fallback="1000"
        :min="0"
        label-width="w-72"
        tip="Consecutive forwarded messages from one sender within this many milliseconds become one turn."
      />
      <SettingsConfigField
        config-key="telegram.mentionPatterns"
        label="mentionPatterns"
        kind="text"
        fallback=""
        label-width="w-72"
        tip="Wake words for group chats: comma-separated Java regular expressions that count as addressing the bot, alongside an @mention or a reply to it. Prefix (?i) to ignore case. Empty turns wake words off."
      />
      <SettingsConfigField
        config-key="telegram.keyboardScope"
        label="keyboardScope"
        kind="select"
        fallback="all"
        :options="choices('off', 'dm', 'group', 'all')"
        label-width="w-72"
        tip="Which chats may use inline keyboards such as the /model picker: off none, dm private chats only, group groups only, all both."
      />
    </div>

    <h3 class="text-[11px] font-semibold text-fg-muted uppercase tracking-wide pt-2">
      Agent Message Actions
    </h3>
    <div class="bg-surface-elevated border border-border divide-y divide-border">
      <SettingsConfigField
        config-key="telegram.actions.reply"
        label="actions.reply"
        kind="boolean"
        fallback="true"
        label-width="w-72"
        tip="Let the agent reply to a specific message with the message tool."
      />
      <SettingsConfigField
        config-key="telegram.actions.edit"
        label="actions.edit"
        kind="boolean"
        fallback="true"
        label-width="w-72"
        tip="Let the agent edit messages it sent."
      />
      <SettingsConfigField
        config-key="telegram.actions.delete"
        label="actions.delete"
        kind="boolean"
        fallback="true"
        label-width="w-72"
        tip="Let the agent delete messages."
      />
      <SettingsConfigField
        config-key="telegram.actions.react"
        label="actions.react"
        kind="boolean"
        fallback="true"
        label-width="w-72"
        tip="Let the agent add emoji reactions to messages."
      />
      <SettingsConfigField
        config-key="telegram.actions.poll"
        label="actions.poll"
        kind="boolean"
        fallback="true"
        label-width="w-72"
        tip="Let the agent send polls."
      />
      <SettingsConfigField
        config-key="telegram.actions.pin"
        label="actions.pin"
        kind="boolean"
        fallback="false"
        label-width="w-72"
        tip="Let the agent pin and unpin messages. Pinning changes the chat for everyone, so it stays off until you turn it on."
      />
    </div>
  </section>
</template>
