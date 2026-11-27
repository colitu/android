package com.v2ray.ang.colitu.data

/**
 * One connect flow in flight. A start request for the same target (selection,
 * method, flags) within [windowMs] of the last accepted one, while that flow
 * is still running, is folded into it: a double tap, a row change racing a
 * tap or a selection callback firing twice started ~10 connects in 1.5 s.
 * A different target, or one after the window, always starts.
 */
class ColituConnectGate(private val windowMs: Long = WINDOW_MS) {
    private var lastKey: String? = null
    private var lastAt = Long.MIN_VALUE / 2

    /** True when a flow for [key] should start at [nowMs]; [running]: the previous flow is still active. */
    @Synchronized
    fun shouldStart(key: String, nowMs: Long, running: Boolean): Boolean {
        if (running && key == lastKey && nowMs - lastAt < windowMs) return false
        lastKey = key
        lastAt = nowMs
        return true
    }

    companion object {
        const val WINDOW_MS = 300L
    }
}
