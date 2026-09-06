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

        // Same problem, same fix, as AttachmentPickerActivity's
        // ACTION_ATTACHMENT_SELECTED broadcast (see its own comment): this
        // Activity round-trips through TWO separate DocumentsUI pickers
        // (ACTION_OPEN_DOCUMENT then ACTION_OPEN_DOCUMENT_TREE) before the
        // user gets back to whatever app they were typing in. On some
        // Android versions (reported on both 8 and 14 - not just the 14
        // case the attachment picker comment mentions) that back-navigation
        // does not implicitly re-show the IME, leaving the user with no
        // visible keyboard until they manually tap the field again.
        // Broadcasting on onStop() lets the IME ask the system to show
        // itself again right after this Activity (and, transitively, the
        // whole file-crypto flow launched from it) actually leaves the
        // screen - see SecureInputMethodService's handling of this action.
        const val ACTION_FILE_CRYPTO_RETURNED = "com.securekeyboard.app.FILE_CRYPTO_RETURNED"

        // FIXED: "decrypt says the key/file is corrupted even though the
        // file is valid, seems to confuse contact keys with the public
        // key" - this is not actually a key-selection bug. SKF2 files are
        // ECDHE sealed-box encryption: a file encrypted "to" contact X can
        // ONLY ever be decrypted using X's own private key, on X's own
        // device - not by the sender, and not by re-selecting the same
        // contact on the SAME device that encrypted it. There was
        // previously no way to encrypt something you could later decrypt
        // yourself (e.g. to protect a personal file at rest, with no
        // second device/contact involved) - every such attempt correctly
        // fails the GCM auth check, which surfaces as this exact "corrupted"
        // message, because it IS a genuinely different, non-matching key -
        // just not for the reason the message implied.
        //
        // This pseudo-contact fixes the actual gap: it lets a user target
        // their OWN device identity, so encrypt-then-decrypt on one device
        // is possible when that is genuinely what they want. Self-ECDH
        // (this device's own private key agreement with its own public
        // key) is a well-defined, deterministic Diffie-Hellman operation -
        // not a special case requiring different code, just a normal
        // agreement where the peer happens to be yourself.
        const val SELF_LABEL = "🔒 نفسي (هذا الجهاز فقط)"
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
            // +1: index 0 in the spinner is always the self pseudo-entry
            // (see refreshContacts) - real contacts start at 1.
            if (index >= 0) contactSpinner.setSelection(index + 1)
        }
    }

    override fun onResume() {
        super.onResume()
        refreshContacts()
        refreshSessionStatus()
    }

    // Fires every time this Activity leaves the foreground - including the
    // transient dips while the OPEN_DOCUMENT / OPEN_DOCUMENT_TREE pickers are
    // on top, not just the final exit back to the host app. That is
    // deliberate: requestShowSelf(SHOW_IMPLICIT) on the receiving end is a
    // no-op when there is no real field to show a keyboard for (i.e. while a
    // system picker is genuinely on top), so the extra broadcasts during the
    // picker round-trips cost nothing, while the one that matters - the
    // final onStop as the user returns to their original app - now actually
    // asks the IME to reappear instead of leaving them without a keyboard.
    override fun onStop() {
        super.onStop()
        sendBroadcast(Intent(ACTION_FILE_CRYPTO_RETURNED).setPackage(packageName))
    }

    private fun refreshContacts() {
        names.clear()
        names.addAll(ContactStore.listPairedContactNames(this))
        // Self is always selectable at position 0, regardless of whether
        // any real contact is paired yet - see SELF_LABEL's doc comment.
        val labels = listOf(SELF_LABEL) + names
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
        // Self (position 0) is always available, so there is no longer a
        // reason to block on an empty contact list here.
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
            val safeInputName = sanitizeFilename(selectedDisplayName)
            val name = if (pendingOperation == 1) "$safeInputName.skf" else "decrypted_$safeInputName"
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
        // Position 0 is always the self pseudo-entry (see refreshContacts);
        // real paired contacts start at position 1, i.e. names[position - 1].
        val selectedPosition = contactSpinner.selectedItemPosition
        val targetLabel: String
        val publicKeyB64: String
        if (selectedPosition <= 0) {
            targetLabel = SELF_LABEL
            publicKeyB64 = DeviceIdentity.myPublicKeyBase64(this)
        } else {
            val contactName = names.getOrNull(selectedPosition - 1)
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
            targetLabel = contactName
            publicKeyB64 = contactB64
        }
        statusText.text = if (operation == 1) "جارٍ تشفير الملف… لا تغلق هذه الشاشة" else "جارٍ فك تشفير الملف… لا تغلق هذه الشاشة"
        findViewById<Button>(R.id.file_pick_encrypt).isEnabled = false
        findViewById<Button>(R.id.file_pick_decrypt).isEnabled = false
        Thread {
            try {
                val publicKey = DeviceIdentity.parseContactPublicKey(publicKeyB64)
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
                    } finally { SecureMemory.secureDelete(temp) }
                    selectedDisplayName = filename
                }
                runOnUiThread {
                    statusText.text = if (operation == 1) "✓ تم تشفير الملف بنجاح" else "✓ تم فك تشفير الملف بنجاح"
                    findViewById<Button>(R.id.file_pick_encrypt).isEnabled = true
                    findViewById<Button>(R.id.file_pick_decrypt).isEnabled = true
                    Toast.makeText(this, statusText.text, Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                // FIXED: this used to show e.message (or a generic "الملف
                // غير صالح") for every failure alike, which is what read as
                // "confusing contact keys with the public key" - a GCM
                // auth-tag failure and a malformed/wrong-type file were
                // indistinguishable in the UI. They are now split apart:
                // an auth failure names the two REAL possible causes
                // (wrong target selected, or the session passphrase not
                // matching the one used at encryption time) instead of the
                // word "corrupted", which was misleading - the file is
                // usually perfectly intact; it just was not encrypted for
                // whatever is currently selected in the spinner, with
                // whatever passphrase is currently active.
                val message = when {
                    e is javax.crypto.AEADBadTagException ||
                    e.cause is javax.crypto.AEADBadTagException -> {
                        "تعذر فك التشفير: المفتاح المستخدم لا يطابق هذا الملف. تأكد من اختيار \"$targetLabel\" هي نفس الجهة/الجهاز الذي جرى التشفير له، ومن أن عبارة المرور الحالية للجلسة هي نفسها التي استُخدمت عند التشفير."
                    }
                    e.message?.contains("not a SecureKeyboard file") == true -> {
                        "هذا ليس ملفًا مشفّرًا بواسطة هذا التطبيق (لاحقة .skf متوقعة)."
                    }
                    else -> "تعذر تنفيذ العملية: ${e.message ?: "خطأ غير معروف"}"
                }
                runOnUiThread {
                    statusText.text = message
                    findViewById<Button>(R.id.file_pick_encrypt).isEnabled = true
                    findViewById<Button>(R.id.file_pick_decrypt).isEnabled = true
                    Toast.makeText(this, statusText.text, Toast.LENGTH_LONG).show()
                }
            } finally {
                Arrays.fill(pass, '\u0000')
            }
        }.start()
    }

    private fun sanitizeFilename(value: String): String {
        val normalized = value
            .replace('\\', '_')
            .replace('/', '_')
            .replace(Regex("[\\u0000-\\u001F\\u007F]"), "_")
            .trim()
        val safe = normalized.trim('.', ' ')
        return when {
            safe.isBlank() -> "file"
            safe.length > 180 -> safe.take(180)
            else -> safe
        }
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
