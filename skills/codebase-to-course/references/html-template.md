## Navigation & Progress (always include)

```html
<nav class="nav">
  <div class="progress-bar" role="progressbar" aria-valuenow="0"></div>
  <div class="nav-inner">
    <span class="nav-title">Course Title</span>
    <div class="nav-dots">
      <button class="nav-dot" data-target="module-1" data-tooltip="Module 1 Name" role="tab" aria-label="Module 1"></button>
      <!-- one per module -->
    </div>
  </div>
</nav>
```

JS:

```javascript
// Progress bar
const progressBar = document.querySelector('.progress-bar');
function updateProgressBar() {
  const h = document.documentElement.scrollHeight - window.innerHeight;
  progressBar.style.width = ((window.scrollY / h) * 100) + '%';
}
window.addEventListener('scroll', () => requestAnimationFrame(updateProgressBar), { passive: true });

// Nav dots click → scroll to module
document.querySelectorAll('.nav-dot').forEach(dot => {
  dot.addEventListener('click', () => {
    document.getElementById(dot.dataset.target)?.scrollIntoView({ behavior: 'smooth' });
  });
});

// Keyboard navigation
const modules = [...document.querySelectorAll('.module')];
function currentModuleIndex() {
  const y = window.scrollY + 100;
  for (let i = modules.length - 1; i >= 0; i--) {
    if (modules[i].offsetTop <= y) return i;
  }
  return 0;
}
function nextModule() { const i = currentModuleIndex(); if (i < modules.length - 1) modules[i+1].scrollIntoView({ behavior: 'smooth' }); }
function prevModule() { const i = currentModuleIndex(); if (i > 0) modules[i-1].scrollIntoView({ behavior: 'smooth' }); }
document.addEventListener('keydown', (e) => {
  if (['INPUT','TEXTAREA'].includes(e.target.tagName)) return;
  if (e.key === 'ArrowDown' || e.key === 'ArrowRight') { nextModule(); e.preventDefault(); }
  if (e.key === 'ArrowUp' || e.key === 'ArrowLeft') { prevModule(); e.preventDefault(); }
});

// Scroll-triggered reveals
const observer = new IntersectionObserver((entries) => {
  entries.forEach(entry => {
    if (entry.isIntersecting) { entry.target.classList.add('visible'); observer.unobserve(entry.target); }
  });
}, { rootMargin: '0px 0px -10% 0px', threshold: 0.1 });
document.querySelectorAll('.animate-in').forEach(el => observer.observe(el));
```

---

## Module HTML Template

```html
<section class="module" id="module-N" style="background: var(--color-bg-warm);">
  <div class="module-content">
    <header class="module-header animate-in">
      <span class="module-number">0N</span>
      <h1 class="module-title">Module Title</h1>
      <p class="module-subtitle">One-line description of what this module teaches</p>
    </header>
    <div class="module-body">
      <section class="screen animate-in">
        <h2 class="screen-heading">Screen Title</h2>
        <p>Content...</p>
        <!-- Interactive elements, translation blocks, etc. -->
      </section>
      <!-- more screens -->
    </div>
  </div>
</section>
```
