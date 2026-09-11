import { computed, onBeforeUnmount, ref, watch, type ComputedRef, type Ref } from 'vue'
import { resolveThinkingLock, type ThinkingLock } from '~/utils/thinking-lock'
import { effectiveThinkingLevels, type Provider, type ProviderModel } from '~/composables/useProviders'
import type { Agent, Conversation } from '~/types/api'

/**
 * Agent + model + thinking-config state for the chat header/composer (JCLAW-690
 * stage 5a; behaviour extracted verbatim from pages/chat.vue). Owns the model
 * resolution chain (selectedAgent → effectiveModel → selectedModelInfo → the
 * capability pills + thinking-level state), the teleported thinking-level menu's
 * positioning/lifecycle, and the conversation-scoped writes a model or thinking
 * pick makes (JCLAW-108, JCLAW-1196). Nothing here writes to the agent row: a
 * pick with a conversation open is that conversation's override, and a pick on
 * a fresh chat is held as a pending override the first message carries.
 *
 * `selectedAgentId` stays a page-level ref passed in (it's cross-coupled with
 * `selectedConvoId` — effectiveModel needs both — and the template v-models it),
 * mirroring how `streaming` stays page-level for the stream composable.
 */
export interface UseAgentModelDeps {
  agents: Ref<Agent[] | null | undefined>
  selectedAgentId: Ref<number | null>
  selectedConvoId: Ref<number | null>
  conversations: Ref<Conversation[] | null | undefined>
  providers: Ref<Provider[]>
  refreshConversations: () => Promise<void> | void
}

/** Picks made on a fresh chat before its first message; sent with that message (JCLAW-1196). */
export interface PendingOverrides {
  modelProvider?: string
  modelId?: string
  thinkingMode?: string
}

export interface UseAgentModel {
  selectedAgent: ComputedRef<Agent | undefined>
  currentConversation: ComputedRef<Conversation | null>
  selectedModelInfo: ComputedRef<ProviderModel | null>
  selectedModelKey: ComputedRef<string>
  thinkingSupported: ComputedRef<boolean>
  thinkingLock: ComputedRef<ThinkingLock>
  thinkingLevels: ComputedRef<string[]>
  thinkingActive: ComputedRef<boolean>
  thinkingPillInert: ComputedRef<boolean>
  thinkingPillTitle: ComputedRef<string>
  visionSupported: ComputedRef<boolean>
  audioSupported: ComputedRef<boolean>
  videoSupported: ComputedRef<boolean>
  thinkingMenuOpen: Ref<boolean>
  thinkPillRef: Ref<HTMLButtonElement | null>
  thinkingMenuStyle: Ref<Record<string, string>>
  toggleThinkingPill: () => void
  openThinkingMenu: () => void
  scheduleCloseThinkingMenu: () => void
  setThinkingLevel: (level: string) => void
  onModelKeyChange: (key: string) => Promise<void>
  /** The reasoning level in force for the open conversation or the pending pick; null when off. */
  currentThinkingLevel: ComputedRef<string | null>
  /** Which facets the open conversation, or the pending pick on a fresh chat, overrides. */
  sessionOverrides: ComputedRef<{ model: boolean, thinking: boolean }>
  /** What the next fresh-chat message must carry, or null when nothing was picked. */
  pendingOverrides: ComputedRef<PendingOverrides | null>
  /** The last rejected override write, for the header to show; null once one succeeds. */
  overrideError: Ref<string | null>
  resetSessionOverrides: () => Promise<void>
}

