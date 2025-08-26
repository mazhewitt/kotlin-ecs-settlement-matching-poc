#!/usr/bin/env python3
"""
Benchmark analysis and report generation for ECS Settlement Matching

This script analyzes benchmark results and generates reports showing:
- Performance trends across dataset sizes
- Memory usage patterns
- Throughput characteristics
- Scalability analysis

Usage:
    python analyze_benchmarks.py --dir benchmark_results
    python analyze_benchmarks.py --compare run1.json run2.json
"""

import argparse
import json
import statistics
import time
from pathlib import Path
from typing import List, Dict, Any

# Optional plotting imports
try:
    import matplotlib.pyplot as plt
    import seaborn as sns
    HAS_PLOTTING = True
except ImportError:
    HAS_PLOTTING = False

def load_benchmark_results(results_dir: Path) -> List[Dict[str, Any]]:
    """Load all benchmark result files from directory"""
    results = []
    for json_file in results_dir.glob("benchmark_*.json"):
        with open(json_file) as f:
            result = json.load(f)
            results.append(result)
    return sorted(results, key=lambda r: r['mean_metrics']['obligations_count'])

def generate_performance_report(results: List[Dict[str, Any]]) -> str:
    """Generate a markdown performance report"""
    report = []
    report.append("# ECS Settlement Matching - Performance Report\n")
    
    # Overview table
    report.append("## Performance Overview\n")
    report.append("| Scenario | Obligations | Events | Throughput (ops/sec) | Duration (ms) | Memory (MB) |")
    report.append("|----------|-------------|---------|---------------------|---------------|-------------|")
    
    for result in results:
        m = result['mean_metrics']
        report.append(f"| {m['scenario_name']:<8} | {m['obligations_count']:>10,} | {m['status_events_count']:>6,} | {m['throughput_ops_per_sec']:>18.1f} | {m['duration_ms']:>12.1f} | {m['memory_used_mb']:>10.1f} |")
    
    # Scalability analysis
    report.append("\n## Scalability Analysis\n")
    
    if len(results) > 1:
        # Calculate scalability metrics
        first_result = results[0]['mean_metrics']
        last_result = results[-1]['mean_metrics']
        
        size_increase = last_result['obligations_count'] / first_result['obligations_count']
        throughput_change = last_result['throughput_ops_per_sec'] / first_result['throughput_ops_per_sec']
        memory_change = last_result['memory_used_mb'] / first_result['memory_used_mb']
        
        report.append(f"- **Dataset Size Range**: {first_result['obligations_count']:,} to {last_result['obligations_count']:,} obligations ({size_increase:.1f}x increase)")
        report.append(f"- **Throughput Scaling**: {throughput_change:.2f}x (ideal would be ~1.0x)")
        report.append(f"- **Memory Scaling**: {memory_change:.2f}x (vs {size_increase:.1f}x data increase)")
        
        # Performance per obligation
        report.append(f"- **Performance Density**: {last_result['throughput_ops_per_sec'] / last_result['obligations_count']:.2f} ops/sec per obligation")
    
    # Memory efficiency
    report.append("\n## Memory Efficiency\n")
    for result in results:
        m = result['mean_metrics']
        kb_per_obligation = (m['memory_used_mb'] * 1024) / m['obligations_count']
        report.append(f"- **{m['scenario_name']}**: {kb_per_obligation:.1f} KB per obligation")
    
    # GC behavior
    report.append("\n## Garbage Collection Impact\n")
    for result in results:
        m = result['mean_metrics']
        gc_overhead = (m['gc_time_ms'] / m['duration_ms']) * 100 if m['duration_ms'] > 0 else 0
        report.append(f"- **{m['scenario_name']}**: {m['gc_time_ms']:.1f}ms GC time ({gc_overhead:.1f}% overhead)")
    
    # Recommendations
    report.append("\n## Performance Recommendations\n")
    
    # Find best performing scenario by throughput
    best_throughput = max(results, key=lambda r: r['mean_metrics']['throughput_ops_per_sec'])
    worst_throughput = min(results, key=lambda r: r['mean_metrics']['throughput_ops_per_sec'])
    
    report.append(f"- **Peak Performance**: {best_throughput['mean_metrics']['scenario_name']} scenario achieves {best_throughput['mean_metrics']['throughput_ops_per_sec']:.1f} ops/sec")
    report.append(f"- **Performance Floor**: {worst_throughput['mean_metrics']['scenario_name']} scenario at {worst_throughput['mean_metrics']['throughput_ops_per_sec']:.1f} ops/sec")
    
    # Memory efficiency recommendations
    most_memory_efficient = min(results, key=lambda r: (r['mean_metrics']['memory_used_mb'] * 1024) / r['mean_metrics']['obligations_count'])
    report.append(f"- **Most Memory Efficient**: {most_memory_efficient['mean_metrics']['scenario_name']} scenario at {(most_memory_efficient['mean_metrics']['memory_used_mb'] * 1024) / most_memory_efficient['mean_metrics']['obligations_count']:.1f} KB per obligation")
    
    return "\n".join(report)

