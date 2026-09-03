package com.securekeyboard.app

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import androidx.core.content.ContextCompat

object Prefs {
    private const val FILE = "secure_keyboard_prefs"
    private const val KEY_ACCENT = "accent_color_name"
    private const val KEY_DARK = "dark_mode"
    private const val KEY_FONT = "font_choice"
    private const val KEY_DENSITY = "density"
    private const val KEY_KEYBOARD_HEIGHT = "keyboard_height_dp"
    private const val KEY_AUTOCORRECT = "autocorrect_enabled"

    // Preferences here are only UI/configuration state, not secrets.  The
    // performance fix is to keep ONE process-local SharedPreferences handle
    // instead of reopening and rereading the same XML file on every key draw.
    // The previous implementation performed a disk-backed lookup for nearly
    // every keyboard repaint. Values remain persisted across restarts, while
    // hot-path reads are now memory-only.
    @Volatile
    private var sharedPrefs: SharedPreferences? = null

    private fun prefs(context: Context): SharedPreferences =
        sharedPrefs ?: synchronized(this) {
            sharedPrefs ?: context.applicationContext
                .getSharedPreferences(FILE, Context.MODE_PRIVATE)
                .also { sharedPrefs = it }
        }

    // Reasonable dp bounds for a comfortable-but-compact keyboard row.
    // (For reference: 1cm on a phone screen is roughly 63dp - the slider
    // lets the user go a bit under or over that instead of a single
    // hardcoded value baked into the code.)
    const val MIN_KEYBOARD_HEIGHT_DP = 40
    const val MAX_KEYBOARD_HEIGHT_DP = 72
    const val DEFAULT_KEYBOARD_HEIGHT_DP = 52

    // SECURITY/RELIABILITY FIX: the accent color used to be stored as a
    // raw android resource id (prefs.getInt(KEY_ACCENT, R.color.accent_cyan)
    // / putInt(..., colorRes)). Resource ids are NOT guaranteed stable
    // across build variants - and this project's release build has BOTH
    // minifyEnabled and shrinkResources enabled (see app/build.gradle),
    // either of which can renumber resource ids when the resource table
    // is stripped/repacked. A value saved by one installed build could
    // silently resolve to a completely different (or nonexistent)
    // resource after an app update, and ContextCompat.getColor() throws
    // Resources.NotFoundException on an invalid id - uncaught, that
    // crashes every single screen AND the keyboard itself (accentColor()
    // is called from SettingsActivity, ThemeSettingsActivity,
    // EncryptActivity, and every key drawn by the IME). A crashing
    // keyboard is a real availability problem: it can lock the user out
    // of typing in ANY app, not just this one.
    //
    // Fixed by storing a small, stable string key instead and mapping it
    // through a fixed table. An unrecognized/corrupted stored value can
    // only ever fall back to a safe default - it can never resolve to an
    // arbitrary or invalid resource id.
    private const val ACCENT_CYAN = "cyan"
    private const val ACCENT_TEAL = "teal"
    private const val ACCENT_GOLD = "gold"
    private const val ACCENT_PURPLE = "purple"

    private fun accentNameToRes(name: String?): Int = when (name) {
        ACCENT_TEAL -> R.color.accent_teal
        ACCENT_GOLD -> R.color.accent_gold
        ACCENT_PURPLE -> R.color.accent_purple
        else -> R.color.accent_cyan
    }

    private fun resToAccentName(colorRes: Int): String = when (colorRes) {
        R.color.accent_teal -> ACCENT_TEAL
        R.color.accent_gold -> ACCENT_GOLD
        R.color.accent_purple -> ACCENT_PURPLE
        else -> ACCENT_CYAN
    }

    fun accentColorRes(context: Context): Int {
        val pref = prefs(context)
        return accentNameToRes(pref.getString(KEY_ACCENT, ACCENT_GOLD))
    }

