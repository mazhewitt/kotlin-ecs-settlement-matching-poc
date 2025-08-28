# ECS Settlement Matching - Performance Report

## Performance Overview

| Scenario | Obligations | Events | Throughput (ops/sec) | Duration (ms) | Memory (MB) |
|----------|-------------|---------|---------------------|---------------|-------------|
| micro    |         10 |     20 |               39.7 |        504.0 |        4.0 |
| micro    |         10 |     20 |               39.0 |        513.3 |        4.0 |
| small    |        100 |    250 |              463.3 |        539.9 |        4.0 |
| small    |        100 |    250 |              464.4 |        538.5 |        4.0 |
| throughput |        500 |  5,000 |             6391.2 |        782.8 |        7.1 |
| throughput |        500 |  5,000 |             6496.4 |        769.7 |        7.1 |
| medium   |      1,000 |  2,500 |             3394.6 |        736.5 |        1.8 |
| medium   |      1,000 |  2,500 |             3501.5 |        714.1 |        1.9 |
| medium   |      1,000 |  2,500 |             3311.6 |        756.9 |        1.9 |
| medium   |      1,000 |  2,500 |             3758.2 |        666.2 |        2.2 |
| memory   |      5,000 |  5,500 |             5534.3 |        995.6 |       21.4 |
| large    |      5,000 | 12,500 |            11091.3 |       1127.7 |       53.9 |
| large    |      5,000 | 12,500 |            10336.2 |       1210.0 |       44.1 |
| large    |      5,000 | 12,500 |            10021.3 |       1247.4 |       41.5 |
| xl       |     10,000 | 25,000 |            10290.2 |       2429.6 |       25.9 |
| xl       |     10,000 | 25,000 |            14600.8 |       1712.3 |       51.6 |

## Scalability Analysis

- **Dataset Size Range**: 10 to 10,000 obligations (1000.0x increase)
- **Throughput Scaling**: 367.94x (ideal would be ~1.0x)
- **Memory Scaling**: 12.89x (vs 1000.0x data increase)
- **Performance Density**: 1.46 ops/sec per obligation

## Memory Efficiency

- **micro**: 409.6 KB per obligation
- **micro**: 409.6 KB per obligation
- **small**: 41.0 KB per obligation
- **small**: 41.0 KB per obligation
- **throughput**: 14.6 KB per obligation
- **throughput**: 14.6 KB per obligation
- **medium**: 1.9 KB per obligation
- **medium**: 2.0 KB per obligation
- **medium**: 2.0 KB per obligation
- **medium**: 2.3 KB per obligation
- **memory**: 4.4 KB per obligation
- **large**: 11.0 KB per obligation
- **large**: 9.0 KB per obligation
- **large**: 8.5 KB per obligation
- **xl**: 2.7 KB per obligation
- **xl**: 5.3 KB per obligation

## Garbage Collection Impact

- **micro**: 0.0ms GC time (0.0% overhead)
- **micro**: 0.0ms GC time (0.0% overhead)
- **small**: 0.0ms GC time (0.0% overhead)
- **small**: 0.0ms GC time (0.0% overhead)
- **throughput**: 3.3ms GC time (0.4% overhead)
- **throughput**: 3.0ms GC time (0.4% overhead)
- **medium**: 2.0ms GC time (0.3% overhead)
- **medium**: 2.0ms GC time (0.3% overhead)
- **medium**: 2.0ms GC time (0.3% overhead)
- **medium**: 2.0ms GC time (0.3% overhead)
- **memory**: 4.3ms GC time (0.4% overhead)
- **large**: 6.0ms GC time (0.5% overhead)
- **large**: 7.0ms GC time (0.6% overhead)
- **large**: 7.0ms GC time (0.6% overhead)
- **xl**: 18.5ms GC time (0.8% overhead)
- **xl**: 20.5ms GC time (1.2% overhead)

## Performance Recommendations

- **Peak Performance**: xl scenario achieves 14600.8 ops/sec
- **Performance Floor**: micro scenario at 39.0 ops/sec
- **Most Memory Efficient**: medium scenario at 1.9 KB per obligation