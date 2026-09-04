package com.securekeyboard.app

import android.content.Context
import android.os.SystemClock

/**
 * Short-lived session passphrase.
 *
 * The passphrase is encrypted at rest with the app's Android-Keystore-backed
 * LocalStorageCrypto key so leaving the encryption screen / keyboard does not
 * force the user to enter it again. Only the encrypted blob and an expiry
 * timestamp are persisted; plaintext is kept in memory only while active.
 */
object SessionKeyStore {
    const val DEFAULT_DURATION_MS = 30 * 60 * 1000L
    private const val MAX_DURATION_MS = 24 * 60 * 60 * 1000L
    private const val PREFS = "secure_session_key_v2"
    private const val BLOB = "encrypted_passphrase"
    private const val EXPIRES_WALL = "expires_wall_ms"

    @Volatile private var passphrase: CharArray? = null
    @Volatile private var expiresAtElapsedMs: Long = 0L
    @Volatile private var expiresAtWallMs: Long = 0L
    @Volatile private var initialized = false
    private var appContext: Context? = null

    @Synchronized
    fun initialize(context: Context) {
        if (initialized) return
        appContext = context.applicationContext
        initialized = true
        restorePersisted()
    }

    @Synchronized
    private fun restorePersisted() {
        val ctx = appContext ?: return
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val expiresWall = prefs.getLong(EXPIRES_WALL, 0L)
        val encrypted = prefs.getString(BLOB, null)
        if (expiresWall <= System.currentTimeMillis() || encrypted.isNullOrEmpty()) {
            prefs.edit().remove(BLOB).remove(EXPIRES_WALL).apply()
            return
        }
        try {
            val encoded = android.util.Base64.decode(encrypted, android.util.Base64.NO_WRAP)
            val plain = LocalStorageCrypto.decrypt(encoded) ?: return
            try {
                val text = String(plain, Charsets.UTF_8)
                if (text.isNotEmpty()) {
                    passphrase = text.toCharArray()
                    expiresAtWallMs = expiresWall
                    expiresAtElapsedMs = SystemClock.elapsedRealtime() +
                        (expiresWall - System.currentTimeMillis()).coerceAtLeast(1L)
                }
            } finally {
                SecureMemory.wipe(plain)
            }
        } catch (_: Exception) {
            prefs.edit().remove(BLOB).remove(EXPIRES_WALL).apply()
        }
    }

    @Synchronized
    fun set(chars: CharArray, durationMs: Long = DEFAULT_DURATION_MS) {
        require(chars.isNotEmpty()) { "passphrase is empty" }
        val safeDuration = durationMs.coerceIn(1L, MAX_DURATION_MS)
        clear()
        passphrase = chars.copyOf()
        expiresAtElapsedMs = SystemClock.elapsedRealtime() + safeDuration
        expiresAtWallMs = System.currentTimeMillis() + safeDuration
        persist()
    }

    @Synchronized
    private fun persist() {
        val ctx = appContext ?: return
        val p = passphrase ?: return
        try {
            val plain = String(p).toByteArray(Charsets.UTF_8)
            try {
                val encrypted = LocalStorageCrypto.encrypt(plain)
                val b64 = android.util.Base64.encodeToString(encrypted, android.util.Base64.NO_WRAP)
                ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString(BLOB, b64)
                    .putLong(EXPIRES_WALL, expiresAtWallMs)
                    .apply()
                SecureMemory.wipe(encrypted)
            } finally {
                SecureMemory.wipe(plain)
            }
        } catch (_: Exception) {
            // If persistence fails, keep the secure in-memory session alive.
        }
    }

    @Synchronized
    fun get(): CharArray? {
        if (!initialized) return null
        val p = passphrase ?: return null
        if (SystemClock.elapsedRealtime() >= expiresAtElapsedMs ||
            System.currentTimeMillis() >= expiresAtWallMs) {
            clear()
            return null
        }
        return p.copyOf()
    }

    @Synchronized
    fun isActive(): Boolean = get()?.also { SecureMemory.wipe(it) } != null

    @Synchronized
    fun remainingMinutes(): Long {
        if (passphrase == null) return 0L
        val remainingMs = minOf(
            expiresAtElapsedMs - SystemClock.elapsedRealtime(),
            expiresAtWallMs - System.currentTimeMillis()
        )
        if (remainingMs <= 0L) {
            clear()
            return 0L
        }
        return ((remainingMs + 59_999L) / 60_000L).coerceAtLeast(1L)
    }

    @Synchronized
    fun clear() {
        passphrase?.let { SecureMemory.wipe(it) }
        passphrase = null
        expiresAtElapsedMs = 0L
        expiresAtWallMs = 0L
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()
            ?.remove(BLOB)?.remove(EXPIRES_WALL)?.apply()
    }
}