    fun setAccentColorRes(context: Context, colorRes: Int) {
        prefs(context).edit()
            .putString(KEY_ACCENT, resToAccentName(colorRes)).apply()
    }

    fun isDarkMode(context: Context): Boolean {
        val prefs = prefs(context)
        return prefs.getBoolean(KEY_DARK, true)
    }

    fun setDarkMode(context: Context, dark: Boolean) {
        prefs(context).edit()
            .putBoolean(KEY_DARK, dark).apply()
    }

    // OFF by default - autocorrect only ever touches a tiny fixed table
    // of unambiguous typos (see Autocorrect.kt), but it still changes
    // what gets typed without an explicit tap, so it stays opt-in.
    fun autocorrectEnabled(context: Context): Boolean {
        val prefs = prefs(context)
        return prefs.getBoolean(KEY_AUTOCORRECT, false)
    }

    fun setAutocorrectEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit()
            .putBoolean(KEY_AUTOCORRECT, enabled).apply()
    }

    // 0 = default sans-serif, 1 = serif, 2 = monospace
    fun fontChoice(context: Context): Int {
        val prefs = prefs(context)
        return prefs.getInt(KEY_FONT, 0)
    }

    fun setFontChoice(context: Context, choice: Int) {
        prefs(context).edit()
            .putInt(KEY_FONT, choice).apply()
    }

    // 0 = comfortable, 1 = compact
    fun density(context: Context): Int {
        val prefs = prefs(context)
        return prefs.getInt(KEY_DENSITY, 0)
    }

    fun setDensity(context: Context, value: Int) {
        prefs(context).edit()
            .putInt(KEY_DENSITY, value).apply()
    }

    /**
     * Height (in dp, NOT raw pixels) of a single keyboard key row.
     *
     * IMPORTANT FIX: the keyboard used to hardcode a raw pixel value
     * (130px) for key height. Raw pixels are NOT device-independent, so
     * the same "130" produced wildly different physical sizes depending
     * on screen density (huge on an old mdpi screen, tiny on a modern
     * xxxhdpi screen). Storing/using dp here and converting to px with
     * the device's actual density (see SecureInputMethodService.dpToPx)
     * makes the key height consistent across devices, and adjustable by
     * the user in Settings instead of a single value baked in once.
     */
    fun keyboardHeightDp(context: Context): Int {
        val prefs = prefs(context)
        val value = prefs.getInt(KEY_KEYBOARD_HEIGHT, DEFAULT_KEYBOARD_HEIGHT_DP)
        return value.coerceIn(MIN_KEYBOARD_HEIGHT_DP, MAX_KEYBOARD_HEIGHT_DP)
    }

    fun setKeyboardHeightDp(context: Context, dp: Int) {
        val clamped = dp.coerceIn(MIN_KEYBOARD_HEIGHT_DP, MAX_KEYBOARD_HEIGHT_DP)
        prefs(context).edit()
            .putInt(KEY_KEYBOARD_HEIGHT, clamped).apply()
    }
}

object Fonts {
    /**
     * IMPORTANT (fixed after a real bug report): this used to return the raw
     * generic families Typeface.SERIF / Typeface.MONOSPACE for the "classic"
     * and "mono" choices. On many ROMs (MIUI/Xiaomi in particular) those
     * generic families have NO Arabic glyph coverage at all, so Arabic text
     * silently fell back to tofu boxes or unrelated glyphs from a fallback
     * font - this is exactly what showed up as "symbols instead of letters"
     * on the keyboard.
     *
     * Typeface.DEFAULT (and its bold variant) is backed by the system's
     * default font family, which always includes a proper Arabic fallback
     * chain on stock Android and every major OEM skin. So instead of
     * swapping the font family for "classic"/"mono", we keep the same
     * Arabic-safe family and only vary the style, which gives visual
     * differentiation without ever risking broken Arabic rendering.
     */
    fun currentTypeface(context: Context): Typeface {
        return when (Prefs.fontChoice(context)) {
            1 -> Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            2 -> Typeface.create(Typeface.DEFAULT, Typeface.ITALIC)
            else -> Typeface.DEFAULT
        }
    }

