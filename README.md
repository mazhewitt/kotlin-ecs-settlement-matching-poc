# ECS Settlement Matching Engine

A high-performance, **Entity Component System (ECS)** based settlement matching engine built with Kotlin and the Fleks ECS framework. This system processes incoming settlement status messages and matches them against internal settlement obligations using strict **Test-Driven Development (TDD)** principles.

## 🚀 Quick Start

```bash
# Run all tests
./gradlew test

# Start the matching engine
./gradlew run

# Run existing test harness
python py/generate_and_assert.py

# Run performance benchmarks
python py/benchmark.py --scenario all
```

## 📊 Performance Highlights

- **10,290+ events/sec** peak throughput
- **1.9 KB per obligation** memory efficiency  
- **<1% GC overhead** even at 25,000 events
- **259x throughput scaling** with super-linear performance gains

See [Benchmark.md](Benchmark.md) for complete performance analysis.

---

## 🏗️ Architecture Overview

### Entity Component System (ECS) Design

The system uses the **Fleks ECS framework** to achieve high performance through data-oriented design:

```
┌─────────────────┐    ┌─────────────────┐    ┌─────────────────┐
│  StatusEvents   │    │ SettlementOblig │    │  DomainEvents   │
│   (ephemeral)   │───▶│  (long-lived)   │───▶│   (outbox)      │
└─────────────────┘    └─────────────────┘    └─────────────────┘
        │                       │                       │
        ▼                       ▼                       ▼
┌─────────────────┐    ┌─────────────────┐    ┌─────────────────┐
│  ECS Components │    │   ECS Systems   │    │ Event Handlers  │
│  (data-only)    │    │  (processing)   │    │  (side effects) │
└─────────────────┘    └─────────────────┘    └─────────────────┘
```

### Core Processing Pipeline

The engine processes status updates through **four ordered ECS systems**:

1. **DedupSystem**: Deduplication and correlation matching
2. **CorrelateSystem**: Entity relationship management  
3. **LifecycleSystem**: State machine transitions
4. **OutboxSystem**: Event emission and cleanup

### Domain Model

**Settlement Obligations** (long-lived entities):
- Represent trades awaiting settlement status updates
- Maintain state through the settlement lifecycle
- Track quantities, matching keys, and idempotency

**Status Events** (ephemeral entities):
- Incoming CSD (Central Securities Depository) status messages
- Processed in batches for optimal performance
- Removed after successful correlation and processing

---

## 📁 Code Layout

```
├── app/src/main/kotlin/org/example/
│   ├── App.kt                           # Main application entry point
│   └── settlement/
│       ├── Engine.kt                    # Core engine interface
│       ├── SettlementEngine.kt          # Main ECS implementation
│       ├── BenchmarkProfiler.kt         # JVM performance profiling
│       ├── components/
│       │   └── Components.kt            # ECS components (data structures)
│       ├── domain/
│       │   ├── DomainEvents.kt          # Event hierarchy
│       │   └── Enums.kt                 # Domain enumerations
│       └── matching/
│           ├── FileLineQueues.kt        # File-based I/O adapters
│           ├── MatchingEngine.kt        # Orchestration layer
│           └── Queue.kt                 # Queue abstractions
│
├── app/src/test/kotlin/org/example/
│   ├── AppTest.kt                       # Application smoke tests
│   └── settlement/
│       ├── SettlementEngineTest.kt      # Core engine tests
│       └── matching/
│           └── MatchingEngineTest.kt    # Integration tests
│
├── py/                                  # Python tooling and benchmarks
│   ├── benchmark.py                     # Comprehensive benchmark suite
│   ├── analyze_benchmarks.py           # Performance analysis tools
│   ├── generate_and_assert.py          # Test harness
│   ├── feeder_bank.py                  # Bank data feeder
│   ├── feeder_market.py                # Market data feeder
│   ├── status_tail.py                  # Status monitoring
│   └── README_BENCHMARKS.md            # Benchmark documentation
│
├── runtime/                             # File-based queue storage
│   ├── bank.txt                        # Bank obligations input
│   ├── market.txt                      # Market status events input
│   └── status.txt                      # Status indications output
│
├── Benchmark.md                        # Performance analysis results
├── CLAUDE.md                           # Implementation progress log
└── README.md                           # This file
```

---

## 🧩 ECS Components Architecture

### Data Components (Pure Data Structures)

**Settlement Obligation Components:**
```kotlin
IdentityC(obligationId: String, venue: String)
MatchingKeyC(isin: String, account: String, settleDate: LocalDate, qty: Long)
LifecycleC(state: LifecycleState)
QuantitiesC(intendedQty: Long, settledQty: Long, remainingQty: Long)
CsdStatusC(lastCode: CanonCode?, lastMsgId: String?, lastSeq: Long?, lastAt: Instant?)
IdempotencyC(seen: MutableSet<Pair<String,Long>>)
CorrelationC(lastStatusEventId: String?)
```

