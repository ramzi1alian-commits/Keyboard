package com.securekeyboard.app

import android.os.Bundle
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity

/**
 * AboutActivity
 *
 * A plain, static explanation of the app's privacy model in everyday
 * language - for the ordinary user (and for Play Store reviewers/store-
 * listing visitors) who won't read the source code or the class-level
 * comments elsewhere in this project. Nothing here is dynamic and
 * nothing here reads or writes any user data.
 */
class AboutActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )
        setContentView(R.layout.activity_about)
        Fonts.applyToTree(findViewById(android.R.id.content), Fonts.currentTypeface(this))
    }
}
