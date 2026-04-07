## CHANGED Requirements

### Requirement: ArrayDeque mutations in interrupt mode must be synchronized
`ConversationQueue` SHALL hold the `QueueState` monitor whenever it mutates `state.pending`, including the `interrupt` mode clear path.

#### Scenario: Interrupt mode clears pending queue
- **WHEN** a new message arrives in `interrupt` mode while the agent is busy
- **THEN** `state.pending.clear()` SHALL execute inside a `synchronized(state)` block
- **AND** a concurrent `drain()` call holding the same monitor SHALL not race with the clear

#### Scenario: Concurrent enqueue and clear do not corrupt ArrayDeque
- **WHEN** one thread calls `tryAcquire()` in interrupt mode (clear) while another thread is inside `drain()` (also synchronized on state)
- **THEN** only one thread SHALL hold the monitor at a time, and `ArrayDeque` internal state SHALL remain consistent

### Requirement: getQueueSize must be synchronized
`ConversationQueue.getQueueSize()` SHALL synchronize on the `QueueState` instance before reading `state.pending.size()`.

#### Scenario: Queue size read during concurrent enqueue
- **WHEN** `getQueueSize()` is called concurrently with a `tryAcquire()` that is adding to `state.pending`
- **THEN** the returned size SHALL reflect either the state before or after the enqueue, never a partially-written intermediate state

### Requirement: Queue mode must be resolved once per acquisition and must not be written to shared state from tryAcquire
`tryAcquire()` SHALL read `ConfigService.get()` into a local variable and SHALL pass the resolved mode to downstream logic rather than writing it to `state.mode`.

#### Scenario: Two concurrent tryAcquire calls for the same conversation
- **WHEN** two threads call `tryAcquire()` simultaneously for the same `conversationId`
- **THEN** each thread SHALL use its own locally resolved mode value
- **AND** one thread's mode SHALL not overwrite the other's in `state.mode` between the read at line 63 and the read inside `drain()`

#### Scenario: Mode used in drain matches mode snapshot taken at acquire time
- **WHEN** the mode is resolved in `tryAcquire()` and written to `state.mode` under `synchronized(state)`
- **THEN** `drain()` SHALL read `state.mode` inside its own `synchronized(state)` block, ensuring it sees the mode set by the most recent `tryAcquire()` that completed under the monitor
- **AND** no signature change to `drain()` SHALL be required — mode is communicated via the guarded `state.mode` field

### Requirement: finishProcessing must be called inside the synchronized drain block
`drain()` SHALL call `state.finishProcessing()` inside the `synchronized(state)` block, before inspecting `state.pending`.

#### Scenario: New message arrives between finishProcessing and synchronized block
- **WHEN** `finishProcessing()` is called outside `synchronized` and a new `tryAcquire()` succeeds before the lock is re-acquired
- **THEN** the newly acquired message could be drained immediately and then removed — this window SHALL be closed by moving `finishProcessing()` inside the monitor

#### Scenario: Normal drain with pending messages
- **WHEN** `drain()` is called after processing completes and `state.pending` is not empty
- **THEN** `finishProcessing()` SHALL be called atomically with the pending check under `synchronized(state)`
- **AND** the next message (or batch in collect mode) SHALL be returned correctly

### Requirement: Dead lock field must be removed from QueueState
The unused `ReentrantLock lock` field in `QueueState` SHALL be removed.

#### Scenario: QueueState created
- **WHEN** a new `QueueState` is instantiated for a conversation
- **THEN** no `ReentrantLock` SHALL be allocated
- **AND** all synchronization SHALL use `synchronized(state)` on the `QueueState` instance itself
