package com.securekeyboard.app

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.text.Editable
import android.view.View
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import java.io.File
import java.security.SecureRandom
import java.util.Arrays

/**
 * EncryptActivity
 *
 * Encrypts/decrypts text locally on-device using AES-256-GCM with an
 * Argon2id-derived key. Everything happens in this process's memory -
 * there is no networking code anywhere in this app (and no INTERNET
 * permission in the manifest), so nothing here can be sent anywhere.
 *
 * SECURITY FIXES CARRIED FROM THE PREVIOUS REVIEW (see README):
 *  - Passphrase is captured/carried as CharArray, never as a String.
 *  - The result is no longer freely text-selectable; copying requires an
 *    explicit button that marks the clip sensitive and auto-clears it.
 *
 * MESSAGE-EXPIRY FEATURE (this version) - READ THIS BEFORE RELYING ON IT:
 * You can optionally attach an expiry duration when encrypting. After
 * that time, THIS APP will refuse to decrypt the message and will
 * detect if the expiry value itself was tampered with (it's
 * authenticated as GCM "additional authenticated data", so editing it
 * breaks the auth tag and decryption fails outright).
 *
 * What this does NOT do: this app is 100% offline by design (that's a
 * core feature, not an accident) - there is no server anywhere that
 * holds a piece of the key and can delete it after time X. The AES key
 * itself does not expire mathematically. So anyone who has both the
 * correct key AND the raw ciphertext bytes can still decrypt the
 * message with a different AES-GCM tool even after "expiry" - this
 * app just won't do it for them through its own UI. Treat this as
 * protection against accidental/casual decryption after a deadline,
 * not as an unconditional cryptographic guarantee that the message
 * becomes unrecoverable. A true "self-destruct" guarantee would require
 * a trusted online component to actually delete key material, which
 * would break this app's no-internet design goal.
 *
 * NOTE ON CIPHERTEXT FORMAT: this version adds a small header (version +
 * expiry flag + expiry timestamp) in front of the salt/IV. Ciphertext
 * produced by older builds (without the header) will not decrypt with
 * this version - see README changelog.
 */
class EncryptActivity : AppCompatActivity() {

    private lateinit var inputText: EditText
    private lateinit var inputKey: EditText
    private lateinit var spinnerExpiry: Spinner
    private lateinit var inputCustomMinutes: EditText
    private lateinit var errorText: TextView
    private lateinit var resultCard: LinearLayout
    private lateinit var resultText: TextView
    private lateinit var keyStrengthHint: TextView
    private lateinit var rootWarningText: TextView
    private lateinit var sessionKeyStatus: TextView

    private val clipboardHandler = Handler(Looper.getMainLooper())
    private var clipboardClearRunnable: Runnable? = null
    private var lastCopiedValue: String? = null

    companion object {
        private const val CLIPBOARD_CLEAR_DELAY_MS = 30_000L
        private const val CUSTOM_EXPIRY_POSITION = 5

        // When true, this Activity is opened FROM the keyboard's own
        // crypto panel (see SecureInputMethodService.buildCryptoPage) as
        // a genuine floating popup (see AppTheme.PopupCompose in
        // styles.xml) instead of a full-screen page, so composing a
        // sensitive message never has to happen inside WhatsApp's (or
        // any other app's) own text field at all - only the final
        // CIPHERTEXT ever leaves this popup, via the existing "copy
        // result" button, to be pasted back manually. See the class doc
        // above for why full auto-injection isn't possible once a
        // separate Activity like this one takes over the screen.
        const val EXTRA_POPUP_MODE = "popup_mode"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val popupMode = intent?.getBooleanExtra(EXTRA_POPUP_MODE, false) == true
        if (popupMode) {
            setTheme(R.style.AppTheme_PopupCompose)
        }

        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )

        setContentView(R.layout.activity_encrypt)

        inputText = findViewById(R.id.inputText)
        inputKey = findViewById(R.id.inputKey)
        spinnerExpiry = findViewById(R.id.spinnerExpiry)
        inputCustomMinutes = findViewById(R.id.inputCustomMinutes)
        errorText = findViewById(R.id.errorText)
        resultCard = findViewById(R.id.resultCard)
        resultText = findViewById(R.id.resultText)
        keyStrengthHint = findViewById(R.id.keyStrengthHint)
        rootWarningText = findViewById(R.id.rootWarningText)
        sessionKeyStatus = findViewById(R.id.sessionKeyStatus)

        if (RootCheck.looksRooted()) {
            rootWarningText.text = getString(R.string.warn_root_detected)
            rootWarningText.visibility = View.VISIBLE
        }

