package com.securekeyboard.app

import android.app.Activity
import android.content.Intent
import android.provider.DocumentsContract
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Arrays

/** Full-screen file operation launched from the secure keyboard's file button. */
class FileCryptoActivity : AppCompatActivity() {
    private lateinit var contactSpinner: Spinner
    private lateinit var selectedFileText: TextView
    private lateinit var statusText: TextView
    private var selectedInputUri: Uri? = null
    private var selectedDisplayName: String = "file"
    private var pendingOperation: Int = 0 // 1 encrypt, 2 decrypt
    private val names = mutableListOf<String>()

    companion object {
        const val EXTRA_CONTACT_NAME = "contact_name"
        private const val PICK_INPUT = 5101
                private const val ADD_CONTACT = 5103
        private const val CREATE_OUTPUT_TREE = 5104
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        SessionKeyStore.initialize(this)
        super.onCreate(savedInstanceState)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )
        setContentView(R.layout.activity_file_crypto)
        contactSpinner = findViewById(R.id.file_contact_spinner)
        selectedFileText = findViewById(R.id.file_selected_text)
        statusText = findViewById(R.id.file_status)

        findViewById<Button>(R.id.file_add_contact).setOnClickListener {
            startActivityForResult(Intent(this, ContactPairingActivity::class.java), ADD_CONTACT)
        }
        findViewById<Button>(R.id.file_open_encrypt).setOnClickListener {
            startActivity(Intent(this, EncryptActivity::class.java))
        }
        findViewById<Button>(R.id.file_pick_encrypt).setOnClickListener { chooseInput(1) }
        findViewById<Button>(R.id.file_pick_decrypt).setOnClickListener { chooseInput(2) }
        refreshContacts()
        refreshSessionStatus()
        ThemeUtil.tintPrimary(this, findViewById(R.id.file_pick_encrypt))
        ThemeUtil.tintOutline(this, findViewById(R.id.file_add_contact), findViewById(R.id.file_pick_decrypt), findViewById(R.id.file_open_encrypt))
        val preferred = intent.getStringExtra(EXTRA_CONTACT_NAME)
        if (!preferred.isNullOrBlank()) {
            val index = names.indexOf(preferred)
            if (index >= 0) contactSpinner.setSelection(index)
        }
    }

    override fun onResume() {
        super.onResume()
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
        val openButton = findViewById<Button>(R.id.file_open_encrypt)
        if (SessionKeyStore.isActive()) {
            statusText.text = getString(R.string.file_session_active, SessionKeyStore.remainingMinutes())
            statusText.setTextColor(ThemeUtil.accentColor(this))
            openButton.visibility = View.GONE
        } else {
            statusText.text = getString(R.string.file_session_inactive)
            statusText.setTextColor(ThemeUtil.textSecondaryColor(this))
            openButton.visibility = View.VISIBLE
        }
    }

    private fun chooseInput(operation: Int) {
        if (!SessionKeyStore.isActive()) {
            refreshSessionStatus()
            Toast.makeText(this, R.string.crypto_panel_no_session, Toast.LENGTH_LONG).show()
            return
        }
        if (names.isEmpty()) {
            Toast.makeText(this, "أضف جهة اتصال آمنة أولًا", Toast.LENGTH_LONG).show()
            return
        }
        pendingOperation = operation
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        startActivityForResult(intent, PICK_INPUT)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == ADD_CONTACT && resultCode == Activity.RESULT_OK) {
            refreshContacts()
            return
        }
        if (requestCode == PICK_INPUT && resultCode == Activity.RESULT_OK) {
            selectedInputUri = data?.data
            selectedInputUri?.let { uri ->
                selectedDisplayName = queryDisplayName(uri) ?: "file"
                selectedFileText.text = selectedDisplayName
                statusText.text = if (pendingOperation == 1) "اختر مكان حفظ الملف المشفر" else "اختر مكان حفظ الملف بعد فك التشفير"
                launchOutputPicker()
            }
            return
        }
        if (requestCode == CREATE_OUTPUT_TREE && resultCode == Activity.RESULT_OK) {
            val treeUri = data?.data ?: return
            try {
                contentResolver.takePersistableUriPermission(
                    treeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (_: Exception) {
                // Some OEMs do not grant persistable permissions; the current
                // operation can still proceed while this activity is alive.
            }
            val input = selectedInputUri ?: return
            val name = if (pendingOperation == 1) "$selectedDisplayName.skf" else "decrypted_$selectedDisplayName"
            val output = try {
                DocumentsContract.buildDocumentUriUsingTree(
                    treeUri,
                    DocumentsContract.getTreeDocumentId(treeUri)
                )
            } catch (_: Exception) {
                treeUri
            }
            // Prefer a real file URI when the tree provider supports creating one.
            val created = try {
                DocumentsContract.createDocument(contentResolver, output, "*/*", name)
            } catch (_: Exception) { null }
            if (created != null) {
                runFileOperation(input, created, pendingOperation)
            } else {
                statusText.text = "تعذر إنشاء الملف في المجلد المحدد. اختر مجلدًا قابلًا للكتابة مثل التنزيلات أو المستندات."
                Toast.makeText(this, statusText.text, Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * Pick an output folder rather than relying on OEM-specific CREATE_DOCUMENT
     * confirmation buttons. DocumentsUI then exposes its standard folder
     * confirmation action ("Use this folder" / "اختيار"). The app creates the
     * output file itself inside the selected folder.
     */
    private fun launchOutputPicker() {
        val tree = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        try {
            startActivityForResult(tree, CREATE_OUTPUT_TREE)
        } catch (e: Exception) {
            statusText.text = "تعذر فتح مدير الملفات: ${e.message ?: "غير مدعوم على هذا الجهاز"}"
            Toast.makeText(this, statusText.text, Toast.LENGTH_LONG).show()
        }
    }

    private fun runFileOperation(input: Uri, output: Uri, operation: Int) {
        val pass = SessionKeyStore.get()
        if (pass == null) {
            refreshSessionStatus()
            Toast.makeText(this, R.string.crypto_panel_no_session, Toast.LENGTH_LONG).show()
            return
        }
        refreshSessionStatus()
        val contactName = names.getOrNull(contactSpinner.selectedItemPosition)
        if (contactName == null) {
            Toast.makeText(this, "اختر جهة اتصال آمنة", Toast.LENGTH_LONG).show()
            Arrays.fill(pass, '\u0000')
            return
        }
        val contactB64 = ContactStore.getPairedContact(this, contactName)
        if (contactB64 == null) {
            Toast.makeText(this, "جهة الاتصال غير موجودة", Toast.LENGTH_LONG).show()
            Arrays.fill(pass, '\u0000')
            return
        }
        statusText.text = if (operation == 1) "جارٍ تشفير الملف…" else "جارٍ فك تشفير الملف…"
        Thread {
            try {
                val publicKey = DeviceIdentity.parseContactPublicKey(contactB64)
                if (operation == 1) {
                    SecureFileCrypto.encrypt(this, input, output, publicKey, pass, selectedDisplayName)
                } else {
                    val (temp, filename) = SecureFileCrypto.decryptToTemp(this, input, publicKey, pass)
                    try {
                        contentResolver.openOutputStream(output).use { out ->
                            require(out != null) { "cannot open destination" }
                            FileInputStream(temp).use { src ->
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
                    } finally { temp.delete() }
                    selectedDisplayName = filename
                }
                runOnUiThread {
                    statusText.text = if (operation == 1) "✓ تم تشفير الملف بنجاح" else "✓ تم فك تشفير الملف بنجاح"
                    Toast.makeText(this, statusText.text, Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    statusText.text = "تعذر تنفيذ العملية: ${e.message ?: "الملف غير صالح"}"
                    Toast.makeText(this, statusText.text, Toast.LENGTH_LONG).show()
                }
            } finally {
                Arrays.fill(pass, '\u0000')
            }
        }.start()
    }

    private fun queryDisplayName(uri: Uri): String? {
        contentResolver.query(uri, arrayOf("_display_name"), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val i = c.getColumnIndex("_display_name")
                if (i >= 0) return c.getString(i)
            }
        }
        return null
    }
}
