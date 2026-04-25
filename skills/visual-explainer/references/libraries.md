# Libraries and Dependencies for Visual Explainer

This document lists the libraries used in Visual Explainer HTML outputs and how to integrate them properly.

## Mermaid.js

For flowcharts, sequence diagrams, class diagrams, state diagrams, etc.

### Basic Integration

```html
<script src="https://cdn.jsdelivr.net/npm/mermaid/dist/mermaid.min.js"></script>
<script>
  mermaid.initialize({
    theme: window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'default',
    startOnLoad: true,
    securityLevel: 'loose',
    flowchart: {
      htmlLabels: true,
      curve: 'basis'
    }
  });
  
  // Handle theme changes
  window.matchMedia('(prefers-color-scheme: dark)').addEventListener('change', event => {
    mermaid.initialize({ theme: event.matches ? 'dark' : 'default' });
    location.reload(); // Needed to rerender with new theme
  });
</script>

<!-- Diagram container -->
<div class="mermaid">
  graph TD
    A[Client] --> B[Load Balancer]
    B --> C[Server 1]
    B --> D[Server 2]
</div>
```

### Mermaid Syntax Templates

#### Flowchart

```
graph TD
  A[Start] --> B{Decision}
  B -->|Yes| C[Action 1]
  B -->|No| D[Action 2]
  C --> E[End]
  D --> E
```

#### Sequence Diagram

```
sequenceDiagram
  participant Client
  participant Server
  participant Database
  
  Client->>Server: Request Data
  Server->>Database: Query
  Database->>Server: Results
  Server->>Client: Response
```

#### Class Diagram

```
classDiagram
  class Animal {
    +name: string
    +age: int
    +makeSound() void
  }
  
  class Dog {
    +breed: string
    +bark() void
  }
  
  Animal <|-- Dog
```

## Chart.js

For data visualization charts and graphs.

### Basic Integration

```html
<script src="https://cdn.jsdelivr.net/npm/chart.js"></script>

<div class="chart-container" style="height: 400px; width: 100%;">
  <canvas id="myChart"></canvas>
</div>

<script>
  const ctx = document.getElementById('myChart').getContext('2d');
  const chart = new Chart(ctx, {
    type: 'bar', // or 'line', 'pie', etc.
    data: {
      labels: ['Category 1', 'Category 2', 'Category 3'],
      datasets: [{
        label: 'Dataset Name',
        data: [12, 19, 8],
        backgroundColor: [
          'rgba(52, 152, 219, 0.7)',
          'rgba(46, 204, 113, 0.7)',
          'rgba(231, 76, 60, 0.7)'
        ],
        borderColor: [
          'rgba(52, 152, 219, 1)',
          'rgba(46, 204, 113, 1)',
          'rgba(231, 76, 60, 1)'
        ],
        borderWidth: 1
      }]
    },
    options: {
      responsive: true,
      maintainAspectRatio: false,
      scales: {
        y: {
          beginAtZero: true
        }
      }
    }
  });
  
  // Handle dark mode
  function updateChartTheme(darkMode) {
    const textColor = darkMode ? '#f0f0f0' : '#333333';
    const gridColor = darkMode ? 'rgba(255, 255, 255, 0.1)' : 'rgba(0, 0, 0, 0.1)';
    
    chart.options.scales.x.grid.color = gridColor;
    chart.options.scales.y.grid.color = gridColor;
    chart.options.scales.x.ticks.color = textColor;
    chart.options.scales.y.ticks.color = textColor;
    chart.update();
  }
  
  updateChartTheme(window.matchMedia('(prefers-color-scheme: dark)').matches);
  window.matchMedia('(prefers-color-scheme: dark)').addEventListener('change', e => {
    updateChartTheme(e.matches);
  });
</script>
```

## Prism.js

For syntax highlighting in code blocks.

### Basic Integration

