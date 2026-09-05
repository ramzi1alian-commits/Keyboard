package com.securekeyboard.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns

/** Tiny transparent bridge used because an InputMethodService has no ActivityResult API. */
class AttachmentPickerActivity : Activity() {
    companion object {
        const val ACTION_ATTACHMENT_SELECTED = "com.securekeyboard.app.ATTACHMENT_SELECTED"
        const val EXTRA_URI = "uri"
        const val EXTRA_NAME = "name"
        const val EXTRA_MIME = "mime"
        private const val PICK = 7401
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            }, PICK)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PICK && resultCode == RESULT_OK) {
            val uri = data?.data
            if (uri != null) {
                try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
                val name = queryName(uri)
                val mime = contentResolver.getType(uri) ?: "application/octet-stream"
                sendBroadcast(Intent(ACTION_ATTACHMENT_SELECTED).apply {
                    setPackage(packageName)
                    putExtra(EXTRA_URI, uri)
                    putExtra(EXTRA_NAME, name)
                    // The selected file's MIME is only metadata for the picker.
                    // The keyboard encrypts it into an SKF container before
                    // sharing, so the outgoing encrypted file must advertise
                    // application/octet-stream, not the source MIME.
                    putExtra(EXTRA_MIME, "application/octet-stream")
                })
            }
        }
        finish()
    }

    private fun queryName(uri: Uri): String {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) return c.getString(0).orEmpty().ifBlank { "file" }
        }
        return uri.lastPathSegment?.substringAfterLast('/')?.ifBlank { "file" } ?: "file"
    }
}
