package com.securekeyboard.app

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import java.security.MessageDigest
import java.security.PublicKey

/**
 * ContactPairingActivity
 *
 * One-time exchange screen run ONCE per new contact, ideally in person.
 * Two things happen here, both required:
 *
 * 1. Each side shows/scans a QR code carrying the OTHER side's public
 *    key (DeviceIdentity.myPublicKeyBase64()) - this is how the app
 *    learns which public key belongs to which contact.
 *
 * 2. Both sides compare a short SAFETY NUMBER derived from BOTH public
 *    keys combined. This step is not optional decoration - it is what
 *    actually defeats a man-in-the-middle who substituted a different
 *    public key during the QR exchange itself (e.g. a malicious relay
 *    app, or a compromised screen-mirroring session). If the numbers
 *    match on both screens, both sides are provably holding the same
 *    pair of public keys - see explanation in class doc / README.
 *
 * This screen intentionally does NOT let pairing complete silently -
 * the user must tap "Numbers match, confirm" after visually or
 * verbally checking the number against the other device, exactly like
 * Signal's Safety Number confirmation.
 */
class ContactPairingActivity : AppCompatActivity() {

    private lateinit var contactName: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_contact_pairing)

        contactName = intent.getStringExtra(EXTRA_CONTACT_NAME) ?: ""

        showMyQrCode()
        setupScanButton()
        setupConfirmButton()
    }

    /** Renders this device's own public key as a QR code for the other person to scan. */
    private fun showMyQrCode() {
        val myKey = DeviceIdentity.myPublicKeyBase64()
        val qrView = findViewById<android.widget.ImageView>(R.id.qr_image_view)
        qrView.setImageBitmap(generateQrBitmap(myKey, 600))
    }

    private fun generateQrBitmap(text: String, sizePx: Int): Bitmap {
        val writer = QRCodeWriter()
        val matrix = writer.encode(text, BarcodeFormat.QR_CODE, sizePx, sizePx)
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.RGB_565)
        for (x in 0 until sizePx) {
            for (y in 0 until sizePx) {
                bitmap.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
            }
        }
        return bitmap
    }

    private fun setupScanButton() {
        findViewById<android.widget.Button>(R.id.scan_button).setOnClickListener {
            // Delegates to a standard QR scanning library/activity (e.g.
            // ZXing's IntentIntegrator or CameraX + ML Kit barcode
            // scanning). Result comes back in onActivityResult below as
            // the scanned contact's public key string.
            launchQrScanner()
        }
    }

    private fun launchQrScanner() {
        // Implementation depends on which scanning library is added to
        // build.gradle - wire up to onScanResult(scannedText) below.
    }

    /** Call this once the QR scanner returns the other device's public key string. */
    private fun onScanResult(scannedPublicKeyBase64: String) {
        try {
            val contactPublicKey = DeviceIdentity.parseContactPublicKey(scannedPublicKeyBase64)
            val safetyNumber = computeSafetyNumber(
                DeviceIdentity.myPublicKeyBase64(),
                scannedPublicKeyBase64
            )
            findViewById<android.widget.TextView>(R.id.safety_number_view).text = safetyNumber
            pendingContactPublicKey = contactPublicKey
            pendingContactPublicKeyBase64 = scannedPublicKeyBase64
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.pairing_invalid_qr), Toast.LENGTH_LONG).show()
        }
    }

    private var pendingContactPublicKey: PublicKey? = null
    private var pendingContactPublicKeyBase64: String? = null

    /**
     * Derives a short, human-comparable number from BOTH public keys
     * combined, order-independent (sorting first) so both devices
     * compute the identical number regardless of who scanned whom.
     * This is the same purpose as Signal's Safety Numbers - NOT a
     * secret, just something short enough for two humans to read aloud
     * and compare.
     */
    private fun computeSafetyNumber(keyA: String, keyB: String): String {
        val sorted = listOf(keyA, keyB).sorted()
        val digest = MessageDigest.getInstance("SHA-256")
            .digest((sorted[0] + sorted[1]).toByteArray(Charsets.UTF_8))
        // Take the first 6 bytes -> 6 groups of 2 digits, easy to read aloud
        val sb = StringBuilder()
        for (i in 0 until 6) {
            sb.append(String.format("%02d", digest[i].toInt() and 0xFF))
            if (i != 5) sb.append(" ")
        }
        return sb.toString()
    }

    private fun setupConfirmButton() {
        findViewById<android.widget.Button>(R.id.confirm_match_button).setOnClickListener {
            val contactKey = pendingContactPublicKeyBase64
            if (contactKey == null) {
                Toast.makeText(this, getString(R.string.pairing_scan_first), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            // Persist the verified contact - see ContactStore (key-value
            // store of name -> public key, encrypted at rest via
            // LocalStorageCrypto, same as the rest of this app's local data).
            ContactStore.savePairedContact(this, contactName, contactKey)
            Toast.makeText(this, getString(R.string.pairing_success), Toast.LENGTH_SHORT).show()
            setResult(RESULT_OK)
            finish()
        }
    }

    companion object {
        const val EXTRA_CONTACT_NAME = "contact_name"
    }
}