    /** Recursively applies the chosen typeface to every TextView/Button/EditText in a view tree. */
    fun applyToTree(view: android.view.View, typeface: Typeface) {
        if (view is android.widget.TextView) {
            view.typeface = typeface
        }
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) {
                applyToTree(view.getChildAt(i), typeface)
            }
        }
    }
}

/**
 * Centralizes accent-color application so every screen (including the
 * theme-picker screen itself) actually reflects the chosen accent, instead
 * of each Activity manually tinting one or two buttons and forgetting the
 * rest - which was the root cause of "the theme doesn't apply" bug reports.
 */
object ThemeUtil {

    /**
     * FIXED: the keyboard (SecureInputMethodService, a plain
     * InputMethodService - not an AppCompatActivity) used to resolve all
     * of its colors straight from `context.resources`, whose day/night
     * qualifier is driven ONLY by the phone's SYSTEM dark-mode setting.
     * The in-app "الوضع الليلي/النهاري" toggle in ThemeSettingsActivity
     * only ever called AppCompatDelegate.setDefaultNightMode(), which
     * AppCompat applies to AppCompatActivity screens (Settings, theme
     * settings, the encrypt tool) by wrapping THEIR OWN Resources - it
     * has no effect on a Service. Net result: picking "الوضع الليلي"
     * inside the app correctly darkened those three screens, but the
     * actual typing keyboard kept rendering values/colors.xml (the light
     * palette) unless the PHONE itself also happened to be in system
     * dark mode - which read as "doesn't turn black, looks washed out".
     *
     * Fixed by making Prefs.isDarkMode() the single source of truth
     * everywhere, instead of two different, sometimes-disagreeing
     * signals (AppCompatDelegate for activities, system Configuration
     * for the service). Every color/drawable lookup in this object goes
     * through a Context whose night-mode bit is explicitly forced to
     * match Prefs.isDarkMode(), so values-night/colors.xml is always the
     * one actually driving the keyboard's colors when the user picked
     * night mode - regardless of what the phone's own system setting is.
     */
    private fun themedContext(context: Context): Context {
        val forced = Configuration(context.resources.configuration)
        val nightBit = if (Prefs.isDarkMode(context)) {
            Configuration.UI_MODE_NIGHT_YES
        } else {
            Configuration.UI_MODE_NIGHT_NO
        }
        forced.uiMode = (forced.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or nightBit
        return context.createConfigurationContext(forced)
    }

    fun accentColor(context: Context): Int =
        ContextCompat.getColor(themedContext(context), Prefs.accentColorRes(context))

    /**
     * The normal (non-accented) label/text color - was being read directly
     * via `resources.getColor(R.color.slate_200, theme)` in
     * SecureInputMethodService, which bypasses this object entirely and so
     * missed the same day/night fix as everything else here. Route it
     * through here instead so key labels and suggestion-chip text actually
     * follow Prefs.isDarkMode() too, not just the key/background shapes.
     */
    fun textColor(context: Context): Int =
        ContextCompat.getColor(themedContext(context), R.color.slate_200)

    /** Fixed dark color for text drawn on top of an accent-colored surface - see R.color.text_on_accent. */
    fun textOnAccentColor(context: Context): Int =
        ContextCompat.getColor(themedContext(context), R.color.text_on_accent)

    /** Tints a filled ("primary") button's background with the current accent. */
    fun tintPrimary(context: Context, vararg buttons: com.google.android.material.button.MaterialButton) {
        val tint = android.content.res.ColorStateList.valueOf(accentColor(context))
        buttons.forEach { it.backgroundTintList = tint }
    }

    /** Colors an outline ("secondary") button's stroke with the current accent. */
    fun tintOutline(context: Context, vararg buttons: com.google.android.material.button.MaterialButton) {
        val color = accentColor(context)
        buttons.forEach {
            it.strokeColor = android.content.res.ColorStateList.valueOf(color)
            it.setTextColor(color)
        }
    }

    /**
     * Draws a colored selection border around a plain swatch card (a
     * LinearLayout, not a Material widget) so the user can see which
     * accent color is active - this was missing entirely before, which
     * made color choices look like they weren't being saved.
     */
    fun setSelected(view: android.view.View, selected: Boolean, accent: Int) {
        val ctx = themedContext(view.context)
        val bg = GradientDrawable()
        bg.cornerRadius = 10f * view.resources.displayMetrics.density
        bg.setColor(ContextCompat.getColor(ctx, R.color.navy_800))
        val strokeWidthDp = if (selected) 2f else 1f
        val strokeColor = if (selected) accent else ContextCompat.getColor(ctx, R.color.navy_700)
        bg.setStroke((strokeWidthDp * view.resources.displayMetrics.density).toInt(), strokeColor)
        view.background = bg
    }

    /**
     * Same idea but for a MaterialButton (mode/font/density buttons) -
     * uses MaterialButton's own stroke properties instead of replacing its
     * background drawable outright, so we don't fight its built-in ripple
     * and corner-radius handling.
     */
    fun setSelected(
        button: com.google.android.material.button.MaterialButton,
        selected: Boolean,
        accent: Int
    ) {
        val context = button.context
        val ctx = themedContext(context)
        if (selected) {
            button.strokeWidth = (2 * context.resources.displayMetrics.density).toInt()
            button.strokeColor = android.content.res.ColorStateList.valueOf(accent)
            button.setTextColor(accent)
        } else {
            button.strokeWidth = (1 * context.resources.displayMetrics.density).toInt()
            button.strokeColor = android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(ctx, R.color.navy_700)
            )
            button.setTextColor(ContextCompat.getColor(ctx, R.color.slate_200))
        }
    }