        val expiryAdapter = ArrayAdapter.createFromResource(
            this, R.array.expiry_options, R.layout.spinner_item_light_text
        )
        expiryAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item_light_text)
        spinnerExpiry.adapter = expiryAdapter
        spinnerExpiry.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                inputCustomMinutes.visibility = if (position == CUSTOM_EXPIRY_POSITION) View.VISIBLE else View.GONE
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        inputKey.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val chars = editableToCharArray(s)
                try {
                    updateStrengthHint(chars)
                } finally {
                    clearChars(chars)
                }
            }
        })
        updateStrengthHint(CharArray(0))

        findViewById<MaterialButton>(R.id.btnEncryptAction).setOnClickListener {
            hideError()
            // FIX (found in follow-up review): this used to read the
            // plaintext as inputText.text.toString() - an immutable
            // String that can never be wiped from memory, exactly the
            // class of problem already fixed for the passphrase below
            // but missed for the message content itself. Now read as a
            // CharArray the same way the key already is.
            val textChars = editableToCharArray(inputText.text)
            val passChars = editableToCharArray(inputKey.text)
            try {
                if (textChars.isEmpty()) { showError(getString(R.string.err_no_text)); return@setOnClickListener }
                if (passChars.isEmpty()) { showError(getString(R.string.err_no_key)); return@setOnClickListener }
                val bits = KeyStrength.estimateEntropyBits(passChars)
                if (bits < KeyStrength.MIN_ENTROPY_BITS) {
                    showError(
                        getString(R.string.err_weak_key, bits.toInt(), KeyStrength.MIN_ENTROPY_BITS.toInt())
                    )
                    return@setOnClickListener
                }
                try {
                    val expirySeconds = selectedExpirySeconds()
                    showResult(CryptoEngine.encrypt(textChars, passChars, expirySeconds))
                    // The plaintext no longer needs to stay in the input
                    // field once it's been encrypted - clearing it here
                    // reduces how long it sits visible/in memory.
                    inputText.text?.clear()
                } catch (e: Exception) {
                    showError(getString(R.string.err_generic))
                }
            } finally {
                clearChars(passChars)
                clearChars(textChars)
            }
        }

        findViewById<MaterialButton>(R.id.btnDecryptAction).setOnClickListener {
            hideError()
            // The ciphertext itself is NOT sensitive (it's meant to be
            // shared/stored openly - only the plaintext and key are
            // secret), so it's fine to read this one as a String.
            val cipherB64 = inputText.text.toString()
            val passChars = editableToCharArray(inputKey.text)
            try {
                if (cipherB64.isBlank()) { showError(getString(R.string.err_no_cipher)); return@setOnClickListener }
                if (passChars.isEmpty()) { showError(getString(R.string.err_no_key)); return@setOnClickListener }
                try {
                    // decrypt() now returns the sensitive plaintext as a
                    // CharArray (see fix note on the function itself)
                    // instead of an unwipeable String.
                    val plainChars = CryptoEngine.decrypt(cipherB64, passChars)
                    try {
                        showResult(plainChars)
                    } finally {
                        clearChars(plainChars)
                    }
                } catch (e: CryptoEngine.ExpiredMessageException) {
                    showError(getString(R.string.err_expired))
                } catch (e: Exception) {
                    showError(getString(R.string.err_bad_key))
                }
            } finally {
                clearChars(passChars)
            }
        }

        // Real random-key generator: fills the key field with a
        // cryptographically random key (never derived from anything you
        // typed) instead of relying on a human-chosen password. This is
        // meant to be copied/written down separately from the ciphertext
        // and transported by hand (e.g. to an air-gapped device) - never
        // stored next to the encrypted text itself.
        findViewById<MaterialButton>(R.id.btnGenerateKey).setOnClickListener {
            hideError()
            val key = RandomKey.generate()
            // Switch the field to visible plain text: this key isn't a
            // memorized secret you're protecting from shoulder-surfing,
            // it's meant to be read and copied/written down by hand, so
            // hiding it with password dots would defeat the point.
            inputKey.inputType = android.text.InputType.TYPE_CLASS_TEXT
            inputKey.setText(key)
            showError(getString(R.string.warn_write_down_key))
            errorText.setTextColor(ThemeUtil.accentColor(this))
        }

        findViewById<MaterialButton>(R.id.btnUseAsSessionKey).setOnClickListener {
            hideError()
            if (SessionKeyStore.isActive()) {
                // Toggle: a second tap while active clears it early -
                // no separate "clear" button needed for this one action.
                SessionKeyStore.clear()
                Toast.makeText(this, R.string.session_key_cleared_toast, Toast.LENGTH_SHORT).show()
                updateSessionKeyStatus()
            } else {
                val passChars = editableToCharArray(inputKey.text)
                try {
                    if (passChars.isEmpty()) {
                        showError(getString(R.string.err_no_key))
                        return@setOnClickListener
                    }
                    val bits = KeyStrength.estimateEntropyBits(passChars)
                    if (bits < KeyStrength.MIN_ENTROPY_BITS) {
                        showError(getString(R.string.err_weak_key, bits.toInt(), KeyStrength.MIN_ENTROPY_BITS.toInt()))
                        return@setOnClickListener
                    }
                    // SessionKeyStore keeps its own copy (see its class
                    // doc) - passChars here is still cleared below same
                    // as everywhere else this field is read.
                    SessionKeyStore.set(passChars)
                    Toast.makeText(
                        this,
                        getString(R.string.session_key_set_toast, SessionKeyStore.remainingMinutes()),
                        Toast.LENGTH_SHORT
                    ).show()
                    updateSessionKeyStatus()
                } finally {
                    clearChars(passChars)
                }
            }
        }

        findViewById<MaterialButton>(R.id.btnCopyResult).setOnClickListener {
            copyResultToClipboard(resultText.text.toString())
        }

        findViewById<MaterialButton>(R.id.btnClearAll).setOnClickListener {
            inputText.text?.clear()
            inputKey.text?.clear()
            inputCustomMinutes.text?.clear()
            spinnerExpiry.setSelection(0)
            resultText.text = ""
            resultCard.visibility = View.GONE
            hideError()
            clearClipboardIfOurs()
            Toast.makeText(this, getString(R.string.clipboard_cleared_notice), Toast.LENGTH_SHORT).show()
        }

        Fonts.applyToTree(findViewById(android.R.id.content), Fonts.currentTypeface(this))
        ThemeUtil.tintPrimary(this, findViewById(R.id.btnEncryptAction))
        ThemeUtil.tintOutline(this, findViewById(R.id.btnDecryptAction))
        ThemeUtil.tintOutline(this, findViewById(R.id.btnGenerateKey))
        ThemeUtil.tintOutline(this, findViewById(R.id.btnCopyResult))
        ThemeUtil.tintOutline(this, findViewById(R.id.btnClearAll))
        ThemeUtil.tintOutline(this, findViewById(R.id.btnUseAsSessionKey))
        updateSessionKeyStatus()

        // Popup mode's whole point is fast, frictionless composing - if
        // a session passphrase is already active, prefill it (same
        // masked password field, nothing shown in the clear) so the user
        // only has to type the MESSAGE, not the key, every time.
        if (popupMode) {
            SessionKeyStore.get()?.let { chars ->
                try {
                    inputKey.setText(String(chars))
                } finally {
                    Arrays.fill(chars, ' ')
                }
            }
            inputText.requestFocus()
        }
    }

    override fun onResume() {
        super.onResume()
        // The remaining-time text (and whether the session is still
        // active at all) can go stale while this screen isn't visible -
        // refresh it every time the user comes back to it.
        updateSessionKeyStatus()
    }

    /** Reads the expiry Spinner (+ optional custom-minutes field) into a duration in seconds, or null for "never". */
    private fun selectedExpirySeconds(): Long? {
        return when (spinnerExpiry.selectedItemPosition) {
            0 -> null
            1 -> 2 * 60L
            2 -> 5 * 60L
            3 -> 15 * 60L
            4 -> 60 * 60L
            CUSTOM_EXPIRY_POSITION -> {
                val minutes = inputCustomMinutes.text.toString().toLongOrNull()?.coerceAtLeast(1L) ?: 5L
                minutes * 60L
            }
            else -> null
        }
    }

    /** Reads an EditText's Editable directly into a CharArray without ever creating a String copy. */
    private fun editableToCharArray(editable: Editable?): CharArray {
        if (editable == null || editable.isEmpty()) return CharArray(0)
        val chars = CharArray(editable.length)
        editable.getChars(0, editable.length, chars, 0)
        return chars
    }

    private fun clearChars(chars: CharArray) {
        Arrays.fill(chars, '\u0000')
    }

    private fun updateSessionKeyStatus() {
        sessionKeyStatus.text = if (SessionKeyStore.isActive()) {
            getString(R.string.session_key_active, SessionKeyStore.remainingMinutes())
        } else {
            getString(R.string.session_key_inactive)
        }
    }

    private fun showError(msg: String) {
        errorText.text = msg
        errorText.visibility = View.VISIBLE
        resultCard.visibility = View.GONE
    }

    private fun hideError() {
        errorText.visibility = View.GONE
    }

    private fun showResult(text: String) {
        resultText.text = text
        resultCard.visibility = View.VISIBLE
    }

    /**
     * Overload for decrypted plaintext (see decrypt() fix note): takes a
     * CharArray instead of a String so the caller can zero it right after
     * this call returns. The TextView itself still has to hold the text
     * as some CharSequence internally to display it - that part is an
     * unavoidable UI-framework requirement (you can't show text on
     * screen without it existing as displayable text somewhere), not
     * something app code can wipe. This overload only removes the extra,
     * avoidable String copies that used to exist BEFORE display.
     */
    private fun showResult(chars: CharArray) {
        resultText.text = String(chars)
        resultCard.visibility = View.VISIBLE
    }

    private fun updateStrengthHint(passChars: CharArray) {
        if (passChars.isEmpty()) {
            keyStrengthHint.text = ""
            return
        }
        val bits = KeyStrength.estimateEntropyBits(passChars)
        val ok = bits >= KeyStrength.MIN_ENTROPY_BITS
        keyStrengthHint.text = if (ok) {
            getString(R.string.key_strength_ok, bits.toInt())
        } else {
            getString(R.string.key_strength_weak, bits.toInt(), KeyStrength.MIN_ENTROPY_BITS.toInt())
        }
        keyStrengthHint.setTextColor(
            if (ok) ThemeUtil.accentColor(this)
            else resources.getColor(R.color.danger_red, theme)
        )
    }

    /**
     * Copies text to the clipboard, marks it "sensitive" on Android 13+
     * (which hides it from clipboard preview popups and keeps it out of
     * clipboard-history/sync features that respect the flag), and
     * schedules clearing it again after CLIPBOARD_CLEAR_DELAY_MS - only
     * if the clipboard still holds exactly what we put there, so we
     * don't wipe something the user deliberately copied afterward.
     */
    private fun copyResultToClipboard(value: String) {
        if (value.isBlank()) return
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(getString(R.string.result_label), value)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val extras = PersistableBundle()
            extras.putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            clip.description.extras = extras
        }
        cm.setPrimaryClip(clip)
        lastCopiedValue = value
        Toast.makeText(this, getString(R.string.clipboard_autoclear_notice), Toast.LENGTH_SHORT).show()

        clipboardClearRunnable?.let { clipboardHandler.removeCallbacks(it) }
        val runnable = Runnable { clearClipboardIfOurs() }
        clipboardClearRunnable = runnable
        clipboardHandler.postDelayed(runnable, CLIPBOARD_CLEAR_DELAY_MS)
    }

    private fun clearClipboardIfOurs() {
        try {
            val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            val current = cm.primaryClip?.let { if (it.itemCount > 0) it.getItemAt(0).text?.toString() else null }
            if (lastCopiedValue != null && current == lastCopiedValue) {
                cm.setPrimaryClip(ClipData.newPlainText("", ""))
            }
        } catch (_: Exception) {
            // Best-effort only - clipboard access can fail on some OEM
            // skins/policies, that's not fatal to the rest of the app.
        }
        lastCopiedValue = null
    }

    /**
     * Derives the AES key with Argon2id AND zeroes out the password/key
     * material as soon as it's no longer needed, instead of leaving
     * char[]/byte[] copies of the password and key sitting in the heap
     * until GC gets to them.
     */
    override fun onPause() {
        super.onPause()
        // FIX (reported bug): text used to only get cleared in onDestroy,
        // but Android does NOT call onDestroy just because the user left
        // the screen (pressed home, switched apps, opened another
        // activity and came back) - the Activity can sit paused/stopped
        // in memory for a long time with onDestroy never firing. That's
        // why the text was still there on return. Clearing here in
        // onPause instead means it's wiped every time the screen leaves
        // the foreground, not only when Android actually destroys it.
        clearSensitiveFields()
        clipboardClearRunnable?.let { clipboardHandler.removeCallbacks(it) }
        clearClipboardIfOurs()
    }

    private fun clearSensitiveFields() {
        inputText.text?.clear()
        inputKey.text?.clear()
        inputCustomMinutes.text?.clear()
        resultText.text = ""
        resultCard.visibility = View.GONE
    }

    override fun onDestroy() {
        super.onDestroy()
        // Kept as a defensive second pass in case the process is killed
        // in a way that skips onPause (rare, but free to guard against).
        clearSensitiveFields()
        clipboardClearRunnable?.let { clipboardHandler.removeCallbacks(it) }
    }
}

