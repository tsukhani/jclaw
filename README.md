<h1 align="center">🦞 JClaw - Java-based Enterprise AI Assistant</h1>

<p align="center">
  <img src="combined-logo.png" width="650" alt="JClaw Logo">
</p>

<p align="center">
  <strong>JAVA FIRST. NO BLOAT. PURE POWER.</strong>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/BUILD-PASSING-brightgreen?style=for-the-badge" alt="Build: Passing">
  <img src="https://img.shields.io/badge/RELEASE-V0.2.0-blue?style=for-the-badge" alt="Release: v0.2.0">
  <img src="https://img.shields.io/badge/LICENSE-MIT-green?style=for-the-badge" alt="License: MIT">
  <img src="https://img.shields.io/badge/JDK-25%2B-orange?style=for-the-badge" alt="JDK: 25+">
</p>

<br>

## Overview

JClaw is Abundent's take on an AI-powered automation platform — fully implemented in **pure Java** to eliminate runtime dependencies and reduce complexity. Built on a customized [Play Framework 1.x](https://github.com/tsukhani/play1) foundation, it brings together:

- **[OpenClaw](https://github.com/tsukhani/openclaw)** agent orchestration, memory system, and conversational AI patterns
- **[JavaClaw](https://github.com/jobrunr/javaclaw)** job scheduling, background task processing, and distributed execution

The result: A leaner, faster, more maintainable platform for building AI agents and automation workflows.

---

## Screenshot

<p align="center">
  <img src="jclaw-screenshot.png" width="900" alt="JClaw Chat Interface">
</p>
<p align="center"><em>Web chat with memory-aware agents, tool execution, and markdown rendering.</em></p>

---

## Features

- 🤖 **Agent System** — Conversational AI agents with memory and context
- ⚡ **Job Scheduling** — Background tasks, cron jobs, and distributed execution
- 🔧 **Pure Java** — No Python/JavaScript runtimes required
- 📦 **Built-in Frontend** — Nuxt 3 SPA (Vue 3 + TypeScript)
- 🔌 **Plugin Architecture** — Modular, extensible design
- 🧠 **Memory & Context** — Persistent conversations across sessions
- 🚀 **Lightweight** — Minimal resource footprint, fast startup

---

## Directory Structure

```
jclaw/
├── app/                          # Application code
│   ├── controllers/              # HTTP controllers (Play 1.x pattern)
│   ├── models/                   # Domain models, entities
│   ├── services/                 # Business logic, services
│   ├── agents/                   # AI agent implementations
│   ├── jobs/                     # Background job handlers
│   ├── skills/                   # Modular skills/plugins
│   └── utils/                    # Utility classes
├── conf/                         # Play configuration
│   ├── application.conf          # Main app config
│   ├── routes                    # URL routing
│   ├── dependencies.yml          # Module dependencies
│   └── initial-data.yml          # Bootstrap data
├── frontend/                     # Nuxt 3 SPA
│   ├── app/                      # Nuxt app directory
│   ├── components/               # Vue components
│   ├── composables/              # Vue composables
│   ├── layouts/                  # Page layouts
│   ├── pages/                    # Nuxt pages
│   ├── stores/                   # Pinia stores
│   ├── server/                   # Server middleware
│   ├── public/                   # Static assets
│   └── nuxt.config.ts            # Nuxt configuration
├── lib/                          # Custom JARs (if needed)
├── modules/                      # Play modules (auto-managed)
├── public/                       # Static web assets
├── test/                         # Unit and integration tests
├── tmp/                          # Play temp/runtime files
├── logs/                         # Application logs
├── .github/                      # GitHub workflows (if migrated)
└── README.md                     # This file
```

---

## Getting Started

### Prerequisites

- JDK 25+ (Zulu recommended)
- Python 3.9+ (3.12 recommended) — Play's CLI commands are Python scripts
- [Abundent's Play Framework 1.x](https://github.com/tsukhani/play1) (`play` command in PATH)
- Node.js 20+ (24 recommended) for frontend
- pnpm for frontend package management

### Clone & Setup

```bash
# Clone the repository
git clone https://bitbucket.abundent.com/scm/jclaw/jclaw.git
cd jclaw

# Install frontend dependencies
cd frontend && pnpm install && cd ..

# Resolve backend dependencies
play deps --sync
```

### Development

```bash
# Terminal 1: Start Play backend
play run

# Terminal 2: Start Nuxt dev server
cd frontend && pnpm dev
```

The application will be available at:
- **Backend API**: http://localhost:9000
- **Frontend SPA**: http://localhost:3000

### Production Deployment

```bash
# 1. Package the application as a ZIP
play dist
# Creates dist/jclaw.zip

# 2. Copy dist/jclaw.zip to your deployment machine, then:
unzip jclaw.zip
cd jclaw

# 3. Install and build the frontend
cd frontend && pnpm install && pnpm build && cd ..

# 4. Resolve backend dependencies and start
play deps --sync
play start --%prod
cd frontend && node .output/server/index.mjs
```

---

## Architecture

### Backend (Play 1.x + Java)

- **Models**: JPA entities with Play's model pattern
- **Controllers**: RESTful API endpoints
- **Services**: Business logic with dependency injection
- **Agents**: Conversational AI with memory/context persistence
- **Jobs**: Background processing powered by Play's built-in job system

### Frontend (Nuxt 3)

- **Framework**: Vue 3 + TypeScript + Nuxt 3
- **State**: Pinia stores
- **Styling**: Tailwind CSS
- **API**: Auto-generated from Play backend

---

## Key Principles

1. **Java-First** — Everything in Java. No Python, no Node for server-side logic.
2. **Minimal Dependencies** — Only bring in what we absolutely need.
3. **Memory & Context** — Agents remember. Context persists. Conversations flow.
4. **Async by Default** — Jobs run in background. APIs are non-blocking.
5. **Modular Skills** — Add capabilities via plugins, not monolithic code.

---

## Documentation

- [Play Framework 1.x](https://github.com/tsukhani/play1)
- [OpenClaw Reference](https://docs.openclaw.ai)
- [Nuxt 3 Docs](https://nuxt.com/docs)
- [JavaClaw Concepts](https://github.com/jobrunr/javaclaw)

---

## Contributing

This is an internal Abundent project. For questions or contributions, ping the team.

---

## License

This project is licensed under the [MIT License](LICENSE).

---

*Built with ☕ Java and ❤️ by the Abundent crew.*