export function useAgentModel(deps: UseAgentModelDeps): UseAgentModel {
  const { agents, selectedAgentId, selectedConvoId, conversations, providers, refreshConversations } = deps

  // Fresh-chat picks. They apply to the conversation the next message creates, so
  // they die with a change of agent, and once that conversation's row has arrived
  // (it carries them as overrides by then). Not on the id alone: the init frame
  // assigns the id before the list refresh lands, and dropping the picks there
  // showed the agent default in the header while the first reply streamed.
  const pendingModel = ref<{ providerName: string, modelId: string } | null>(null)
  const pendingThinking = ref<string | null>(null)
  const overrideError = ref<string | null>(null)
  function clearPending() {
    pendingModel.value = null
    pendingThinking.value = null
  }
  watch(selectedAgentId, clearPending)

  // The currently selected agent object
  const selectedAgent = computed(() => agents.value?.find(a => a.id === selectedAgentId.value))

  /**
   * The currently open conversation row, if any. Exposes the modelProvider /
   * modelId override fields (JCLAW-108) so the model dropdown can reflect
   * per-conversation state.
   *
   * Defensive: useFetch's `data` can briefly hold non-array values during
   * pending / error states (null, SSR hydration mismatch, or a route that
   * returned an error object). Check Array.isArray before .find rather than
   * relying on optional chaining alone.
   */
  const currentConversation = computed(() => {
    const list = conversations.value
    if (!Array.isArray(list)) return null
    return list.find(c => c.id === selectedConvoId.value) ?? null
  })
  watch(currentConversation, (row) => {
    if (row) clearPending()
  })

  /** True until the open conversation's row is loaded — the window a fresh-chat pick still speaks for. */
  const pendingApplies = computed(() => currentConversation.value == null)

  /**
   * Effective (provider, modelId) for the currently open conversation.
   * Honors the JCLAW-108 conversation override when both override columns are
   * set; falls back to the agent's default otherwise. This is the single
   * resolver both the dropdown key (selectedModelKey) and the capability
   * pills (selectedModelInfo) route through — preventing the JCLAW-112
   * drift where one side honored the override and the other didn't.
   */
  const effectiveModel = computed<{ providerName: string | null, modelId: string | null }>(() => {
    const conv = currentConversation.value
    if (conv?.modelProviderOverride && conv?.modelIdOverride) {
      return { providerName: conv.modelProviderOverride, modelId: conv.modelIdOverride }
    }
    if (pendingApplies.value && pendingModel.value) return pendingModel.value
    return {
      providerName: selectedAgent.value?.modelProvider ?? null,
      modelId: selectedAgent.value?.modelId ?? null,
    }
  })

  /**
   * Effective thinking level (JCLAW-1196): the open conversation's override, else the
   * pending pick on a fresh chat, else the agent default. Null means off; the server
   * still intersects with what the effective model advertises.
   */
  const effectiveThinking = computed<string | null>(() => {
    const override = pendingApplies.value
      ? pendingThinking.value
      : currentConversation.value?.thinkingModeOverride ?? null
    if (override != null) return override === 'off' ? null : override
    const mode = selectedAgent.value?.thinkingMode
    return typeof mode === 'string' && mode.length > 0 ? mode : null
  })

  /**
   * ModelInfo for the effective (override-or-agent) model. The Think / Vision /
   * Audio pills and the thinking-level dropdown all derive from this, so they
   * reflect the capabilities of the model that will actually run the next
   * turn — not the agent's default when an override is active.
   */
  const selectedModelInfo = computed<ProviderModel | null>(() => {
    const { providerName, modelId } = effectiveModel.value
    if (!providerName || !modelId) return null
    const provider = providers.value.find(p => p.name === providerName)
    return provider?.models.find(m => m.id === modelId) ?? null
  })

  /**
   * Compound key used as the `<option>` value for the model dropdown so the
   * change handler can read both provider and model from a single DOM value.
   * Routes through {@link effectiveModel} so the dropdown and the capability
   * pills stay in sync. "::" separator is safe against every provider name
   * and model id we currently ship.
   */
  const selectedModelKey = computed(() => {
    const { providerName, modelId } = effectiveModel.value
    return providerName && modelId ? `${providerName}::${modelId}` : ''
  })

  // Whether the selected model supports thinking
  const thinkingSupported = computed(() => selectedModelInfo.value?.supportsThinking === true)

  // Provider/model combos where reasoning cannot be disabled even with the
  // toggle off. Two converging causes — model architecture (alwaysThinks pure
  // reasoners like o1/R1) and provider integration limits (JCLAW-127:
  // ollama-cloud + Gemini 2.5 Pro / 3). Both surface as a locked pill with an
  // explanatory tooltip so the operator isn't misled into thinking their
  // preference was honored.
  const thinkingLock = computed(() =>
    resolveThinkingLock(
      effectiveModel.value.providerName,
      effectiveModel.value.modelId,
      selectedModelInfo.value,
    ),
  )

  // Thinking levels advertised by the currently selected model. Empty for
  // non-thinking models — the toolbar hides the selector in that case.
  const thinkingLevels = computed<string[]>(() => effectiveThinkingLevels(selectedModelInfo.value))

  // Model capability flags surfaced as pills next to the paperclip. Mirrors LM
  // Studio's "Think / Vision" chip row so users can see at a glance which input
  // types the currently-selected model accepts. Flags originate in provider
  // metadata (OpenRouter architecture.input_modalities, Ollama capabilities)
  // or the operator-toggled checkbox in Settings; see ModelDiscoveryService.
  const visionSupported = computed(() => selectedModelInfo.value?.supportsVision === true)
  // JCLAW-165: capability indicator only — there's no per-agent audio toggle
  // to drive (transcription gives every model an audio path). The pill in
  // the composer signals "this model handles audio natively"; voice notes
  // to non-supportsAudio models go through the transcription pipeline
  // transparently.
  const audioSupported = computed(() => selectedModelInfo.value?.supportsAudio === true)
  // Capability indicator only — uploaded videos work on any model. A
  // supportsVideo model watches the clip natively; others route to the dedicated
  // video model (Settings → Video Interpretation), then to frames/captions.
  const videoSupported = computed(() => selectedModelInfo.value?.supportsVideo === true)

  // --- Pill toggle state ---

  // Think pill: active when the agent currently has a reasoning-effort level set.
  // Null/blank thinkingMode means thinking is off even on a capable model — except
  // on a locked one, which reasons regardless of what we store, so reporting it as
  // off would contradict both the pill and the request the backend actually sends.
  const thinkingActive = computed(() => {
    if (thinkingLock.value.locked) return true
    return effectiveThinking.value != null
  })

  // A locked pill still opens the level menu, so it is only truly inoperable when
  // the model advertises nothing to pick between. Marking a menu trigger
  // aria-disabled would tell assistive tech the opposite of what it does.
  const thinkingPillInert = computed(() =>
    thinkingLock.value.locked && thinkingLevels.value.length <= 1)

  // The lock reason alone reads as a dead end. When a ladder exists the tooltip
  // has to say what is still possible, or the effort control stays undiscovered.
  const thinkingPillTitle = computed(() => {
    if (thinkingLock.value.locked) {
      return thinkingPillInert.value
        ? thinkingLock.value.reason
        : `${thinkingLock.value.reason} Hover to pick an effort level.`
    }
    return thinkingActive.value
      ? 'Thinking on — click to turn off, or hover to pick a level'
      : 'Thinking off — click to turn on'
  })

  // Remember the last non-off thinking level the operator picked for THIS session so
  // toggling the pill off → on restores "medium" or whatever they'd most recently
  // chosen, instead of always jumping back to the first advertised level. The ref
  // is intentionally module-local and not persisted — the next page load starts
  // fresh from whatever the agent's stored thinkingMode was.
  const lastThinkingLevel = ref<string>('medium')

  // Vision and audio are pure capability indicators — no LLM provider exposes
  // an API-level off-switch for either modality, so a client-side toggle would
  // just be "don't attach images/audio." The chat composer renders the pills
  // when the model supports the capability; clicks are no-ops.

  function toggleThinkingPill() {
    if (!thinkingSupported.value) return
    // A locked model cannot be turned off — either its architecture has no
    // non-thinking mode or the upstream API ignores the off signal — so clicking
    // is a no-op. Choosing a LEVEL is still allowed; only this toggle is barred.
    if (thinkingLock.value.locked) return
    if (thinkingActive.value) {
      void applyThinking('off')
    }
    else {
      // Prefer the session-remembered level if it's still a valid option on this
      // model, otherwise fall back to the first advertised level so we never
      // send an invalid enum value the backend would have to defensively reject.
      const levels = thinkingLevels.value
      const next = levels.includes(lastThinkingLevel.value) ? lastThinkingLevel.value : levels[0]
      if (next) void applyThinking(next)
    }
  }

  // Hover/focus menu above the Think pill: lets the user pick a specific
  // reasoning level (low/medium/high — whatever the current model advertises)
  // without going through the off → on dance. Click-to-toggle on the pill
  // itself is preserved as the cheap one-click affordance; the menu is the
  // power-user path. The 150ms close delay covers the cursor traversing the
  // gap between pill and menu — without it the menu vanishes mid-traverse.
  //
  // The menu is teleported to <body> because the composer <form> has
  // overflow-hidden (necessary for its rounded-[22px] border) which would
  // otherwise clip the upward-growing menu — that's why bumping z-index
  // alone didn't fix the "Low is missing" report. With Teleport the menu
  // becomes a viewport-positioned floater anchored to the trigger button's
  // bounding rect; scroll/resize listeners keep it pinned while open.
  const thinkingMenuOpen = ref(false)
  const thinkPillRef = ref<HTMLButtonElement | null>(null)
  const thinkingMenuStyle = ref<Record<string, string>>({})
  let thinkingMenuCloseTimer: ReturnType<typeof setTimeout> | null = null
  let thinkingMenuListenersAttached = false

  function computeThinkingMenuStyle() {
    const btn = thinkPillRef.value
    if (!btn) return
    const r = btn.getBoundingClientRect()
    thinkingMenuStyle.value = {
      left: `${r.left + r.width / 2}px`,
      top: `${r.top - 6}px`,
      transform: 'translate(-50%, -100%)',
    }
  }

  function attachMenuTrackingListeners() {
    if (thinkingMenuListenersAttached) return
    // capture: true so scroll events on nested overflow-auto containers
    // (the chat history scrollbox) reposition the floating menu too.
    window.addEventListener('scroll', computeThinkingMenuStyle, { passive: true, capture: true })
    window.addEventListener('resize', computeThinkingMenuStyle)
    thinkingMenuListenersAttached = true
  }

  function detachMenuTrackingListeners() {
    if (!thinkingMenuListenersAttached) return
    window.removeEventListener('scroll', computeThinkingMenuStyle, { capture: true } as EventListenerOptions)
    window.removeEventListener('resize', computeThinkingMenuStyle)
    thinkingMenuListenersAttached = false
  }

  function openThinkingMenu() {
    if (thinkingMenuCloseTimer) {
      clearTimeout(thinkingMenuCloseTimer)
      thinkingMenuCloseTimer = null
    }
    if (!thinkingSupported.value) return
    if (!thinkingLevels.value.length) return
    // Only surface the level picker when Think is currently on. The pill's
    // click-to-toggle handles on/off; the menu is purely "now that thinking
    // is on, let me change the level." Showing it for a disabled pill would
    // confusingly let the user re-enable Think via a hover-then-click that
    // looks like nothing more than picking a level.
    if (!thinkingActive.value) return
    computeThinkingMenuStyle()
    thinkingMenuOpen.value = true
    attachMenuTrackingListeners()
  }

  function scheduleCloseThinkingMenu() {
    if (thinkingMenuCloseTimer) clearTimeout(thinkingMenuCloseTimer)
    thinkingMenuCloseTimer = setTimeout(() => {
      thinkingMenuOpen.value = false
      thinkingMenuCloseTimer = null
      detachMenuTrackingListeners()
    }, 150)
  }

  function setThinkingLevel(level: string) {
    if (!thinkingSupported.value) return
    lastThinkingLevel.value = level
    void applyThinking(level)
    thinkingMenuOpen.value = false
    detachMenuTrackingListeners()
  }

  onBeforeUnmount(() => {
    if (thinkingMenuCloseTimer) {
      clearTimeout(thinkingMenuCloseTimer)
      thinkingMenuCloseTimer = null
    }
    detachMenuTrackingListeners()
  })

  function describeRejection(err: unknown): string {
    const data = (err as { data?: { message?: string } })?.data
    return data?.message ?? (err instanceof Error ? err.message : 'The change was rejected.')
  }

  /**
   * Thinking change (JCLAW-1196): 'off' or a level. With a conversation open it is
   * that conversation's override; on a fresh chat it is held for the first message.
   */
  async function applyThinking(mode: string) {
    const convoId = selectedConvoId.value
    if (convoId == null) {
      pendingThinking.value = mode
      return
    }
    try {
      await $fetch(`/api/conversations/${convoId}/thinking-override`, {
        method: 'PUT',
        body: { thinkingMode: mode },
      })
      overrideError.value = null
      refreshConversations()
    }
    catch (err) {
      overrideError.value = describeRejection(err)
      refreshConversations()
    }
  }

  /**
   * Model-dropdown change handler.
   *
   * JCLAW-108: when a conversation is open, writes a conversation-scoped
   * override (PUT /api/conversations/{id}/model-override) instead of mutating
   * the Agent row. This keeps mid-chat model switches bounded to the current
   * conversation — matching the `/model NAME` slash command's semantics.
   *
   * JCLAW-1196: with no conversation open the pick is held as a pending
   * override that the first message carries, so a fresh chat never writes
   * the agent's default either. The agent detail page is the only place
   * defaults change.
   */
  async function onModelKeyChange(key: string) {
    const sepIdx = key.indexOf('::')
    if (sepIdx < 0) return
    const modelProvider = key.slice(0, sepIdx)
    const modelId = key.slice(sepIdx + 2)

    const convoId = selectedConvoId.value
    if (convoId != null) {
      // Write the conversation override. Match the refresh-on-success pattern
      // used by updateAgentSetting so the local conversations list realigns
      // with persisted state (including the fields listConversations now
      // returns — modelProviderOverride / modelIdOverride).
      try {
        await $fetch(`/api/conversations/${convoId}/model-override`, {
          method: 'PUT',
          body: { modelProvider, modelId },
        })
        overrideError.value = null
        refreshConversations()
      }
      catch (err) {
        // Server rejected (unknown provider/model) or network error. Refetch
        // to realign the dropdown with persisted state, and say why.
        overrideError.value = describeRejection(err)
        refreshConversations()
      }
      return
    }

    // No conversation open — hold the pick for the first message.
    const provider = providers.value.find(p => p.name === modelProvider)
    const model = provider?.models.find(m => m.id === modelId) ?? null
    pendingModel.value = { providerName: modelProvider, modelId }
    // A level the new model does not advertise would be silently dropped server-side;
    // make the off explicit so the pill reflects what the turn will actually do.
    const nextLevels = effectiveThinkingLevels(model)
    const current = effectiveThinking.value
    if (current && nextLevels.length && !nextLevels.includes(current)) {
      pendingThinking.value = 'off'
    }
  }

  const sessionOverrides = computed(() => {
    if (pendingApplies.value) {
      return { model: pendingModel.value != null, thinking: pendingThinking.value != null }
    }
    const conv = currentConversation.value
    return {
      model: !!(conv?.modelProviderOverride && conv?.modelIdOverride),
      thinking: conv?.thinkingModeOverride != null,
    }
  })

  const pendingOverrides = computed<PendingOverrides | null>(() => {
    if (selectedConvoId.value != null) return null
    const o: PendingOverrides = {}
    if (pendingModel.value) {
      o.modelProvider = pendingModel.value.providerName
      o.modelId = pendingModel.value.modelId
    }
    if (pendingThinking.value) o.thinkingMode = pendingThinking.value
    return Object.keys(o).length ? o : null
  })

  /** Back to the agent's defaults: drop the pending picks, or clear the open conversation's overrides. */
  async function resetSessionOverrides() {
    const convoId = selectedConvoId.value
    if (convoId == null) {
      clearPending()
      return
    }
    const facets = sessionOverrides.value
    try {
      if (facets.model) await $fetch(`/api/conversations/${convoId}/model-override`, { method: 'DELETE' })
      if (facets.thinking) await $fetch(`/api/conversations/${convoId}/thinking-override`, { method: 'DELETE' })
      overrideError.value = null
    }
    catch (err) {
      overrideError.value = describeRejection(err)
    }
    refreshConversations()
  }

  return {
    selectedAgent,
    currentConversation,
    selectedModelInfo,
    selectedModelKey,
    thinkingSupported,
    thinkingLock,
    thinkingLevels,
    thinkingActive,
    thinkingPillInert,
    thinkingPillTitle,
    visionSupported,
    audioSupported,
    videoSupported,
    thinkingMenuOpen,
    thinkPillRef,
    thinkingMenuStyle,
    toggleThinkingPill,
    openThinkingMenu,
    scheduleCloseThinkingMenu,
    setThinkingLevel,
    onModelKeyChange,
    currentThinkingLevel: effectiveThinking,
    sessionOverrides,
    pendingOverrides,
    overrideError,
    resetSessionOverrides,
  }
}
