# Architecture Diagram

Architecture diagrams show the components and connections in a system. They focus on structural relationships rather than sequence or flow.

## When to Use

Use architecture diagrams when you need to show:
- System components and how they relate to each other
- Communication patterns between services
- Deployment topology
- Domain boundaries and integration points

## Layout Conventions

- **Layout direction**: Typically left-to-right or top-to-bottom
- **Component proximity**: Related components should be closer together
- **Layer grouping**: Group components by logical layer (frontend, backend, data, etc.)
- **Flow indication**: Arrows should follow a consistent direction (usually left→right or top→bottom)
- **Connection density**: Minimize crossing lines by thoughtful placement

## Component Types

| Type | Visual Treatment | Usage |
|------|------------------|-------|
| Frontend | Paper-2 fill, ink stroke | User interfaces, clients, web apps |
| Backend | Paper-2 fill, ink stroke | Services, APIs, servers |
| Database | Soft fill, muted stroke | Persistent storage, data stores |
| Cache | Soft fill, muted stroke | In-memory stores, caches |
| Queue | Soft fill, muted stroke | Message queues, event buses |
| External | Light fill, dashed stroke | Third-party services, external APIs |
| Focal | Accent-tint fill, accent stroke | The 1-2 components you want to highlight |

## Connection Types

| Type | Visual Treatment | Usage |
|------|------------------|-------|
| Default | Muted stroke | Generic connections |
| HTTP/API | Link color | RESTful or API calls |
| Event | Accent color | Event publishing, subscriptions |
| Data flow | Muted stroke | Data movement |
| Async | Dashed muted | Asynchronous operations |
| Optional | Dotted muted | Optional interactions |

## Component Template

```html
<!-- Component box -->
<rect x="240" y="120" width="160" height="80" rx="6" fill="#ffffff" stroke="#1c1917" stroke-width="1"/>
<!-- Type tag -->
<rect x="248" y="126" width="28" height="12" rx="2" fill="transparent" stroke="rgba(28,25,23,0.4)" stroke-width="0.8"/>
<text x="262" y="135" fill="rgba(28,25,23,0.8)" font-size="7" font-family="'Geist Mono', monospace" text-anchor="middle" letter-spacing="0.08em">API</text>
<!-- Component name -->
<text x="320" y="162" fill="#1c1917" font-size="12" font-weight="600" font-family="'Geist', sans-serif" text-anchor="middle">User Service</text>
<!-- Technical sublabel -->
<text x="320" y="178" fill="#52534e" font-size="9" font-family="'Geist Mono', monospace" text-anchor="middle">nodejs:8080</text>
```

## Arrow Template

```html
<!-- Draw the line first, before components (z-order) -->
<line x1="160" y1="160" x2="240" y2="160" stroke="#52534e" stroke-width="1" marker-end="url(#arrow)"/>
<!-- Arrow label with background -->
<rect x="190" y="148" width="36" height="12" rx="2" fill="#faf7f2"/>
<text x="208" y="157" fill="#65655c" font-size="8" font-family="'Geist Mono', monospace" text-anchor="middle" letter-spacing="0.06em">HTTP</text>
```

## Anti-patterns

- Don't create components for every minor service or library
- Don't use different colors for every component (use the type system)
- Don't connect everything to everything (show meaningful relationships)
- Don't use bidirectional arrows (split into two directed arrows)
- Don't overload the diagram with technical details (use sublabels for that)

## Example Architecture

