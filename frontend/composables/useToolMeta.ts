export interface ToolFunction {
  name: string
  description: string
}

export interface ToolMeta {
  shortDescription: string
  icon: string
  category: 'System' | 'Web' | 'Files' | 'Utilities'
  categoryColor: string
  iconBg: string
  iconColor: string
  requiresConfig?: string
  system?: boolean   // always-on; hidden from toggle UI
  functions: ToolFunction[]
}

const META: Record<string, ToolMeta> = {
  exec: {
    shortDescription: "Execute shell commands on the host system with allowlist-based security controls.",
    icon: 'terminal',
    category: 'System',
    categoryColor: 'text-neutral-400 bg-neutral-800',
    iconBg: 'bg-neutral-800',
    iconColor: 'text-neutral-300',
    requiresConfig: 'shell.enabled',
    functions: [
      { name: 'exec', description: 'Run a shell command; validated against the permitted binary allowlist before execution' }
    ]
  },
  filesystem: {
    shortDescription: "Read, write, edit, list, and patch plain text files in the agent's workspace.",
    icon: 'folder',
    category: 'Files',
    categoryColor: 'text-amber-400 bg-amber-500/15',
    iconBg: 'bg-amber-500/15',
    iconColor: 'text-amber-400',
    functions: [
      { name: 'readFile',   description: 'Read file content up to 1 MB' },
      { name: 'writeFile',  description: 'Create or overwrite a file with full content' },
      { name: 'appendFile', description: 'Append content to the end of a file, creating it if missing' },
      { name: 'editFile',   description: 'Apply a batch of oldText → newText replacements atomically' },
      { name: 'applyPatch', description: 'Apply a multi-file unified diff patch' },
      { name: 'listFiles',  description: 'List the contents of a directory' }
    ]
  },
  documents: {
    shortDescription: "Read and author rich formats — PDF, DOCX, HTML, XLSX, PPTX, EPUB — from markdown.",
    icon: 'document',
    category: 'Files',
    categoryColor: 'text-amber-400 bg-amber-500/15',
    iconBg: 'bg-amber-500/15',
    iconColor: 'text-amber-400',
    functions: [
      { name: 'readDocument',   description: 'Extract text from PDF, DOCX, XLSX, PPTX, HTML, RTF, ODT, EPUB via Apache Tika' },
      { name: 'writeDocument',  description: 'Author a new HTML, PDF, or DOCX file from markdown input' },
      { name: 'appendDocument', description: 'Append markdown to a draft file for incremental large-document authoring' },
      { name: 'renderDocument', description: 'Convert an accumulated markdown draft into the target output format' }
    ]
  },
  web_fetch: {
    shortDescription: "Fetch and extract readable text or raw HTML from any URL.",
    icon: 'globe',
    category: 'Web',
    categoryColor: 'text-blue-400 bg-blue-500/15',
    iconBg: 'bg-blue-500/15',
    iconColor: 'text-blue-400',
    functions: [
      { name: 'fetch (text)', description: 'Retrieve a URL and extract clean, readable text content' },
      { name: 'fetch (html)', description: 'Retrieve a URL and return the raw HTML source' }
    ]
  },
  web_search: {
    shortDescription: "Search the web using Exa, Brave, Tavily, or Perplexity for current results.",
    icon: 'search',
    category: 'Web',
    categoryColor: 'text-blue-400 bg-blue-500/15',
    iconBg: 'bg-blue-500/15',
    iconColor: 'text-blue-400',
    functions: [
      { name: 'search', description: 'Execute a web search; provider is auto-selected or can be specified explicitly' }
    ]
  },
  browser: {
    shortDescription: "Headless browser automation for SPAs, login flows, and JavaScript-heavy pages.",
    icon: 'browser',
    category: 'Web',
    categoryColor: 'text-blue-400 bg-blue-500/15',
    iconBg: 'bg-blue-500/15',
    iconColor: 'text-blue-400',
    requiresConfig: 'playwright.enabled',
    functions: [
      { name: 'navigate',   description: 'Load a URL, wait for network idle, and return page text' },
      { name: 'click',      description: 'Click a DOM element by CSS selector' },
      { name: 'fill',       description: 'Fill a form field with a value by CSS selector' },
      { name: 'getText',    description: 'Extract the text content of a CSS selector' },
      { name: 'screenshot', description: 'Capture a full-page screenshot and save it to the workspace' },
      { name: 'evaluate',   description: 'Execute a JavaScript expression and return the result' },
      { name: 'close',      description: 'Close the browser session and free all resources' }
    ]
  },
  datetime: {
    shortDescription: "Get the current time, convert between timezones, and calculate date differences.",
    icon: 'clock',
    category: 'Utilities',
    categoryColor: 'text-emerald-400 bg-emerald-500/15',
    iconBg: 'bg-emerald-500/15',
    iconColor: 'text-emerald-400',
    functions: [
      { name: 'now',       description: 'Return the current date and time in the specified timezone' },
      { name: 'convert',   description: 'Convert a timestamp from one timezone to another' },
      { name: 'calculate', description: 'Add or subtract a duration from a timestamp, or compute the difference between two timestamps' }
    ]
  },
  checklist: {
    shortDescription: "Create and manage structured checklists to track multi-step work in progress.",
    icon: 'check',
    category: 'Utilities',
    categoryColor: 'text-emerald-400 bg-emerald-500/15',
    iconBg: 'bg-emerald-500/15',
    iconColor: 'text-emerald-400',
    functions: [
      { name: 'update', description: 'Submit a checklist with items and statuses; exactly one item must be in_progress at a time' }
    ]
  },
  task_manager: {
    shortDescription: "Create, schedule, and manage recurring background tasks for the agent.",
    icon: 'tasks',
    category: 'Utilities',
    categoryColor: 'text-emerald-400 bg-emerald-500/15',
    iconBg: 'bg-emerald-500/15',
    iconColor: 'text-emerald-400',
    functions: [
      { name: 'createTask',            description: 'Create an immediate background task with a name and description' },
      { name: 'scheduleTask',          description: 'Schedule a task to run once at a specific ISO 8601 datetime' },
      { name: 'scheduleRecurringTask', description: 'Create a recurring task using a cron expression' },
      { name: 'deleteRecurringTask',   description: 'Cancel a recurring task by name' },
      { name: 'listRecurringTasks',    description: 'List all currently active recurring tasks' }
    ]
  },
  skills: {
    shortDescription: "Runtime introspection: discover which tools and skills are currently available to the agent.",
    icon: 'skills',
    category: 'Utilities',
    categoryColor: 'text-emerald-400 bg-emerald-500/15',
    iconBg: 'bg-emerald-500/15',
    iconColor: 'text-emerald-400',
    system: true,
    functions: [
      { name: 'listTools',  description: 'List all tools currently enabled for this agent' },
      { name: 'listSkills', description: 'List all skills currently available to this agent' },
      { name: 'readSkill',  description: 'Read the full content of a specific skill by name' }
    ]
  }
}

export const ORDERED_TOOLS = [
  'exec',
  'filesystem', 'documents',
  'web_fetch', 'web_search', 'browser',
  'datetime', 'checklist', 'task_manager', 'skills',
] as const

export const TOOL_CATEGORIES = ['System', 'Files', 'Web', 'Utilities'] as const

const PILL_CLASSES: Record<ToolMeta['category'], string> = {
  System:    'bg-neutral-800 border-neutral-700/60 text-neutral-400',
  Files:     'bg-amber-500/10 border-amber-500/25 text-amber-400',
  Web:       'bg-blue-500/10 border-blue-500/25 text-blue-400',
  Utilities: 'bg-emerald-500/10 border-emerald-500/25 text-emerald-400',
}

export function useToolMeta() {
  function getToolMeta(name: string): ToolMeta | null {
    return META[name] ?? null
  }

  function getPillClass(name: string): string {
    const category = META[name]?.category
    return category ? PILL_CLASSES[category] : PILL_CLASSES.System
  }

  return { TOOL_META: META, getToolMeta, getPillClass }
}
