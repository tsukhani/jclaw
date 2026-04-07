## MODIFIED Requirements

### Requirement: Request body size must be bounded
All API endpoints that read JSON request bodies SHALL enforce a maximum content length to prevent memory exhaustion from oversized payloads.

#### Scenario: Normal request body
- **WHEN** a JSON request body is under 10MB
- **THEN** the request SHALL be processed normally

#### Scenario: Oversized request body
- **WHEN** a JSON request body exceeds the configured maximum (10MB default)
- **THEN** the system SHALL reject the request with a 413 status code

### Requirement: FileSystemTools must enforce file size limits
The `readFile` tool SHALL check file size before reading to prevent loading arbitrarily large files into memory.

#### Scenario: Read a small workspace file
- **WHEN** an agent reads a file under 1MB from its workspace
- **THEN** the file content SHALL be returned normally

#### Scenario: Read a large file
- **WHEN** an agent attempts to read a file exceeding 1MB
- **THEN** the tool SHALL return an error message indicating the file exceeds the read limit

### Requirement: pgvector queries must use parameterized SQL
Vector literal values in `JpaMemoryStore` SHALL be passed as bound parameters (not string-interpolated) to enable prepared statement caching and prevent query plan pollution.

#### Scenario: Hybrid search with embedding
- **WHEN** a hybrid search is executed with a vector embedding
- **THEN** the embedding SHALL be passed as a parameterized value (cast via `?::text::vector`), not interpolated into the SQL string

### Requirement: ConfigService must cache negative hits
`ConfigService.get()` SHALL cache null results for keys that do not exist in the database, with the same TTL as positive results.

#### Scenario: Lookup a non-existent key twice
- **WHEN** `ConfigService.get("nonexistent.key")` is called twice within the TTL window
- **THEN** only the first call SHALL hit the database; the second SHALL return the cached null

### Requirement: ProviderRegistry refresh must be atomic and stampede-free
`ProviderRegistry.refresh()` SHALL use an atomic reference swap and double-checked locking to prevent cache stampedes and stale reads.

#### Scenario: Concurrent refresh requests
- **WHEN** multiple threads trigger `refreshIfNeeded()` simultaneously after the TTL expires
- **THEN** only one thread SHALL execute `refresh()` and the others SHALL use the freshly cached result

#### Scenario: Concurrent read during refresh
- **WHEN** a thread calls `get(name)` while another thread is refreshing
- **THEN** the reading thread SHALL see either the old complete cache or the new complete cache, never an empty or partial cache

### Requirement: ToolRegistry must be thread-safe
The `ToolRegistry` static tool map SHALL be safe for concurrent access from multiple virtual threads.

#### Scenario: Concurrent tool execution and registration
- **WHEN** a tool is being executed on one virtual thread while the registry is being refreshed on another
- **THEN** the execution SHALL see a consistent snapshot of the registry (not a partially populated map)

### Requirement: CronParser must terminate efficiently
`CronParser.nextExecution()` SHALL skip non-matching months and days at coarse granularity before falling back to minute-by-minute iteration.

#### Scenario: Monthly cron on the 1st
- **WHEN** calculating the next execution for `0 0 1 * *` (first of every month)
- **THEN** the parser SHALL skip directly to the 1st of the next month, not iterate minute-by-minute through 30 days

### Requirement: Retry-After header must handle all formats
The `Retry-After` header parser SHALL handle both delta-seconds (integer) and HTTP-date formats without throwing exceptions.

#### Scenario: Retry-After as integer
- **WHEN** the provider returns `Retry-After: 30`
- **THEN** the system SHALL wait 30 seconds before retrying

#### Scenario: Retry-After as HTTP-date
- **WHEN** the provider returns `Retry-After: Wed, 21 Oct 2025 07:28:00 GMT`
- **THEN** the system SHALL fall back to the default exponential backoff delay (not throw NumberFormatException)

### Requirement: Shared HttpClient instances
The system SHALL consolidate HTTP client instances to minimize connection pool overhead.

#### Scenario: LLM and channel HTTP calls
- **WHEN** the LLM client and channel integrations make HTTP requests
- **THEN** they SHALL share at most 2 HttpClient instances (one for LLM with 60s timeout, one for general use with 15s timeout)

### Requirement: Skill and workspace file reads must be cached
`SkillLoader` and `AgentService.readWorkspaceFile` SHALL cache file contents with a TTL to avoid disk I/O on every LLM call.

#### Scenario: Two LLM calls within 30 seconds
- **WHEN** two chat messages arrive for the same agent within 30 seconds
- **THEN** the second call SHALL read AGENT.md, IDENTITY.md, USER.md, and skill files from cache (not disk)

#### Scenario: Workspace file is modified
- **WHEN** a workspace file is updated via the API
- **THEN** the cache for that file SHALL be invalidated immediately