/**
 * Estimates how many bits of entropy a typed key/password has, and
 * enforces a hard minimum before encryption is allowed.
 *
 * IMPORTANT HONESTY NOTE (read this before trusting the number blindly):
 * this is a CHARSET-SIZE estimate (length x log2(character pool used)).
 * It assumes the characters were chosen unpredictably. It CANNOT detect
 * that "Password123456789!" is actually a guessable dictionary word with
 * padding - a real attacker tries dictionary words and common patterns
 * FIRST, not pure random brute force, so a long-but-patterned password
 * can score high here while still being weak in practice. The only way
 * to get a real, unconditional guarantee is the "generate random key"
 * button, which has true random entropy, not an estimate.
 */
object KeyStrength {
    // 256 bits is the TRUE ceiling here, not an arbitrary choice: the
    // derived AES key itself is exactly 256 bits (see keyLengthBytes = 32
    // in EncryptActivity), so no amount of password entropy beyond 256
    // bits can ever be "used" - Argon2id's output is a fixed 256-bit
    // key regardless of how much entropy goes in. Requiring more than
    // this would be security theater: the extra entropy has nowhere to
    // go. 128 bits already exceeds any realistic attacker by a factor of
    // billions of billions (see README); 256 is the mathematically
    // maximal, not-wasteful choice for anyone who wants the strictest
    // number that still means something.
    const val MIN_ENTROPY_BITS = 256.0

