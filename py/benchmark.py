#!/usr/bin/env python3
"""
Benchmark suite for ECS Settlement Matching Engine

This benchmark framework measures:
- Throughput (obligations processed per second)
- Latency (time to process batches of various sizes)
- Memory usage (heap growth, GC pressure)
- Scalability (performance across different data sizes)
- System behavior under stress conditions

Usage:
    python benchmark.py --scenario all
    python benchmark.py --scenario throughput --size 1000
    python benchmark.py --scenario latency --iterations 10
"""

import argparse
import json
import os
import random
import statistics
import subprocess
import sys
import time
from dataclasses import dataclass, asdict
from datetime import datetime
from pathlib import Path
from typing import List, Dict, Any, Tuple, Optional
import contextlib

# Import our existing test harness
from generate_and_assert import (
    Trade, make_trades, as_bank_line, as_market_line, write_files,
    RUNTIME, BANK, MARKET, STATUS, PROJECT_ROOT
)

@dataclass
class BenchmarkConfig:
    """Configuration for a benchmark scenario"""
    name: str
    description: str
    num_obligations: int
    num_status_events: int
    num_duplicates: int
    num_unmatches: int
    warmup_iterations: int = 3
    measurement_iterations: int = 5
    timeout_sec: float = 60.0

@dataclass
class PerformanceMetrics:
    """Performance measurement results"""
    scenario_name: str
    obligations_count: int
    status_events_count: int
    duration_ms: float
    throughput_ops_per_sec: float
    memory_used_mb: float
    gc_time_ms: float
    cpu_time_ms: float
    peak_entities: int
    
    def to_dict(self) -> Dict[str, Any]:
        return asdict(self)

@dataclass
class BenchmarkResult:
    """Complete benchmark run results"""
    config: BenchmarkConfig
    metrics: List[PerformanceMetrics]
    mean_metrics: PerformanceMetrics
    timestamp: str
    
    def to_dict(self) -> Dict[str, Any]:
        return {
            'config': asdict(self.config),
            'metrics': [m.to_dict() for m in self.metrics],
            'mean_metrics': self.mean_metrics.to_dict(),
            'timestamp': self.timestamp
        }

