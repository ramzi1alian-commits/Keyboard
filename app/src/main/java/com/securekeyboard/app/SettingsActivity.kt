package com.securekeyboard.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.switchmaterial.SwitchMaterial
import java.io.BufferedReader
import java.io.InputStreamReader

class SettingsActivity : AppCompatActivity() {

    // Storage Access Framework launchers - the user picks the file's
    // name/location (export) or which file to read (import) via
    // Android's own system picker UI. No permission of any kind is
    // requested for this; SAF grants access only to the single file the
    // user themselves selects in that dialog.
    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/tab-separated-values")
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        try {
            contentResolver.openOutputStream(uri)?.use { out ->
                out.write(LearnedDictionary.exportText().toByteArray(Charsets.UTF_8))
            }
            Toast.makeText(this, R.string.export_success_toast, Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
            Toast.makeText(this, R.string.export_fail_toast, Toast.LENGTH_SHORT).show()
        }
    }

    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        try {
            val text = contentResolver.openInputStream(uri)?.use { input ->
                BufferedReader(InputStreamReader(input, Charsets.UTF_8)).readText()
            }
            val imported = if (text != null) LearnedDictionary.importText(this, text) else 0
            if (imported > 0) {
                Toast.makeText(this, getString(R.string.import_success_toast, imported), Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, R.string.import_fail_toast, Toast.LENGTH_SHORT).show()
            }
        } catch (_: Exception) {
            Toast.makeText(this, R.string.import_fail_toast, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // This screen can show sensitive setup info, so block screenshots here too.
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )

        setContentView(R.layout.activity_settings)

        findViewById<android.view.View>(R.id.btnEnable).setOnClickListener {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }

        findViewById<android.view.View>(R.id.btnSwitch).setOnClickListener {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showInputMethodPicker()
        }

        findViewById<android.view.View>(R.id.btnEncrypt).setOnClickListener {
            startActivity(Intent(this, EncryptActivity::class.java))
        }

        findViewById<android.view.View>(R.id.btnThemeSettings).setOnClickListener {
            startActivity(Intent(this, ThemeSettingsActivity::class.java))
        }

        findViewById<android.view.View>(R.id.btnAbout).setOnClickListener {
            startActivity(Intent(this, AboutActivity::class.java))
        }

        findViewById<android.view.View>(R.id.btnClearLearned).setOnClickListener {
            // Immediate, user-triggered wipe of the personal learned-word
            // dictionary (see LearnedDictionary.kt) - both the in-memory
            // map and the on-disk file. Does not touch the static bundled
            // dictionary (WordDictionary), which was never user data to
            // begin with.
            LearnedDictionary.clear(this)
            Toast.makeText(this, R.string.learned_cleared_toast, Toast.LENGTH_SHORT).show()
        }

        findViewById<android.view.View>(R.id.btnClearPhrases).setOnClickListener {
            // Same idea as btnClearLearned, but for whole saved sentences
            // (see PhraseDictionary.kt) - a separate wipe since it's a
            // separate, bigger privacy trade-off from single-word
            // learning, and a user may want to clear one without the
            // other.
            PhraseDictionary.clear(this)
            Toast.makeText(this, R.string.phrases_cleared_toast, Toast.LENGTH_SHORT).show()
        }

        val autocorrectSwitch = findViewById<SwitchMaterial>(R.id.switchAutocorrect)
        autocorrectSwitch.isChecked = Prefs.autocorrectEnabled(this)
        autocorrectSwitch.setOnCheckedChangeListener { _, checked ->
            Prefs.setAutocorrectEnabled(this, checked)
        }

        findViewById<android.view.View>(R.id.btnExportLearned).setOnClickListener {
            exportLauncher.launch("learned_words_backup.tsv")
        }

        findViewById<android.view.View>(R.id.btnImportLearned).setOnClickListener {
            importLauncher.launch(arrayOf("text/*", "text/tab-separated-values", "application/octet-stream"))
        }

        applyCurrentTheme()
    }

    override fun onResume() {
        super.onResume()
        // Re-apply (without a full recreate, to avoid a recreate/onResume
        // loop) in case the user changed the accent/font on the theme
        // screen and came back here - onCreate alone would miss that.
        applyCurrentTheme()
    }

    private fun applyCurrentTheme() {
        ThemeUtil.tintPrimary(
            this,
            findViewById(R.id.btnEnable),
            findViewById(R.id.btnEncrypt)
        )
        ThemeUtil.tintOutline(
            this,
            findViewById(R.id.btnSwitch),
            findViewById(R.id.btnThemeSettings),
            findViewById(R.id.btnClearLearned),
            findViewById(R.id.btnClearPhrases),
            findViewById(R.id.btnExportLearned),
            findViewById(R.id.btnImportLearned),
            findViewById(R.id.btnAbout)
        )
        Fonts.applyToTree(findViewById(android.R.id.content), Fonts.currentTypeface(this))
    }
}
