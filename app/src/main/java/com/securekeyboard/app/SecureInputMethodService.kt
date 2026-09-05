package com.securekeyboard.app

import android.content.ClipboardManager
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.net.Uri
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputContentInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView

/**
 * SecureInputMethodService
 *
 * Privacy guarantees this class is designed to uphold:
 *
 * 1. NOTHING TYPED IS EVER STORED. Key presses are sent straight to the
 *    focused text field via InputConnection.commitText() and are never
 *    written to a variable, file, database, or log that outlives the
 *    single key press. There is no history buffer, no "recent words"
 *    file, no analytics call, anywhere in this class.
 *
 * 2. NO NETWORK ACCESS IS POSSIBLE. This entire app declares no
 *    android.permission.INTERNET in the manifest, so even if this code
 *    were modified to try to send data somewhere, the Android sandbox
 *    would block the socket at the OS level.
 *
 * 3. SCREENSHOTS OF THE KEYBOARD ARE BLOCKED where the platform allows
 *    it, via WindowManager.LayoutParams.FLAG_SECURE on this input
 *    window. See onCreateInputView().
 *
 * Limitations to be upfront about (see README):
 * - FLAG_SECURE blocks Android's built-in screenshot/screen-recording
 *   APIs. It cannot stop someone physically photographing the screen
 *   with another camera, and it cannot override a rooted device with
 *   modified system software.
 * - The app you're typing INTO (e.g. a chat app) still receives the
 *   text you type, exactly as it would with any keyboard - that's
 *   how typing works. This keyboard only guarantees that this app,
 *   specifically, does not add any collection, storage, or
 *   transmission of what you type.
 *
 * FIXED IN THIS VERSION: key row height used to be a hardcoded raw pixel
 * value (130px), which is NOT device-independent - the same number
 * produced a huge key on an old low-density screen and a tiny sliver on
 * a modern high-density screen. It's now computed in dp (see dpToPx)
 * from a user-adjustable preference (Settings > keyboard height), so it
 * looks consistent across devices and the user - not a one-time
 * hardcoded guess - controls how tall the keys are.
 *
 * WORD SUGGESTIONS - how this fits the privacy guarantees above:
 * A suggestion strip was added above the key rows, backed by two fixed,
 * read-only, bundled-in-the-APK data files derived from a public Arabic
 * news corpus: a word-frequency list for prefix completion (see
 * WordDictionary.kt) and a word-pair list for NEXT-word prediction - the
 * instant a word is finished, likely following words are offered before
 * anything of the next word is typed (see NextWordDictionary.kt). On top
 * of that, THIS APP'S OWN sensitive fields (and any other app's field
 * marked the same way) get a stricter guarantee than the rest of the
 * device - see the split below.
 *
 * ⚠️ IMPORTANT UPDATE - guarantee #1 above is now SCOPED, not global:
 *
 * - IN A FIELD MARKED SENSITIVE (password type, or
 *   TYPE_TEXT_FLAG_NO_SUGGESTIONS - this includes this app's own
 *   EncryptActivity message/key fields, see activity_encrypt.xml):
 *   guarantee #1 still holds EXACTLY as before. No suggestions are
 *   shown, nothing typed there is added to any dictionary, and the only
 *   state kept is the transient [currentWord] buffer, cleared
 *   immediately on space/enter/field-switch/keyboard-hide.
 *
 * - IN ANY OTHER FIELD (i.e. normal typing in any other app, since this
 *   is installable as the device's system keyboard): completed words
 *   (on space/enter/tapped suggestion) are now saved to a small
 *   per-device, per-word frequency file (see LearnedDictionary.kt) so
 *   suggestions improve based on the user's own vocabulary over time.
 *   This is real, new, on-device storage of individual words typed
 *   outside this app's own sensitive screens - read LearnedDictionary.kt's
 *   class doc for the full, honest scope of what that does and does not
 *   mean (never transmitted anywhere - still no INTERNET permission
 *   anywhere in the app; never full messages, only individual words with
 *   a typed-count; excluded from Android backups like the rest of this
 *   app's data; user-clearable anytime from Settings).
 *
 * FIXED IN THIS VERSION - suggestion bar layout jump: the suggestion
 * strip used to be View.GONE when there was nothing to suggest, which
 * removes it from the layout entirely and makes every row of keys below
 * it jump up/down as the user types (bar appears/disappears). It now
 * uses View.INVISIBLE instead, which keeps its height permanently
 * reserved in the layout - the keys never move, whether or not
 * suggestions are currently showing.
 *
 * ADDED IN THIS VERSION - two long-press behaviors, neither of which
 * changes any key's position in its row:
 * 1. Hamza popup on ا: long-pressing ا (and dragging, like a normal
 *    Android popup key) picks between ا / أ / إ / آ - the hamza forms
 *    that were missing before. See [LETTER_VARIANTS], [showVariantPopup].
 * 2. Tatweel (kashida, "ـ") hold-to-extend: holding down any other
 *    letter key (instead of a quick tap) repeatedly inserts the ـ
 *    elongation character instead of repeating the letter, so the user
 *    can stretch a word for emphasis/calligraphic effect. A quick tap
 *    still always just types the letter as before.
 *
 * FIXED IN THIS VERSION - the current word used to be "forgotten" the
 * moment backspace crossed back over a space into an already-finished
 * word (or the moment the user tapped the cursor into the middle of one):
 * [currentWord] was only ever built forward, one appended letter at a
 * time, so anything that put the cursor somewhere it hadn't typed
 * through left the buffer empty and suggestions stuck off until a brand
 * new word was started. [currentWord] is now re-derived from the actual
 * field content around the cursor - see [resyncCurrentWordFromField] -
 * both after backspace and on every cursor move ([onUpdateSelection]),
 * so going back into a word (by backspacing OR by tapping) restores it
 * in full instead of starting over.
 *
 * ADDED IN THIS VERSION - saved phrases: finishing a line with إدخال
 * (Enter) now also learns that whole line as a phrase (see
 * [PhraseDictionary]), gated by the exact same suggestionsEnabled check
 * as everything else here. This is a bigger privacy trade-off than
 * single-word learning - PhraseDictionary.kt's class doc spells out
 * exactly what that does and doesn't mean, and it's wipeable separately
 * from the learned-words list in Settings.
 *
 * FIXED IN THIS VERSION - dark mode never visibly applied: this view is
 * only ever built once per keyboard session, so its colors (from
 * res/values/colors.xml or res/values-night/colors.xml) were resolved
 * once and never re-resolved when the system's dark/light mode changed
 * afterwards. See [appliedNightMode], [onConfigurationChanged], and the
 * extra check added to onStartInputView - the view now rebuilds itself
 * whenever the applied night mode differs from what's currently applied.
 *
 * FIXED IN THIS VERSION (deeper bug behind the above) - the keyboard
 * didn't actually turn black when "الوضع الليلي" was chosen INSIDE the
 * app's own theme settings, only when the PHONE's system dark mode was
 * also on: this Service (unlike the AppCompatActivity screens) never
 * consulted AppCompatDelegate's forced night mode at all, only the raw
 * system Configuration. Every color/drawable this keyboard draws now
 * goes through ThemeUtil, which resolves everything against
 * Prefs.isDarkMode() directly (see ThemeUtil.themedContext()) - the
 * app's own toggle is the one actual source of truth everywhere now, not
 * the phone's separate system setting. The night palette itself
 * (res/values-night/colors.xml) was also given more contrast between the
 * keyboard surface, key faces, and borders - same shapes/corners/
 * elevation as before, just clearer separation between them instead of
 * reading as a flat, slightly-hazy dark gray.
 */
class SecureInputMethodService : InputMethodService() {

    companion object {
        // Time the user has to hold a key before it's treated as a
        // long-press (popup or tatweel-extend) instead of a normal tap.
        private const val LONG_PRESS_MS = 320L
        // How often ـ is inserted while a tatweel-extend key is held.
        private const val TATWEEL_REPEAT_MS = 110L
        // How often a "repeatable" key (currently: backspace) re-fires
        // its action while held down, after the initial LONG_PRESS_MS.
        private const val KEY_REPEAT_MS = 60L
        // Standard backspace/erase icon (U+232B), used instead of the
        // Arabic word "حذف" so the key reads as a universal icon the
        // way every other mainstream keyboard renders it. The word is
        // kept as the key's accessibility label (see a11yLabel) so
        // screen readers still announce "حذف", not the raw glyph.
        private const val BACKSPACE_GLYPH = "⌫"
        // Return/enter icon, replacing the word "إدخال" for the same
        // reason as BACKSPACE_GLYPH above.
        private const val ENTER_GLYPH = "↵"
        // "Back/exit secure-compose" icon, replacing the word "رجوع".
        private const val BACK_GLYPH = "⟵"

        // Keys that get a hamza-forms popup on long-press instead of the
        // tatweel-extend behavior. Order here is left-to-right in the
        // popup, matching the LTR row direction used for the key rows.
        private val LETTER_VARIANTS = mapOf(
            "ا" to listOf("ا", "أ", "إ", "آ")
        )

        // ADDED - multi-mode keyboard: Arabic letters (default), English
        // letters, a symbols/numbers page, and a small emoji page. Only
        // the currently active page's rows are ever built, so none of
        // this changes what gets sent to the field beyond the extra
        // characters/keys the user explicitly taps.
        private val ENGLISH_ROWS_LOWER = listOf(
            "q w e r t y u i o p",
            "a s d f g h j k l",
            "z x c v b n m"
        )
        private val ENGLISH_ROWS_UPPER = listOf(
            "Q W E R T Y U I O P",
            "A S D F G H J K L",
            "Z X C V B N M"
        )
        private val SYMBOL_ROWS = listOf(
            "1 2 3 4 5 6 7 8 9 0",
            "@ # \$ % & * - + ( )",
            "! \" ' : ; , . ? /"
        )
        // A modest, fixed set of common emoji - not exhaustive, just
        // enough to cover everyday use without bloating the keyboard.
        private val EMOJI_LIST = listOf(
            "😀", "😂", "🥰", "😍", "😊", "🙂", "😉", "😢",
            "😭", "😡", "👍", "👎", "🙏", "❤️", "💔", "🔥",
            "🎉", "🎂", "🌹", "⭐", "✅", "❌", "🤔", "🙄",
            "😴", "🤗", "😎", "🥳", "😇", "🤝", "👏", "🙌",
            "💪", "🌙", "☀️", "⚡", "🎁", "📌", "📍", "🕌",
            "📖", "✍️", "☕", "🍵", "🍎", "🍕", "🚗", "🏠"
        )
    }

    // Which base letter alphabet is showing (persists across symbol/emoji
    // page visits so "ABC"/"ابجد" always returns to the right one), and
    // whether the symbols or emoji overlay page is currently on top of
    // it. Purely a UI/display choice - never written to disk, resets to
    // Arabic + no overlay every time a fresh field is focused, same as
    // currentWord below.
    private enum class LetterMode { ARABIC, ENGLISH }
    private var letterMode = LetterMode.ARABIC
    private var showingSymbols = false
    private var showingEmoji = false
    private var showingCrypto = false
    private var showingSecureCompose = false
    // True from the moment the user enters secure-compose mode until
    // they explicitly tap "رجوع" on that screen. While true,
    // onStartInputView/onFinishInputView restore secure mode instead of
    // silently dropping it - otherwise the target app's own send button
    // (which can drop and re-grant this keyboard's input focus, e.g.
    // WhatsApp) would kick the user back to the normal keyboard after
    // every single message, forcing them to re-open secure mode by hand
    // each time. See both callbacks below.
    private var secureComposeSticky = false
    // Contact currently selected for secure compose. Null means no contact selected.
    private var selectedSecureContact: String? = null
    // Selected attachment for secure-compose. The original URI is never modified;
    // pressing the lock creates a separate encrypted outgoing copy and commits it
    // to the focused chat app as content (WhatsApp can render it as an attachment).
    private var selectedAttachmentUri: Uri? = null
    private var selectedAttachmentName: String = ""
    private var selectedAttachmentMime: String = "application/octet-stream"