class BenchmarkRunner:
    """Main benchmark execution engine"""
    
    def __init__(self, output_dir: Path = Path("benchmark_results")):
        self.output_dir = output_dir
        self.output_dir.mkdir(exist_ok=True)
        
    def run_benchmark(self, config: BenchmarkConfig) -> BenchmarkResult:
        """Run a complete benchmark scenario"""
        print(f"\n🚀 Running benchmark: {config.name}")
        print(f"Description: {config.description}")
        print(f"Config: {config.num_obligations} obligations, {config.num_status_events} events")
        
        # Warmup runs
        print(f"Warming up ({config.warmup_iterations} iterations)...")
        for i in range(config.warmup_iterations):
            self._run_single_measurement(config, warmup=True)
        
        # Measurement runs
        print(f"Measuring performance ({config.measurement_iterations} iterations)...")
        metrics = []
        for i in range(config.measurement_iterations):
            print(f"  Iteration {i+1}/{config.measurement_iterations}...")
            metric = self._run_single_measurement(config)
            metrics.append(metric)
            print(f"    {metric.throughput_ops_per_sec:.1f} ops/sec, {metric.duration_ms:.1f}ms")
        
        # Calculate mean metrics
        mean_metrics = self._calculate_mean_metrics(config, metrics)
        
        result = BenchmarkResult(
            config=config,
            metrics=metrics,
            mean_metrics=mean_metrics,
            timestamp=datetime.now().isoformat()
        )
        
        self._save_results(result)
        self._print_summary(result)
        return result
    
    def _run_single_measurement(self, config: BenchmarkConfig, warmup: bool = False) -> Optional[PerformanceMetrics]:
        """Execute a single benchmark measurement"""
        if warmup:
            # Simplified warmup run
            bank_lines, market_lines = self._generate_dataset(config)
            write_files(bank_lines, market_lines)
            self._run_kotlin_with_profiling(config, measure=False)
            return None
        
        # Generate test data
        bank_lines, market_lines = self._generate_dataset(config)
        write_files(bank_lines, market_lines)
        
        # Run with profiling
        start_time = time.perf_counter()
        profiling_data = self._run_kotlin_with_profiling(config)
        end_time = time.perf_counter()
        
        duration_ms = (end_time - start_time) * 1000
        throughput = config.num_status_events / (duration_ms / 1000) if duration_ms > 0 else 0
        
        return PerformanceMetrics(
            scenario_name=config.name,
            obligations_count=config.num_obligations,
            status_events_count=config.num_status_events,
            duration_ms=duration_ms,
            throughput_ops_per_sec=throughput,
            memory_used_mb=profiling_data.get('memory_mb', 0.0),
            gc_time_ms=profiling_data.get('gc_time_ms', 0.0),
            cpu_time_ms=profiling_data.get('cpu_time_ms', duration_ms),
            peak_entities=profiling_data.get('peak_entities', config.num_obligations)
        )
    
    def _generate_dataset(self, config: BenchmarkConfig) -> Tuple[List[str], List[str]]:
        """Generate benchmark dataset based on config"""
        trades = make_trades(config.num_obligations)
        bank_lines = [as_bank_line(t) for t in trades]
        
        # Market lines: generate the specified number of status events
        market_lines = []
        msg_seq = {}
        
        # Primary events (MATCHED for each obligation)
        for t in trades:
            msg_id = f"M_{t.obligation_id}"
            market_lines.append(as_market_line(t, msg_id, 1))
            msg_seq[t.obligation_id] = (msg_id, 1)
        
        # Additional events to reach target count
        additional_events = config.num_status_events - len(trades)
        for i in range(additional_events):
            t = random.choice(trades)
            msg_id, last_seq = msg_seq[t.obligation_id]
            new_seq = last_seq + 1
            
            # Mix of different event types
            if i % 3 == 0:
                # Partial settlement
                line = f"{msg_id},{new_seq},PARTIAL_SETTLED,{t.isin},{t.account},{t.settle_date},{t.intended_qty//4},2024-01-01T00:00:00Z"
            elif i % 3 == 1:
                # Final settlement
                line = f"{msg_id},{new_seq},SETTLED,{t.isin},{t.account},{t.settle_date},{t.intended_qty},2024-01-01T00:00:00Z"
            else:
                # Acknowledgment
                line = f"{msg_id},{new_seq},ACK,{t.isin},{t.account},{t.settle_date},{t.intended_qty},2024-01-01T00:00:00Z"
            
            market_lines.append(line)
            msg_seq[t.obligation_id] = (msg_id, new_seq)
        
        # Add duplicates
        dup_indices = random.sample(range(len(market_lines)), k=min(config.num_duplicates, len(market_lines)))
        for idx in dup_indices:
            market_lines.append(market_lines[idx])  # exact duplicate
        
        # Add unmatches
        for i in range(config.num_unmatches):
            fake_line = f"FAKE_{i},1,MATCHED,FAKE{random.randint(100000,999999)},ACC999,2024-12-31,100,2024-01-01T00:00:00Z"
            market_lines.append(fake_line)
        
        # Shuffle for realistic ordering
        random.shuffle(market_lines)
        
        return bank_lines, market_lines
    
    def _run_kotlin_with_profiling(self, config: BenchmarkConfig, measure: bool = True) -> Dict[str, Any]:
        """Run Kotlin with JVM profiling enabled"""
        cmd = ["./gradlew", "-q", "run"]
        
        env = os.environ.copy()
        env["BENCHMARK_MODE"] = "true"
        
        try:
            proc = subprocess.Popen(cmd, cwd=PROJECT_ROOT, env=env, 
                                  stdout=subprocess.PIPE, stderr=subprocess.PIPE, 
                                  text=True)
            stdout, stderr = proc.communicate(timeout=config.timeout_sec)
            
            # Parse profiling data from Kotlin output
            profiling_data = {}
            if measure:
                profiling_data = self._parse_kotlin_metrics(stdout)
            
            return profiling_data
            
        except subprocess.TimeoutExpired:
            proc.kill()
            raise RuntimeError(f"Benchmark timed out after {config.timeout_sec} seconds")
    
    def _parse_kotlin_metrics(self, stdout: str) -> Dict[str, Any]:
        """Parse metrics output from Kotlin benchmark mode"""
        profiling_data = {
            'memory_mb': 0.0,
            'gc_time_ms': 0.0,
            'cpu_time_ms': 0.0,
            'peak_entities': 0
        }
        
        # Look for the BENCHMARK_METRICS line
        for line in stdout.splitlines():
            if line.startswith("BENCHMARK_METRICS:"):
                # Parse key=value pairs
                metrics_str = line.replace("BENCHMARK_METRICS:", "").strip()
                for pair in metrics_str.split(", "):
                    if "=" in pair:
                        key, value = pair.split("=", 1)
                        key = key.strip()
                        value = value.strip()
                        
                        try:
                            if key == "memory_mb":
                                profiling_data['memory_mb'] = float(value)
                            elif key == "gc_time_ms":
                                profiling_data['gc_time_ms'] = float(value)
                            elif key == "duration_ms":
                                profiling_data['cpu_time_ms'] = float(value)
                            elif key == "peak_entities":
                                profiling_data['peak_entities'] = int(value)
                        except ValueError:
                            pass  # ignore parsing errors
                break
        
        return profiling_data
    
    def _calculate_mean_metrics(self, config: BenchmarkConfig, metrics: List[PerformanceMetrics]) -> PerformanceMetrics:
        """Calculate mean values from multiple measurement runs"""
        return PerformanceMetrics(
            scenario_name=config.name,
            obligations_count=config.num_obligations,
            status_events_count=config.num_status_events,
            duration_ms=statistics.mean(m.duration_ms for m in metrics),
            throughput_ops_per_sec=statistics.mean(m.throughput_ops_per_sec for m in metrics),
            memory_used_mb=statistics.mean(m.memory_used_mb for m in metrics),
            gc_time_ms=statistics.mean(m.gc_time_ms for m in metrics),
            cpu_time_ms=statistics.mean(m.cpu_time_ms for m in metrics),
            peak_entities=int(statistics.mean(m.peak_entities for m in metrics))
        )
    
    def _save_results(self, result: BenchmarkResult):
        """Save benchmark results to JSON file"""
        filename = f"benchmark_{result.config.name}_{int(time.time())}.json"
        filepath = self.output_dir / filename
        
        with open(filepath, 'w') as f:
            json.dump(result.to_dict(), f, indent=2)
        
        print(f"📊 Results saved to: {filepath}")
    
    def _print_summary(self, result: BenchmarkResult):
        """Print human-readable benchmark summary"""
        m = result.mean_metrics
        print(f"\n📈 Benchmark Summary: {result.config.name}")
        print(f"{'='*60}")
        print(f"Obligations:     {m.obligations_count:,}")
        print(f"Status Events:   {m.status_events_count:,}")
        print(f"Duration:        {m.duration_ms:.1f} ms")
        print(f"Throughput:      {m.throughput_ops_per_sec:.1f} events/sec")
        print(f"Memory Used:     {m.memory_used_mb:.1f} MB")
        print(f"GC Time:         {m.gc_time_ms:.1f} ms")
        print(f"Peak Entities:   {m.peak_entities:,}")
        
        # Show variance if we have multiple measurements
        if len(result.metrics) > 1:
            throughputs = [m.throughput_ops_per_sec for m in result.metrics]
            durations = [m.duration_ms for m in result.metrics]
            print(f"\nVariance:")
            print(f"Throughput StdDev: {statistics.stdev(throughputs):.1f} ops/sec")
            print(f"Duration StdDev:   {statistics.stdev(durations):.1f} ms")