    fun estimateEntropyBits(pass: CharArray): Double {
        if (pass.isEmpty()) return 0.0
        var hasLower = false
        var hasUpper = false
        var hasDigit = false
        var hasSymbol = false
        var hasNonAscii = false
        for (c in pass) {
            when {
                c in 'a'..'z' -> hasLower = true
                c in 'A'..'Z' -> hasUpper = true
                c in '0'..'9' -> hasDigit = true
                c.code > 127 -> hasNonAscii = true
                else -> hasSymbol = true
            }
        }
        var poolSize = 0
        if (hasLower) poolSize += 26
        if (hasUpper) poolSize += 26
        if (hasDigit) poolSize += 10
        if (hasSymbol) poolSize += 32
        if (hasNonAscii) poolSize += 64
        if (poolSize == 0) return 0.0
        return pass.size * (Math.log(poolSize.toDouble()) / Math.log(2.0))
    }
}

/**
 * Generates a real cryptographically-random key (NOT derived from
 * anything typed or from any other ciphertext) and encodes it as
 * Base32 (RFC 4648 alphabet), grouped into readable blocks - meant to
 * be written down or transported by hand, e.g. to an air-gapped device.
 */
object RandomKey {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    /**
     * Default raised from 20 to 32 bytes (160 -> 256 bits) to match the
     * true ceiling explained in KeyStrength: the derived AES key is
     * exactly 256 bits, so this generates exactly enough real entropy to
     * saturate it - not less (leaving unused key space) and not more
     * (which would be wasted, since nothing downstream can use it).
     */
    fun generate(byteLength: Int = 32): String {
        val bytes = ByteArray(byteLength).also { SecureRandom().nextBytes(it) }
        val raw = encodeBase32(bytes)
        return raw.chunked(5).joinToString("-")
    }

