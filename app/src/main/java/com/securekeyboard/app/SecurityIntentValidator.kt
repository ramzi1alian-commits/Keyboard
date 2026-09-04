package com.securekeyboard.app

import android.content.Intent
import android.net.Uri
import android.os.Build

/**
 * Validates external file intents before any file is opened.
 *
 * Security goals:
 * - Accept only content:// and file:// URIs.
 * - Reject null/invalid URIs.
 * - Accept only expected encrypted-file MIME types or .skf extension.
 * - Avoid unsafe nullable Uri access.
 */
object SecurityIntentValidator {

    private const val SKF_EXTENSION = ".skf"

    private val ALLOWED_MIME_TYPES = setOf(
        "application/octet-stream",
        "application/x-secure-file"
    )

    fun extractValidatedUri(intent: Intent?): Uri? {
        if (intent == null) return null

        val uri: Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                ?: intent.data
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
                ?: intent.data
        }

        if (!isAllowedUri(uri)) return null

        // Explicitly establish non-nullability for Kotlin.
        val safeUri = uri ?: return null

        val mimeType = intent.type?.lowercase()
        val path = safeUri.path?.lowercase()

        val extensionAllowed = path?.endsWith(SKF_EXTENSION) == true
        val mimeAllowed = mimeType in ALLOWED_MIME_TYPES

        if (!extensionAllowed && !mimeAllowed) {
            return null
        }

        return safeUri
    }

    private fun isAllowedUri(uri: Uri?): Boolean {
        if (uri == null) return false

        return when (uri.scheme?.lowercase()) {
            "content", "file" -> true
            else -> false
        }
    }
}
