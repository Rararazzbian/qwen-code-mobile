package com.qwen.mobile

import java.util.concurrent.CopyOnWriteArraySet

object RuntimeState {
    enum class Phase { STOPPED, INSTALLING, STARTING, RUNNING, STOPPING, FAILED }

    @Volatile var phase = Phase.STOPPED
        private set
    @Volatile var detail = ""
        private set

    private const val MAX_LINES = 500
    private val lines = ArrayDeque<String>()
    private val listeners = CopyOnWriteArraySet<() -> Unit>()

    fun set(phase: Phase, detail: String = "") {
        this.phase = phase
        this.detail = detail
        log("[${phase.name.lowercase()}] $detail".trimEnd())
    }

    fun log(line: String) {
        synchronized(lines) {
            lines.addLast(line)
            while (lines.size > MAX_LINES) lines.removeFirst()
        }
        listeners.forEach { it() }
    }

    fun logText(): String = synchronized(lines) { lines.joinToString("\n") }

    fun addListener(listener: () -> Unit) = listeners.add(listener)
    fun removeListener(listener: () -> Unit) = listeners.remove(listener)
}
