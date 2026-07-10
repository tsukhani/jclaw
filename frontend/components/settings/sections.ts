/**
 * Registry of Settings sections (JCLAW-680).
 *
 * Each entry pairs section metadata (id, title, icon) with the panel
 * component that renders it. `pages/settings.vue` drives a vertical TOC +
 * single-section swap off this array — the rail lists every entry, and only
 * the active section's component is mounted (`<component :is>`), so a panel's
 * probes / API fetches fire only when its section is opened.
 *
 * Adding a section: create `Settings<Name>Panel.vue`, import it here, and
 * append an entry. `pages/settings.vue` needs no change.
 *
 * The `id` is the stable URL identifier (`/settings?section=<id>`) — don't
 * rename a shipped id, operator bookmarks and deep links point at it.
 */
import type { Component } from 'vue'
import {
  ArrowUpTrayIcon,
  BoltIcon,
  ChatBubbleBottomCenterTextIcon,
  ChatBubbleOvalLeftEllipsisIcon,
  ClipboardDocumentCheckIcon,
  Cog6ToothIcon,
  CommandLineIcon,
  CpuChipIcon,
  DocumentMagnifyingGlassIcon,
  DocumentTextIcon,
  ExclamationTriangleIcon,
  EyeIcon,
  FilmIcon,
  KeyIcon,
  MagnifyingGlassIcon,
  MicrophoneIcon,
  PhotoIcon,
  PuzzlePieceIcon,
  ShieldCheckIcon,
  UserGroupIcon,
} from '@heroicons/vue/24/outline'

import SettingsChatPanel from './SettingsChatPanel.vue'
import SettingsGeneralPanel from './SettingsGeneralPanel.vue'
import SettingsImageCaptionPanel from './SettingsImageCaptionPanel.vue'
import SettingsImageGenPanel from './SettingsImageGenPanel.vue'
import SettingsLoggingPanel from './SettingsLoggingPanel.vue'
import SettingsMalwarePanel from './SettingsMalwarePanel.vue'
import SettingsOcrPanel from './SettingsOcrPanel.vue'
import SettingsPasswordPanel from './SettingsPasswordPanel.vue'
import SettingsPerformancePanel from './SettingsPerformancePanel.vue'
import SettingsProvidersPanel from './SettingsProvidersPanel.vue'
import SettingsSearchPanel from './SettingsSearchPanel.vue'
import SettingsShellPanel from './SettingsShellPanel.vue'
import SettingsSkillsPanel from './SettingsSkillsPanel.vue'
import SettingsSubagentsPanel from './SettingsSubagentsPanel.vue'
import SettingsTasksPanel from './SettingsTasksPanel.vue'
import SettingsTranscriptionPanel from './SettingsTranscriptionPanel.vue'
import SettingsUnmanagedPanel from './SettingsUnmanagedPanel.vue'
import SettingsUploadsPanel from './SettingsUploadsPanel.vue'
import SettingsVideoGenPanel from './SettingsVideoGenPanel.vue'
import SettingsVideoInterpPanel from './SettingsVideoInterpPanel.vue'

export interface SettingsSection {
  /** Stable URL id (`/settings?section=<id>`). Lowercase kebab; don't rename once shipped. */
  id: string
  /** Label shown in the TOC rail. */
  title: string
  /** Icon ref shown beside the title in the rail. */
  icon: Component
  /** The panel component mounted when this section is active. */
  component: Component
}

// Order mirrors the pre-TOC stacked page so operators' spatial memory carries over.
export const sections: SettingsSection[] = [
  { id: 'general', title: 'General', icon: Cog6ToothIcon, component: SettingsGeneralPanel },
  { id: 'logging', title: 'Logging', icon: DocumentTextIcon, component: SettingsLoggingPanel },
  { id: 'providers', title: 'LLM Providers', icon: CpuChipIcon, component: SettingsProvidersPanel },
  { id: 'search', title: 'Search Providers', icon: MagnifyingGlassIcon, component: SettingsSearchPanel },
  { id: 'ocr', title: 'OCR Backends', icon: DocumentMagnifyingGlassIcon, component: SettingsOcrPanel },
  { id: 'transcription', title: 'Transcription', icon: MicrophoneIcon, component: SettingsTranscriptionPanel },
  { id: 'image-caption', title: 'Image Captioning', icon: ChatBubbleBottomCenterTextIcon, component: SettingsImageCaptionPanel },
  { id: 'image-generation', title: 'Image Generation', icon: PhotoIcon, component: SettingsImageGenPanel },
  { id: 'video-interpretation', title: 'Video Interpretation', icon: EyeIcon, component: SettingsVideoInterpPanel },
  { id: 'video-generation', title: 'Video Generation', icon: FilmIcon, component: SettingsVideoGenPanel },
  { id: 'chat', title: 'Chat', icon: ChatBubbleOvalLeftEllipsisIcon, component: SettingsChatPanel },
  { id: 'subagents', title: 'Subagents', icon: UserGroupIcon, component: SettingsSubagentsPanel },
  { id: 'tasks', title: 'Tasks', icon: ClipboardDocumentCheckIcon, component: SettingsTasksPanel },
  { id: 'performance', title: 'Performance', icon: BoltIcon, component: SettingsPerformancePanel },
  { id: 'uploads', title: 'Uploads', icon: ArrowUpTrayIcon, component: SettingsUploadsPanel },
  { id: 'skills', title: 'Skills Promotion', icon: PuzzlePieceIcon, component: SettingsSkillsPanel },
  { id: 'shell', title: 'Shell Execution', icon: CommandLineIcon, component: SettingsShellPanel },
  { id: 'malware', title: 'Malware Scanners', icon: ShieldCheckIcon, component: SettingsMalwarePanel },
  { id: 'password', title: 'Password', icon: KeyIcon, component: SettingsPasswordPanel },
  { id: 'unmanaged', title: 'Unmanaged Config', icon: ExclamationTriangleIcon, component: SettingsUnmanagedPanel },
]
