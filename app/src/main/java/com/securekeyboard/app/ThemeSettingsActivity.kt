package com.securekeyboard.app

import android.os.Bundle
import android.view.View
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import com.google.android.material.button.MaterialButton

class ThemeSettingsActivity : AppCompatActivity() {

    private lateinit var swatchCyan: View
    private lateinit var swatchTeal: View
    private lateinit var swatchGold: View
    private lateinit var swatchPurple: View

    private lateinit var btnDayMode: MaterialButton
    private lateinit var btnNightMode: MaterialButton
    private lateinit var btnFontSans: MaterialButton
    private lateinit var btnFontSerif: MaterialButton
    private lateinit var btnFontMono: MaterialButton
    private lateinit var btnCompact: MaterialButton
    private lateinit var btnComfortable: MaterialButton

    private lateinit var seekKeyboardHeight: SeekBar
    private lateinit var textKeyboardHeightValue: TextView
    private lateinit var previewKeyA: TextView
    private lateinit var previewKeyB: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_theme_settings)

        swatchCyan = findViewById(R.id.swatchCyan)
        swatchTeal = findViewById(R.id.swatchTeal)
        swatchGold = findViewById(R.id.swatchGold)
        swatchPurple = findViewById(R.id.swatchPurple)

        btnDayMode = findViewById(R.id.btnDayMode)
        btnNightMode = findViewById(R.id.btnNightMode)
        btnFontSans = findViewById(R.id.btnFontSans)
        btnFontSerif = findViewById(R.id.btnFontSerif)
        btnFontMono = findViewById(R.id.btnFontMono)
        btnCompact = findViewById(R.id.btnCompact)
        btnComfortable = findViewById(R.id.btnComfortable)

        seekKeyboardHeight = findViewById(R.id.seekKeyboardHeight)
        textKeyboardHeightValue = findViewById(R.id.textKeyboardHeightValue)
        previewKeyA = findViewById(R.id.previewKeyA)
        previewKeyB = findViewById(R.id.previewKeyB)

        swatchCyan.setOnClickListener { pickAccent(R.color.accent_cyan) }
        swatchTeal.setOnClickListener { pickAccent(R.color.accent_teal) }
        swatchGold.setOnClickListener { pickAccent(R.color.accent_gold) }
        swatchPurple.setOnClickListener { pickAccent(R.color.accent_purple) }

        btnDayMode.setOnClickListener {
            Prefs.setDarkMode(this, false)
            // setDefaultNightMode recreates any *other* running activities
            // automatically, but this screen itself needs an explicit
            // recreate() to reliably pick up the new day/night resources
            // right away on every OS version/OEM skin.
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            recreate()
        }
        btnNightMode.setOnClickListener {
            Prefs.setDarkMode(this, true)
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            recreate()
        }

        btnFontSans.setOnClickListener { Prefs.setFontChoice(this, 0); recreate() }
        btnFontSerif.setOnClickListener { Prefs.setFontChoice(this, 1); recreate() }
        btnFontMono.setOnClickListener { Prefs.setFontChoice(this, 2); recreate() }

        btnCompact.setOnClickListener { Prefs.setDensity(this, 1); refreshSelectionState() }
        btnComfortable.setOnClickListener { Prefs.setDensity(this, 0); refreshSelectionState() }

        // Keyboard height: instead of a single hardcoded shorter value,
        // the user controls it directly with this slider. The keyboard
        // (SecureInputMethodService) picks up the new value live the
        // next time it's shown - see onStartInputView there.
        seekKeyboardHeight.max = Prefs.MAX_KEYBOARD_HEIGHT_DP - Prefs.MIN_KEYBOARD_HEIGHT_DP
        val currentDp = Prefs.keyboardHeightDp(this)
        seekKeyboardHeight.progress = currentDp - Prefs.MIN_KEYBOARD_HEIGHT_DP
        updateHeightPreview(currentDp)

        seekKeyboardHeight.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val dp = Prefs.MIN_KEYBOARD_HEIGHT_DP + progress
                Prefs.setKeyboardHeightDp(this@ThemeSettingsActivity, dp)
                updateHeightPreview(dp)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        applyFontToScreen()
        refreshSelectionState()
    }

    private fun updateHeightPreview(dp: Int) {
        textKeyboardHeightValue.text = getString(R.string.keyboard_height_value, dp)
        val px = (dp * resources.displayMetrics.density).toInt()
        previewKeyA.layoutParams = previewKeyA.layoutParams.apply { height = px }
        previewKeyB.layoutParams = previewKeyB.layoutParams.apply { height = px }
        previewKeyA.requestLayout()
        previewKeyB.requestLayout()
        val accent = ThemeUtil.accentColor(this)
        previewKeyA.background = ThemeUtil.keyBackgroundSelector(this, accented = false)
        previewKeyB.background = ThemeUtil.keyBackgroundSelector(this, accented = true)
        previewKeyB.setTextColor(accent)
    }

    private fun pickAccent(colorRes: Int) {
        Prefs.setAccentColorRes(this, colorRes)
        recreate()
    }

    private fun applyFontToScreen() {
        val root = findViewById<View>(android.R.id.content)
        Fonts.applyToTree(root, Fonts.currentTypeface(this))
    }

    /**
     * Applies the current accent to every swatch/button on THIS screen too
     * (previously nothing here reflected the chosen accent at all), and
     * draws a visible border around whichever color/mode/font/density
     * option is currently active, so choices are actually visible instead
     * of only being saved silently to SharedPreferences.
     */
    private fun refreshSelectionState() {
        val accent = ThemeUtil.accentColor(this)
        val selectedAccentRes = Prefs.accentColorRes(this)

        ThemeUtil.setSelected(swatchCyan, selectedAccentRes == R.color.accent_cyan, accent)
        ThemeUtil.setSelected(swatchTeal, selectedAccentRes == R.color.accent_teal, accent)
        ThemeUtil.setSelected(swatchGold, selectedAccentRes == R.color.accent_gold, accent)
        ThemeUtil.setSelected(swatchPurple, selectedAccentRes == R.color.accent_purple, accent)

        val isDark = Prefs.isDarkMode(this)
        ThemeUtil.setSelected(btnDayMode, !isDark, accent)
        ThemeUtil.setSelected(btnNightMode, isDark, accent)

        val font = Prefs.fontChoice(this)
        ThemeUtil.setSelected(btnFontSans, font == 0, accent)
        ThemeUtil.setSelected(btnFontSerif, font == 1, accent)
        ThemeUtil.setSelected(btnFontMono, font == 2, accent)

        val density = Prefs.density(this)
        ThemeUtil.setSelected(btnCompact, density == 1, accent)
        ThemeUtil.setSelected(btnComfortable, density == 0, accent)

        updateHeightPreview(Prefs.keyboardHeightDp(this))
    }
}
