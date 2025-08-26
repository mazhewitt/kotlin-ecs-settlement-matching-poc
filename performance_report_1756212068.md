# ECS Settlement Matching - Performance Report

## Performance Overview

| Scenario | Obligations | Events | Throughput (ops/sec) | Duration (ms) | Memory (MB) |
|----------|-------------|---------|---------------------|---------------|-------------|
| micro    |         10 |     20 |               39.0 |        513.3 |        4.0 |
| small    |        100 |    250 |              463.3 |        539.9 |        4.0 |
| medium   |      1,000 |  2,500 |             3394.6 |        736.5 |        1.8 |

## Scalability Analysis

- **Dataset Size Range**: 10 to 1,000 obligations (100.0x increase)
- **Throughput Scaling**: 87.09x (ideal would be ~1.0x)
- **Memory Scaling**: 0.46x (vs 100.0x data increase)
- **Performance Density**: 3.39 ops/sec per obligation

## Memory Efficiency

- **micro**: 409.6 KB per obligation
- **small**: 41.0 KB per obligation
- **medium**: 1.9 KB per obligation

## Garbage Collection Impact

- **micro**: 0.0ms GC time (0.0% overhead)
- **small**: 0.0ms GC time (0.0% overhead)
- **medium**: 2.0ms GC time (0.3% overhead)

## Performance Recommendations

- **Peak Performance**: medium scenario achieves 3394.6 ops/sec
- **Performance Floor**: micro scenario at 39.0 ops/sec
- **Most Memory Efficient**: medium scenario at 1.9 KB per obligation