# CSS Patterns for Visual Explainer

This document provides reusable CSS patterns for creating consistent, responsive, and visually appealing HTML outputs.

## Theming System

Use CSS variables with dark mode support:

```css
:root {
  --primary-color: #3498db;
  --secondary-color: #2ecc71;
  --background-color: #ffffff;
  --text-color: #333333;
  --accent-color: #e74c3c;
  --link-color: #2980b9;
  --card-bg: #f8f9fa;
  --border-color: #e1e4e8;
}

@media (prefers-color-scheme: dark) {
  :root {
    --primary-color: #2980b9;
    --secondary-color: #27ae60;
    --background-color: #1a1a1a;
    --text-color: #f0f0f0;
    --accent-color: #c0392b;
    --link-color: #3498db;
    --card-bg: #2d2d2d;
    --border-color: #444444;
  }
}
```

## Responsive Grid Layout

For architecture diagrams and component relationships:

```css
.grid-container {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(250px, 1fr));
  gap: 1.5rem;
  margin: 2rem 0;
}

.grid-item {
  background-color: var(--card-bg);
  border-radius: 8px;
  padding: 1.5rem;
  box-shadow: 0 2px 10px rgba(0, 0, 0, 0.1);
  transition: transform 0.2s, box-shadow 0.2s;
}

.grid-item:hover {
  transform: translateY(-5px);
  box-shadow: 0 5px 15px rgba(0, 0, 0, 0.15);
}
```

## Data Table Styling

For comparison tables and data displays:

```css
.data-table {
  width: 100%;
  border-collapse: separate;
  border-spacing: 0;
  margin: 1.5rem 0;
  font-size: 0.95rem;
}

.data-table th, .data-table td {
  padding: 0.75rem 1rem;
  border-bottom: 1px solid var(--border-color);
  text-align: left;
}

.data-table th {
  background-color: var(--card-bg);
  font-weight: 600;
  position: sticky;
  top: 0;
  z-index: 1;
}

.data-table tr:last-child td {
  border-bottom: none;
}

.data-table tbody tr:hover {
  background-color: rgba(0, 0, 0, 0.03);
}

@media (prefers-color-scheme: dark) {
  .data-table tbody tr:hover {
    background-color: rgba(255, 255, 255, 0.05);
  }
}
```

## Card Components

For feature highlights and comparison cards:

```css
.card-container {
  display: flex;
  flex-wrap: wrap;
  gap: 1.5rem;
  margin: 2rem 0;
}

.card {
  flex: 1 1 300px;
  background-color: var(--card-bg);
  border-radius: 8px;
  padding: 1.5rem;
  box-shadow: 0 2px 10px rgba(0, 0, 0, 0.1);
}

.card-header {
  margin-bottom: 1rem;
  padding-bottom: 0.5rem;
  border-bottom: 1px solid var(--border-color);
}

.card-title {
  font-size: 1.25rem;
  margin: 0;
  color: var(--primary-color);
}
```

## Navigation and TOC

For multi-section pages:

```css
.toc {
  position: sticky;
  top: 2rem;
  max-height: calc(100vh - 4rem);
  overflow-y: auto;
  padding: 1rem;
  background-color: var(--card-bg);
  border-radius: 8px;
  margin-bottom: 2rem;
}

.toc-list {
  list-style-type: none;
  padding: 0;
  margin: 0;
}

.toc-item {
  margin-bottom: 0.5rem;
}

.toc-link {
  color: var(--text-color);
  text-decoration: none;
  display: block;
  padding: 0.5rem;
  border-radius: 4px;
  transition: background-color 0.2s;
}

.toc-link:hover, .toc-link.active {
  background-color: rgba(0, 0, 0, 0.05);
  color: var(--primary-color);
}

@media (prefers-color-scheme: dark) {
  .toc-link:hover, .toc-link.active {
    background-color: rgba(255, 255, 255, 0.05);
  }
}
```

## Animations

For interactive elements and transitions:

```css
/* Fade In */
@keyframes fadeIn {
  from { opacity: 0; }
  to { opacity: 1; }
}

.fade-in {
  animation: fadeIn 0.5s ease forwards;
}

/* Slide In */
@keyframes slideIn {
  from { transform: translateY(20px); opacity: 0; }
  to { transform: translateY(0); opacity: 1; }
}

.slide-in {
  animation: slideIn 0.5s ease forwards;
}

/* Staggered Animation */
.staggered > * {
  opacity: 0;
}

.staggered > *:nth-child(1) { animation: fadeIn 0.5s ease forwards 0.1s; }
.staggered > *:nth-child(2) { animation: fadeIn 0.5s ease forwards 0.2s; }
.staggered > *:nth-child(3) { animation: fadeIn 0.5s ease forwards 0.3s; }
.staggered > *:nth-child(4) { animation: fadeIn 0.5s ease forwards 0.4s; }
.staggered > *:nth-child(5) { animation: fadeIn 0.5s ease forwards 0.5s; }
```

## Print Styles

For printable versions of diagrams and documentation:

```css
@media print {
  body {
    background-color: white;
    color: black;
  }
  
  .container {
    max-width: 100%;
    padding: 0;
  }
  
  .no-print {
    display: none;
  }
  
  a {
    text-decoration: none;
    color: black;
  }
  
  .page-break {
    page-break-after: always;
  }
}
```

## Accessibility Features

Focus styles and screen reader support:

```css
:focus {
  outline: 3px solid var(--primary-color);
  outline-offset: 2px;
}

.sr-only {
  position: absolute;
  width: 1px;
  height: 1px;
  padding: 0;
  margin: -1px;
  overflow: hidden;
  clip: rect(0, 0, 0, 0);
  white-space: nowrap;
  border-width: 0;
}
```
