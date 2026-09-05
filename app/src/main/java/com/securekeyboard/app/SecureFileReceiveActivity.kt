package com.securekeyboard.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.DocumentsContract
import android.view.View
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.io.FileInputStream
import java.util.Arrays

/**
 * Secure receive path for .skf files opened from WhatsApp, Files, or the
 * Android Sharesheet. The encrypted file is never copied into the keyboard's
 * clipboard and is only decrypted after the user selects a paired contact
 * and has an active session passphrase.
 */
class SecureFileReceiveActivity : Activity() {
    companion object {
        private const val PICK_OUTPUT_TREE = 6201
    }

    private lateinit var contactSpinner: Spinner
    private lateinit var statusText: TextView
    private lateinit var selectedText: TextView
    private val names = mutableListOf<String>()
    private var inputUri: android.net.Uri? = null
    private var pendingFilename = "decrypted_file"
    private var pendingTempPath: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        setContentView(R.layout.activity_secure_file_receive)

        contactSpinner = findViewById(R.id.receive_contact_spinner)
        statusText = findViewById(R.id.receive_status)
        selectedText = findViewById(R.id.receive_selected_text)

        findViewById<Button>(R.id.receive_decrypt_btn).setOnClickListener { beginDecrypt() }
        findViewById<Button>(R.id.receive_close_btn).setOnClickListener { finish() }