def generate_visualizations(results: List[Dict[str, Any]], output_dir: Path):
    """Generate performance visualization charts"""
    if not HAS_PLOTTING:
        print("⚠️  matplotlib/seaborn not available - skipping charts")
        print("Install with: pip install matplotlib seaborn")
        return
        
    output_dir.mkdir(exist_ok=True)
    
    # Extract data for plotting
    scenarios = [r['mean_metrics']['scenario_name'] for r in results]
    obligations = [r['mean_metrics']['obligations_count'] for r in results]
    throughput = [r['mean_metrics']['throughput_ops_per_sec'] for r in results]
    memory = [r['mean_metrics']['memory_used_mb'] for r in results]
    duration = [r['mean_metrics']['duration_ms'] for r in results]
    gc_time = [r['mean_metrics']['gc_time_ms'] for r in results]
    
    # Set up the plotting style
    try:
        plt.style.use('seaborn-v0_8')
    except:
        pass  # fallback to default style
        
    fig, ((ax1, ax2), (ax3, ax4)) = plt.subplots(2, 2, figsize=(15, 12))
    fig.suptitle('ECS Settlement Matching - Performance Analysis', fontsize=16)
    
    # Throughput vs Dataset Size
    ax1.scatter(obligations, throughput, c='blue', s=100, alpha=0.7)
    for i, scenario in enumerate(scenarios):
        ax1.annotate(scenario, (obligations[i], throughput[i]), xytext=(5, 5), textcoords='offset points')
    ax1.set_xlabel('Number of Obligations')
    ax1.set_ylabel('Throughput (ops/sec)')
    ax1.set_title('Throughput vs Dataset Size')
    ax1.grid(True, alpha=0.3)
    
    # Memory Usage vs Dataset Size
    ax2.scatter(obligations, memory, c='red', s=100, alpha=0.7)
    for i, scenario in enumerate(scenarios):
        ax2.annotate(scenario, (obligations[i], memory[i]), xytext=(5, 5), textcoords='offset points')
    ax2.set_xlabel('Number of Obligations')
    ax2.set_ylabel('Memory Usage (MB)')
    ax2.set_title('Memory Usage vs Dataset Size')
    ax2.grid(True, alpha=0.3)
    
    # Duration vs Dataset Size
    ax3.scatter(obligations, duration, c='green', s=100, alpha=0.7)
    for i, scenario in enumerate(scenarios):
        ax3.annotate(scenario, (obligations[i], duration[i]), xytext=(5, 5), textcoords='offset points')
    ax3.set_xlabel('Number of Obligations')
    ax3.set_ylabel('Duration (ms)')
    ax3.set_title('Processing Duration vs Dataset Size')
    ax3.grid(True, alpha=0.3)
    
    # GC Time vs Duration
    gc_percentage = [(gc / dur) * 100 if dur > 0 else 0 for gc, dur in zip(gc_time, duration)]
    ax4.bar(scenarios, gc_percentage, color='orange', alpha=0.7)
    ax4.set_xlabel('Scenario')
    ax4.set_ylabel('GC Overhead (%)')
    ax4.set_title('Garbage Collection Overhead')
    ax4.tick_params(axis='x', rotation=45)
    ax4.grid(True, alpha=0.3)
    
    plt.tight_layout()
    chart_file = output_dir / "performance_analysis.png"
    plt.savefig(chart_file, dpi=300, bbox_inches='tight')
    plt.close()
    
    print(f"📊 Performance charts saved to: {chart_file}")

