package com.nwcring.app.lock

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Decides whether the app is locked. It starts locked, locks when told the app left the
 * screen, and locks itself after [idleLimitMs] without a touch. [nowMs] must be a clock
 * that cannot be changed by the user (time since boot).
 */
class LockController(
    private val nowMs: () -> Long,
    private val idleLimitMs: Long = IDLE_LIMIT_MS,
) {
    private val _locked = MutableStateFlow(true)
    val locked: StateFlow<Boolean> = _locked

    @Volatile
    private var lastTouchMs = 0L

    fun unlocked() {
        lastTouchMs = nowMs()
        _locked.value = false
    }

    fun lock() {
        _locked.value = true
    }

    fun touched() {
        lastTouchMs = nowMs()
    }

    /** Called about once a second while the app is on screen. */
    fun tick() {
        if (!_locked.value && nowMs() - lastTouchMs >= idleLimitMs) lock()
    }

    companion object {
        const val IDLE_LIMIT_MS = 60_000L
    }
}