def create_benchmark_scenarios() -> List[BenchmarkConfig]:
    """Define standard benchmark scenarios"""
    return [
        BenchmarkConfig(
            name="micro",
            description="Micro benchmark: minimal dataset for baseline",
            num_obligations=10,
            num_status_events=20,
            num_duplicates=2,
            num_unmatches=1
        ),
        BenchmarkConfig(
            name="small",
            description="Small dataset: typical single-batch processing",
            num_obligations=100,
            num_status_events=250,
            num_duplicates=10,
            num_unmatches=5,
            measurement_iterations=10
        ),
        BenchmarkConfig(
            name="medium",
            description="Medium dataset: moderate real-world load",
            num_obligations=1000,
            num_status_events=2500,
            num_duplicates=50,
            num_unmatches=25
        ),
        BenchmarkConfig(
            name="large",
            description="Large dataset: high-volume processing test",
            num_obligations=5000,
            num_status_events=12500,
            num_duplicates=250,
            num_unmatches=100,
            measurement_iterations=3,
            timeout_sec=120.0
        ),
        BenchmarkConfig(
            name="xl",
            description="Extra large: stress test for scalability limits",
            num_obligations=10000,
            num_status_events=25000,
            num_duplicates=500,
            num_unmatches=200,
            measurement_iterations=2,
            timeout_sec=300.0
        ),
        BenchmarkConfig(
            name="throughput",
            description="Throughput test: many events per obligation",
            num_obligations=500,
            num_status_events=5000,
            num_duplicates=100,
            num_unmatches=50,
            measurement_iterations=5
        ),
        BenchmarkConfig(
            name="memory",
            description="Memory test: many obligations, few events each",
            num_obligations=5000,
            num_status_events=5500,
            num_duplicates=25,
            num_unmatches=25,
            measurement_iterations=3
        )
    ]

