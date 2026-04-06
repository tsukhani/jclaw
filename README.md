# 🦞 JClaw - Java-based Enterprise AI Assistant

---

<p align="center">
  <table>
    <tr>
      <td valign="middle">
        <img src="mascot.jpg" height="260" alt="JClaw Mascot">
      </td>
      <td>&nbsp;&nbsp;&nbsp;&nbsp;</td>
      <td valign="middle">
        <img src="jclaw-logo.png" height="120" alt="JClaw Logo">
      </td>
    </tr>
  </table>
</p>

<p align="center">
  <strong>JAVA FIRST. NO BLOAT. PURE POWER.</strong>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/BUILD-WORK%20IN%20PROGRESS-yellow?style=for-the-badge" alt="Build: WIP">
  <img src="https://img.shields.io/badge/RELEASE-V0.1.0--ALPHA-blue?style=for-the-badge" alt="Release: v0.1.0-alpha">
  <img src="https://img.shields.io/badge/LICENSE-MIT-green?style=for-the-badge" alt="License: MIT">
  <img src="https://img.shields.io/badge/JDK-25%2B-orange?style=for-the-badge" alt="JDK: 25+">
</p>

---

**Abundent's Java-based Automation Platform**

A lightweight, pure Java implementation combining the best of [OpenClaw](https://github.com/openclaw/openclaw) and [JavaClaw](https://github.com/jobrunr/javaclaw), built on a customized Play Framework 1.x.

> 🎯 **Mission**: Powerful automation without the bloat. A lean Java-native alternative for workflow orchestration, job scheduling, and task automation.

---

## Overview

JClaw is Abundent's take on an AI-powered automation platform — fully implemented in **pure Java** to eliminate runtime dependencies and reduce complexity. Built on a customized [Play Framework 1.x](https://github.com/tsukhani/play1) foundation, it brings together:

- **OpenClaw's** agent orchestration, memory system, and conversational AI patterns
- **JavaClaw's** job scheduling, background task processing, and distributed execution

The result: A leaner, faster, more maintainable platform for building AI agents and automation workflows.

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
- Play Framework 1.x (`play` command in PATH)
- Node.js 20+ (for frontend)
- pnpm (for frontend package management)

### Clone & Setup

```bash
# Clone the repository
git clone https://bitbucket.abundent.com/scm/jclaw/jclaw.git
cd jclaw

# Install frontend dependencies
cd frontend && pnpm install && cd ..
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

### Production Build

```bash
# Build Nuxt frontend
cd frontend && pnpm build

# Deploy Play application
play dist
# Uploads to Bitbucket or run standalone
```

---

## Architecture

### Backend (Play 1.x + Java)

- **Models**: JPA entities with Play's model pattern
- **Controllers**: RESTful API endpoints
- **Services**: Business logic with dependency injection
- **Agents**: Conversational AI with memory/context persistence
- **Jobs**: Background processing powered by Quartz/JobRunr

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

- [Play Framework 1.x Docs](https://www.playframework.com/documentation/1.11.5/home)
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