```html
<svg width="100%" height="480" viewBox="0 0 800 480" xmlns="http://www.w3.org/2000/svg">
  <defs>
    <marker id="arrow" markerWidth="8" markerHeight="6" refX="7" refY="3" orient="auto">
      <polygon points="0 0, 8 3, 0 6" fill="#52534e"/>
    </marker>
    <marker id="arrow-accent" markerWidth="8" markerHeight="6" refX="7" refY="3" orient="auto">
      <polygon points="0 0, 8 3, 0 6" fill="#b5523a"/>
    </marker>
    <marker id="arrow-link" markerWidth="8" markerHeight="6" refX="7" refY="3" orient="auto">
      <polygon points="0 0, 8 3, 0 6" fill="#1a70c7"/>
    </marker>
  </defs>
  
  <!-- Background -->
  <rect width="100%" height="100%" fill="#faf7f2"/>
  
  <!-- Frontend (focal) -->
  <rect x="80" y="120" width="160" height="80" rx="6" fill="#fbeae6" stroke="#b5523a" stroke-width="1"/>
  <rect x="88" y="126" width="28" height="12" rx="2" fill="transparent" stroke="rgba(181,82,58,0.4)" stroke-width="0.8"/>
  <text x="102" y="135" fill="rgba(181,82,58,0.8)" font-size="7" font-family="'Geist Mono', monospace" text-anchor="middle" letter-spacing="0.08em">WEB</text>
  <text x="160" y="162" fill="#1c1917" font-size="12" font-weight="600" font-family="'Geist', sans-serif" text-anchor="middle">React App</text>
  <text x="160" y="178" fill="#52534e" font-size="9" font-family="'Geist Mono', monospace" text-anchor="middle">ts:3000</text>
  
  <!-- API Service -->
  <rect x="320" y="120" width="160" height="80" rx="6" fill="#ffffff" stroke="#1c1917" stroke-width="1"/>
  <rect x="328" y="126" width="28" height="12" rx="2" fill="transparent" stroke="rgba(28,25,23,0.4)" stroke-width="0.8"/>
  <text x="342" y="135" fill="rgba(28,25,23,0.8)" font-size="7" font-family="'Geist Mono', monospace" text-anchor="middle" letter-spacing="0.08em">API</text>
  <text x="400" y="162" fill="#1c1917" font-size="12" font-weight="600" font-family="'Geist', sans-serif" text-anchor="middle">User Service</text>
  <text x="400" y="178" fill="#52534e" font-size="9" font-family="'Geist Mono', monospace" text-anchor="middle">node:8080</text>
  
  <!-- Database -->
  <rect x="320" y="280" width="160" height="80" rx="6" fill="rgba(28,25,23,0.05)" stroke="#52534e" stroke-width="1"/>
  <rect x="328" y="286" width="28" height="12" rx="2" fill="transparent" stroke="rgba(82,83,78,0.4)" stroke-width="0.8"/>
  <text x="342" y="295" fill="rgba(82,83,78,0.8)" font-size="7" font-family="'Geist Mono', monospace" text-anchor="middle" letter-spacing="0.08em">DB</text>
  <text x="400" y="322" fill="#1c1917" font-size="12" font-weight="600" font-family="'Geist', sans-serif" text-anchor="middle">User Database</text>
  <text x="400" y="338" fill="#52534e" font-size="9" font-family="'Geist Mono', monospace" text-anchor="middle">postgres:5432</text>
  
  <!-- Cache -->
  <rect x="560" y="120" width="160" height="80" rx="6" fill="rgba(82,83,78,0.10)" stroke="#65655c" stroke-width="1"/>
  <rect x="568" y="126" width="28" height="12" rx="2" fill="transparent" stroke="rgba(101,101,92,0.4)" stroke-width="0.8"/>
  <text x="582" y="135" fill="rgba(101,101,92,0.8)" font-size="7" font-family="'Geist Mono', monospace" text-anchor="middle" letter-spacing="0.08em">CACHE</text>
  <text x="640" y="162" fill="#1c1917" font-size="12" font-weight="600" font-family="'Geist', sans-serif" text-anchor="middle">Redis Cache</text>
  <text x="640" y="178" fill="#52534e" font-size="9" font-family="'Geist Mono', monospace" text-anchor="middle">redis:6379</text>
  
  <!-- Connections -->
  <!-- Frontend to API -->
  <line x1="240" y1="160" x2="320" y2="160" stroke="#1a70c7" stroke-width="1" marker-end="url(#arrow-link)"/>
  <rect x="270" y="148" width="36" height="12" rx="2" fill="#faf7f2"/>
  <text x="288" y="157" fill="#65655c" font-size="8" font-family="'Geist Mono', monospace" text-anchor="middle" letter-spacing="0.06em">REST</text>
  
  <!-- API to DB -->
  <line x1="400" y1="200" x2="400" y2="280" stroke="#52534e" stroke-width="1" marker-end="url(#arrow)"/>
  <rect x="400" y="234" width="36" height="12" rx="2" fill="#faf7f2"/>
  <text x="418" y="243" fill="#65655c" font-size="8" font-family="'Geist Mono', monospace" text-anchor="middle" letter-spacing="0.06em">QUERY</text>
  
  <!-- API to Cache -->
  <line x1="480" y1="160" x2="560" y2="160" stroke="#52534e" stroke-width="1" marker-end="url(#arrow)"/>
  <rect x="510" y="148" width="36" height="12" rx="2" fill="#faf7f2"/>
  <text x="528" y="157" fill="#65655c" font-size="8" font-family="'Geist Mono', monospace" text-anchor="middle" letter-spacing="0.06em">GET</text>
  
  <!-- Legend -->
  <line x1="80" y1="400" x2="720" y2="400" stroke="rgba(11,13,11,0.10)" stroke-width="0.8"/>
  <text x="80" y="420" fill="#52534e" font-size="8" font-family="'Geist Mono', monospace" letter-spacing="0.14em">LEGEND</text>
  
  <!-- Legend items -->
  <rect x="160" y="412" width="12" height="12" rx="2" fill="#fbeae6" stroke="#b5523a" stroke-width="1"/>
  <text x="180" y="420" fill="#52534e" font-size="9" font-family="'Geist', sans-serif">Focal Component</text>
  
  <rect x="280" y="412" width="12" height="12" rx="2" fill="#ffffff" stroke="#1c1917" stroke-width="1"/>
  <text x="300" y="420" fill="#52534e" font-size="9" font-family="'Geist', sans-serif">Service</text>
  
  <rect x="380" y="412" width="12" height="12" rx="2" fill="rgba(28,25,23,0.05)" stroke="#52534e" stroke-width="1"/>
  <text x="400" y="420" fill="#52534e" font-size="9" font-family="'Geist', sans-serif">Storage</text>
  
  <rect x="480" y="412" width="12" height="12" rx="2" fill="rgba(82,83,78,0.10)" stroke="#65655c" stroke-width="1"/>
  <text x="500" y="420" fill="#52534e" font-size="9" font-family="'Geist', sans-serif">Cache</text>
</svg>
```