def compare_benchmarks(file1: Path, file2: Path) -> str:
    """Compare two benchmark runs"""
    with open(file1) as f:
        result1 = json.load(f)
    with open(file2) as f:
        result2 = json.load(f)
    
    m1 = result1['mean_metrics']
    m2 = result2['mean_metrics']
    
    report = []
    report.append(f"# Benchmark Comparison: {m1['scenario_name']} vs {m2['scenario_name']}\n")
    
    # Basic comparison
    report.append("## Performance Comparison\n")
    report.append(f"- **Throughput**: {m1['throughput_ops_per_sec']:.1f} vs {m2['throughput_ops_per_sec']:.1f} ops/sec ({((m2['throughput_ops_per_sec'] / m1['throughput_ops_per_sec']) - 1) * 100:+.1f}%)")
    report.append(f"- **Duration**: {m1['duration_ms']:.1f} vs {m2['duration_ms']:.1f} ms ({((m2['duration_ms'] / m1['duration_ms']) - 1) * 100:+.1f}%)")
    report.append(f"- **Memory**: {m1['memory_used_mb']:.1f} vs {m2['memory_used_mb']:.1f} MB ({((m2['memory_used_mb'] / m1['memory_used_mb']) - 1) * 100:+.1f}%)")
    report.append(f"- **GC Time**: {m1['gc_time_ms']:.1f} vs {m2['gc_time_ms']:.1f} ms ({((m2['gc_time_ms'] / m1['gc_time_ms']) - 1) * 100:+.1f}%)")
    
    return "\n".join(report)

def main():
    parser = argparse.ArgumentParser(description="Analyze ECS Settlement Matching benchmarks")
    parser.add_argument("--dir", type=Path, default=Path("benchmark_results"), 
                       help="Directory containing benchmark result files")
    parser.add_argument("--compare", nargs=2, type=Path,
                       help="Compare two specific benchmark files")
    parser.add_argument("--charts", action="store_true",
                       help="Generate performance visualization charts")
    parser.add_argument("--output", type=Path, default=Path("."),
                       help="Output directory for reports and charts")
    
    args = parser.parse_args()
    
    if args.compare:
        # Compare two specific benchmark files
        report = compare_benchmarks(args.compare[0], args.compare[1])
        print(report)
        
        # Save comparison report
        report_file = args.output / f"comparison_{int(time.time())}.md"
        with open(report_file, 'w') as f:
            f.write(report)
        print(f"📝 Comparison report saved to: {report_file}")
    
    else:
        # Analyze all results in directory
        if not args.dir.exists():
            print(f"❌ Benchmark results directory not found: {args.dir}")
            return
        
        results = load_benchmark_results(args.dir)
        if not results:
            print(f"❌ No benchmark results found in: {args.dir}")
            return
        
        print(f"📊 Found {len(results)} benchmark results")
        
        # Generate performance report
        report = generate_performance_report(results)
        print(report)
        
        # Save report
        import time
        report_file = args.output / f"performance_report_{int(time.time())}.md"
        with open(report_file, 'w') as f:
            f.write(report)
        print(f"📝 Performance report saved to: {report_file}")
        
        # Generate charts if requested
        if args.charts:
            generate_visualizations(results, args.output)

if __name__ == "__main__":
    main()