package com.securekeyboard.app

import android.os.Bundle
import android.view.WindowManager
import com.journeyapps.barcodescanner.CaptureActivity

/**
 * App-owned QR capture activity. Keeping the capture Activity in our own
 * package lets us apply the same secure window/theme consistently across
 * Android/OEM versions instead of relying on the library's default theme.
 */
class SecureCaptureActivity : CaptureActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )
        super.onCreate(savedInstanceState)
    }
}