**Status Event Components:**
```kotlin
ParsedStatusC(msgId: String, seq: Long, code: CanonCode, isin: String, ...)
CandidateKeyC(isin: String, account: String, settleDate: LocalDate, qty: Long)
ProcessedStatusC(obligationEntityId: Int)
CorrelatedStatusC(obligationEntityId: Int)
```

### Processing Systems (Behavior)

**System Execution Order:**
1. `DedupSystem` - Deduplication and matching
2. `CorrelateSystem` - Entity correlation
3. `LifecycleSystem` - State transitions
4. `OutboxSystem` - Event emission

Each system operates on specific component families for optimal performance.

---

## ⚡ Domain Logic

### Settlement Lifecycle States

```
New → Matched → PartiallySettled → Settled
 ↑      ↓           ↓               ↓
ACK   MATCHED   PARTIAL_SETTLED   SETTLED
```

### Event Processing Rules

**Idempotency**: Duplicate `(msgId, seq)` pairs are ignored  
**Ordering**: Out-of-order messages (`seq <= lastSeq`) are rejected  
**Matching**: Exact key matching on `(isin, account, settleDate)`  
**Quantities**: Partial settlements accumulate until fully settled

### Domain Events

```kotlin
sealed interface DomainEvent {
    data class NoMatch(msgId: String, seq: Long, key: String)
    data class DuplicateIgnored(obligationId: String, msgId: String, seq: Long)  
    data class OutOfOrderIgnored(obligationId: String, lastSeq: Long, msgId: String, seq: Long)
    data class StateChanged(obligationId: String, from: LifecycleState, to: LifecycleState, ...)
}
```

---

## 🧪 Testing Strategy

### Test-Driven Development (TDD)

The entire system was built using **strict TDD** with each feature following:
1. **Write failing test** → 2. **Implement minimum code** → 3. **Refactor** → **Repeat**

### Test Coverage

**Unit Tests** (`SettlementEngineTest.kt`):
- ✅ `createsObligationWithInitialComponents()`
- ✅ `matchedEventAdvancesStateToMatchedAndEmitsEvent()`
- ✅ `partialSettlesAccumulateQuantitiesAndRemainPartiallySettled()`
- ✅ `settledEventCompletesObligationAndEmitsEvent()`
- ✅ `duplicateEventIsIgnoredAndEmitsDuplicateIgnored()`
- ✅ `outOfOrderEventIsIgnoredAndEmitsOutOfOrderIgnored()`
- ✅ `noMatchingObligationEmitsNoMatch()`
- ✅ `replayIsDeterministicForSameInputSequence()`

**Integration Tests** (`MatchingEngineTest.kt`):
- End-to-end orchestration testing
- File-based queue integration
- Multi-system coordination

**End-to-End Tests** (`generate_and_assert.py`):
- Python harness generates synthetic data
- Kotlin runner processes through file queues
- Assertions verify expected outcomes

### Test Execution

```bash
# Run Kotlin unit tests
./gradlew test

# Run integration test harness  
python py/generate_and_assert.py

# Run specific test scenarios
python py/benchmark.py --scenario small
```

---

## 📈 Benchmark Suite

### Comprehensive Performance Testing

The benchmark suite provides **7 different scenarios** testing various aspects:

| Scenario | Purpose | Scale | Key Metric |
|----------|---------|-------|------------|
| `micro` | Baseline measurement | 10/20 | Startup overhead analysis |
| `small` | Typical batch processing | 100/250 | Production batch simulation |
| `medium` | Real-world moderate load | 1K/2.5K | Memory efficiency sweet spot |
| `throughput` | Event-heavy processing | 500/5K | Peak throughput testing |
| `memory` | Obligation-heavy load | 5K/5.5K | Memory scaling validation |
| `large` | High-volume production | 5K/12.5K | Production capacity testing |
| `xl` | Stress testing limits | 10K/25K | Scalability boundary testing |

### Performance Metrics Collected

**Throughput Metrics:**
- Events processed per second
- Processing duration and latency
- Per-event processing time

**Resource Metrics:**
- JVM heap memory consumption  
- Garbage collection time and overhead
- Peak ECS entity counts

**System Behavior:**
- Idempotency handling performance
- State transition efficiency  
- Event correlation speed

### Benchmark Usage

```bash
# Run all benchmark scenarios
python py/benchmark.py --scenario all

# Run specific scenario with custom parameters
python py/benchmark.py --scenario throughput --size 2000 --iterations 10

# Generate analysis reports
python py/analyze_benchmarks.py

# Compare benchmark runs
python py/analyze_benchmarks.py --compare baseline.json optimized.json
```

### Key Performance Results

**Exceptional Scalability:**
- **Super-linear throughput scaling**: 259x performance gain for 1000x data increase
- **Sub-linear memory scaling**: Memory per obligation decreases with scale
- **Minimal GC impact**: <1% overhead even at 25,000 events

**Production Readiness:**
- Peak throughput: 10,290 events/sec
- Memory efficiency: 1.9 KB per obligation (optimal)
- Consistent low-variance performance across runs

See [Benchmark.md](Benchmark.md) for detailed performance analysis.

---

## 🔧 Configuration & Deployment

