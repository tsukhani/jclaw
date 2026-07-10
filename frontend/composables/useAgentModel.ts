import { computed, onBeforeUnmount, ref, type ComputedRef, type Ref } from 'vue'
import { resolveThinkingLock, type ThinkingLock } from '~/utils/thinking-lock'
import { effectiveThinkingLevels, type Provider, type ProviderModel } from '~/composables/useProviders'
import type { Agent, Conversation } from '~/types/api'

/**
 * Agent + model + thinking-config state for the chat header/composer (JCLAW-690
 * stage 5a; behaviour extracted verbatim from pages/chat.vue). Owns the model
 * resolution chain (selectedAgent → effectiveModel → selectedModelInfo → the
 * capability pills + thinking-level state), the teleported thinking-level menu's
 * positioning/lifecycle, and the writes that push a model/thinking change back
 * to the agent (or a JCLAW-108 per-conversation override).
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
  refreshAgents: () => Promise<void> | void
  refreshConversations: () => Promise<void> | void
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
}

export function useAgentModel(deps: UseAgentModelDeps): UseAgentModel {
  const { agents, selectedAgentId, selectedConvoId, conversations, providers, refreshAgents, refreshConversations } = deps

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
    return {
      providerName: selectedAgent.value?.modelProvider ?? null,
      modelId: selectedAgent.value?.modelId ?? null,
    }
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
  // Null/blank thinkingMode means thinking is off even on a capable model.
  const thinkingActive = computed(() => {
    const mode = selectedAgent.value?.thinkingMode
    return typeof mode === 'string' && mode.length > 0
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
    // JCLAW-127: on a locked combo (ollama-cloud + Gemini 2.5 Pro / 3) the
    // upstream Google API ignores our off signal, so clicking is a no-op. The
    // tooltip communicates why.
    if (thinkingLock.value.locked) return
    if (thinkingActive.value) {
      updateAgentSetting({ thinkingMode: null })
    }
    else {
      // Prefer the session-remembered level if it's still a valid option on this
      // model, otherwise fall back to the first advertised level so we never
      // send an invalid enum value the backend would have to defensively reject.
      const levels = thinkingLevels.value
      const next = levels.includes(lastThinkingLevel.value) ? lastThinkingLevel.value : levels[0]
      if (next) updateAgentSetting({ thinkingMode: next })
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
    if (thinkingLock.value.locked) return
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
    if (thinkingLock.value.locked) return
    lastThinkingLevel.value = level
    updateAgentSetting({ thinkingMode: level })
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

  // Sync model or thinking mode change back to the agent
  async function updateAgentSetting(updates: Partial<Agent>) {
    if (!selectedAgentId.value) return
    try {
      await $fetch(`/api/agents/${selectedAgentId.value}`, { method: 'PUT', body: updates })
      refreshAgents()
    }
    catch { /* ignore */ }
  }

  /**
   * Model-dropdown change handler.
   *
   * JCLAW-108: when a conversation is open, writes a conversation-scoped
   * override (PUT /api/conversations/{id}/model-override) instead of mutating
   * the Agent row. This keeps mid-chat model switches bounded to the current
   * conversation — matching the `/model NAME` slash command's semantics.
   *
   * When no conversation is open (the user is about to start a fresh one),
   * falls back to the pre-JCLAW-108 behavior of mutating the agent's default
   * model. This preserves the settings-page flow where editing the agent from
   * here is the intent.
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
        refreshConversations()
      }
      catch (err) {
        // Server rejected (unknown provider/model) or network error. Refetch
        // to realign the dropdown with persisted state.
        refreshConversations()
        throw err
      }
      return
    }

    // No conversation open — fall back to mutating the agent default.
    const provider = providers.value.find(p => p.name === modelProvider)
    const model = provider?.models.find(m => m.id === modelId) ?? null
    const updates: Partial<Agent> = { modelProvider, modelId }
    // If the new model doesn't advertise the current thinking level, clear it in
    // the same PUT so the backend doesn't have to normalize the mismatch. The
    // backend also collapses unknown levels to null defensively, but sending the
    // cleared value keeps the optimistic UI and the persisted state aligned.
    const nextLevels = effectiveThinkingLevels(model)
    const current = selectedAgent.value?.thinkingMode
    if (current && !nextLevels.includes(current)) {
      updates.thinkingMode = null
    }
    updateAgentSetting(updates)
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
  }
}
