## ADDED Requirements

### Requirement: Headless browser automation tool
The system SHALL provide a `PlaywrightBrowserTool` that gives agents access to a headless Chromium browser for interacting with JavaScript-heavy web pages.

#### Scenario: Navigate to a page and extract text
- **WHEN** an agent calls browser(action: "navigate", url: "https://app.example.com")
- **THEN** the system SHALL launch a headless Chromium browser (if not already running), navigate to the URL, wait for the page to load, and return the page title and extracted text content (truncated to 50,000 characters)

#### Scenario: Fill a form and submit
- **WHEN** an agent calls browser(action: "fill", selector: "#email", value: "user@example.com") followed by browser(action: "click", selector: "button[type=submit]")
- **THEN** the system SHALL fill the input field and click the submit button, waiting for navigation to complete after each action

#### Scenario: Extract text from a specific element
- **WHEN** an agent calls browser(action: "getText", selector: ".main-content")
- **THEN** the system SHALL return the text content of the matching element

#### Scenario: Take a screenshot
- **WHEN** an agent calls browser(action: "screenshot")
- **THEN** the system SHALL capture a full-page PNG screenshot and save it to the agent's workspace as screenshot.png

#### Scenario: Execute JavaScript
- **WHEN** an agent calls browser(action: "evaluate", expression: "document.querySelectorAll('.item').length")
- **THEN** the system SHALL execute the expression in the page context and return the result as a string

### Requirement: Browser lifecycle management
The system SHALL manage browser instances with lazy initialization and idle cleanup.

#### Scenario: First browser access
- **WHEN** an agent uses the browser tool for the first time
- **THEN** the system SHALL create a new Playwright instance, launch headless Chromium, and create a page
- **AND** if Chromium is not installed, the system SHALL attempt to install it automatically

#### Scenario: Subsequent browser access
- **WHEN** an agent uses the browser tool while a session already exists
- **THEN** the system SHALL reuse the existing browser session

#### Scenario: Idle session cleanup
- **WHEN** a browser session has been idle for 5 minutes
- **THEN** the system SHALL close the browser, Playwright instance, and release resources

### Requirement: Browser tool security
The browser tool SHALL be disabled by default for non-main agents.

#### Scenario: New agent created
- **WHEN** a new agent is created (not the built-in main agent)
- **THEN** the browser tool SHALL be disabled via AgentToolConfig

#### Scenario: Agent isolation
- **WHEN** two agents each have browser sessions active
- **THEN** each agent SHALL have its own isolated browser context with no shared cookies, storage, or sessions