### JVM Configuration

**Recommended Production Settings:**
```bash
-Xmx2G                    # 2GB heap for large datasets
-XX:+UseG1GC             # Low-latency garbage collector
-XX:MaxGCPauseMillis=50  # Target 50ms GC pauses  
```

### Environment Variables

```bash
BENCHMARK_MODE=true      # Enable benchmark profiling mode
BENCHMARK_DEBUG=true     # Verbose benchmark output
```

### File-Based Integration

**Input Files:**
- `runtime/bank.txt` - CSV format bank obligations
- `runtime/market.txt` - CSV format market status events

**Output Files:**
- `runtime/status.txt` - Settlement status indications

**CSV Formats:**
```
# Bank obligations (bank.txt)
obligationId,venue,isin,account,settleDate,intendedQty

# Market status events (market.txt)  
msgId,seq,code,isin,account,settleDate,qty,at

# Status indications (status.txt)
DomainEvent.toString() format
```

---

## 🛠️ Development Workflow

### Prerequisites

- **Kotlin/JVM**: Java 21 toolchain
- **Gradle**: Build automation  
- **Python 3.7+**: For tooling and benchmarks
- **Optional**: matplotlib/seaborn for benchmark charts

### Building

```bash
# Build project
./gradlew build

# Compile only
./gradlew compileKotlin

# Run application
./gradlew run
```

### Testing

```bash
# Run all Kotlin tests
./gradlew test

# Run test harness
python py/generate_and_assert.py

# Run benchmarks
python py/benchmark.py --scenario medium
```

### Adding New Features

1. **Write failing test** in appropriate test file
2. **Run test** to confirm failure: `./gradlew test`
3. **Implement minimum code** to make test pass
4. **Run test** to confirm success: `./gradlew test`
5. **Refactor** and repeat

### Performance Optimization

1. **Baseline measurement**: Run relevant benchmark scenario
2. **Implement optimization**: Modify code with performance focus
3. **Measure impact**: Re-run benchmark and compare results
4. **Document findings**: Update benchmark documentation

---

## 🎯 Design Decisions & Trade-offs

### ECS Architecture Benefits

**Performance Advantages:**
- **Data-oriented design**: Components stored contiguously for cache efficiency
- **System batching**: Process multiple entities efficiently  
- **Minimal allocations**: Component reuse reduces GC pressure

**Scalability Benefits:**
- **Linear complexity**: Most operations are O(n) or better
- **Batch processing**: Fixed costs amortized across large datasets
- **Predictable performance**: Consistent behavior across scales

### Memory Management

**Entity Lifecycle:**
- **Long-lived obligations**: Persist throughout processing
- **Ephemeral events**: Created and destroyed within single processing cycle
- **Component pooling**: Fleks framework handles efficient memory reuse

### Error Handling Strategy

**Fail-Fast Approach:**
- **Validation at ingestion**: Malformed messages rejected early
- **Deterministic behavior**: Same inputs always produce same outputs  
- **Comprehensive logging**: All edge cases emit appropriate domain events

---

## 🚀 Production Considerations

### Scalability Guidelines

**Optimal Batch Sizes:**
- **Small workloads** (<100 obligations): Accept higher overhead
- **Medium workloads** (1K-5K obligations): Optimal efficiency zone
- **Large workloads** (10K+ obligations): Maximum throughput

### Monitoring Recommendations

**Key Performance Indicators:**
- **Throughput**: Target >5,000 events/sec for production loads
- **Memory efficiency**: Maintain <10KB per obligation  
- **GC overhead**: Keep <2% of total processing time
- **Error rates**: Monitor NoMatch/Duplicate/OutOfOrder events

### Operational Integration

**CI/CD Integration:**
```bash
# Performance regression detection
python py/benchmark.py --scenario medium
# Compare with baseline and fail build if degraded >10%
```

**Health Checks:**
- Monitor file queue depths
- Track processing latency
- Alert on error rate spikes

---

## 📚 References & Documentation

- **[Benchmark.md](Benchmark.md)** - Complete performance analysis
- **[py/README_BENCHMARKS.md](py/README_BENCHMARKS.md)** - Benchmark suite documentation  
- **[CLAUDE.md](CLAUDE.md)** - Implementation progress and technical notes
- **[Fleks ECS Documentation](https://github.com/Quillraven/Fleks)** - ECS framework reference

---

## 🤝 Contributing

### Code Style
- **Kotlin conventions**: Follow official Kotlin style guide
- **No comments**: Code should be self-documenting (per project requirements)
- **TDD approach**: Always write tests first

### Adding Benchmarks
1. Add scenario to `create_benchmark_scenarios()` in `py/benchmark.py`
2. Update analysis logic in `py/analyze_benchmarks.py`  
3. Document expected behavior and performance characteristics
4. Test across multiple dataset sizes

### Performance Improvements
- Run baseline benchmarks before changes
- Validate improvements with statistical significance
- Document performance impact in commit messages
- Update benchmark documentation as needed

---

**Built with ❤️ using Entity Component Systems for high-performance settlement processing**