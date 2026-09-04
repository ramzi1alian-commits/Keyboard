package com.securekeyboard.app

import android.content.Intent
import android.net.Uri
import android.os.Build

/**
 * Central validation for externally supplied intents.
 *
 * Exported components must treat every incoming URI/type as hostile input.
 * Only content:// and file:// URIs are accepted.
 * SKF files are accepted by extension or canonical binary MIME.
 */
object SecurityIntentValidator {

    private const val SKF_EXTENSION = ".skf"
    private const val OCTET_STREAM = "application/octet-stream"

    /**
     * Extract and validate an encrypted SKF file URI from an external Intent.
     *
     * Supported actions:
     * - ACTION_VIEW
     * - ACTION_SEND
     *
     * Supported URI sources:
     * - Intent.data
     * - Intent.EXTRA_STREAM
     * - first ClipData item as fallback
     */
    fun encryptedFileUri(intent: Intent?): Uri? {
        if (intent == null) return null

        val action = intent.action

        if (action != Intent.ACTION_VIEW &&
            action != Intent.ACTION_SEND
        ) {
            return null
        }

        val uri: Uri? = when (action) {

            Intent.ACTION_VIEW -> {
                intent.data
            }

            Intent.ACTION_SEND -> {
                if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(
                        Intent.EXTRA_STREAM,
                        Uri::class.java
                    )
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra<Uri>(
                        Intent.EXTRA_STREAM
                    )
                }
            }

            else -> null
        } ?: intent.clipData
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.uri

        if (!isAllowedUri(uri)) {
            return null
        }

        val safeUri = uri ?: return null

        val mime = intent.type
            ?.lowercase()
            ?.trim()

        val name = safeUri.lastPathSegment
            ?.lowercase()
            ?.trim()
            ?: ""

        val extensionAllowed = name.endsWith(SKF_EXTENSION)

        val mimeAllowed = mime == null || mime == OCTET_STREAM

        /*
         * Accept:
         *   1. files ending with .skf
         *   2. application/octet-stream
         *
         * Reject arbitrary MIME types unless the filename proves
         * that the selected object is an SKF encrypted file.
         */
        if (!extensionAllowed && !mimeAllowed) {
            return null
        }

        return safeUri
    }

    /**
     * Validate the URI scheme.
     *
     * Only Android content providers and file URIs are accepted.
     * Network schemes such as http://, https://, ftp://, etc. are rejected.
     */
    private fun isAllowedUri(uri: Uri?): Boolean {
        if (uri == null) {
            return false
        }

        return when (uri.scheme?.lowercase()) {
            "content",
            "file" -> true

            else -> false
        }
    }
}
