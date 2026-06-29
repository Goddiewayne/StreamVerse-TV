package com.streamverse.core.util

import android.util.Log

class PerformanceMonitor(private val tag: String = "Perf") {
    private val starts = mutableMapOf<String, Long>()
    private val phases = mutableListOf<Phase>()
    private var globalStart = 0L

    data class Phase(val name: String, val elapsedMs: Long)

    fun start() { globalStart = now(); phases.clear() }

    fun phaseStarted(name: String) { starts[name] = now() }

    fun phaseEnded(name: String) {
        val s = starts.remove(name) ?: return
        phases.add(Phase(name, now() - s))
    }

    fun phaseEnded(name: String, detail: String) {
        val s = starts.remove(name) ?: return
        phases.add(Phase("$name($detail)", now() - s))
    }

    fun logSummary() {
        val total = now() - globalStart
        val sb = StringBuilder("╔══════════════════════════════════════╗\n║ Performance Summary (${total}ms total)       ║\n╚══════════════════════════════════════╝\n")
        for (p in phases) {
            val pct = if (total > 0) (p.elapsedMs * 100 / total) else 0
            sb.appendLine("  ${p.name.padEnd(24)} ${p.elapsedMs.toString().padStart(5)}ms  $pct%")
        }
        sb.appendLine("  ${"─".repeat(35)}")
        sb.append("  TOTAL".padEnd(24) + " ${total.toString().padStart(5)}ms")
        Log.d(tag, sb.toString())
    }

    fun elapsed(name: String): Long = (starts[name] ?: globalStart).let { now() - it }

    private fun now() = System.currentTimeMillis()
}
