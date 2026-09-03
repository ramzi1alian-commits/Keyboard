package com.securekeyboard.app

import android.os.SystemClock
import java.util.Arrays

/**
 * Short-lived, in-process passphrase session used only to avoid retyping the
 * same passphrase for the keyboard quick-crypto actions. It is NEVER persisted.
 *
 * The timeout uses elapsedRealtime() rather than wall-clock time, so changing
 * the device clock cannot extend or shorten the session. The caller receives a
 * defensive copy and must clear it after use.
 */
object SessionKeyStore {

    const val DEFAULT_DURATION_MS = 30 * 60 * 1000L
    private const val MAX_DURATION_MS = 24 * 60 * 60 * 1000L

    @Volatile
    private var passphrase: CharArray? = null

    @Volatile
    private var expiresAtElapsedMs: Long = 0L

    @Synchronized
    fun set(chars: CharArray, durationMs: Long = DEFAULT_DURATION_MS) {
        val safeDuration = durationMs.coerceIn(1L, MAX_DURATION_MS)
        clear()
        passphrase = chars.copyOf()
        expiresAtElapsedMs = SystemClock.elapsedRealtime() + safeDuration
    }

    @Synchronized
    fun get(): CharArray? {
        val p = passphrase ?: return null
        if (SystemClock.elapsedRealtime() >= expiresAtElapsedMs) {
            clear()
            return null
        }
        return p.copyOf()
    }

    @Synchronized
    fun isActive(): Boolean {
        val p = passphrase ?: return false
        if (p.isEmpty() || SystemClock.elapsedRealtime() >= expiresAtElapsedMs) {
            clear()
            return false
        }
        return true
    }

    @Synchronized
    fun remainingMinutes(): Long {
        if (!isActive()) return 0L
        val remainingMs = expiresAtElapsedMs - SystemClock.elapsedRealtime()
        return ((remainingMs + 59_999L) / 60_000L).coerceAtLeast(1L)
    }

    @Synchronized
    fun clear() {
        passphrase?.let { Arrays.fill(it, '\u0000') }
        passphrase = null
        expiresAtElapsedMs = 0L
    }
}
