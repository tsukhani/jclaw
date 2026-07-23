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
  ClockIcon,
  CommandLineIcon,
  CpuChipIcon,
  DocumentMagnifyingGlassIcon,
  DocumentTextIcon,
  EyeIcon,
  FilmIcon,
  KeyIcon,
  MagnifyingGlassIcon,
  MicrophoneIcon,
  PhotoIcon,
  PuzzlePieceIcon,
  ShieldCheckIcon,
  SpeakerWaveIcon,
  UserGroupIcon,
} from '@heroicons/vue/24/outline'

import SettingsChatPanel from './SettingsChatPanel.vue'
import SettingsTimezonePanel from './SettingsTimezonePanel.vue'
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
import SettingsSpeechPanel from './SettingsSpeechPanel.vue'
import SettingsSubagentsPanel from './SettingsSubagentsPanel.vue'
import SettingsTasksPanel from './SettingsTasksPanel.vue'
import SettingsTranscriptionPanel from './SettingsTranscriptionPanel.vue'
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

/** A labelled group of sections rendered as one block (header + items) in the rail. */
export interface SettingsSectionGroup {
  label: string
  sections: SettingsSection[]
}

// Sections are organised into functional domains so the 20-item rail stays
// scannable. The media features group by modality (2026-07-23): Audio
// (Transcription, Speech), Image (OCR, Image Captioning, Image Generation), and
// Video (Video Interpretation, Video Generation) — each modality pairs its
// understand-media and generate-media features in one block. Section ids are
// unchanged (stable deep-link URLs); only the grouping and rail order moved.
export const sectionGroups: SettingsSectionGroup[] = [
  {
    label: 'System',
    sections: [
      { id: 'timezone', title: 'Timezone', icon: ClockIcon, component: SettingsTimezonePanel },
      { id: 'logging', title: 'Logging', icon: DocumentTextIcon, component: SettingsLoggingPanel },
      { id: 'performance', title: 'Performance', icon: BoltIcon, component: SettingsPerformancePanel },
      { id: 'uploads', title: 'Uploads', icon: ArrowUpTrayIcon, component: SettingsUploadsPanel },
      { id: 'password', title: 'Password', icon: KeyIcon, component: SettingsPasswordPanel },
    ],
  },
  {
    label: 'Providers',
    sections: [
      { id: 'providers', title: 'LLM Providers', icon: CpuChipIcon, component: SettingsProvidersPanel },
      { id: 'search', title: 'Search Providers', icon: MagnifyingGlassIcon, component: SettingsSearchPanel },
    ],
  },
  {
    label: 'Audio',
    sections: [
      { id: 'transcription', title: 'Transcription', icon: MicrophoneIcon, component: SettingsTranscriptionPanel },
      { id: 'speech', title: 'Speech', icon: SpeakerWaveIcon, component: SettingsSpeechPanel },
    ],
  },
  {
    label: 'Image',
    sections: [
      { id: 'ocr', title: 'OCR', icon: DocumentMagnifyingGlassIcon, component: SettingsOcrPanel },
      { id: 'image-caption', title: 'Image Captioning', icon: ChatBubbleBottomCenterTextIcon, component: SettingsImageCaptionPanel },
      { id: 'image-generation', title: 'Image Generation', icon: PhotoIcon, component: SettingsImageGenPanel },
    ],
  },
  {
    label: 'Video',
    sections: [
      { id: 'video-interpretation', title: 'Video Interpretation', icon: EyeIcon, component: SettingsVideoInterpPanel },
      { id: 'video-generation', title: 'Video Generation', icon: FilmIcon, component: SettingsVideoGenPanel },
    ],
  },
  {
    label: 'Agents & Automation',
    sections: [
      { id: 'chat', title: 'Chat', icon: ChatBubbleOvalLeftEllipsisIcon, component: SettingsChatPanel },
      { id: 'subagents', title: 'Subagents', icon: UserGroupIcon, component: SettingsSubagentsPanel },
      { id: 'tasks', title: 'Tasks', icon: ClipboardDocumentCheckIcon, component: SettingsTasksPanel },
      { id: 'skills', title: 'Skills Promotion', icon: PuzzlePieceIcon, component: SettingsSkillsPanel },
    ],
  },
  {
    label: 'Security',
    sections: [
      { id: 'shell', title: 'Shell Execution', icon: CommandLineIcon, component: SettingsShellPanel },
      { id: 'malware', title: 'Malware Scanners', icon: ShieldCheckIcon, component: SettingsMalwarePanel },
    ],
  },
]

/**
 * Flat list of every section in rail order, derived from the groups so ordering
 * is single-sourced. Used for id lookup and the default (first) section.
 */
export const sections: SettingsSection[] = sectionGroups.flatMap(g => g.sections)
