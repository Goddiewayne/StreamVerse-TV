package com.streamverse.pipeline.telemetry

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class MetricsCollector {
    private val counters = ConcurrentHashMap<String, AtomicLong>()
    private val gauges = ConcurrentHashMap<String, Double>()

    fun increment(name: String, amount: Long = 1) {
        counters.getOrPut(name) { AtomicLong(0) }.addAndGet(amount)
    }

    fun gauge(name: String, value: Double) {
        gauges[name] = value
    }

    fun getCounter(name: String): Long = counters[name]?.get() ?: 0
    fun getGauge(name: String): Double = gauges[name] ?: 0.0

    fun snapshot(): Map<String, Number> {
        val snapshot = linkedMapOf<String, Number>()
        for ((name, value) in counters) {
            snapshot["counter.$name"] = value.get()
        }
        for ((name, value) in gauges) {
            snapshot["gauge.$name"] = value
        }
        return snapshot
    }

    fun reset() {
        counters.clear()
        gauges.clear()
    }

    fun report(logger: StructuredLogger) {
        val snapshot = snapshot()
        logger.info("MetricsCollector", "=== Metrics Snapshot ===")
        for ((name, value) in snapshot) {
            logger.info("MetricsCollector", "  $name = $value")
        }
        logger.info("MetricsCollector", "========================")
    }
}