def main():
    parser = argparse.ArgumentParser(description="ECS Settlement Matching Benchmark Suite")
    parser.add_argument("--scenario", default="all", 
                       help="Benchmark scenario to run (all, micro, small, medium, large, xl, throughput, memory)")
    parser.add_argument("--size", type=int, help="Custom obligation count for throughput scenario")
    parser.add_argument("--iterations", type=int, help="Custom iteration count")
    parser.add_argument("--output", type=Path, default=Path("benchmark_results"), 
                       help="Output directory for results")
    
    args = parser.parse_args()
    
    # Set random seed for reproducible benchmarks
    random.seed(12345)
    
    runner = BenchmarkRunner(args.output)
    scenarios = create_benchmark_scenarios()
    
    # Apply custom parameters
    if args.size and args.scenario == "throughput":
        for scenario in scenarios:
            if scenario.name == "throughput":
                scenario.num_obligations = args.size
                scenario.num_status_events = args.size * 3
    
    if args.iterations:
        for scenario in scenarios:
            scenario.measurement_iterations = args.iterations
    
    # Run selected scenarios
    if args.scenario == "all":
        print("🎯 Running all benchmark scenarios...")
        results = []
        for scenario in scenarios:
            result = runner.run_benchmark(scenario)
            results.append(result)
        
        # Print comparative summary
        print(f"\n🏆 Comparative Results")
        print(f"{'='*80}")
        print(f"{'Scenario':<15} {'Obligations':<12} {'Events':<8} {'Throughput':<15} {'Duration':<10}")
        print(f"{'-'*80}")
        for result in results:
            m = result.mean_metrics
            print(f"{m.scenario_name:<15} {m.obligations_count:<12,} {m.status_events_count:<8,} "
                  f"{m.throughput_ops_per_sec:<15.1f} {m.duration_ms:<10.1f}")
    
    else:
        # Run single scenario
        scenario = next((s for s in scenarios if s.name == args.scenario), None)
        if not scenario:
            print(f"❌ Unknown scenario: {args.scenario}")
            print(f"Available scenarios: {', '.join(s.name for s in scenarios)}")
            sys.exit(1)
        
        runner.run_benchmark(scenario)

if __name__ == "__main__":
    main()