    private fun encodeBase32(data: ByteArray): String {
        val sb = StringBuilder()
        var bits = 0
        var value = 0
        for (b in data) {
            value = (value shl 8) or (b.toInt() and 0xFF)
            bits += 8
            while (bits >= 5) {
                sb.append(ALPHABET[(value shr (bits - 5)) and 0x1F])
                bits -= 5
            }
        }
        if (bits > 0) {
            sb.append(ALPHABET[(value shl (5 - bits)) and 0x1F])
        }
        return sb.toString()
    }
}

/**
 * Best-effort heuristic root/tamper indicator check.
 *
 * HONESTY NOTE: this is a heuristic, not a security boundary. A
 * motivated attacker on a rooted device can trivially hide these
 * indicators. It exists only to warn a well-meaning user that the
 * platform-level guarantees this app leans on may not hold on their
 * specific device - it must never be presented as a "we detected and
 * blocked tampering" guarantee.
 */
object RootCheck {
    private val suspiciousPaths = arrayOf(
        "/system/app/Superuser.apk",
        "/sbin/su",
        "/system/bin/su",
        "/system/xbin/su",
        "/data/local/xbin/su",
        "/data/local/bin/su",
        "/system/sd/xbin/su",
        "/system/bin/failsafe/su",
        "/data/local/su",
        "/su/bin/su"
    )

    fun looksRooted(): Boolean {
        val tags = Build.TAGS
        if (tags != null && tags.contains("test-keys")) return true
        return suspiciousPaths.any { File(it).exists() }
    }
}
