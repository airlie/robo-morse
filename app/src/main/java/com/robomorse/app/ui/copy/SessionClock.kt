package com.robomorse.app.ui.copy

/** Active practice time: runs while practising, stops while paused. */
internal class SessionClock {
    var startedAt = 0L
        private set
    private var activeMs = 0L
    private var runningSince: Long? = null

    val started: Boolean get() = startedAt != 0L

    fun start() {
        startedAt = System.currentTimeMillis()
        runningSince = startedAt
    }

    fun pause() {
        runningSince?.let { activeMs += System.currentTimeMillis() - it }
        runningSince = null
    }

    fun resume() {
        if (runningSince == null) runningSince = System.currentTimeMillis()
    }

    fun activeNow(): Long = activeMs + (runningSince?.let { System.currentTimeMillis() - it } ?: 0L)

    fun reset() {
        startedAt = 0L
        activeMs = 0L
        runningSince = null
    }
}
