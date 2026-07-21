/**
 * <deck-stage> — Slide deck shell web component.
 *
 * Usage:
 *   <script src="deck_stage.js"></script>
 *   <deck-stage>
 *     <section data-screen-label="01 Title">...</section>
 *     <section data-screen-label="02 Content">...</section>
 *   </deck-stage>
 *
 * Features:
 *   - Scales 1920×1080 canvas to fit any viewport, letterboxed on black
 *   - Keyboard nav: ArrowRight/Space = next, ArrowLeft = prev, Home/End = first/last
 *   - Click/tap on left/right half for prev/next (on touch devices)
 *   - Slide counter overlay bottom-right
 *   - Persists current slide to localStorage (key: "deck-stage-current")
 *   - @media print: one slide per page for PDF export
 *   - Auto-tags slides with data-screen-label if missing
 *   - Posts {slideIndexChanged: N} to window.parent on slide change (for speaker notes)
 *
 * Customize:
 *   <deck-stage width="1920" height="1080" storage-key="my-deck">
 */

(function () {
  const DEFAULT_WIDTH = 1920;
  const DEFAULT_HEIGHT = 1080;

  class DeckStage extends HTMLElement {
    constructor() {
      super();
      this.attachShadow({ mode: 'open' });
      this._current = 0;
      this._slides = [];
    }

    connectedCallback() {
      this.width = parseInt(this.getAttribute('width') || DEFAULT_WIDTH, 10);
      this.height = parseInt(this.getAttribute('height') || DEFAULT_HEIGHT, 10);
      this.storageKey = this.getAttribute('storage-key') || 'deck-stage-current';

      this._render();
      this._collectSlides();
      this._restoreFromStorage();
      this._updateView();

      this._onKey = this._onKey.bind(this);
      this._onResize = this._onResize.bind(this);
      this._onTap = this._onTap.bind(this);

      window.addEventListener('keydown', this._onKey);
      window.addEventListener('resize', this._onResize);
      this.shadowRoot.addEventListener('click', this._onTap);

      this._onResize();
      this._postSlideChange();
    }

    disconnectedCallback() {
      window.removeEventListener('keydown', this._onKey);
      window.removeEventListener('resize', this._onResize);
    }

    _render() {
      this.shadowRoot.innerHTML = `
        <style>
          :host {
            display: block;
            position: fixed;
            inset: 0;
            background: #000;
            overflow: hidden;
          }
          :host([noscale]) { position: static; background: transparent; }
          :host([noscale]) .stage { transform: none !important; }
          .stage {
            position: absolute;
            top: 50%;
            left: 50%;
            transform-origin: 0 0;
            background: #fff;
            overflow: hidden;
          }
          ::slotted(section) {
            position: absolute;
            inset: 0;
            display: none;
            overflow: hidden;
          }
          ::slotted(section.active) { display: block; }
          .counter {
            position: fixed;
            bottom: 16px;
            right: 16px;
            z-index: 100;
            font: 500 14px/1 ui-monospace, 'SFMono-Regular', Menlo, monospace;
            color: rgba(255,255,255,0.7);
            padding: 6px 10px;
            background: rgba(0,0,0,0.4);
            border-radius: 999px;
            pointer-events: none;
            user-select: none;
          }
          .nav {
            position: fixed;
            top: 50%;
            transform: translateY(-50%);
            width: 48px;
            height: 48px;
            display: flex;
            align-items: center;
            justify-content: center;
            background: rgba(255,255,255,0.1);
            color: white;
            border: none;
            border-radius: 999px;
            cursor: pointer;
            font-size: 20px;
            z-index: 100;
            user-select: none;
          }
          .nav:hover { background: rgba(255,255,255,0.2); }
          .nav.prev { left: 16px; }
          .nav.next { right: 16px; }
          @media print {
            :host { position: static; background: white; }
            .stage { position: static; transform: none !important; width: 100%; height: 100vh; }
            .counter, .nav { display: none; }
            ::slotted(section) { display: block !important; page-break-after: always; position: relative; }
          }
        </style>
        <div class="stage" style="width: ${this.width}px; height: ${this.height}px;">
          <slot></slot>
        </div>
        <button class="nav prev" aria-label="Previous slide">‹</button>
        <button class="nav next" aria-label="Next slide">›</button>
        <div class="counter"></div>
      `;
      this._stage = this.shadowRoot.querySelector('.stage');
      this._counter = this.shadowRoot.querySelector('.counter');
    }

    _collectSlides() {
      this._slides = Array.from(this.children).filter(el => el.tagName === 'SECTION');
      this._slides.forEach((sec, i) => {
        if (!sec.hasAttribute('data-screen-label')) {
          sec.setAttribute('data-screen-label', String(i + 1).padStart(2, '0'));
        }
        if (!sec.hasAttribute('data-om-validate')) {
          sec.setAttribute('data-om-validate', '');
        }
      });
    }

    _restoreFromStorage() {
      try {
        const saved = parseInt(localStorage.getItem(this.storageKey), 10);
        if (!isNaN(saved) && saved >= 0 && saved < this._slides.length) {
          this._current = saved;
        }
      } catch {}
    }

    _saveToStorage() {
      try { localStorage.setItem(this.storageKey, String(this._current)); } catch {}
    }

    _updateView() {
      this._slides.forEach((sec, i) => {
        sec.classList.toggle('active', i === this._current);
      });
      if (this._counter) {
        this._counter.textContent = `${this._current + 1} / ${this._slides.length}`;
      }
    }

    _onResize() {
      if (this.hasAttribute('noscale')) return;
      const vw = window.innerWidth;
      const vh = window.innerHeight;
      const scale = Math.min(vw / this.width, vh / this.height);
      const w = this.width * scale;
      const h = this.height * scale;
      this._stage.style.transform = `translate(${-this.width / 2}px, ${-this.height / 2}px) scale(${scale})`;
    }

    _onKey(e) {
      if (e.key === 'ArrowRight' || e.key === ' ' || e.key === 'PageDown') {
        this.next(); e.preventDefault();
      } else if (e.key === 'ArrowLeft' || e.key === 'PageUp') {
        this.prev(); e.preventDefault();
      } else if (e.key === 'Home') {
        this.go(0); e.preventDefault();
      } else if (e.key === 'End') {
        this.go(this._slides.length - 1); e.preventDefault();
      }
    }

    _onTap(e) {
      const target = e.target;
      if (target.closest('.nav.prev')) { this.prev(); return; }
      if (target.closest('.nav.next')) { this.next(); return; }
    }

    _postSlideChange() {
      try {
        window.parent.postMessage({ slideIndexChanged: this._current }, '*');
      } catch {}
    }

    next() { this.go(Math.min(this._current + 1, this._slides.length - 1)); }
    prev() { this.go(Math.max(this._current - 1, 0)); }
    go(i) {
      if (i === this._current) return;
      this._current = i;
      this._updateView();
      this._saveToStorage();
      this._postSlideChange();
    }
  }

  customElements.define('deck-stage', DeckStage);

  // Expose a programmatic helper for exporters / speaker-note viewers
  window.goToSlide = function (i) {
    const stage = document.querySelector('deck-stage');
    if (stage) stage.go(i);
  };
})();