```html
<link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/prismjs/themes/prism.css">
<link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/prismjs/themes/prism-tomorrow.css" media="(prefers-color-scheme: dark)">
<script src="https://cdn.jsdelivr.net/npm/prismjs/prism.js"></script>
<script src="https://cdn.jsdelivr.net/npm/prismjs/components/prism-javascript.js"></script>
<script src="https://cdn.jsdelivr.net/npm/prismjs/components/prism-css.js"></script>
<script src="https://cdn.jsdelivr.net/npm/prismjs/components/prism-python.js"></script>
<!-- Add other languages as needed -->

<pre><code class="language-javascript">
function example() {
  const message = "Hello, world!";
  console.log(message);
  return true;
}
</code></pre>
```

## Font Libraries

### System Font Stack

Use the system font stack for best performance and native look:

```css
body {
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 
    Oxygen, Ubuntu, Cantarell, 'Open Sans', 'Helvetica Neue', sans-serif;
}
```

### Google Fonts (optional)

If specific fonts are needed:

```html
<link rel="preconnect" href="https://fonts.googleapis.com">
<link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
<link href="https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700&display=swap" rel="stylesheet">

<style>
  body {
    font-family: 'Inter', sans-serif;
  }
</style>
```

## Icon Libraries

### Feather Icons (lightweight)

```html
<script src="https://unpkg.com/feather-icons"></script>
<script>
  document.addEventListener('DOMContentLoaded', function() {
    feather.replace();
  });
</script>

<i data-feather="circle"></i>
<i data-feather="arrow-right"></i>
```

## Interactive Components

### Collapsible Sections

```html
<style>
  .collapsible {
    margin-bottom: 1rem;
  }
  
  .collapsible-header {
    background-color: var(--card-bg);
    padding: 1rem;
    border-radius: 6px;
    cursor: pointer;
    display: flex;
    justify-content: space-between;
    align-items: center;
  }
  
  .collapsible-content {
    max-height: 0;
    overflow: hidden;
    transition: max-height 0.3s ease;
    background-color: var(--background-color);
    border-radius: 0 0 6px 6px;
    padding: 0 1rem;
  }
  
  .collapsible-content.open {
    max-height: 1000px; /* Large enough to contain content */
    padding: 1rem;
  }
  
  .collapsible-icon {
    transition: transform 0.3s ease;
  }
  
  .collapsible-icon.open {
    transform: rotate(180deg);
  }
</style>

<div class="collapsible">
  <div class="collapsible-header">
    <h3>Section Title</h3>
    <span class="collapsible-icon">▼</span>
  </div>
  <div class="collapsible-content">
    <p>Content goes here...</p>
  </div>
</div>

<script>
  document.querySelectorAll('.collapsible-header').forEach(header => {
    header.addEventListener('click', () => {
      const content = header.nextElementSibling;
      const icon = header.querySelector('.collapsible-icon');
      
      content.classList.toggle('open');
      icon.classList.toggle('open');
    });
  });
</script>
```

## Accessibility Tools

### Focus Trap for Modals

```html
<script>
  class FocusTrap {
    constructor(element) {
      this.element = element;
      this.focusableElements = this.element.querySelectorAll(
        'button, [href], input, select, textarea, [tabindex]:not([tabindex="-1"])'
      );
      this.firstFocusable = this.focusableElements[0];
      this.lastFocusable = this.focusableElements[this.focusableElements.length - 1];
      
      this.handleKeyDown = this.handleKeyDown.bind(this);
    }
    
    activate() {
      document.addEventListener('keydown', this.handleKeyDown);
      this.firstFocusable.focus();
    }
    
    deactivate() {
      document.removeEventListener('keydown', this.handleKeyDown);
    }
    
    handleKeyDown(e) {
      if (e.key !== 'Tab') return;
      
      if (e.shiftKey) { // Shift + Tab
        if (document.activeElement === this.firstFocusable) {
          e.preventDefault();
          this.lastFocusable.focus();
        }
      } else { // Tab
        if (document.activeElement === this.lastFocusable) {
          e.preventDefault();
          this.firstFocusable.focus();
        }
      }
    }
  }
</script>
```
