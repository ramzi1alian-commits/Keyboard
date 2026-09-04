package com.securekeyboard.app

import android.content.Context
import android.os.Build
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import java.security.KeyFactory
import java.security.KeyStore
import java.security.spec.X509EncodedKeySpec
import javax.crypto.SecretKey

/** Reports the protection level actually provided by Android Keystore. */
object KeySecurityStatus {
    data class Status(val alias: String, val exists: Boolean, val hardwareBacked: Boolean, val strongBoxBacked: Boolean)

    fun localStorageKey(context: Context): Status {
        val alias = "secure_keyboard_local_storage_key_v1"
        return inspectSecretKey(alias)
    }

    private fun inspectSecretKey(alias: String): Status {
        return try {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val key = ks.getKey(alias, null) as? SecretKey
                ?: return Status(alias, false, false, false)
            if (Build.VERSION.SDK_INT >= 23) {
                val factory = KeyFactory.getInstance(key.algorithm, "AndroidKeyStore")
                val info = factory.getKeySpec(key, KeyInfo::class.java)
                val hardware = info.isInsideSecureHardware
                val strongBox = Build.VERSION.SDK_INT >= 31 &&
                    try { info.securityLevel == KeyProperties.SECURITY_LEVEL_STRONGBOX }
                    catch (_: Throwable) { false }
                return Status(alias, true, hardware, strongBox)
            }
            Status(alias, true, false, false)
        } catch (_: Throwable) {
            Status(alias, false, false, false)
        }
    }

}
