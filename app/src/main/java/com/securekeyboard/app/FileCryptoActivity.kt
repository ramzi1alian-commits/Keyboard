package com.securekeyboard.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
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
        private const val CREATE_OUTPUT = 5102
        private const val ADD_CONTACT = 5103
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_file_crypto)
        contactSpinner = findViewById(R.id.file_contact_spinner)
        selectedFileText = findViewById(R.id.file_selected_text)
        statusText = findViewById(R.id.file_status)

        findViewById<Button>(R.id.file_add_contact).setOnClickListener {
            startActivityForResult(Intent(this, ContactPairingActivity::class.java), ADD_CONTACT)
        }
        findViewById<Button>(R.id.file_pick_encrypt).setOnClickListener { chooseInput(1) }
        findViewById<Button>(R.id.file_pick_decrypt).setOnClickListener { chooseInput(2) }
        refreshContacts()
        val preferred = intent.getStringExtra(EXTRA_CONTACT_NAME)
        if (!preferred.isNullOrBlank()) {
            val index = names.indexOf(preferred)
            if (index >= 0) contactSpinner.setSelection(index)
        }
    }

    private fun refreshContacts() {
        names.clear()
        names.addAll(ContactStore.listPairedContactNames(this))
        val labels = if (names.isEmpty()) listOf("لا توجد جهات اتصال آمنة") else names.toList()
        contactSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
    }

    private fun chooseInput(operation: Int) {
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
                val create = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "application/octet-stream"
                    putExtra(Intent.EXTRA_TITLE, if (pendingOperation == 1) "$selectedDisplayName.skf" else "decrypted_$selectedDisplayName")
                }
                startActivityForResult(create, CREATE_OUTPUT)
            }
            return
        }
        if (requestCode == CREATE_OUTPUT && resultCode == Activity.RESULT_OK) {
            val input = selectedInputUri ?: return
            val output = data?.data ?: return
            runFileOperation(input, output, pendingOperation)
        }
    }

    private fun runFileOperation(input: Uri, output: Uri, operation: Int) {
        val pass = SessionKeyStore.get()
        if (pass == null) {
            Toast.makeText(this, R.string.crypto_panel_no_session, Toast.LENGTH_LONG).show()
            return
        }
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
