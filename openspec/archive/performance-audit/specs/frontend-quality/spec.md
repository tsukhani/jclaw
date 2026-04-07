## MODIFIED Requirements

### Requirement: Chat markdown rendering must be sanitized
All LLM-generated markdown rendered via `v-html` SHALL be sanitized with DOMPurify to prevent XSS.

#### Scenario: Normal markdown rendering
- **WHEN** an assistant message contains standard markdown (headers, lists, code blocks)
- **THEN** the markdown SHALL be rendered correctly after sanitization

#### Scenario: Malicious HTML in assistant message
- **WHEN** an assistant message contains `<script>alert('xss')</script>` or `<img onerror=...>`
- **THEN** the script tags and event handlers SHALL be stripped by DOMPurify before rendering

### Requirement: SSE streams must be aborted on component unmount
The chat page SHALL use an `AbortController` to cancel in-flight SSE streams when the user navigates away.

#### Scenario: User navigates away during streaming
- **WHEN** the user navigates to another page while a streaming response is in progress
- **THEN** the fetch request SHALL be aborted via `AbortController.abort()` in the `onUnmounted` hook

### Requirement: useFetch must only be called at setup top-level
All imperative data fetching inside event handlers and watchers SHALL use `$fetch` instead of `useFetch` to prevent watcher leaks.

#### Scenario: Loading conversation messages
- **WHEN** the user clicks a conversation in the sidebar
- **THEN** the messages SHALL be loaded via `$fetch` (not `useFetch`)

#### Scenario: Loading skills after agent selection
- **WHEN** the user selects a different agent in the skills page
- **THEN** the skills SHALL be loaded via `$fetch` inside the watcher (not `useFetch`)

### Requirement: Dashboard fetches must be parallel
The dashboard page SHALL fetch all data sources concurrently, not sequentially.

#### Scenario: Dashboard page load
- **WHEN** the dashboard page loads
- **THEN** the agents, channels, tasks, and logs fetches SHALL execute in parallel via `Promise.all`

### Requirement: Mutation calls must handle errors
All `$fetch` mutation calls (POST, PUT, DELETE) SHALL be wrapped in try/catch with `finally` blocks that reset loading state.

#### Scenario: Save agent fails with 409
- **WHEN** an agent save request returns a 409 error
- **THEN** the error SHALL be caught and `saving.value` SHALL be reset to `false`

### Requirement: Message list must use stable keys
The chat message list SHALL use stable unique identifiers for `:key` instead of array indices.

#### Scenario: Streaming token arrives
- **WHEN** a new token arrives during SSE streaming
- **THEN** Vue SHALL patch only the last message element (not re-render the entire list) because each message has a stable key

### Requirement: scrollToBottom must be throttled
The `scrollToBottom` function SHALL be throttled to at most one call per animation frame during streaming.

#### Scenario: 50 tokens arrive in rapid succession
- **WHEN** 50 SSE token events arrive within 100ms
- **THEN** `scrollToBottom` SHALL execute at most 1-2 times (via `requestAnimationFrame`), not 50 times

### Requirement: Settings page must not render entries twice
Provider config entries SHALL appear only in the provider-grouped section, not also in the raw config table.

#### Scenario: View settings with 2 providers
- **WHEN** the settings page renders with ollama-cloud and openrouter configured
- **THEN** each provider entry SHALL appear once (in its provider section), and the raw config table SHALL only show non-provider entries

### Requirement: Log polling must pause when tab is hidden
The logs page auto-refresh interval SHALL skip polling when `document.hidden` is true.

#### Scenario: User switches to another browser tab
- **WHEN** the user switches to another tab while the logs page is open with auto-refresh enabled
- **THEN** no API requests SHALL be made until the user returns to the tab
