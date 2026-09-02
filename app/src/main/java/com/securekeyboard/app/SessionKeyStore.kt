package com.securekeyboard.app

import java.util.Arrays

/**
 * SessionKeyStore
 *
 * Holds ONE passphrase (not a derived AES key - see note below) in a
 * plain in-process static field, for a limited time, so the keyboard's
 * own quick-encrypt/quick-decrypt panel (see SecureInputMethodService)
 * doesn't require re-typing a password for every single message during
 * one conversation.
 *
 * WHAT THIS IS NOT:
 * - NOT written to disk, NOT part of SharedPreferences, NOT backed up.
 *   It exists only as long as this app's process is alive - a swipe-
 *   away from Recents, a device restart, or Android killing the process
 *   under memory pressure all wipe it as a side effect of the process
 *   dying, on top of the explicit timer below.
 * - NOT a derived AES key. CryptoEngine already generates a fresh random
 *   salt for every single message and re-derives the AES key from
 *   (passphrase + that message's salt) via Argon2id - see
 *   CryptoEngine.encrypt/decrypt. Storing the raw passphrase here and
 *   re-deriving per message is what already happens when a user retypes
 *   the same passphrase for message after message on the full Encrypt
 *   screen; this store just remembers that same passphrase for them for
 *   a while instead. It does not reuse a single AES key across messages.
 *
 * The 30-minute default is a balance point, not a security promise by
 * itself - a shorter session is more cautious, a longer one is more
 * convenient. The user can always clear it early from the keyboard's
 * crypto panel or the Encrypt screen.
 */
object SessionKeyStore {

    const val DEFAULT_DURATION_MS = 30 * 60 * 1000L

    @Volatile
    private var passphrase: CharArray? = null

    @Volatile
    private var expiresAtMillis: Long = 0L

    @Synchronized
    fun set(chars: CharArray, durationMs: Long = DEFAULT_DURATION_MS) {
        clear()
        passphrase = chars.copyOf()
        expiresAtMillis = System.currentTimeMillis() + durationMs
    }

    /**
     * Returns a COPY of the passphrase if a session is currently active,
     * or null if none was set or it has expired (expiry is checked here
     * on every call, not on a background timer, so there's nothing
     * running while the keyboard isn't in use). The caller owns the
     * returned array and is responsible for zeroing it after use, same
     * convention as every other passphrase/plaintext CharArray in this
     * app.
     */
    @Synchronized
    fun get(): CharArray? {
        val p = passphrase ?: return null
        if (System.currentTimeMillis() > expiresAtMillis) {
            clear()
            return null
        }
        return p.copyOf()
    }

    @Synchronized
    fun isActive(): Boolean {
        val p = passphrase ?: return false
        if (System.currentTimeMillis() > expiresAtMillis) {
            clear()
            return false
        }
        return true
    }

    @Synchronized
    fun remainingMinutes(): Long {
        if (!isActive()) return 0L
        val remainingMs = expiresAtMillis - System.currentTimeMillis()
        return (remainingMs / 60_000L).coerceAtLeast(1L)
    }

    @Synchronized
    fun clear() {
        passphrase?.let { Arrays.fill(it, ' ') }
        passphrase = null
        expiresAtMillis = 0L
    }
}