    /**
     * Corner radius used for every key / chip surface. Updated visual system: 9dp corners keep keys soft and modern without
     * becoming oversized pills; paired with a restrained elevation system
     * for clear tactile separation.
     */
    private const val KEY_CORNER_RADIUS_DP = 9f

    /**
     * Elevation (real View.elevation, not a drawn gradient trick) applied
     * to each key so it reads as a raised card floating over the darker
     * keyboard surface (see keyboardBackground) - this is the main thing
     * that was missing before and made the keyboard look flat/dated. Key
     * presses drop to a lower elevation (see applyPressedElevation) to
     * sell a tactile "push down" on touch.
     */
    const val KEY_ELEVATION_DP = 3.0f
    const val KEY_ELEVATION_PRESSED_DP = 0.8f

    /**
     * Professional-looking keyboard key background: a subtle vertical
     * gradient (instead of a flat single color) with a thin border, built
     * as a proper pressed/normal state-list so keys give visual feedback
     * on touch. When [accented] is true (the space/delete/enter action
     * keys) the border uses the user's chosen accent color instead of a
     * neutral one, so those keys read as visually distinct actions.
     */
    fun keyBackgroundSelector(context: Context, accented: Boolean): Drawable {
        val states = StateListDrawable()
        states.addState(intArrayOf(android.R.attr.state_pressed), keyShape(context, pressed = true, accented = accented))
        states.addState(intArrayOf(), keyShape(context, pressed = false, accented = accented))
        return states
    }

