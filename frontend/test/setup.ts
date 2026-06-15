import { vi } from 'vitest'

// Polyfills for DOM APIs that jsdom (the Vitest `domEnvironment`) does not
// implement but the app calls at runtime. happy-dom shipped these; after the
// migration to jsdom we provide minimal stubs here so component mounts don't
// crash on environment gaps unrelated to what each test asserts.

// matchMedia — used by useTheme (prefers-color-scheme + prefers-reduced-motion)
// and the login/setup-password pages. Reports no match (light theme, motion
// enabled) and supports change subscription so useTheme's
// `mq.addEventListener('change', …)` wiring doesn't throw.
if (typeof window.matchMedia !== 'function') {
  window.matchMedia = vi.fn((query: string) => ({
    matches: false,
    media: query,
    onchange: null,
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
    addListener: vi.fn(),
    removeListener: vi.fn(),
    dispatchEvent: vi.fn(() => false),
  })) as unknown as typeof window.matchMedia
}

// scrollIntoView — used by guide.vue and chat.vue for smooth scrolling.
if (typeof Element.prototype.scrollIntoView !== 'function') {
  Element.prototype.scrollIntoView = vi.fn() as unknown as typeof Element.prototype.scrollIntoView
}

// DataTransfer — used by the skills/settings drag-and-drop handlers and their
// tests (`new DataTransfer()`; setData/getData round-trips the dragged id).
if (typeof globalThis.DataTransfer === 'undefined') {
  class DataTransferStub {
    private store = new Map<string, string>()
    effectAllowed = 'uninitialized'
    dropEffect = 'none'
    files: File[] = []
    items: unknown[] = []

    get types(): string[] {
      return [...this.store.keys()]
    }

    setData(format: string, data: string): void {
      this.store.set(format, String(data))
    }

    getData(format: string): string {
      return this.store.get(format) ?? ''
    }

    clearData(format?: string): void {
      if (format) this.store.delete(format)
      else this.store.clear()
    }

    setDragImage(): void {}
  }
  globalThis.DataTransfer = DataTransferStub as unknown as typeof DataTransfer
}
