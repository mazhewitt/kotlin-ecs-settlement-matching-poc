# From Game Loops to Ledgers: Applying ECS to Settlement Matching

## TL;DR
- Entity–Component–System (ECS) is a data‑oriented pattern popular in gaming for composing behavior from small, cache‑friendly components processed by systems.
- We show how ECS maps neatly onto securities settlement matching: obligations and status events are entities; state, quantities, and matching keys are components; matching, idempotency, and lifecycle are systems.
- Benchmarks on this PoC indicate deterministic, single‑threaded processing with predictable throughput; Python harness included to scale test concurrency and out‑of‑order scenarios.
- ECS vs Event Sourcing: ECS excels at hot‑path, in‑memory, deterministic processing with clean separation of reads/writes; ES excels at auditability, replay, and historical queries. Hybrid is often best.

## What is ECS (Entity–Component–System)?
ECS is a data‑oriented architecture used extensively in games to manage thousands to millions of objects in real time. Its core ideas:

- Entities: Opaque identifiers (often ints). They represent “things” but carry no behavior.
- Components: Flat, data‑only structs attached to entities (position, velocity, health…). No logic inside.
- Systems: Pure-ish functions that iterate families of entities having specific component sets and transform that data each frame (aka a world “tick”).

Why games love ECS:
- Composition over inheritance: mix components to model behavior without deep class trees.
- Cache friendliness: contiguous arrays of component data → fewer cache misses, higher throughput.
- Determinism: one ordered pipeline per frame, same inputs → same outputs.

## Mapping ECS to Settlement Matching
Although from gaming, ECS is a great fit for workflows that are:
- stateful, incremental
- single‑threaded for determinism
- need explicit idempotency and ordering guards

In securities settlement matching, a custodian or broker needs to reconcile internal obligations against external venue (CSD) status messages. Our PoC adapts ECS like so:

- Entities
  - Long‑lived: SettlementObligation (one per trade obligation)
  - Ephemeral: StatusEvent (incoming CSD message per arrival)

- Components (data‑only)
  - IdentityC, MatchingKeyC, LifecycleC, QuantitiesC
  - CsdStatusC, IdempotencyC, CorrelationC
  - ParsedStatusC (for StatusEvent), CandidateKeyC

- Systems (ordered once per processStatusUpdates)
  1) DedupSystem: correlate by key; emit NoMatch | DuplicateIgnored | OutOfOrderIgnored; mark seen (msgId, seq).
  2) CorrelateSystem: copy message metadata onto the obligation (CsdStatusC), set last correlation.
  3) LifecycleSystem: New → Matched → PartiallySettled → Settled; update quantities; emit StateChanged.
  4) OutboxSystem: collect DomainEvents and clean up ephemeral StatusEvent entities.

ECS note: processStatusUpdates is the domain‑aligned name for a single world “tick”.

## Architecture Overview (Applied to Matching)
- World wiring: components registered; families (queries) for obligations and status events.
- Orchestrator: a thin MatchingEngine that ingests bank obligations, ingests market statuses, runs one ECS cycle, and publishes DomainEvents to a status outbox (exposed as a queue).
- Outbox: in‑memory list per cycle; systems append DomainEvents; exported as status indications.
- Interop: file‑backed queues (CSV lines) for bank/market inputs and status outputs; Python CLIs to feed and tail; a generator ensures testable counts of matches, unmatches, and duplicates.
- Tests: Kotest suite for unit behavior and determinism; Python harness to validate end‑to‑end counts under randomized ordering and duplicates.

Key properties
- Deterministic: ordered systems + idempotency set → repeatable results.
- Composable: adding a new lifecycle or rule is a new system or a small extension, without touching persistence schemas.
- Observable: DomainEvents are the single source of truth for external effects.

## ECS vs Event Sourcing (ES): Trade‑offs
Both can coexist; here’s when each shines.

ECS strengths
- Hot path performance: process N events over M entities with tight, cache‑friendly loops.
- Determinism by construction: fixed system order per cycle.
- Isolation of side effects: systems emit DomainEvents; orchestrator handles IO.
- Simplicity for in‑memory PoCs and simulations.

ECS limitations
- Historical audit: ECS itself doesn’t persist every change; you need to persist DomainEvents deliberately.
- Querying history: without ES, historical reconstruction needs extra storage and code.
- Scaling writes: multi‑threading requires careful partitioning to keep determinism.

Event Sourcing strengths
- Full audit trail: append‑only event log, perfect for compliance and replay.
- Time travel and projections: rebuild state at any point; multiple read models.
- Natural integration with streaming platforms.

Event Sourcing limitations
- Write amplification and complexity: many small events, schema/versioning concerns.
- Operational overhead: projection lag, backfills, compaction.
- Performance on hot path: projections and IO can be the bottleneck if not carefully tuned.

Hybrid approach
- Use ECS for the hot, deterministic core (matching and lifecycle transitions).
- Persist the DomainEvents to an event log for audit and replay.
- Build projections for external consumers without complicating the core loop.

## Benchmarks (PoC)
Setup
- Kotlin/JVM (Java 21), Fleks 2.12, single‑threaded.
- Deterministic datasets via Python harness.
- In‑memory world; file‑backed queues for interop.

Findings (indicative, not exhaustive)
- Unit suite: all green; deterministic replay verified.
- End‑to‑end counts: generator produced 25 matches, 7 unmatches, 5 duplicates; system emitted exactly those counts.
- Latency: dominated by file polling in the simple runner; with in‑memory queues, per‑cycle time is tiny (sub‑millisecond scale on dev laptop for small N).

Next for rigorous benchmarking
- Switch to NDJSON over pipes or sockets; timestamp each stage; measure p50/p95.
- Introduce parallel partitions (by isin or account) while preserving determinism within a partition.
- Add a metrics system to increment counters per transition and per ignore path.

## When To Use ECS Outside Gaming
Recommended when your domain has:
- High‑volume, stateful entities processed repeatedly with clear component sets.
- Strong determinism requirements (compliance, reconciliation, simulation).
- A small, well‑defined set of transitions that evolve over time.

Not a fit when:
- You primarily need historical/audit queries and replay with minimal in‑memory logic → lean towards Event Sourcing.
- Your write path is IO‑heavy and dominated by external systems rather than in‑memory computation.

Pragmatic guidance
- Start with ECS for the core computation and determinism.
- Emit and persist DomainEvents to get ES‑like audit benefits.
- Keep IO and storage at the edges (orchestrators, adapters) so the core remains small, testable, and fast.
