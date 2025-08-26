package org.example.settlement

import java.lang.management.ManagementFactory
import java.lang.management.MemoryMXBean
import java.lang.management.GarbageCollectorMXBean
import kotlin.system.measureTimeMillis
import kotlinx.datetime.Clock

data class ProfilerMetrics(
    val durationMs: Long,
    val memoryUsedMb: Double,
    val gcTimeMs: Long,
    val gcCollections: Long,
    val peakEntities: Int,
    val throughputOpsPerSec: Double
)

class BenchmarkProfiler {
    private val memoryBean: MemoryMXBean = ManagementFactory.getMemoryMXBean()
    private val gcBeans: List<GarbageCollectorMXBean> = ManagementFactory.getGarbageCollectorMXBeans()
    
    private var startMemory: Long = 0
    private var startGcTime: Long = 0
    private var startGcCollections: Long = 0
    private var peakEntityCount: Int = 0
    
    fun startProfiling() {
        // Force GC to get clean baseline
        System.gc()
        Thread.sleep(100)
        
        val memoryUsage = memoryBean.heapMemoryUsage
        startMemory = memoryUsage.used
        
        startGcTime = gcBeans.sumOf { it.collectionTime }
        startGcCollections = gcBeans.sumOf { it.collectionCount }
        
        peakEntityCount = 0
    }
    
    fun updatePeakEntities(currentCount: Int) {
        if (currentCount > peakEntityCount) {
            peakEntityCount = currentCount
        }
    }
    
    fun stopProfiling(operationCount: Int, durationMs: Long): ProfilerMetrics {
        val memoryUsage = memoryBean.heapMemoryUsage
        val endMemory = memoryUsage.used
        val memoryUsedMb = (endMemory - startMemory) / (1024.0 * 1024.0)
        
        val endGcTime = gcBeans.sumOf { it.collectionTime }
        val endGcCollections = gcBeans.sumOf { it.collectionCount }
        
        val gcTimeMs = endGcTime - startGcTime
        val gcCollections = endGcCollections - startGcCollections
        
        val throughput = if (durationMs > 0) {
            (operationCount * 1000.0) / durationMs
        } else {
            0.0
        }
        
        return ProfilerMetrics(
            durationMs = durationMs,
            memoryUsedMb = memoryUsedMb,
            gcTimeMs = gcTimeMs,
            gcCollections = gcCollections,
            peakEntities = peakEntityCount,
            throughputOpsPerSec = throughput
        )
    }
}

fun <T> profiledExecution(
    operationCount: Int,
    peakEntityCounter: () -> Int = { 0 },
    operation: (profiler: BenchmarkProfiler) -> T
): Pair<T, ProfilerMetrics> {
    val profiler = BenchmarkProfiler()
    profiler.startProfiling()
    
    val result: T
    val durationMs = measureTimeMillis {
        result = operation(profiler)
        // Update peak entity count at the end
        profiler.updatePeakEntities(peakEntityCounter())
    }
    
    val metrics = profiler.stopProfiling(operationCount, durationMs)
    return Pair(result, metrics)
}