## Output Example

Here's how a complete architecture diagram HTML file would look. This includes:

1. The HTML structure with head/body
2. Embedded CSS
3. Inline SVG
4. No external dependencies except Google Fonts

```html
<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>Architecture Diagram</title>
  <link href="https://fonts.googleapis.com/css2?family=Instrument+Serif:ital@0;1&family=Geist:wght@400;500;600&family=Geist+Mono:wght@400;500;600&display=swap" rel="stylesheet">
  <style>
    :root {
      --paper: #faf7f2;
      --paper-2: #ffffff;
      --ink: #1c1917;
      --muted: #52534e;
      --soft: #65655c;
      --rule: rgba(11,13,11,0.10);
      --rule-solid: #e0ddd7;
      --accent: #b5523a;
      --accent-tint: #fbeae6;
      --link: #1a70c7;
    }
    
    body {
      background-color: var(--paper);
      color: var(--ink);
      font-family: 'Geist', system-ui, sans-serif;
      max-width: 960px;
      margin: 0 auto;
      padding: 2rem 1rem;
      line-height: 1.5;
    }
    
    .eyebrow {
      font-family: 'Geist Mono', monospace;
      font-size: 12px;
      letter-spacing: 0.08em;
      text-transform: uppercase;
      color: var(--muted);
      margin: 0 0 0.5rem 0;
    }
    
    h1 {
      font-family: 'Instrument Serif', serif;
      font-weight: 400;
      font-size: 1.75rem;
      margin: 0 0 1rem 0;
    }
    
    .diagram-container {
      background-color: var(--paper);
      border-radius: 8px;
      border: 1px solid var(--rule);
      overflow-x: auto;
      margin: 1.5rem 0;
    }
    
    .colophon {
      font-family: 'Geist Mono', monospace;
      font-size: 11px;
      color: var(--muted);
      border-top: 1px solid var(--rule);
      padding-top: 1rem;
      margin-top: 3rem;
    }
  </style>
</head>
<body>
  <p class="eyebrow">ARCHITECTURE</p>
  <h1>User Management System</h1>
  
  <div class="diagram-container">
    <svg width="100%" height="480" viewBox="0 0 800 480" xmlns="http://www.w3.org/2000/svg">
      <!-- SVG content from example above -->
    </svg>
  </div>
  
  <p class="colophon">Generated with diagram-design</p>
</body>
</html>
```

## Checklist Before Generating

- [ ] Components represent distinct ideas (merge any that always travel together)
- [ ] Connections carry meaningful information (remove if relationship is obvious)
- [ ] Accent color used on only 1-2 focal elements
- [ ] Legend covers all component and connection types used
- [ ] Within complexity budget (≤9 components, ≤12 connections)
- [ ] All coordinates divisible by 4
- [ ] Arrow labels have background rectangles
- [ ] Legend is horizontal strip at bottom
- [ ] Appropriate font choices (sans for names, mono for technical details)