    private fun keyShape(context: Context, pressed: Boolean, accented: Boolean): GradientDrawable {
        val ctx = themedContext(context)
        val density = context.resources.displayMetrics.density
        val bg = GradientDrawable()
        bg.cornerRadius = KEY_CORNER_RADIUS_DP * density
        bg.orientation = GradientDrawable.Orientation.TOP_BOTTOM
        if (pressed) {
            val pressedColor = ContextCompat.getColor(ctx, R.color.navy_700)
            bg.colors = intArrayOf(pressedColor, pressedColor)
        } else {
            // Subtle surface gradient with clear separation from the keyboard
            // background. The same surface hierarchy is used in both
            // light and dark modes so the keyboard stays calm and premium.
            bg.colors = intArrayOf(
                ContextCompat.getColor(ctx, R.color.navy_900),
                ContextCompat.getColor(ctx, R.color.navy_800)
            )
        }
        val strokeColor = if (accented) accentColor(context) else ContextCompat.getColor(ctx, R.color.navy_700)
        val strokeWidthDp = if (accented) 1.6f else 1f
        bg.setStroke((strokeWidthDp * density).toInt(), strokeColor)
        return bg
    }

    /**
     * Pill-shaped background (state-list, so tapping still gives visual
     * feedback exactly like a key) for suggestion-strip chips - fully
     * rounded rather than the boxier key corner radius, matching the
     * rounded "chip" look of modern keyboard suggestion bars.
     */
    fun suggestionChipBackground(context: Context): Drawable {
        val states = StateListDrawable()
        states.addState(intArrayOf(android.R.attr.state_pressed), pillShape(context, pressed = true))
        states.addState(intArrayOf(), pillShape(context, pressed = false))
        return states
    }

    /**
     * Background for the whole suggestion strip container: a slightly
     * lighter, rounded card than the raw keyboard surface, so the strip
     * reads as one distinct panel (rather than chips floating directly
     * on the keyboard background) and has something solid to cast the
     * shadow from [KEY_ELEVATION_DP]-style elevation onto.
     */
    fun suggestionBarBackground(context: Context): Drawable {
        val ctx = themedContext(context)
        val density = context.resources.displayMetrics.density
        val bg = GradientDrawable()
        bg.cornerRadius = (KEY_CORNER_RADIUS_DP + 2f) * density
        bg.setColor(ContextCompat.getColor(ctx, R.color.navy_900))
        return bg
    }

    /**
     * Thin vertical rule dropped between (not around) suggestion chips,
     * so a row of 3+ suggestions reads as clearly separated options
     * instead of one run-on strip of text.
     */
    fun suggestionDividerColor(context: Context): Int =
        ContextCompat.getColor(themedContext(context), R.color.navy_700)

    private fun pillShape(context: Context, pressed: Boolean): GradientDrawable {
        val ctx = themedContext(context)
        val density = context.resources.displayMetrics.density
        val bg = GradientDrawable()
        bg.cornerRadius = 999f * density // large enough to always render as a full pill
        bg.setColor(
            ContextCompat.getColor(ctx, if (pressed) R.color.navy_700 else R.color.navy_800)
        )
        return bg
    }

    /**
     * Applies (or removes) the "pressed" elevation drop for tactile
     * feedback on touch. Animated (short ObjectAnimator, not an instant
     * jump) so the key visibly "settles" down and back up instead of
     * snapping between the two elevations - a small touch, but it's
     * what separates a raised card that feels alive from one that just
     * flickers between two fixed heights.
     */
    fun applyPressedElevation(view: android.view.View, pressed: Boolean) {
        val density = view.resources.displayMetrics.density
        val target = (if (pressed) KEY_ELEVATION_PRESSED_DP else KEY_ELEVATION_DP) * density
        view.animate().cancel()
        view.animate()
            .z(target)
            .setDuration(if (pressed) 40L else 120L)
            .start()
    }

    /** Flat, slightly darker fill for the whole keyboard surface so raised keys have contrast to sit on. */
    fun keyboardBackground(context: Context): Drawable {
        val bg = GradientDrawable()
        bg.setColor(ContextCompat.getColor(themedContext(context), R.color.navy_950))
        return bg
    }
}
