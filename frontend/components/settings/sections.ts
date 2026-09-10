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
  AdjustmentsHorizontalIcon,
  ArrowsUpDownIcon,
  ArrowUpTrayIcon,
  BoltIcon,
  ChatBubbleBottomCenterTextIcon,
  ChatBubbleOvalLeftEllipsisIcon,
  ClipboardDocumentCheckIcon,
  ClockIcon,
  CircleStackIcon,
  CodeBracketIcon,
  ServerStackIcon,
  CommandLineIcon,
  CpuChipIcon,
  DocumentMagnifyingGlassIcon,
  DocumentTextIcon,
  EyeIcon,
  FilmIcon,
  HandRaisedIcon,
  MagnifyingGlassIcon,
  MicrophoneIcon,
  PhotoIcon,
  PrinterIcon,
  PuzzlePieceIcon,
  ShieldCheckIcon,
  SpeakerWaveIcon,
  UserGroupIcon,
  WrenchScrewdriverIcon,
  ChartBarSquareIcon,
} from '@heroicons/vue/24/outline'

import SettingsApprovalsPanel from './SettingsApprovalsPanel.vue'
import SettingsChatPanel from './SettingsChatPanel.vue'
import SettingsCodingPanel from './SettingsCodingPanel.vue'
import SettingsDatabasePanel from './SettingsDatabasePanel.vue'
import SettingsTimezonePanel from './SettingsTimezonePanel.vue'
import SettingsImageCaptionPanel from './SettingsImageCaptionPanel.vue'
import SettingsImageGenPanel from './SettingsImageGenPanel.vue'
import SettingsLoggingPanel from './SettingsLoggingPanel.vue'
import SettingsMaintenancePanel from './SettingsMaintenancePanel.vue'
import SettingsTelemetryPanel from './SettingsTelemetryPanel.vue'
import SettingsMalwarePanel from './SettingsMalwarePanel.vue'
import SettingsMemoryEmbeddingsPanel from './SettingsMemoryEmbeddingsPanel.vue'
import SettingsMemoryLimitsPanel from './SettingsMemoryLimitsPanel.vue'
import SettingsMemoryRerankerPanel from './SettingsMemoryRerankerPanel.vue'
import SettingsOcrPanel from './SettingsOcrPanel.vue'
import SettingsPerformancePanel from './SettingsPerformancePanel.vue'
import SettingsPrintersPanel from './SettingsPrintersPanel.vue'
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
      { id: 'printers', title: 'Printers', icon: PrinterIcon, component: SettingsPrintersPanel },
      { id: 'telemetry', title: 'Telemetry', icon: ChartBarSquareIcon, component: SettingsTelemetryPanel },
      // The last two deliberately: their controls take the instance down, so they
      // shouldn't sit next to the section the rail opens on by default. Database
      // sits beside Maintenance for that reason (restore and repair restart), while
      // its health strip stays a glance away (JCLAW-1165).
      { id: 'database', title: 'Database', icon: ServerStackIcon, component: SettingsDatabasePanel },
      { id: 'maintenance', title: 'Maintenance', icon: WrenchScrewdriverIcon, component: SettingsMaintenancePanel },
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
      { id: 'coding', title: 'Coding', icon: CodeBracketIcon, component: SettingsCodingPanel },
      { id: 'tasks', title: 'Tasks', icon: ClipboardDocumentCheckIcon, component: SettingsTasksPanel },
      { id: 'skills', title: 'Skills Promotion', icon: PuzzlePieceIcon, component: SettingsSkillsPanel },
    ],
  },
  {
    label: 'Memory',
    sections: [
      { id: 'memory-limits', title: 'Limits', icon: AdjustmentsHorizontalIcon, component: SettingsMemoryLimitsPanel },
      // Keeps the shipped `memory` id: it has always addressed the embedding panel, and
      // the header rule above applies — bookmarks and deep links point at it.
      { id: 'memory', title: 'Embeddings', icon: CircleStackIcon, component: SettingsMemoryEmbeddingsPanel },
      { id: 'memory-reranker', title: 'Reranker', icon: ArrowsUpDownIcon, component: SettingsMemoryRerankerPanel },
    ],
  },
  {
    label: 'Security',
    sections: [
      // First in the group: it governs every dangerous action, where the two below are
      // each one mechanism. Shell Execution configures what exec may run; this decides
      // whether a dangerous action runs at all when nobody can be asked (JCLAW-1022).
      { id: 'approvals', title: 'Tool Approvals', icon: HandRaisedIcon, component: SettingsApprovalsPanel },
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

/**
 * Ids that no longer name a section, mapped to the one that absorbed them.
 *
 * Retiring an id without an entry here is silent: `settings.vue` falls back to
 * the FIRST section for anything it doesn't recognise, so every shipped
 * bookmark and doc link lands on Timezone and looks merely broken.
 */
const retiredSectionIds: Record<string, string> = {
  // Merged into Maintenance (JCLAW-1057).
  upgrade: 'maintenance',
  restart: 'maintenance',
  password: 'maintenance',
}

/**
 * Canonical id for a `?section=` value, following any retirement, or null when
 * it names nothing at all.
 */
export function resolveSectionId(id: unknown): string | null {
  if (typeof id !== 'string') return null
  if (sections.some(s => s.id === id)) return id
  return retiredSectionIds[id] ?? null
}