    private val attachmentReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != AttachmentPickerActivity.ACTION_ATTACHMENT_SELECTED) return
            val uri = intent.getParcelableExtra<Uri>(AttachmentPickerActivity.EXTRA_URI) ?: return
            selectedAttachmentUri = uri
            selectedAttachmentName = intent.getStringExtra(AttachmentPickerActivity.EXTRA_NAME).orEmpty().ifBlank { "file" }
            selectedAttachmentMime = "application/octet-stream"
            rebuildKeyboardView()
            // Android 14 can keep the IME hidden after DocumentsUI returns.
            // Ask the system to show this IME again after the bridge Activity
            // has finished, without changing the selected target field.
            mainHandler.postDelayed({
                try { requestShowSelf(android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT) } catch (_: Exception) {}
            }, 180L)
        }
    }
    private var contactPopup: PopupWindow? = null
    // Same idea as secureComposeSticky, for the crypto MENU page
    // (buildCryptoPage) specifically: set while the user is on that
    // page, so opening "نافذة تركيب منفصلة" (EncryptActivity as a real,
    // separate Activity - see its EXTRA_POPUP_MODE doc) and coming back
    // restores the menu they came from, instead of dropping to the
    // plain letters page. Opening a full Activity like that necessarily
    // fires this service's onFinishInputView/onStartInputView, same as
    // switching apps would.
    private var cryptoMenuSticky = false
    private var englishShiftOn = false
    // Message body while in secure-compose mode (see buildSecureComposePage
    // below) - deliberately NEVER passed to the InputConnection until the
    // moment it's encrypted and sent, so the target app's field (and
    // anything else with access to it, e.g. an Accessibility Service)
    // never sees a single character of the plaintext, not even
    // transiently. Cleared on send, on "back", and on any field switch -
    // see clearSecureCompose() and every call site of it.
    private val composeBuffer = StringBuilder()
    private var composePreviewView: TextView? = null
    // Holds decrypted plaintext ONLY while the crypto panel is actively
    // displaying it for the user to read - never written anywhere, and
    // cleared (see clearCryptoResult()) the moment the panel closes or a
    // new field is focused. This is the sole in-memory copy; the
    // CharArray CryptoEngine.decrypt() returns is converted once here
    // and immediately zeroed (see buildCryptoPage's decrypt handler).
    private var cryptoDecryptedText: String? = null
    // Persistent operation status shown inside secure-compose. Toasts are
    // transient and can be missed while Android 14 switches between the
    // document picker and the IME; this view gives the user a stable status.
    private var cryptoStatusView: TextView? = null

    // Single Handler for every key's long-press/repeat timers. Each
    // posted Runnable is stored on that key's own local variables (see
    // makeKey) and removed by reference (never by clearing everything),
    // so one key's timer can never cancel another key's.
    private val longPressHandler = Handler(Looper.getMainLooper())
    // Argon2id key derivation (see CryptoEngine) is deliberately
    // expensive - ~1-2s of CPU+memory work by design, to resist
    // brute-forcing the passphrase offline. Running that on the main
    // thread would freeze the whole keyboard (and risk an ANR) for that
    // whole second-plus on every single encrypt/decrypt tap. This single
    // background thread is where that work actually happens instead;
    // results are posted back via mainHandler. A single thread (not a
    // pool) is deliberate - Argon2id at 256 MB is already memory-heavy,
    // so two of them running at once is something to avoid, not enable.
    private val cryptoExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    // Coalesces duplicate suggestion refreshes generated by both key actions
    // and InputConnection selection callbacks. One refresh per UI frame keeps
    // fast typing smooth without changing suggestion behavior.
    private val suggestionUpdateRunnable = Runnable { updateSuggestions() }
    // Sidesteps a double-tap (or tapping encrypt then decrypt before the
    // first finishes) queueing up a second heavy Argon2id job while one
    // is already grinding - the button just shows "جارٍ..." and ignores
    // further taps until the in-flight one posts back.
    private var cryptoBusy = false

    // Tracks which settings were baked into the currently-built view, so
    // onStartInputView can detect "the user changed something in
    // Settings since the keyboard was last drawn" and rebuild live,
    // instead of requiring the user to restart the app for a height or
    // accent-color change to take effect.
    private var attachmentReceiverRegistered = false
    private var appliedHeightDp = -1
    private var appliedAccentRes = -1
    // FIXED IN THIS VERSION: the keyboard's colors come from
    // res/values/colors.xml vs res/values-night/colors.xml, which
    // Android is supposed to switch between automatically based on
    // system dark/light mode - but this view is only ever BUILT once
    // (onCreateInputView) and its drawables/colors are resolved to
    // fixed values at that moment, not re-resolved live. Without this
    // tracked value, switching system dark mode while (or before) this
    // keyboard is showing had no visible effect until the whole app was
    // killed and restarted. See onConfigurationChanged and the extra
    // check in onStartInputView below - both now rebuild the view
    // whenever the current night-mode bits differ from what's applied.
    private var appliedNightMode = -1

    // In-memory only, cleared aggressively (see class doc above). This
    // is intentionally the ONLY typed-content state this class keeps,
    // and it never outlives the current word/field/session.
    private val currentWord = StringBuilder()
    private var suggestionsEnabled = false

    // In-memory only, same lifetime/rules as currentWord above: the single
    // most recently FINISHED word (via space, enter, or tapping a
    // suggestion), kept purely so NextWordDictionary can offer likely next
    // words the instant a new word starts - never written to disk, never
    // part of any log, cleared alongside currentWord on field switch.
    private var lastFinishedWord: String? = null
    private var suggestionBar: LinearLayout? = null
    // See onCreateInputView / showDecryptedResultPopup.
    private var keyboardRootView: View? = null

    private fun dpToPx(dp: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, resources.displayMetrics).toInt()

    override fun onCreateInputView(): View {
        SessionKeyStore.initialize(this)
        if (!attachmentReceiverRegistered) {
            val filter = IntentFilter(AttachmentPickerActivity.ACTION_ATTACHMENT_SELECTED)
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                registerReceiver(attachmentReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(attachmentReceiver, filter)
            }
            attachmentReceiverRegistered = true
        }
        // Block screenshots / screen recording of the keyboard surface,
        // to the extent the Android platform allows for an IME window.
        window?.window?.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )

        val heightDp = Prefs.keyboardHeightDp(this)
        appliedHeightDp = heightDp
        appliedAccentRes = Prefs.accentColorRes(this)
        appliedNightMode = currentNightMode()

        // Kick off (or no-op if already done) the background load of the
        // bundled static word list AND the user's own learned-words file
        // (empty until words get learned in a non-sensitive field - see
        // class doc above and LearnedDictionary.kt).
        WordDictionary.preload(this)
        NextWordDictionary.preload(this)
        LearnedDictionary.preload(this)
        PhraseDictionary.preload(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = ThemeUtil.keyboardBackground(this@SecureInputMethodService)
            setPadding(dpToPx(3f), dpToPx(5f), dpToPx(3f), dpToPx(5f))
            // FIX: rows of keys must always render in FIXED PHYSICAL order
            // matching a real Arabic keyboard's key positions, which are
            // the same physical layout as QWERTY (e.g. ض sits where Q is -
            // far left - and د sits where ] is - far right). This is why
            // the row arrays below are authored left-to-right. Setting
            // layoutDirection to RTL here was WRONG and reversed every row
            // (ض ended up on the right, د on the left) - the opposite of
            // any real Arabic keyboard. LTR is the correct fixed direction
            // for the key ROWS specifically; it has nothing to do with the
            // app's RTL support elsewhere (dialogs, settings text, etc. -
            // those correctly stay RTL via supportsRtl in the manifest).
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }

        // Suggestion strip: built once here, populated/hidden dynamically
        // by scheduleSuggestionsUpdate() as the user types. Height is a fixed,
        // modest fraction of the key height so it doesn't dominate the
        // keyboard on short screens.
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            // INVISIBLE (not GONE): the bar's height stays reserved in
            // the layout at all times, so the key rows below it never
            // shift up/down as suggestions come and go while typing.
            visibility = View.INVISIBLE
            // Distinct rounded panel + a light shadow (real elevation,
            // matching the same technique used for keys) so the whole
            // suggestion strip reads as one raised card sitting above
            // the keyboard surface, not chips floating with nothing
            // behind them.
            background = ThemeUtil.suggestionBarBackground(this@SecureInputMethodService)
            elevation = dpToPx(1.5f).toFloat()
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dpToPx((heightDp * 0.72f))
            )
            lp.setMargins(0, 0, 0, dpToPx(3f))
            layoutParams = lp
        }
        suggestionBar = bar
        root.addView(bar)
        // Anchor for showDecryptedResultPopup() below - a floating popup
        // needs some attached View to anchor itself to; this is always
        // the current keyboard root, whichever page happens to be on it.
        keyboardRootView = root

        // ADDED - multi-page keyboard: dispatches to whichever page is
        // currently active (see letterMode/showingSymbols/showingEmoji
        // above). Each builder is responsible for its own rows AND its
        // own bottom action row, since the keys that belong there differ
        // per page (e.g. the emoji page has no need for a "123" key).
        when {
            showingEmoji -> buildEmojiPage(root, heightDp)
            showingSymbols -> buildSymbolsPage(root, heightDp)
            showingSecureCompose -> buildSecureComposePage(root, heightDp)
            showingCrypto -> buildCryptoPage(root, heightDp)
            else -> buildLetterPage(root, heightDp)
        }

        return root
    }

    /** Rebuilds the whole keyboard view in place, e.g. after switching pages. */
    private fun rebuildKeyboardView() {
        setInputView(onCreateInputView())
    }

    private fun clearSecureCompose() {
        // StringBuilder can't be securely zeroed the way a CharArray can
        // (see CryptoEngine's comments on this same limitation) - this
        // is a best-effort clear like everything else in this app that
        // has to use a mutable text container instead, not an absolute
        // guarantee. It IS still strictly better than committing the
        // text to WhatsApp's own field, which this whole feature exists
        // to avoid.
        composeBuffer.setLength(0)
        composePreviewView = null
        showingSecureCompose = false
        secureComposeSticky = false
        // Any decrypted result being shown inline on this same screen
        // (see buildSecureComposePage) is sensitive plaintext - don't
        // leave it sitting in memory once the user has explicitly left
        // secure mode via "رجوع".
        cryptoDecryptedText = null
    }

    /**
     * Arabic or English letters (per [letterMode]), plus the number row
     * and the shared space/delete/enter/mode-switch bottom row. This is
     * the page shown by default and whenever the user taps back from
     * symbols/emoji.
     */
    private fun buildLetterPage(root: LinearLayout, heightDp: Int) {
        val numberLayout = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (n in "1 2 3 4 5 6 7 8 9 0".split(" ")) {
            numberLayout.addView(makeKey(n, heightDp = heightDp))
        }
        root.addView(numberLayout)

        val rows: List<String>
        val isArabic = letterMode == LetterMode.ARABIC
        if (isArabic) {
            // NOTE: rows/letters and their order are UNCHANGED from
            // before - only sizing (dp instead of raw px) and background
            // styling were touched in that earlier pass.
            rows = listOf(
                "ض ص ث ق ف غ ع ه خ ح ج",
                "ش س ي ب ل ا ت ن م ك ة",
                "؛ ظ ط ذ د ز ر و ؤ"
            )
        } else {
            rows = if (englishShiftOn) ENGLISH_ROWS_UPPER else ENGLISH_ROWS_LOWER
        }

        for (row in rows) {
            val rowLayout = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (ch in row.split(" ")) {
                // Hamza popup / tatweel-hold are Arabic-specific
                // behaviors - English letters just commit plainly, same
                // as any other key with no variants.
                val variants = if (isArabic) LETTER_VARIANTS[ch] else null
                rowLayout.addView(
                    makeKey(
                        ch,
                        heightDp = heightDp,
                        variants = variants,
                        tatweelExtend = isArabic && variants == null
                    ) {
                        commitLetter(ch)
                    }
                )
            }
            root.addView(rowLayout)
        }

        val bottomRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val langLabel = if (isArabic) "EN" else "ع"
        bottomRow.addView(makeKey(langLabel, weight = 1.2f, heightDp = heightDp) {
            // Switching alphabets mid-word is a deliberate choice the
            // user just made (e.g. typing an English name inside an
            // Arabic sentence), so the in-progress word is left alone -
            // only the page changes.
            letterMode = if (isArabic) LetterMode.ENGLISH else LetterMode.ARABIC
            englishShiftOn = false
            rebuildKeyboardView()
        })
        if (!isArabic) {
            bottomRow.addView(makeKey("⇧", weight = 1.2f, heightDp = heightDp, accented = englishShiftOn) {
                englishShiftOn = !englishShiftOn
                rebuildKeyboardView()
            })
        }
        bottomRow.addView(makeKey("123", weight = 1.2f, heightDp = heightDp) {
            showingSymbols = true
            rebuildKeyboardView()
        })
        bottomRow.addView(spaceKey(heightDp, weight = if (isArabic) 4f else 2.6f))
        bottomRow.addView(makeKey("🙂", weight = 1.2f, heightDp = heightDp) {
            showingEmoji = true
            rebuildKeyboardView()
        })
        bottomRow.addView(makeKey("🔒", weight = 1.2f, heightDp = heightDp) {
            showingCrypto = true
            cryptoMenuSticky = true
            rebuildKeyboardView()
        })
        // Direct one-tap shortcut: check the clipboard and decrypt right
        // here, without opening the crypto menu first. Uses the exact
        // same decryptClipboard() the crypto panel's own button calls -
        // see its doc - so behavior (session check, ciphertext check,
        // result screen) is identical either way.
        bottomRow.addView(makeKey("🔓", weight = 1.2f, heightDp = heightDp) {
            decryptClipboard()
        })
        bottomRow.addView(deleteKey(heightDp))
        bottomRow.addView(enterKey(heightDp))
        root.addView(bottomRow)
    }

    /** Numbers/symbols page - "ABC"/"ابجد" returns to whichever alphabet was active. */
    private fun buildSymbolsPage(root: LinearLayout, heightDp: Int) {
        for (row in SYMBOL_ROWS) {
            val rowLayout = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (sym in row.split(" ")) {
                rowLayout.addView(makeKey(sym, heightDp = heightDp) {
                    // A symbol interrupts whatever word was in progress -
                    // it can't extend it the way a letter does.
                    currentInputConnection?.commitText(sym, 1)
                    currentWord.clear()
                    lastFinishedWord = null
                    scheduleSuggestionsUpdate()
                })
            }
            root.addView(rowLayout)
        }

        val bottomRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val backLabel = if (letterMode == LetterMode.ARABIC) "ابجد" else "ABC"
        bottomRow.addView(makeKey(backLabel, weight = 1.5f, heightDp = heightDp) {
            showingSymbols = false
            rebuildKeyboardView()
        })
        bottomRow.addView(spaceKey(heightDp, weight = 4f))
        bottomRow.addView(deleteKey(heightDp))
        bottomRow.addView(enterKey(heightDp))
        root.addView(bottomRow)
    }

    /** Small fixed emoji grid - tapping an emoji commits it directly, no word-buffer involvement. */
    private fun buildEmojiPage(root: LinearLayout, heightDp: Int) {
        val perRow = 8
        var i = 0
        while (i < EMOJI_LIST.size) {
            val rowLayout = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (j in 0 until perRow) {
                if (i >= EMOJI_LIST.size) break
                val emoji = EMOJI_LIST[i]
                rowLayout.addView(makeKey(emoji, heightDp = heightDp) {
                    currentInputConnection?.commitText(emoji, 1)
                })
                i++
            }
            root.addView(rowLayout)
        }

        val bottomRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val backLabel = if (letterMode == LetterMode.ARABIC) "ابجد" else "ABC"
        bottomRow.addView(makeKey(backLabel, weight = 2f, heightDp = heightDp) {
            showingEmoji = false
            rebuildKeyboardView()
        })
        bottomRow.addView(deleteKey(heightDp, weight = 2f))
        root.addView(bottomRow)
    }

    /**
     * Quick-encrypt / quick-decrypt panel, entirely inside the
     * keyboard's own view (no separate Activity/Dialog involved - see
     * class doc addition above). Uses whatever passphrase is currently
     * active in SessionKeyStore; that passphrase itself is only ever
     * set from the full Encrypt screen (EncryptActivity), never typed
     * here. All actual crypto goes through CryptoEngine - the exact
     * same code path the full Encrypt screen uses, so anything produced
     * here decrypts fine there and vice versa.
     */
    private fun buildCryptoPage(root: LinearLayout, heightDp: Int) {
        val padding = dpToPx(8f)
        val decrypted = cryptoDecryptedText

        if (decrypted != null) {
            // RESULT VIEW: show the plaintext for reading only. It is
            // never written back into any field automatically - the
            // whole point of decrypting here is to READ someone else's
            // message, not to inject it anywhere.
            val scroll = ScrollView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dpToPx(heightDp * 2.6f)
                )
            }
            val resultView = TextView(this).apply {
                text = decrypted
                setTextColor(Color.WHITE)
                textSize = 15f
                setPadding(padding, padding, padding, padding)
                layoutDirection = View.LAYOUT_DIRECTION_RTL
                textDirection = View.TEXT_DIRECTION_RTL
            }
            scroll.addView(resultView)
            root.addView(scroll)

            val closeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            closeRow.addView(makeKey(getString(R.string.crypto_panel_close_btn), weight = 2f, heightDp = heightDp, accented = true) {
                cryptoDecryptedText = null
                rebuildKeyboardView()
            })
            root.addView(closeRow)
            return
        }

        val statusView = TextView(this).apply {
            text = if (SessionKeyStore.isActive()) {
                getString(R.string.session_key_active, SessionKeyStore.remainingMinutes())
            } else {
                getString(R.string.crypto_panel_no_session)
            }
            setTextColor(ThemeUtil.accentColor(this@SecureInputMethodService))
            textSize = 13f
            setPadding(padding, padding, padding, padding)
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            textDirection = View.TEXT_DIRECTION_RTL
        }
        root.addView(statusView)

        val sessionActive = SessionKeyStore.isActive()

        val composeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        composeRow.addView(makeKey(getString(R.string.crypto_panel_secure_compose_btn), weight = 1f, heightDp = heightDp, accented = sessionActive) {
            showingSecureCompose = true
            secureComposeSticky = true
            rebuildKeyboardView()
        })
        root.addView(composeRow)

        val composeDesc = TextView(this).apply {
            text = getString(R.string.crypto_panel_secure_compose_desc)
            setTextColor(ThemeUtil.textSecondaryColor(this@SecureInputMethodService))
            textSize = 11f
            setPadding(padding, 0, padding, dpToPx(4f))
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            textDirection = View.TEXT_DIRECTION_RTL
        }
        root.addView(composeDesc)

        // File encryption belongs to this same encryption window as agreed:
        // the dedicated file tool opens from here, while the paperclip in
        // secure-compose remains the quick attach-and-encrypt path.
        val fileRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fileRow.addView(makeKey("📁 تشفير الملفات", weight = 1f, heightDp = heightDp, accented = sessionActive) {
            if (!SessionKeyStore.isActive()) {
                android.widget.Toast.makeText(this@SecureInputMethodService, R.string.crypto_panel_no_session, android.widget.Toast.LENGTH_LONG).show()
            } else {
                openSecureFileCrypto()
            }
        })
        root.addView(fileRow)

        val encryptRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        encryptRow.addView(makeKey(getString(R.string.crypto_panel_encrypt_btn), weight = 1f, heightDp = heightDp, accented = sessionActive) {
            encryptFieldAndInject()
        })
        root.addView(encryptRow)

        val decryptRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        decryptRow.addView(makeKey(getString(R.string.crypto_panel_decrypt_btn), weight = 1f, heightDp = heightDp, accented = sessionActive) {
            decryptClipboard()
        })
        root.addView(decryptRow)

        val popupRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        popupRow.addView(makeKey(getString(R.string.crypto_panel_open_popup_btn), weight = 1f, heightDp = heightDp) {
            // Remember only the UI page, not the draft/plaintext. This survives
            // an IME recreation while the separate Activity is open, so returning
            // from it does not drop the user to the normal keyboard/home screen.
            Prefs.markReturnToCrypto(this@SecureInputMethodService)
            val intent = Intent(this@SecureInputMethodService, EncryptActivity::class.java).apply {
                putExtra(EncryptActivity.EXTRA_POPUP_MODE, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_HISTORY)
            }
            startActivity(intent)
        })
        root.addView(popupRow)

        val popupDesc = TextView(this).apply {
            text = getString(R.string.crypto_panel_popup_desc)
            setTextColor(ThemeUtil.textSecondaryColor(this@SecureInputMethodService))
            textSize = 11f
            setPadding(padding, 0, padding, padding)
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            textDirection = View.TEXT_DIRECTION_RTL
        }
        root.addView(popupDesc)

        val bottomRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val backLabel = if (letterMode == LetterMode.ARABIC) "ابجد" else "ABC"
        bottomRow.addView(makeKey(backLabel, weight = 2f, heightDp = heightDp) {
            showingCrypto = false
            cryptoMenuSticky = false
            rebuildKeyboardView()
        })
        root.addView(bottomRow)
    }

    /**
     * The recommended path: types the message into an internal buffer
     * ONLY (composeBuffer above) - never touching the target field via
     * InputConnection until the single "إرسال" tap encrypts it and
     * commits the CIPHERTEXT in one shot. This means WhatsApp's field
     * (and anything else that can read it, including an Accessibility
     * Service on another app) never sees so much as one character of
     * the plaintext, without needing a separate popup/Activity or a
     * manual copy-paste step either.
     *
     * Both actions the user needs while writing a protected message -
     * encrypting/sending the draft, and reading back a decrypted reply -
     * live on THIS one screen. Neither one exits secure mode by itself;
     * the only way out is the explicit "رجوع" button below, so a whole
     * back-and-forth conversation can happen here without the keyboard
     * ever dropping back to the normal (non-secure) page in between
     * messages.
     */
    /** Opens an in-keyboard side drawer with paired contacts. */
    private fun showSecureContactsPanel(anchor: View) {
        contactPopup?.dismiss()
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dpToPx(12f), dpToPx(12f), dpToPx(12f), dpToPx(12f))
            background = ThemeUtil.keyboardBackground(this@SecureInputMethodService)
            elevation = dpToPx(8f).toFloat()
        }
        val title = TextView(this).apply {
            text = "جهات الاتصال الآمنة"
            setTextColor(ThemeUtil.textColor(this@SecureInputMethodService))
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, 0, dpToPx(10f))
            gravity = Gravity.CENTER_VERTICAL
        }
        panel.addView(title, LinearLayout.LayoutParams(-1, dpToPx(42f)))

        val names = ContactStore.listPairedContactNames(this)
        if (names.isEmpty()) {
            panel.addView(TextView(this).apply {
                text = "لا توجد جهة اتصال مقترنة بعد"
                setTextColor(ThemeUtil.textColor(this@SecureInputMethodService))
                textSize = 13f
                setPadding(0, dpToPx(8f), 0, dpToPx(12f))
            })
        } else {
            for (name in names) {
                val selected = name == selectedSecureContact
                val row = TextView(this).apply {
                    text = if (selected) "✓  🔐 $name" else "    🔐 $name"
                    setTextColor(if (selected) ThemeUtil.accentColor(this@SecureInputMethodService) else ThemeUtil.textColor(this@SecureInputMethodService))
                    textSize = 14f
                    gravity = Gravity.CENTER_VERTICAL or Gravity.RIGHT
                    setPadding(dpToPx(10f), 0, dpToPx(10f), 0)
                    background = ThemeUtil.keyBackgroundSelector(this@SecureInputMethodService, accented = selected)
                    isClickable = true
                    setOnClickListener {
                        selectedSecureContact = name
                        contactPopup?.dismiss()
                        rebuildKeyboardView()
                    }
                }
                val lp = LinearLayout.LayoutParams(-1, dpToPx(44f))
                lp.setMargins(0, 0, 0, dpToPx(5f))
                panel.addView(row, lp)
            }
        }

        val add = TextView(this).apply {
            text = "＋  إضافة جهة اتصال آمنة"
            setTextColor(ThemeUtil.accentColor(this@SecureInputMethodService))
            textSize = 13f
            gravity = Gravity.CENTER
            background = ThemeUtil.keyBackgroundSelector(this@SecureInputMethodService, accented = true)
            isClickable = true
            setOnClickListener {
                contactPopup?.dismiss()
                val intent = Intent(this@SecureInputMethodService, ContactPairingActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
            }
        }
        panel.addView(add, LinearLayout.LayoutParams(-1, dpToPx(44f)))

        val clear = TextView(this).apply {
            text = "إلغاء اختيار الجهة"
            setTextColor(ThemeUtil.textColor(this@SecureInputMethodService))
            textSize = 12f
            gravity = Gravity.CENTER
            isClickable = true
            setPadding(0, dpToPx(8f), 0, 0)
            setOnClickListener {
                selectedSecureContact = null
                contactPopup?.dismiss()
                rebuildKeyboardView()
            }
        }
        panel.addView(clear, LinearLayout.LayoutParams(-1, dpToPx(36f)))

        val width = dpToPx(285f)
        // Keep the drawer bounded to the visible IME instead of using the
        // entire root height. A full-height PopupWindow can be clipped or
        // re-laid-out differently by OEM IME implementations.
        val rootHeight = anchor.rootView.height
        val panelHeight = if (rootHeight > dpToPx(160f)) {
            minOf((rootHeight * 0.88f).toInt(), dpToPx(430f))
        } else {
            WindowManager.LayoutParams.WRAP_CONTENT
        }
        // IMPORTANT for IME stability: a focusable PopupWindow creates a
        // second focusable window above the InputMethodService. Several OEMs
        // (and some Android versions) respond by finishing/reconfiguring the
        // IME input view; onFinishInputView() then dismisses this popup almost
        // immediately. The contacts drawer does not need keyboard focus, so
        // keep it touchable but explicitly non-focusable.
        val popup = PopupWindow(panel, width, panelHeight, false).apply {
            elevation = dpToPx(8f).toFloat()
            setBackgroundDrawable(ThemeUtil.keyboardBackground(this@SecureInputMethodService))
            isTouchable = true
            isOutsideTouchable = true
            isClippingEnabled = false
            inputMethodMode = PopupWindow.INPUT_METHOD_NOT_NEEDED
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
            setOnDismissListener { contactPopup = null }
        }
        contactPopup = popup
        popup.showAtLocation(anchor, Gravity.RIGHT or Gravity.BOTTOM, 0, 0)
    }

    private fun openSecureFileCrypto() {
        // Preserve the crypto keyboard page while the standalone file tool
        // is on screen. Only the page flag is persisted; no plaintext/draft
        // is written to disk.
        Prefs.markReturnToCrypto(this)
        val intent = Intent(this, FileCryptoActivity::class.java).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_HISTORY
        )
        selectedSecureContact?.let { intent.putExtra(FileCryptoActivity.EXTRA_CONTACT_NAME, it) }
        startActivity(intent)
    }

    private fun buildSecureComposePage(root: LinearLayout, heightDp: Int) {
        val decrypted = cryptoDecryptedText
        if (decrypted != null) {
            buildSecureComposeDecryptedResult(root, heightDp, decrypted)
            return
        }

        val preview = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 16f
            setPadding(dpToPx(8f), dpToPx(6f), dpToPx(8f), dpToPx(6f))
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            textDirection = View.TEXT_DIRECTION_RTL
            minLines = 2
            maxLines = 3
            text = composeBuffer.toString()
        }
        composePreviewView = preview
        val previewScroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dpToPx(heightDp * 1.7f)
            )
            addView(preview)
        }
        root.addView(previewScroll)

        if (selectedAttachmentUri != null) {
            val attachmentView = TextView(this).apply {
                text = "📎 ${selectedAttachmentName.ifBlank { "ملف" }}\nسيُشفّر عند الضغط على 🔐 مع إبقاء الأصل كما هو"
                setTextColor(ThemeUtil.accentColor(this@SecureInputMethodService))
                textSize = 11f
                gravity = Gravity.CENTER
                setPadding(dpToPx(6f), dpToPx(4f), dpToPx(6f), dpToPx(4f))
                setOnClickListener { selectedAttachmentUri = null; selectedAttachmentName = ""; rebuildKeyboardView() }
            }
            root.addView(attachmentView)
        }

        // Encrypt/decrypt actions live right above the keys, always
        // visible and always reachable without leaving this screen.
        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }
        actionRow.addView(makeKey("🔐", weight = 1f, heightDp = heightDp, accented = true, a11yLabel = "تشفير وإرسال") {
            if (selectedAttachmentUri != null) sendSecureAttachment() else sendSecureCompose()
        })
        actionRow.addView(makeKey("🔓", weight = 1f, heightDp = heightDp, a11yLabel = "فك تشفير") {
            decryptClipboardInPlace()
        })
        actionRow.addView(makeKey("👥", weight = 1f, heightDp = heightDp, accented = selectedSecureContact != null, a11yLabel = "جهات الاتصال الآمنة") {
            showSecureContactsPanel(root)
        })
        actionRow.addView(makeKey("📎", weight = 1f, heightDp = heightDp, a11yLabel = "اختيار ملف") {
            openAttachmentPicker()
        })
        root.addView(actionRow)

        val operationStatus = TextView(this).apply {
            text = "جاهز — اختر ملفًا ثم اضغط 🔐 للتشفير"
            setTextColor(ThemeUtil.textSecondaryColor(this@SecureInputMethodService))
            textSize = 11f
            gravity = Gravity.CENTER
            setPadding(dpToPx(4f), dpToPx(3f), dpToPx(4f), dpToPx(3f))
            visibility = if (cryptoBusy) View.VISIBLE else View.VISIBLE
        }
        cryptoStatusView = operationStatus
        root.addView(operationStatus)

        val contactStatus = TextView(this).apply {
            text = if (selectedSecureContact == null) "الجهة: غير محددة — سيُستخدم التشفير العام للجلسة" else "الجهة: 🔐 $selectedSecureContact — تشفير مرتبط بالجهازين"
            setTextColor(if (selectedSecureContact == null) ThemeUtil.textColor(this@SecureInputMethodService) else ThemeUtil.accentColor(this@SecureInputMethodService))
            textSize = 11f
            gravity = Gravity.CENTER
            setPadding(0, dpToPx(2f), 0, dpToPx(4f))
        }
        root.addView(contactStatus)

        val isArabic = letterMode == LetterMode.ARABIC
        val rows = if (isArabic) {
            listOf(
                "ض ص ث ق ف غ ع ه خ ح ج",
                "ش س ي ب ل ا ت ن م ك ة",
                "؛ ظ ط ذ د ز ر و ؤ"
            )
        } else {
            if (englishShiftOn) ENGLISH_ROWS_UPPER else ENGLISH_ROWS_LOWER
        }
        for (row in rows) {
            val rowLayout = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (ch in row.split(" ")) {
                rowLayout.addView(makeKey(ch, heightDp = heightDp) {
                    composeBuffer.append(ch)
                    composePreviewView?.text = composeBuffer.toString()
                })
            }
            root.addView(rowLayout)
        }

        val bottomRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        bottomRow.addView(makeKey(BACK_GLYPH, weight = 1.3f, heightDp = heightDp, a11yLabel = "رجوع", isIconGlyph = true) {
            // The ONLY action that leaves secure-compose mode. Leaving
            // without sending discards the draft entirely - no "save for
            // later", consistent with nothing here ever being written to
            // disk.
            clearSecureCompose()
            rebuildKeyboardView()
        })
        bottomRow.addView(makeKey("مسافة", weight = 3f, heightDp = heightDp, accented = true) {
            composeBuffer.append(' ')
            composePreviewView?.text = composeBuffer.toString()
        })
        bottomRow.addView(makeKey(BACKSPACE_GLYPH, weight = 1.3f, heightDp = heightDp, accented = true, repeatable = true, a11yLabel = "حذف", isIconGlyph = true) {
            if (composeBuffer.isNotEmpty()) composeBuffer.deleteCharAt(composeBuffer.length - 1)
            composePreviewView?.text = composeBuffer.toString()
        })
        root.addView(bottomRow)
    }

    /**
     * The decrypted-result sub-view for secure-compose mode: same idea as
     * buildCryptoPage's result view, but its "إغلاق" button returns to
     * the compose screen itself (showingSecureCompose is never touched
     * here), not to the normal keyboard.
     */
    private fun buildSecureComposeDecryptedResult(root: LinearLayout, heightDp: Int, decrypted: String) {
        val padding = dpToPx(8f)
        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dpToPx(heightDp * 2.6f)
            )
        }
        val resultView = TextView(this).apply {
            text = decrypted
            setTextColor(Color.WHITE)
            textSize = 15f
            setPadding(padding, padding, padding, padding)
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            textDirection = View.TEXT_DIRECTION_RTL
        }
        scroll.addView(resultView)
        root.addView(scroll)

        val closeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        closeRow.addView(makeKey(getString(R.string.crypto_panel_close_btn), weight = 2f, heightDp = heightDp, accented = true) {
            // Closes the result only - still on the secure-compose
            // screen afterward, draft (if any) untouched.
            cryptoDecryptedText = null
            rebuildKeyboardView()
        })
        root.addView(closeRow)
    }

    /**
     * Encrypts composeBuffer and commits ONLY the ciphertext, in one shot,
     * to the active field. The Argon2id+AES work itself (see
     * CryptoEngine) runs on cryptoExecutor, not here - only the
     * InputConnection/UI touch-points below run on the main thread.
     */
    private fun openAttachmentPicker() {
        val intent = Intent(this, AttachmentPickerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
    }

    /** Encrypts the selected original into a separate outgoing SKF2 file and
     * injects it into the focused app through InputConnection.commitContent().
     * The source URI is read-only and is never overwritten or deleted. */
    private fun sendSecureAttachment() {
        if (cryptoBusy) return
        val input = selectedAttachmentUri ?: return
        val contactName = selectedSecureContact
        if (contactName.isNullOrBlank()) {
            android.widget.Toast.makeText(this, "اختر جهة اتصال آمنة أولاً", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val passphrase = SessionKeyStore.get()
        if (passphrase == null) {
            android.widget.Toast.makeText(this, R.string.crypto_panel_no_session, android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val keyB64 = ContactStore.getPairedContact(this, contactName)
        if (keyB64.isNullOrBlank()) {
            java.util.Arrays.fill(passphrase, ' ')
            android.widget.Toast.makeText(this, "مفتاح جهة الاتصال غير موجود", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val publicKey = try { DeviceIdentity.parseContactPublicKey(keyB64) } catch (e: Exception) {
            java.util.Arrays.fill(passphrase, ' ')
            android.widget.Toast.makeText(this, "مفتاح جهة الاتصال غير صالح", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val mime = selectedAttachmentMime
        val displayName = selectedAttachmentName.ifBlank { "file" }
        cryptoBusy = true
        cryptoStatusView?.text = "جارٍ تشفير الملف وإرفاقه…"
        android.widget.Toast.makeText(this, "جارٍ تشفير الملف وإرفاقه…", android.widget.Toast.LENGTH_SHORT).show()
        cryptoExecutor.execute {
            var outFile: java.io.File? = null
            try {
                val outDir = java.io.File(filesDir, "secure_outbox").apply { mkdirs() }
                outFile = java.io.File(outDir, "out_${System.currentTimeMillis()}_${displayName.hashCode().toUInt().toString(16)}.skf")
                val outUri = Uri.fromFile(outFile)
                SecureFileCrypto.encrypt(this, input, outUri, publicKey, passphrase, displayName)
                mainHandler.post {
                    cryptoBusy = false
                    cryptoStatusView?.text = "✓ تم تشفير الملف — جارٍ إرفاق النسخة المشفّرة…"
                    val ic = currentInputConnection
                    if (ic == null || android.os.Build.VERSION.SDK_INT < 25) {
                        android.widget.Toast.makeText(this, "هذا التطبيق لا يدعم إرفاق الملفات من لوحة المفاتيح على هذا النظام", android.widget.Toast.LENGTH_LONG).show()
                        return@post
                    }
                    try {
                        val providerUri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.fileprovider", outFile!!)
                        grantUriPermission(packageName, providerUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        val info = InputContentInfo(providerUri, android.content.ClipDescription(displayName, arrayOf("application/octet-stream")), null)
                        val flags = InputConnection.INPUT_CONTENT_GRANT_READ_URI_PERMISSION
                        val accepted = ic.commitContent(info, flags, null)
                        if (!accepted) {
                            // Many chat editors do not advertise application/octet-stream
                            // through commitContent even though they can receive files
                            // through the Android Sharesheet. Do not pretend the file was
                            // sent: fall back to the system share UI so the user can choose
                            // WhatsApp and then press its normal Send button.
                            cryptoBusy = false
                            cryptoStatusView?.text = "✓ لم يدعم التطبيق المستهدف الإرفاق المباشر — فُتحت المشاركة لاختيار واتساب"
                            shareEncryptedAttachment(outFile!!, displayName)
                        } else {
                            cryptoStatusView?.text = "✓ تم إدراج الملف المشفّر — اضغط إرسال في واتساب لإرساله"
                            android.widget.Toast.makeText(this, "تم إدراج الملف المشفّر في المحادثة — اضغط إرسال في واتساب لإرساله، والأصل محفوظ", android.widget.Toast.LENGTH_LONG).show()
                            selectedAttachmentUri = null
                            selectedAttachmentName = ""
                            rebuildKeyboardView()
                        }
                    } catch (e: Exception) {
                        android.widget.Toast.makeText(this, "تعذر إرفاق الملف المشفّر: ${e.message ?: "خطأ"}", android.widget.Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                outFile?.delete()
                mainHandler.post {
                    cryptoBusy = false
                    cryptoStatusView?.text = "فشل تشفير الملف — ${e.message ?: "خطأ غير معروف"}"
                    android.widget.Toast.makeText(this, "فشل تشفير الملف: ${e.message ?: "خطأ غير معروف"}", android.widget.Toast.LENGTH_LONG).show()
                }
            } finally {
                java.util.Arrays.fill(passphrase, ' ')
            }
        }
    }

    private fun shareEncryptedAttachment(file: java.io.File, originalName: String) {
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val share = Intent(Intent.ACTION_SEND).apply {
                type = "application/octet-stream"
                setDataAndType(uri, "application/octet-stream")
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = android.content.ClipData.newRawUri("SecureKeyboard encrypted file", uri)
                putExtra(Intent.EXTRA_TEXT, "SecureKeyboard — ملف مشفّر: $originalName")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(share, "اختر التطبيق لإرسال الملف المشفّر").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            android.widget.Toast.makeText(this, "اختر واتساب من المشاركة ثم اضغط إرسال. الملف الأصلي محفوظ.", android.widget.Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            android.widget.Toast.makeText(this, "تعذر فتح مشاركة الملف المشفّر: ${e.message ?: "خطأ"}", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    private fun sendSecureCompose() {
        if (composeBuffer.isEmpty()) {
            android.widget.Toast.makeText(this, R.string.crypto_panel_empty_field_toast, android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        if (cryptoBusy) return
        val passphrase = SessionKeyStore.get()
        if (passphrase == null) {
            android.widget.Toast.makeText(this, R.string.crypto_panel_no_session, android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val textChars = composeBuffer.toString().toCharArray()
        cryptoBusy = true
        android.widget.Toast.makeText(this, R.string.crypto_panel_encrypting_toast, android.widget.Toast.LENGTH_SHORT).show()
        cryptoExecutor.execute {
            var cipherText: String? = null
            try {
                cipherText = if (selectedSecureContact == null) {
                    CryptoEngine.encrypt(textChars, passphrase, expirySeconds = null)
                } else {
                    val keyB64 = ContactStore.getPairedContact(this, selectedSecureContact!!)
                        ?: throw IllegalStateException("paired contact missing")
                    val publicKey = DeviceIdentity.parseContactPublicKey(keyB64)
                    CryptoEngineV2.encrypt(this, textChars, passphrase, publicKey, expirySeconds = null)
                }
            } finally {
                java.util.Arrays.fill(textChars, ' ')
                java.util.Arrays.fill(passphrase, ' ')
            }
            mainHandler.post {
                cryptoBusy = false
                // The ONLY thing that ever reaches the target app's field
                // from this whole page.
                currentInputConnection?.commitText(cipherText, 1)
                // Clear the draft for the next message, but deliberately
                // do NOT touch showingSecureCompose/showingCrypto here -
                // sending a message no longer kicks the user back to the
                // normal (non-secure) keyboard. They stay right here,
                // ready to type the next message or decrypt a reply,
                // until they explicitly tap "رجوع".
                composeBuffer.setLength(0)
                currentWord.clear()
                lastFinishedWord = null
                android.widget.Toast.makeText(this, R.string.crypto_panel_sent_toast, android.widget.Toast.LENGTH_SHORT).show()
                rebuildKeyboardView()
            }
        }
    }

    /**
     * Same clipboard-decrypt logic as decryptClipboard() below, but for
     * use from INSIDE secure-compose mode: shows the result on this same
     * screen (via buildSecureComposeDecryptedResult) instead of switching
     * to the separate crypto menu page. showingCrypto is never set here,
     * so rebuildKeyboardView() lands back on buildSecureComposePage. The
     * Argon2id+AES work runs on cryptoExecutor - see sendSecureCompose's
     * doc for why.
     */
    private fun decryptClipboardInPlace() {
        if (cryptoBusy) return
        val passphrase = SessionKeyStore.get()
        if (passphrase == null) {
            android.widget.Toast.makeText(this, R.string.crypto_panel_no_session, android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val cm = getSystemService(CLIPBOARD_SERVICE) as? ClipboardManager
        val clip = cm?.primaryClip
        val clipText = if (clip != null && clip.itemCount > 0) clip.getItemAt(0).text?.toString() else null
        if (clipText.isNullOrBlank()) {
            java.util.Arrays.fill(passphrase, ' ')
            android.widget.Toast.makeText(this, R.string.crypto_panel_no_ciphertext_toast, android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        cryptoBusy = true
        android.widget.Toast.makeText(this, R.string.crypto_panel_decrypting_toast, android.widget.Toast.LENGTH_SHORT).show()
        cryptoExecutor.execute {
            var resultText: String? = null
            var expired = false
            var failed = false
            try {
                val plainChars = if (selectedSecureContact == null) {
                    CryptoEngine.decrypt(clipText, passphrase)
                } else {
                    val keyB64 = ContactStore.getPairedContact(this, selectedSecureContact!!)
                        ?: throw IllegalStateException("paired contact missing")
                    val publicKey = DeviceIdentity.parseContactPublicKey(keyB64)
                    CryptoEngineV2.decrypt(this, clipText, passphrase, publicKey)
                }
                try {
                    resultText = String(plainChars)
                } finally {
                    java.util.Arrays.fill(plainChars, ' ')
                }
            } catch (e: CryptoEngine.ExpiredMessageException) {
                expired = true
            } catch (e: CryptoEngineV2.ExpiredMessageException) {
                expired = true
            } catch (e: Exception) {
                failed = true
            } finally {
                java.util.Arrays.fill(passphrase, ' ')
            }
            mainHandler.post {
                cryptoBusy = false
                when {
                    resultText != null -> {
                        cryptoDecryptedText = resultText
                        rebuildKeyboardView()
                    }
                    expired -> android.widget.Toast.makeText(this, R.string.crypto_panel_expired_toast, android.widget.Toast.LENGTH_SHORT).show()
                    else -> android.widget.Toast.makeText(this, R.string.crypto_panel_decrypt_failed_toast, android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /**
     * Reads the ENTIRE content of the currently focused field (not just
     * text around the cursor - see ExtractedText below), encrypts it
     * with the active session passphrase, and replaces the field's
     * content with the ciphertext directly via the InputConnection. No
     * expiry is attached here (that option only exists on the full
     * Encrypt screen) and the plaintext never touches the clipboard. The
     * Argon2id+AES work runs on cryptoExecutor - see sendSecureCompose's
     * doc for why.
     */
    private fun encryptFieldAndInject() {
        if (cryptoBusy) return
        val passphrase = SessionKeyStore.get()
        if (passphrase == null) {
            android.widget.Toast.makeText(this, R.string.crypto_panel_no_session, android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val ic = currentInputConnection
        if (ic == null) {
            java.util.Arrays.fill(passphrase, ' ')
            return
        }
        val extracted = ic.getExtractedText(ExtractedTextRequest(), 0)
        val fullText = extracted?.text?.toString().orEmpty()
        if (fullText.isEmpty()) {
            java.util.Arrays.fill(passphrase, ' ')
            android.widget.Toast.makeText(this, R.string.crypto_panel_empty_field_toast, android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val textChars = fullText.toCharArray()
        cryptoBusy = true
        android.widget.Toast.makeText(this, R.string.crypto_panel_encrypting_toast, android.widget.Toast.LENGTH_SHORT).show()
        cryptoExecutor.execute {
            var cipherText: String? = null
            try {
                cipherText = if (selectedSecureContact == null) {
                    CryptoEngine.encrypt(textChars, passphrase, expirySeconds = null)
                } else {
                    val keyB64 = ContactStore.getPairedContact(this, selectedSecureContact!!)
                        ?: throw IllegalStateException("paired contact missing")
                    val publicKey = DeviceIdentity.parseContactPublicKey(keyB64)
                    CryptoEngineV2.encrypt(this, textChars, passphrase, publicKey, expirySeconds = null)
                }
            } finally {
                java.util.Arrays.fill(textChars, ' ')
                java.util.Arrays.fill(passphrase, ' ')
            }
            mainHandler.post {
                cryptoBusy = false
                // Re-fetch the InputConnection fresh here rather than
                // reusing the one captured above - focus could in theory
                // have moved on during the ~1-2s of background work, and
                // InputConnection calls must happen on the main thread.
                val freshIc = currentInputConnection ?: return@post
                freshIc.setSelection(0, fullText.length)
                freshIc.commitText(cipherText, 1)
                // The field now holds ciphertext, not a word being typed.
                currentWord.clear()
                lastFinishedWord = null
            }
        }
    }

    /**
     * Reads the system clipboard, and - only if it looks like something
     * this app itself produced (see CryptoEngine.looksLikeCiphertext) -
     * attempts to decrypt it with the active session passphrase. Shows
     * the result as a floating popup OVER whatever page is currently on
     * screen (see showDecryptedResultPopup) - it does NOT navigate to a
     * different keyboard page, so letters/symbols/emoji/secure-compose/
     * crypto-menu, whichever was showing, is completely undisturbed
     * underneath and still there the moment the popup is dismissed.
     * Never writes the decrypted text into any field, clipboard, or file.
     *
     * Called both from the crypto panel's own "فك التشفير" button AND
     * directly from the main keyboard row's 🔓 shortcut key (see
     * bottomRow in buildLettersPage) - either way it's a genuine single
     * tap: check clipboard + decrypt + show result, without detouring
     * through the crypto menu first. The Argon2id+AES work runs on
     * cryptoExecutor - see sendSecureCompose's doc for why.
     */
    private fun decryptClipboard() {
        if (cryptoBusy) return
        SessionKeyStore.initialize(this)
        val passphrase = SessionKeyStore.get()
        if (passphrase == null) {
            android.widget.Toast.makeText(this, R.string.crypto_panel_no_session, android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val cm = getSystemService(CLIPBOARD_SERVICE) as? ClipboardManager
        val clip = try { cm?.primaryClip } catch (_: Exception) { null }
        val clipText = try {
            if (clip != null && clip.itemCount > 0) {
                clip.getItemAt(0).coerceToText(this)?.toString()?.trim()
            } else null
        } catch (_: Exception) { null }
        if (clipText.isNullOrBlank()) {
            java.util.Arrays.fill(passphrase, ' ')
            android.widget.Toast.makeText(this, R.string.crypto_panel_no_ciphertext_toast, android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        cryptoBusy = true
        android.widget.Toast.makeText(this, R.string.crypto_panel_decrypting_toast, android.widget.Toast.LENGTH_SHORT).show()
        cryptoExecutor.execute {
            var resultText: String? = null
            var expired = false
            try {
                // First try the selected contact, then every paired contact.
                // This is important after an IME recreation: Android may tear
                // down the keyboard service and selectedSecureContact becomes
                // null even though the copied ciphertext is still valid.
                val candidates = LinkedHashSet<String>()
                selectedSecureContact?.let { candidates.add(it) }
                candidates.addAll(ContactStore.listPairedContactNames(this))

                for (contactName in candidates) {
                    try {
                        val keyB64 = ContactStore.getPairedContact(this, contactName) ?: continue
                        val publicKey = DeviceIdentity.parseContactPublicKey(keyB64)
                        val plainChars = CryptoEngineV2.decrypt(this, clipText, passphrase, publicKey)
                        try {
                            resultText = String(plainChars)
                        } finally {
                            java.util.Arrays.fill(plainChars, ' ')
                        }
                        if (resultText != null) break
                    } catch (e: CryptoEngineV2.ExpiredMessageException) {
                        expired = true
                    } catch (_: Exception) {
                        // Try the next paired contact or passphrase-only mode.
                    }
                }

                if (resultText == null) {
                    try {
                        val plainChars = CryptoEngine.decrypt(clipText, passphrase)
                        try {
                            resultText = String(plainChars)
                        } finally {
                            java.util.Arrays.fill(plainChars, ' ')
                        }
                    } catch (e: CryptoEngine.ExpiredMessageException) {
                        expired = true
                    }
                }
            } finally {
                java.util.Arrays.fill(passphrase, ' ')
            }
            mainHandler.post {
                cryptoBusy = false
                when {
                    resultText != null -> showDecryptedResultPopup(resultText!!)
                    expired -> android.widget.Toast.makeText(this, R.string.crypto_panel_expired_toast, android.widget.Toast.LENGTH_SHORT).show()
                    else -> android.widget.Toast.makeText(this, R.string.crypto_panel_decrypt_failed_toast, android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /**
     * Shows decrypted plaintext as a small floating PopupWindow anchored
     * over whatever keyboard page is currently on screen - the SAME
     * pattern already used for the letter-variant popup (showVariantPopup
     * above), just for reading text instead of picking a character. The
     * page underneath (letters, symbols, secure-compose, crypto menu -
     * whichever it was) is never rebuilt or navigated away from; it's
     * simply covered for a moment and fully intact once this is
     * dismissed, either by the close button or by tapping outside it.
     * The text is read-only here on purpose - nothing decrypted this way
     * is ever written into a field, the clipboard, or a file.
     */
    private fun showDecryptedResultPopup(text: String) {
        val anchor = keyboardRootView ?: return
        val padding = dpToPx(12f)

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = ThemeUtil.keyBackgroundSelector(this@SecureInputMethodService, accented = false)
            elevation = dpToPx(8f).toFloat()
            setPadding(padding, padding, padding, padding)
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }

        val titleView = TextView(this).apply {
            this.text = getString(R.string.crypto_panel_decrypted_title)
            setTextColor(ThemeUtil.accentColor(this@SecureInputMethodService))
            textSize = 13f
            textDirection = View.TEXT_DIRECTION_RTL
            setPadding(0, 0, 0, dpToPx(6f))
        }
        container.addView(titleView)

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                dpToPx(260f),
                dpToPx(130f)
            )
        }
        val textView = TextView(this).apply {
            this.text = text
            setTextColor(Color.WHITE)
            textSize = 15f
            textDirection = View.TEXT_DIRECTION_RTL
        }
        scroll.addView(textView)
        container.addView(scroll)

        lateinit var popup: PopupWindow
        val closeButton = TextView(this).apply {
            this.text = getString(R.string.crypto_panel_close_btn)
            setTextColor(ThemeUtil.textOnAccentColor(this@SecureInputMethodService))
            setBackgroundColor(ThemeUtil.accentColor(this@SecureInputMethodService))
            gravity = Gravity.CENTER
            textSize = 14f
            setPadding(0, dpToPx(10f), 0, dpToPx(10f))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dpToPx(8f) }
            setOnClickListener { popup.dismiss() }
        }
        container.addView(closeButton)

        val popupWidth = minOf(dpToPx(280f), (anchor.width * 0.92f).toInt().coerceAtLeast(dpToPx(220f)))
        popup = PopupWindow(
            container,
            popupWidth,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            false
        )
        // A focusable PopupWindow can cause some Android 12-14 IMEs/OEMs to
        // recreate or finish the input view. Keep it non-focusable but
        // touchable so the close button and outside-dismiss still work.
        popup.isTouchable = true
        popup.isOutsideTouchable = true
        popup.isClippingEnabled = false
        popup.inputMethodMode = PopupWindow.INPUT_METHOD_NOT_NEEDED
        popup.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
        popup.elevation = dpToPx(8f).toFloat()
        popup.showAtLocation(anchor, Gravity.CENTER, 0, 0)
    }

    /**
     * The space key, shared by every page (letters, symbols): finishes
     * the current word exactly as before (optional autocorrect, then
     * LearnedDictionary/NextWordDictionary bookkeeping).
     */
    private fun spaceKey(heightDp: Int, weight: Float = 4f) =
        makeKey("مسافة", weight = weight, heightDp = heightDp, accented = true) {
            val typedWord = currentWord.toString()
            // OPT-IN autocorrect (off by default, see Settings): a tiny,
            // fixed, bundled list of common typos -> corrections (see
            // Autocorrect.kt) - never anything learned from what the user
            // types, same "fixed asset, not a model" convention as
            // WordDictionary. Only runs in non-sensitive fields, same
            // gate as everything else that touches currentWord's content.
            val correction = if (suggestionsEnabled && Prefs.autocorrectEnabled(this)) {
                Autocorrect.correct(typedWord)
            } else null
            if (correction != null) {
                currentInputConnection?.deleteSurroundingText(typedWord.length, 0)
                currentInputConnection?.commitText(correction, 1)
            }
            currentInputConnection?.commitText(" ", 1)
            val finishedWord = correction ?: typedWord
            if (suggestionsEnabled && finishedWord.isNotEmpty()) {
                LearnedDictionary.learn(this@SecureInputMethodService, finishedWord)
                // Learn the sentence-so-far at every space, not only on Enter.
                // This is what allows phrases such as "السلام عليكم ورحمة الله
                // وبركاته" to become predictive while the user is still typing.
                val lineSoFar = currentInputConnection?.getTextBeforeCursor(500, 0)
                    ?.toString()
                    ?.substringAfterLast('\n')
                    ?.trim()
                    ?: ""
                if (lineSoFar.isNotEmpty()) {
                    PhraseDictionary.learn(this@SecureInputMethodService, lineSoFar)
                }
            }
            lastFinishedWord = if (suggestionsEnabled && finishedWord.isNotEmpty()) finishedWord else null
            currentWord.clear()
            scheduleSuggestionsUpdate()
        }

    private fun deleteKey(heightDp: Int, weight: Float = 1.5f) =
        makeKey(BACKSPACE_GLYPH, weight = weight, heightDp = heightDp, accented = true, repeatable = true, a11yLabel = "حذف", isIconGlyph = true) {
            val ic = currentInputConnection
            // Android editors treat a selected range differently from a
            // normal backspace. commitText("", 1) replaces the current
            // selection with nothing, so selecting a paragraph and pressing
            // our delete key really deletes the selection instead of doing
            // nothing. With no selection we fall back to one-character
            // backspace.
            val selected = try { ic?.getSelectedText(0)?.length ?: 0 } catch (_: Exception) { 0 }
            if (selected > 0) {
                ic?.commitText("", 1)
            } else {
                // API 24+: delete one Unicode code point rather than one UTF-16
                // code unit, so emoji/supplementary characters are not split.
                try {
                    ic?.deleteSurroundingTextInCodePoints(1, 0)
                } catch (_: Exception) {
                    // OEM/editor fallback for unusual InputConnection implementations.
                    ic?.deleteSurroundingText(1, 0)
                }
            }
            lastFinishedWord = null
            resyncCurrentWordFromField()
        }

    private fun enterKey(heightDp: Int, weight: Float = 1.5f) =
        makeKey(ENTER_GLYPH, weight = weight, heightDp = heightDp, accented = true, a11yLabel = "إدخال", isIconGlyph = true) {
            val typedWord = currentWord.toString()
            val correction = if (suggestionsEnabled && Prefs.autocorrectEnabled(this)) {
                Autocorrect.correct(typedWord)
            } else null
            val ic = currentInputConnection
            if (correction != null) {
                ic?.deleteSurroundingText(typedWord.length, 0)
                ic?.commitText(correction, 1)
            }
            val finishedWord = correction ?: typedWord
            // Grab the whole line being finished BEFORE sending Enter -
            // some apps submit/clear the field the instant Enter
            // arrives, so there'd be nothing left to read afterward.
            val lineBeforeCursor = ic?.getTextBeforeCursor(500, 0)?.toString() ?: ""
            val finishedLine = lineBeforeCursor.substringAfterLast('\n').trim()
            ic?.sendKeyEvent(
                android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_ENTER)
            )
            if (suggestionsEnabled) {
                if (finishedWord.isNotEmpty()) {
                    LearnedDictionary.learn(this@SecureInputMethodService, finishedWord)
                }
                if (finishedLine.isNotEmpty()) {
                    PhraseDictionary.learn(this@SecureInputMethodService, finishedLine)
                }
                lastFinishedWord = if (finishedWord.isNotEmpty()) finishedWord else null
            } else {
                lastFinishedWord = null
            }
            currentWord.clear()
            scheduleSuggestionsUpdate()
        }

    private fun makeKey(
        label: String,
        weight: Float = 1f,
        heightDp: Int = Prefs.DEFAULT_KEYBOARD_HEIGHT_DP,
        accented: Boolean = false,
        variants: List<String>? = null,
        tatweelExtend: Boolean = false,
        repeatable: Boolean = false,
        // Set this when `label` is a glyph/icon (e.g. ⌫) rather than a
        // real word, so screen readers still announce something
        // meaningful instead of reading out the raw Unicode symbol.
        a11yLabel: String? = null,
        // Icon glyphs read visually "thinner" than letters at the same
        // sp size, so icon keys get a slightly larger size by default -
        // purely cosmetic, doesn't change hit target/layout weight.
        isIconGlyph: Boolean = false,
        onClick: (() -> Unit)? = null
    ): TextView {
        return TextView(this).apply {
            text = label
            contentDescription = a11yLabel ?: label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (isIconGlyph) 21f else 18f)
            gravity = Gravity.CENTER
            isAllCaps = false
            includeFontPadding = false
            // The root cause of the original "letters render as tiny
            // symbols" bug: a plain Button carries a Material-style
            // minWidth (~48-88dp) and internal padding baked into the
            // theme. On a 12-key row each key's actual available width
            // is far smaller than that, so the padding ate almost all
            // the space and clipped the glyph down to a sliver (often
            // just a dot). A TextView has no such baked-in minimum/
            // padding, so it uses the full narrow width for the
            // character itself.
            minWidth = 0
            minHeight = 0
            setPadding(0, 0, 0, 0)
            typeface = Typeface.create(Fonts.currentTypeface(this@SecureInputMethodService) as Typeface, Typeface.NORMAL)
            setTextColor(
                if (accented) ThemeUtil.accentColor(this@SecureInputMethodService)
                else ThemeUtil.textColor(this@SecureInputMethodService)
            )
            background = ThemeUtil.keyBackgroundSelector(this@SecureInputMethodService, accented)
            // Real elevation (not a drawn trick) so the key reads as a
            // raised card over the darker keyboard surface - part of the
            // "modern, not flat" visual refresh. Dropped briefly on
            // press for tactile feedback, see the touch listener below.
            ThemeUtil.applyPressedElevation(this, pressed = false)
            isClickable = true
            isFocusable = true
            // Security/UX policy: this keyboard does not vibrate on key
            // presses. Some Android 14/OEM combinations can otherwise
            // produce a vibration even when the app did not request it.
            // dp -> px conversion is what makes this consistent across
            // screen densities, instead of the old raw-pixel constant.
            val lp = LinearLayout.LayoutParams(0, dpToPx(heightDp.toFloat()), weight)
            val marginPx = dpToPx(2f)
            lp.setMargins(marginPx, marginPx, marginPx, marginPx)
            layoutParams = lp

            // Per-key gesture state. These are local vars captured by the
            // touch listener closure below, so each key gets its own
            // independent state (never shared across keys) even though
            // they all post to the single shared longPressHandler.
            var pendingLongPress: Runnable? = null
            var popup: PopupWindow? = null
            var popupContent: LinearLayout? = null
            var selectedVariantIndex = 0
            var isTatweelRepeating = false
            // BUG FIX: holding backspace used to delete exactly one
            // character, the same as a single tap, because makeKey only
            // ever wired up a hold-to-repeat timer for the tatweel-extend
            // and accent-popup cases - there was no generic "repeat this
            // key's own onClick while held" path at all. `repeatable`
            // keys (currently just backspace) now get that generic path.
            var isKeyRepeating = false

            setOnTouchListener { v, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        v.isPressed = true
                        // No haptic feedback: keyboard input is intentionally
                        // silent on all supported Android versions.
                        ThemeUtil.applyPressedElevation(v, pressed = true)
                        isTatweelRepeating = false
                        isKeyRepeating = false
                        selectedVariantIndex = 0
                        when {
                            variants != null -> {
                                val r = Runnable {
                                    val (pw, content) = showVariantPopup(v, variants)
                                    popup = pw
                                    popupContent = content
                                    highlightVariantChip(content, 0)
                                }
                                pendingLongPress = r
                                longPressHandler.postDelayed(r, LONG_PRESS_MS)
                            }
                            tatweelExtend -> {
                                lateinit var repeatRunnable: Runnable
                                repeatRunnable = Runnable {
                                    isTatweelRepeating = true
                                    currentInputConnection?.commitText("ـ", 1)
                                    longPressHandler.postDelayed(repeatRunnable, TATWEEL_REPEAT_MS)
                                }
                                pendingLongPress = repeatRunnable
                                longPressHandler.postDelayed(repeatRunnable, LONG_PRESS_MS)
                            }
                            repeatable -> {
                                // Generic hold-to-repeat: after the same
                                // LONG_PRESS_MS delay used everywhere else
                                // in this keyboard, fire the key's own
                                // onClick repeatedly every KEY_REPEAT_MS
                                // for as long as it's held - e.g. holding
                                // backspace now deletes continuously
                                // instead of stopping at one character.
                                lateinit var repeatRunnable: Runnable
                                repeatRunnable = Runnable {
                                    isKeyRepeating = true
                                    onClick?.invoke()
                                    longPressHandler.postDelayed(repeatRunnable, KEY_REPEAT_MS)
                                }
                                pendingLongPress = repeatRunnable
                                longPressHandler.postDelayed(repeatRunnable, LONG_PRESS_MS)
                            }
                        }
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val content = popupContent
                        if (content != null) {
                            selectedVariantIndex = variantIndexForRawX(content, variants?.size ?: 1, event.rawX)
                            highlightVariantChip(content, selectedVariantIndex)
                        }
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        v.isPressed = false
                        ThemeUtil.applyPressedElevation(v, pressed = false)
                        pendingLongPress?.let { longPressHandler.removeCallbacks(it) }
                        pendingLongPress = null
                        val pw = popup
                        when {
                            pw != null -> {
                                // Popup was showing: commit whichever
                                // variant is currently highlighted (drag
                                // to change it before lifting, exactly
                                // like Gboard's accent popups).
                                val chosen = variants?.getOrElse(selectedVariantIndex) { label } ?: label
                                commitLetter(chosen)
                                pw.dismiss()
                                popup = null
                                popupContent = null
                            }
                            isTatweelRepeating -> {
                                // The hold-repeat already inserted ـ
                                // characters directly; nothing left to
                                // commit on release.
                                isTatweelRepeating = false
                            }
                            isKeyRepeating -> {
                                // The hold-repeat already invoked onClick
                                // one or more times directly; don't also
                                // fire it again on release, or lifting
                                // the finger after a long hold would
                                // delete one extra character.
                                isKeyRepeating = false
                            }
                            else -> {
                                // Normal short tap - unchanged behavior:
                                // letter rows pass their own onClick
                                // (which calls commitLetter to also track
                                // the word for suggestions); keys with no
                                // onClick (number row) just plain-commit,
                                // exactly like before this change.
                                onClick?.invoke() ?: currentInputConnection?.commitText(label, 1)
                            }
                        }
                        true
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        v.isPressed = false
                        ThemeUtil.applyPressedElevation(v, pressed = false)
                        pendingLongPress?.let { longPressHandler.removeCallbacks(it) }
                        pendingLongPress = null
                        popup?.dismiss()
                        popup = null
                        popupContent = null
                        isTatweelRepeating = false
                        isKeyRepeating = false
                        true
                    }
                    else -> false
                }
            }
        }
    }

    /**
     * True for every character this keyboard can actually type as part
     * of a word: the basic Arabic letter block (which covers every plain
     * letter AND every hamza form - ء through ي, i.e. 0x0621-0x064A) plus
     * tatweel (ـ), which extends a word rather than ending it - AND
     * plain ASCII letters, now that the EN page (see letterMode) can
     * type those too. Anything else (space, digits, punctuation,
     * newline) is a word boundary.
     */
    private fun isArabicWordChar(c: Char): Boolean {
        return c == '\u0640' || (c.code in 0x0621..0x064A) || c in 'a'..'z' || c in 'A'..'Z'
    }

    /**
     * Re-derives [currentWord] directly from the real field content
     * around the cursor, instead of trusting this class's own forward-
     * only bookkeeping. This is what makes backspacing back over a space
     * into an already-finished word - or tapping the cursor into the
     * middle of one - restore that FULL word instead of leaving the
     * suggestion bar blank and treating the next letter as a new word.
     *
     * Reads only a short, bounded window of text immediately before the
     * cursor (never the whole field) purely to locate the current word's
     * start - this is not a history buffer, and nothing read here is
     * stored anywhere beyond this in-memory StringBuilder's own lifetime.
     */
    private fun resyncCurrentWordFromField() {
        if (!suggestionsEnabled) {
            if (currentWord.isNotEmpty()) {
                currentWord.setLength(0)
                scheduleSuggestionsUpdate()
            }
            return
        }
        val ic = currentInputConnection ?: return
        val before = ic.getTextBeforeCursor(64, 0)?.toString() ?: ""
        var start = before.length
        while (start > 0 && isArabicWordChar(before[start - 1])) start--
        val word = before.substring(start)
        if (word != currentWord.toString()) {
            currentWord.setLength(0)
            currentWord.append(word)
            scheduleSuggestionsUpdate()
        }
    }

    /**
     * Fires whenever the cursor/selection in the focused field changes -
     * including when the user taps to move the cursor somewhere else
     * entirely, not just as a side effect of this keyboard's own key
     * presses. Re-syncing here (in addition to after backspace) is what
     * makes moving the cursor back INTO a previously-typed word - by
     * tapping, not only by backspacing - also restore it for
     * suggestions.
     */
    override fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        if (oldSelStart == newSelStart && oldSelEnd == newSelEnd) return
        resyncCurrentWordFromField()
    }

    /**
     * Commits a single letter/variant to the field AND treats it as
     * extending the current word for suggestion purposes - the same
     * bookkeeping every plain letter key did before, now shared by both
     * a normal tap and a hamza-variant popup selection.
     */
    private fun commitLetter(ch: String) {
        currentInputConnection?.commitText(ch, 1)
        currentWord.append(ch)
        scheduleSuggestionsUpdate()
    }

    /**
     * Builds and shows the small horizontal popup of variant letters
     * above [anchor] (e.g. ا / أ / إ / آ above the ا key). The popup is
     * non-touchable itself - the anchor key keeps receiving the same
     * touch gesture (ACTION_MOVE/UP) for as long as the finger is down,
     * which is what lets the caller do drag-to-select against it.
     */
    private fun showVariantPopup(anchor: View, variants: List<String>): Pair<PopupWindow, LinearLayout> {
        val chipSizePx = dpToPx(42f)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            background = ThemeUtil.keyBackgroundSelector(this@SecureInputMethodService, accented = false)
            elevation = dpToPx(6f).toFloat()
            setPadding(dpToPx(3f), dpToPx(3f), dpToPx(3f), dpToPx(3f))
            for (v in variants) {
                addView(TextView(this@SecureInputMethodService).apply {
                    text = v
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                    gravity = Gravity.CENTER
                    includeFontPadding = false
                    typeface = Typeface.create(Fonts.currentTypeface(this@SecureInputMethodService) as Typeface, Typeface.NORMAL)
                    layoutParams = LinearLayout.LayoutParams(chipSizePx, chipSizePx)
                })
            }
        }

        val popup = PopupWindow(content, LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT, false)
        popup.isTouchable = false
        popup.isClippingEnabled = false

        val widthSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        content.measure(widthSpec, widthSpec)
        val loc = IntArray(2)
        anchor.getLocationOnScreen(loc)
        val xOff = loc[0] + anchor.width / 2 - content.measuredWidth / 2
        val yOff = loc[1] - content.measuredHeight - dpToPx(4f)
        popup.showAtLocation(anchor, Gravity.NO_GRAVITY, xOff, yOff)
        return Pair(popup, content)
    }

    /** Finds which chip in an already-shown variant popup a raw (screen) x-coordinate is over. */
    private fun variantIndexForRawX(content: LinearLayout, count: Int, rawX: Float): Int {
        if (content.childCount == 0 || count <= 0) return 0
        val loc = IntArray(2)
        content.getLocationOnScreen(loc)
        val localX = rawX - loc[0]
        val childWidth = content.getChildAt(0).width.takeIf { it > 0 } ?: 1
        return (localX / childWidth).toInt().coerceIn(0, count - 1)
    }

    /** Visually marks the currently drag-selected chip in a variant popup with the accent color. */
    private fun highlightVariantChip(content: LinearLayout, selected: Int) {
        for (i in 0 until content.childCount) {
            val chip = content.getChildAt(i) as? TextView ?: continue
            if (i == selected) {
                chip.setBackgroundColor(ThemeUtil.accentColor(this))
                chip.setTextColor(ThemeUtil.textOnAccentColor(this))
            } else {
                chip.setBackgroundColor(Color.TRANSPARENT)
                chip.setTextColor(ThemeUtil.textColor(this))
            }
        }
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        // No state from a previous session is loaded here - intentionally.
        // Switching into (or back to) a field always starts with a clean
        // word buffer, never whatever was being typed in a previous field.
        currentWord.clear()
        lastFinishedWord = null
        // A fresh field always starts on the letters page - the symbols/
        // emoji pages are a per-field-visit detour, not a sticky choice.
        // The AR/EN letter choice itself (letterMode) is left as-is,
        // same as a real keyboard remembering the language you were
        // just using.
        //
        // Secure-compose mode, and the crypto MENU page, are the two
        // exceptions: while secureComposeSticky/cryptoMenuSticky is set,
        // they survive this reset instead of silently dropping back to
        // the normal keyboard. Without this, tapping the target app's
        // own send button - which can drop and re-grant this keyboard's
        // input focus - would exit secure mode on every single message,
        // and opening "نافذة تركيب منفصلة" (a real separate Activity,
        // which drops focus the same way switching apps does) would
        // dump the user on the plain letters page instead of the crypto
        // menu they came from. Only the explicit "رجوع"/"ابجد" button on
        // each respective screen turns its stickiness off.
        val restoreCrypto = Prefs.consumeReturnToCrypto(this)
        if (restoreCrypto) {
            cryptoMenuSticky = true
        }
        val cameFromOverlay = showingSymbols || showingEmoji || showingCrypto || showingSecureCompose || restoreCrypto
        showingSymbols = false
        showingEmoji = false
        // Sensitive plaintext from a previous decrypt never survives a
        // refocus, sticky or not - only the compose screen itself does.
        cryptoDecryptedText = null
        when {
            secureComposeSticky -> {
                showingSecureCompose = true
                showingCrypto = false
                composePreviewView = null
            }
            cryptoMenuSticky -> {
                showingCrypto = true
                clearSecureCompose()
            }
            else -> {
                showingCrypto = false
                clearSecureCompose()
                selectedSecureContact = null
            }
        }

        // Suggestions are opt-OUT per field, driven entirely by what the
        // app being typed into declares - never by anything this keyboard
        // remembers. A password field, or any field explicitly marked
        // "no suggestions" (this app's own EncryptActivity marks its
        // message/key fields this way - see activity_encrypt.xml),
        // disables the suggestion strip for that field.
        val inputType = info?.inputType ?: InputType.TYPE_NULL
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        val isPassword = inputType and InputType.TYPE_MASK_CLASS == InputType.TYPE_CLASS_TEXT &&
            (variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)
        val noSuggestionsFlag = inputType and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS != 0
        val isTextClass = inputType and InputType.TYPE_MASK_CLASS == InputType.TYPE_CLASS_TEXT
        suggestionsEnabled = isTextClass && !isPassword && !noSuggestionsFlag
        scheduleSuggestionsUpdate()

        // Live-apply a height, accent-color, or day/night change made
        // since this keyboard view was last built, without requiring the
        // user to force-stop/restart the app for it to take effect.
        val currentHeight = Prefs.keyboardHeightDp(this)
        val currentAccent = Prefs.accentColorRes(this)
        val nightMode = currentNightMode()
        if (cameFromOverlay || currentHeight != appliedHeightDp || currentAccent != appliedAccentRes || nightMode != appliedNightMode) {
            setInputView(onCreateInputView())
        }
    }

    /**
     * FIXED: this used to read the SYSTEM's day/night bit
     * (resources.configuration.uiMode), which is what left the keyboard
     * ignoring the app's own "الوضع الليلي/النهاري" setting entirely -
     * see the long comment on ThemeUtil.themedContext() for the full
     * story. Prefs.isDarkMode() is the single source of truth now, so
     * that's what decides whether the keyboard needs rebuilding here too.
     */
    private fun currentNightMode(): Int = if (Prefs.isDarkMode(this)) 1 else 0

    /**
     * Catches the case where the system's dark/light mode changes WHILE
     * this keyboard is already on screen. This app's own night setting is
     * the real source of truth (see currentNightMode()) and doesn't fire
     * this callback on its own - onStartInputView already re-checks it on
     * every focus. This override just makes sure a genuine SYSTEM dark-
     * mode flip doesn't leave stale elevation/shadow rendering behind
     * without at least a redraw, even though it no longer changes which
     * color palette is used.
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (isInputViewShown) {
            setInputView(onCreateInputView())
        }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        // Leaving the field entirely - drop the in-progress word rather
        // than let it linger in memory for a session that's now over.
        currentWord.clear()
        lastFinishedWord = null
        cryptoDecryptedText = null
        cryptoStatusView = null
        contactPopup?.dismiss()
        // Safe to unconditionally drop here even with cryptoMenuSticky
        // set - onStartInputView is what actually decides whether to
        // restore the crypto menu on the next focus, based on that flag,
        // not whatever this flag happens to be while the view is hidden.
        showingCrypto = false
        // See the matching comment in onStartInputView: while
        // secureComposeSticky is set, secure-compose mode survives this
        // keyboard view being torn down (e.g. the target app briefly
        // hiding the keyboard after its own send button is tapped) so
        // the next onStartInputView reopens straight back into it,
        // instead of the user having to re-enter it for every message.
        if (!secureComposeSticky) {
            clearSecureCompose()
        }
        scheduleSuggestionsUpdate()
        // Cancel any in-flight long-press/tatweel-repeat timer so it
        // can't fire against a key view that's about to be torn down.
        longPressHandler.removeCallbacksAndMessages(null)
    }

    /**
     * Shuts down the background crypto thread (see cryptoExecutor above)
     * when the whole service is torn down - e.g. the user switches to a
     * different keyboard app entirely, not just hiding this one
     * temporarily (that's onFinishInputView, which deliberately leaves
     * the executor running so a job already in flight can still finish
     * and post its result).
     */
    override fun onDestroy() {
        if (attachmentReceiverRegistered) {
            try { unregisterReceiver(attachmentReceiver) } catch (_: Exception) {}
            attachmentReceiverRegistered = false
        }
        contactPopup?.dismiss()
        mainHandler.removeCallbacks(suggestionUpdateRunnable)
        super.onDestroy()
        cryptoExecutor.shutdownNow()
    }

    /**
     * Rebuilds the suggestion strip's contents from [currentWord] and
     * shows/hides the bar. Called after every key press that can change
     * the current word (letters, backspace) and whenever suggestions are
     * turned on/off for the focused field.
     */
    private fun scheduleSuggestionsUpdate() {
        mainHandler.removeCallbacks(suggestionUpdateRunnable)
        mainHandler.postDelayed(suggestionUpdateRunnable, 16L)
    }

    private fun updateSuggestions() {
        val bar = suggestionBar ?: return
        bar.removeAllViews()

        if (!suggestionsEnabled) {
            // INVISIBLE, not GONE: keeps the bar's space reserved so the
            // key rows below never jump as suggestions come and go.
            bar.visibility = View.INVISIBLE
            return
        }

        val suggestions = if (currentWord.isEmpty()) {
            // At the start of a new word, prefer a saved phrase continuation
            // based on the COMPLETE text before the cursor. This is more useful
            // than looking only at the last word: "السلام عليكم " can now offer
            // "ورحمة الله وبركاته" from the user's own learned phrases.
            val context = currentInputConnection?.getTextBeforeCursor(500, 0)
                ?.toString()
                ?.substringAfterLast('\n')
                ?.trim()
                ?: ""
            val phraseSuggestions = if (context.isNotEmpty()) {
                PhraseDictionary.suggestionsForContext(context, max = 2)
                    .map { Suggestion(it, isPhrase = true) }
            } else {
                emptyList()
            }
            if (phraseSuggestions.isNotEmpty()) {
                phraseSuggestions
            } else {
                // No learned phrase matches: fall back to the fixed next-word
                // dictionary using the immediately preceding word.
                lastFinishedWord?.let { prev ->
                    NextWordDictionary.suggestionsFor(prev).map { Suggestion(it, isPhrase = false) }
                } ?: emptyList()
            }
        } else {
            mergedSuggestions(currentWord.toString())
        }

        if (suggestions.isEmpty()) {
            bar.visibility = View.INVISIBLE
            return
        }

        val heightDp = Prefs.keyboardHeightDp(this)
        for ((index, suggestion) in suggestions.withIndex()) {
            if (index > 0) {
                // Thin vertical rule BETWEEN chips only (never before the
                // first or after the last), so 3+ suggestions read as
                // clearly separated options instead of one run-on strip.
                bar.addView(View(this).apply {
                    setBackgroundColor(ThemeUtil.suggestionDividerColor(this@SecureInputMethodService))
                    layoutParams = LinearLayout.LayoutParams(dpToPx(1f), dpToPx((heightDp * 0.72f) * 0.5f)).apply {
                        gravity = Gravity.CENTER_VERTICAL
                    }
                })
            }
            val chip = TextView(this).apply {
                text = suggestion.display
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                gravity = Gravity.CENTER
                includeFontPadding = false
                typeface = Typeface.create(Fonts.currentTypeface(this@SecureInputMethodService) as Typeface, Typeface.NORMAL)
                // A saved whole-sentence suggestion gets the accent
                // color so it visibly reads as "a full phrase you've
                // typed before", not just another single-word guess.
                setTextColor(
                    if (suggestion.isPhrase) ThemeUtil.accentColor(this@SecureInputMethodService)
                    else ThemeUtil.textColor(this@SecureInputMethodService)
                )
                background = ThemeUtil.suggestionChipBackground(this@SecureInputMethodService)
                isClickable = true
                isFocusable = true
                val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
                val marginPx = dpToPx(2f)
                lp.setMargins(marginPx, 0, marginPx, 0)
                layoutParams = lp
                setOnClickListener {
                    // Replace the in-progress word with the tapped
                    // suggestion, then a trailing space, matching normal
                    // keyboard suggestion-bar behavior. Tapping counts as
                    // "using" it, so it's learned too (same
                    // suggestionsEnabled gate as space/enter elsewhere) -
                    // a phrase reinforces PhraseDictionary, a single word
                    // reinforces LearnedDictionary, never the other one.
                    currentInputConnection?.deleteSurroundingText(currentWord.length, 0)
                    currentInputConnection?.commitText("${suggestion.display} ", 1)
                    if (suggestionsEnabled) {
                        if (suggestion.isPhrase) {
                            PhraseDictionary.learn(this@SecureInputMethodService, suggestion.display)
                        } else {
                            LearnedDictionary.learn(this@SecureInputMethodService, suggestion.display)
                        }
                    }
                    // A tapped chip finishes a word exactly like typing a
                    // space would, so the word just committed becomes the
                    // context for the NEXT round of (next-word) suggestions.
                    // A tapped whole PHRASE ends the line's train of thought
                    // rather than a single word, so it doesn't set up a
                    // next-word context the same way.
                    lastFinishedWord = if (suggestionsEnabled && !suggestion.isPhrase) {
                        suggestion.display.substringAfterLast(' ')
                    } else null
                    currentWord.clear()
                    scheduleSuggestionsUpdate()
                }
            }
            bar.addView(chip)
        }
        bar.visibility = View.VISIBLE
    }

    /** A single suggestion chip: what to show, and what learns from tapping it. */
    private data class Suggestion(val display: String, val isPhrase: Boolean)

    /**
     * Combines, in priority order: the user's own previously-SAVED FULL
     * SENTENCES that start with this word (see [PhraseDictionary], capped
     * at 2 so they can't crowd out every single-word suggestion), then
     * their learned single words (ranked by how often THEY typed them),
     * then the fixed static dictionary. Only ever called when
     * suggestionsEnabled is true, so this naturally never runs for a
     * sensitive field either.
     */
    private fun mergedSuggestions(prefix: String): List<Suggestion> {
        val out = LinkedHashSet<Suggestion>()
        for (phrase in PhraseDictionary.suggestionsFor(prefix, max = 2)) {
            out.add(Suggestion(phrase, isPhrase = true))
        }
        for (word in LearnedDictionary.suggestionsFor(prefix, 5)) {
            if (out.size >= 5) break
            out.add(Suggestion(word, isPhrase = false))
        }
        for (word in WordDictionary.suggestionsFor(prefix)) {
            if (out.size >= 5) break
            out.add(Suggestion(word, isPhrase = false))
        }
        return out.toList()
    }
}
