package com.securekeyboard.app

import android.content.Intent
import android.net.Uri
import android.os.Build

/**
 * Central validation for externally supplied intents.
 * Exported components must treat every incoming URI/type as hostile input.
 */
object SecurityIntentValidator {
    private const val SKF_EXTENSION = ".skf"
    private const val OCTET_STREAM = "application/octet-stream"

    fun encryptedFileUri(intent: Intent?): Uri? {
        if (intent == null) return null
        val action = intent.action
        if (action != Intent.ACTION_VIEW && action != Intent.ACTION_SEND) return null

        val uri = when (action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_STREAM)
            }
            else -> null
        } ?: intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri

        if (!isAllowedUri(uri)) return null
        val mime = intent.type?.lowercase()?.trim()
        val name = uri.lastPathSegment?.lowercase()?.trim() ?: ""
        // SAF providers legitimately disagree on MIME metadata. Accept an SKF
        // extension or the canonical binary MIME, but never arbitrary types.
        if (mime != null && mime != OCTET_STREAM && !name.endsWith(SKF_EXTENSION)) return null
        return uri
    }

    private fun isAllowedUri(uri: Uri?): Boolean {
        if (uri == null) return false
        return when (uri.scheme?.lowercase()) {
            "content", "file" -> true
            else -> false
        }
    }
}
