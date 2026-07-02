package com.streamverse.pipeline.telemetry

import org.slf4j.Logger
import org.slf4j.LoggerFactory

class StructuredLogger(private val name: String) {
    private val log: Logger = LoggerFactory.getLogger(name)

    fun info(component: String, message: String) {
        log.info("[{}] {}", component, message)
    }

    fun warn(component: String, message: String) {
        log.warn("[{}] {}", component, message)
    }

    fun error(component: String, message: String, throwable: Throwable? = null) {
        if (throwable != null) log.error("[{}] {}", component, message, throwable)
        else log.error("[{}] {}", component, message)
    }

    fun debug(component: String, message: String) {
        log.debug("[{}] {}", component, message)
    }

    companion object {
        fun forClass(clazz: Class<*>): StructuredLogger = StructuredLogger(clazz.simpleName)
        fun forName(name: String): StructuredLogger = StructuredLogger(name)
    }
}
