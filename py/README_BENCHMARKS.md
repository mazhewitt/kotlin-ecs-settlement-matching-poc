# ECS Settlement Matching Benchmark Suite

A comprehensive benchmark framework for measuring performance characteristics of the ECS-based settlement matching engine.

## Overview

The benchmark suite provides:
- **Performance measurement** across different dataset sizes
- **Memory usage analysis** and GC behavior tracking  
- **Scalability testing** from micro to extra-large datasets
- **Automated reporting** with detailed analysis
- **Comparative analysis** between benchmark runs
- **JVM profiling integration** for detailed metrics

## Quick Start

### Run Individual Benchmarks

```bash
# Run a single scenario
python py/benchmark.py --scenario micro
python py/benchmark.py --scenario small  
python py/benchmark.py --scenario medium
python py/benchmark.py --scenario large

# Run all scenarios
python py/benchmark.py --scenario all

# Custom throughput test
python py/benchmark.py --scenario throughput --size 2000 --iterations 3
```

### Generate Analysis Reports

```bash
# Analyze all results in benchmark_results/
python py/analyze_benchmarks.py

# Compare two specific benchmark runs
python py/analyze_benchmarks.py --compare results1.json results2.json

# Generate charts (requires matplotlib)
python py/analyze_benchmarks.py --charts
```

## Benchmark Scenarios

| Scenario | Obligations | Status Events | Description |
|----------|-------------|---------------|-------------|
| `micro` | 10 | 20 | Minimal baseline for quick testing |
| `small` | 100 | 250 | Typical single-batch processing |
| `medium` | 1,000 | 2,500 | Moderate real-world load |
| `large` | 5,000 | 12,500 | High-volume processing test |
| `xl` | 10,000 | 25,000 | Stress test for scalability limits |
| `throughput` | 500 | 5,000 | Many events per obligation |
| `memory` | 5,000 | 5,500 | Many obligations, few events each |

## Metrics Collected

### Performance Metrics
- **Throughput**: Operations per second (events processed/duration)
- **Latency**: Total processing time in milliseconds
- **Scalability**: Performance across different dataset sizes

### Resource Metrics  
- **Memory Usage**: JVM heap consumption in MB
- **GC Impact**: Garbage collection time and overhead
- **Peak Entities**: Maximum number of ECS entities at any time

### System Behavior
- **Idempotency**: Duplicate message handling performance
- **State Transitions**: Lifecycle processing efficiency
- **Event Processing**: Outbox and correlation performance

## Benchmark Architecture

### Components

1. **Python Benchmark Runner** (`benchmark.py`)
   - Orchestrates test execution
   - Generates synthetic datasets
   - Measures end-to-end performance
   - Collects multiple iterations for statistical accuracy

2. **Kotlin Profiler** (`BenchmarkProfiler.kt`)
   - JVM-level performance measurement
   - Memory and GC tracking
   - Entity count monitoring
   - Integration with ECS systems

3. **Analysis Engine** (`analyze_benchmarks.py`)
   - Performance trend analysis
   - Scalability calculations
   - Memory efficiency reports
   - Visualization generation

4. **Integration Layer** (`App.kt` benchmark mode)
   - Environment detection (`BENCHMARK_MODE=true`)
   - Batch processing mode for deterministic results
   - Profiling data output in parseable format

### Data Flow

```
[Python] Generate Dataset → [Files] → [Kotlin] Process with Profiling → [Python] Collect Metrics → [Analysis] Report
```

## Example Results

Recent benchmark run on medium dataset:
- **1,000 obligations, 2,500 events**
- **3,395 ops/sec throughput**
- **736ms total duration** 
- **1.8 MB memory usage**
- **0.3% GC overhead**

Key insights:
- Memory efficiency improves dramatically with scale (409KB → 1.9KB per obligation)
- Throughput scales super-linearly due to batching effects
- GC overhead remains minimal even at scale

## Configuration

### Benchmark Parameters
- `warmup_iterations`: Number of warmup runs (default: 3)
- `measurement_iterations`: Number of measurement runs (default: 5)
- `timeout_sec`: Maximum execution time (default: 60s)

### Data Generation
- **Obligations**: Synthetic trades with random ISINs, accounts, dates
- **Status Events**: MATCHED, PARTIAL_SETTLED, SETTLED, ACK codes
- **Duplicates**: Exact message replicas for idempotency testing
- **Unmatches**: Events with no corresponding obligations

### JVM Profiling
- Heap memory tracking before/after execution
- GC time measurement across all collectors
- Entity count monitoring during processing
- Deterministic execution in batch mode

## Advanced Usage

### Custom Scenarios

Create custom benchmark configurations:

```python
config = BenchmarkConfig(
    name="custom",
    description="Custom scenario for my use case",
    num_obligations=1500,
    num_status_events=4000,
    num_duplicates=100,
    num_unmatches=50,
    measurement_iterations=8
)
```

### Performance Tuning

Monitor these key metrics for optimization:
- **Memory per obligation**: Target < 5KB per obligation
- **GC overhead**: Keep < 2% of total execution time  
- **Throughput scaling**: Should improve with dataset size
- **Peak entities**: Monitor for memory pressure

### Comparative Analysis

Track performance changes over time:
```bash
# Baseline
python py/benchmark.py --scenario medium > baseline.log

# After changes
python py/benchmark.py --scenario medium > optimized.log

# Compare
python py/analyze_benchmarks.py --compare baseline.json optimized.json
```

## Integration with CI/CD

Add performance regression detection:
```bash
# Performance gate in CI
python py/benchmark.py --scenario small
python py/analyze_benchmarks.py | grep "Peak Performance" | awk '{print $4}' > current_perf.txt
# Compare with baseline and fail if degraded > 10%
```

## Dependencies

### Required
- Python 3.7+
- Kotlin/JVM (via Gradle)
- Existing ECS settlement matching engine

### Optional  
- `matplotlib` + `seaborn` for chart generation
- `numpy` for advanced statistical analysis

Install optional dependencies:
```bash
pip install matplotlib seaborn numpy
```

## Troubleshooting

### Common Issues

**Timeout errors**: Increase `timeout_sec` for large datasets
**Memory errors**: Reduce dataset size or increase JVM heap
**Inconsistent results**: Check for background processes, increase warmup iterations

### Debug Mode
Set `BENCHMARK_DEBUG=true` for verbose output:
```bash
BENCHMARK_DEBUG=true python py/benchmark.py --scenario small
```

## Contributing

When adding new scenarios or metrics:
1. Update scenario definitions in `create_benchmark_scenarios()`
2. Add corresponding analysis in `generate_performance_report()`
3. Test across multiple dataset sizes
4. Document expected behavior and performance characteristics

---

Built with ❤️ for high-performance settlement processing