        inputUri = SecurityIntentValidator.encryptedFileUri(intent)
        if (inputUri == null) {
            statusText.text = "رابط أو ملف غير صالح"
            findViewById<Button>(R.id.receive_decrypt_btn).isEnabled = false
        }
        selectedText.text = queryDisplayName(inputUri) ?: "ملف مشفر"
        refreshContacts()
        refreshSessionStatus()
    }

    private fun refreshContacts() {
        names.clear()
        names.addAll(ContactStore.listPairedContactNames(this))
        val labels = if (names.isEmpty()) listOf("لا توجد جهات اتصال آمنة") else names.toList()
        contactSpinner.adapter = ArrayAdapter(this, R.layout.spinner_item_light_text, labels).also {
            it.setDropDownViewResource(R.layout.spinner_dropdown_item_light_text)
        }
    }

    private fun refreshSessionStatus() {
        statusText.text = when {
            inputUri == null -> "لم يتم العثور على ملف مشفر"
            names.isEmpty() -> "أضف جهة اتصال آمنة أولًا"
            !SessionKeyStore.isActive() -> "فعّل مفتاح الجلسة أولًا من شاشة التشفير"
            else -> "مفتاح الجلسة نشط — متبقي ${SessionKeyStore.remainingMinutes()} دقيقة تقريبًا"
        }
    }

    private fun beginDecrypt() {
        val uri = inputUri ?: run {
            Toast.makeText(this, "لم يتم العثور على الملف المشفر", Toast.LENGTH_LONG).show()
            return
        }
        if (!SessionKeyStore.isActive()) {
            Toast.makeText(this, R.string.crypto_panel_no_session, Toast.LENGTH_LONG).show()
            return
        }
        val contactName = names.getOrNull(contactSpinner.selectedItemPosition) ?: run {
            Toast.makeText(this, "اختر جهة اتصال آمنة", Toast.LENGTH_LONG).show()
            return
        }
        val contactB64 = ContactStore.getPairedContact(this, contactName) ?: run {
            Toast.makeText(this, "جهة الاتصال غير موجودة", Toast.LENGTH_LONG).show()
            return
        }
        val pass = SessionKeyStore.get() ?: run {
            Toast.makeText(this, R.string.crypto_panel_no_session, Toast.LENGTH_LONG).show()
            return
        }

        statusText.text = "جارٍ التحقق وفك تشفير الملف…"
        findViewById<Button>(R.id.receive_decrypt_btn).isEnabled = false
        Thread {
            var temp: java.io.File? = null
            try {
                val publicKey = DeviceIdentity.parseContactPublicKey(contactB64)
                val result = SecureFileCrypto.decryptToTemp(this, uri, publicKey, pass)
                temp = result.first
                pendingFilename = sanitizeFilename(result.second)
                pendingTempPath = temp.absolutePath
                runOnUiThread {
                    statusText.text = "✓ تم التحقق من الملف. اختر مكان حفظ النسخة الأصلية بعد فك التشفير."
                    launchOutputPicker()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    statusText.text = "فشل فك التشفير — المفتاح غير صحيح أو الملف تالف."
                    Toast.makeText(this, statusText.text, Toast.LENGTH_LONG).show()
                    findViewById<Button>(R.id.receive_decrypt_btn).isEnabled = true
                }
            } finally {
                Arrays.fill(pass, '\u0000')
            }
        }.start()
    }

    private fun launchOutputPicker() {
        val tree = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        try {
            startActivityForResult(tree, PICK_OUTPUT_TREE)
        } catch (e: Exception) {
            cleanupTemp()
            statusText.text = "تعذر فتح مدير الملفات"
            findViewById<Button>(R.id.receive_decrypt_btn).isEnabled = true
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != PICK_OUTPUT_TREE) return
        if (resultCode != Activity.RESULT_OK || data?.data == null) {
            cleanupTemp()
            statusText.text = "تم إلغاء الحفظ"
            findViewById<Button>(R.id.receive_decrypt_btn).isEnabled = true
            return
        }
        val treeUri = data.data!!
        try {
            contentResolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (_: Exception) { }

        val tempFile = pendingTempPath?.let { java.io.File(it) }
        if (tempFile == null || !tempFile.exists()) {
            statusText.text = "انتهت جلسة الملف المؤقتة، أعد المحاولة"
            findViewById<Button>(R.id.receive_decrypt_btn).isEnabled = true
            return
        }

        Thread {
            try {
                val parent = DocumentsContract.buildDocumentUriUsingTree(
                    treeUri,
                    DocumentsContract.getTreeDocumentId(treeUri)
                )
                val output = DocumentsContract.createDocument(contentResolver, parent, "*/*", pendingFilename)
                    ?: throw IllegalStateException("cannot create output")
                contentResolver.openOutputStream(output).use { out ->
                    require(out != null) { "cannot open destination" }
                    FileInputStream(tempFile).use { src ->
                        val buffer = ByteArray(64 * 1024)
                        try {
                            while (true) {
                                val n = src.read(buffer)
                                if (n < 0) break
                                out.write(buffer, 0, n)
                            }
                            out.flush()
                        } finally { Arrays.fill(buffer, 0) }
                    }
                }
                runOnUiThread {
                    statusText.text = "✓ تم فك التشفير وحفظ الملف بنجاح"
                    Toast.makeText(this, "تم حفظ الملف: $pendingFilename", Toast.LENGTH_LONG).show()
                    findViewById<Button>(R.id.receive_decrypt_btn).isEnabled = true
                }
            } catch (e: Exception) {
                runOnUiThread {
                    statusText.text = "تعذر حفظ الملف في المجلد المحدد"
                    Toast.makeText(this, statusText.text, Toast.LENGTH_LONG).show()
                    findViewById<Button>(R.id.receive_decrypt_btn).isEnabled = true
                }
            } finally {
                cleanupTemp()
            }
        }.start()
    }

    private fun cleanupTemp() {
        pendingTempPath?.let { SecureMemory.secureDelete(java.io.File(it)) }
        pendingTempPath = null
    }

    override fun onDestroy() {
        cleanupTemp()
        super.onDestroy()
    }

    private fun queryDisplayName(uri: android.net.Uri?): String? {
        if (uri == null) return null
        contentResolver.query(uri, arrayOf("_display_name"), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val i = c.getColumnIndex("_display_name")
                if (i >= 0) return c.getString(i)
            }
        }
        return null
    }

    private fun sanitizeFilename(value: String): String {
        val cleaned = value.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
        return if (cleaned.isBlank()) "decrypted_file" else cleaned.take(180)
    }
}
