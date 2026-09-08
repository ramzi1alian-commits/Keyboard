package com.securekeyboard.app

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Debug
import java.io.File
import java.security.MessageDigest

/**
 * Runtime security posture checks used as defense-in-depth before sensitive
 * cryptographic operations. These checks are deliberately fail-closed for
 * the conditions they can actually establish, but are NOT a substitute for
 * Android's sandbox, hardware-backed Keystore, or an independent audit.
 *
 * All checks are local: this class performs no network access and never logs
 * secrets or user input.
 */
object SecurityRuntime {
    data class Posture(
        val debuggable: Boolean,
        val debuggerAttached: Boolean,
        val tracerAttached: Boolean,
        val suspiciousInstrumentation: Boolean,
        val rootedHeuristic: Boolean,
        val signaturePinned: Boolean,
        val signatureMatches: Boolean
    ) {
        val trustedForSensitiveOps: Boolean
            get() = !debuggable && !debuggerAttached && !tracerAttached &&
                    !suspiciousInstrumentation && !rootedHeuristic &&
                    (!signaturePinned || signatureMatches)
    }

    private val suspiciousMapTokens = arrayOf(
        "frida-gadget", "frida-agent", "libfrida", "gadget-frida",
        "xposed", "lsposed", "substrate", "edxp"
    )

    fun assess(context: Context): Posture {
        val appInfo = context.applicationInfo
        val debuggable = (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        val debuggerAttached = Debug.isDebuggerConnected() || Debug.waitingForDebugger()
        val tracerAttached = tracerPid() != 0
        val suspicious = suspiciousInstrumentation()
        val rooted = RootCheck.looksRooted()

        val expected = expectedCertificateDigest(context)
        val actual = signingCertificateDigest(context)
        val pinned = expected != null
        val matches = expected == null || actual.equals(expected, ignoreCase = true)

        return Posture(debuggable, debuggerAttached, tracerAttached, suspicious, rooted, pinned, matches)
    }

    /** Returns the SHA-256 certificate digest, or null if it cannot be read. */
    fun signingCertificateDigest(context: Context): String? = try {
        val pm = context.packageManager
        val packageName = context.packageName
        val certBytes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val info = pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            val signers = info.signingInfo.apkContentsSigners
            if (signers.isEmpty()) null else signers[0].toByteArray()
        } else {
            @Suppress("DEPRECATION")
            val info = pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
            @Suppress("DEPRECATION")
            if (info.signatures.isNullOrEmpty()) null else info.signatures[0].toByteArray()
        }
        certBytes?.let { sha256Hex(it) }
    } catch (_: Exception) {
        null
    }

    private fun expectedCertificateDigest(context: Context): String? {
        // Set at release-build time with -PsecureKeyboardExpectedCertSha256
        // or an equivalent CI-injected BuildConfig value when certificate
        // pinning is desired. Empty means "report, don't pin".
        return try {
            val field = BuildConfig::class.java.getField("EXPECTED_CERT_SHA256")
            field.get(null)?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        }
    }

    private fun tracerPid(): Int {
        return try {
            File("/proc/self/status").useLines { lines ->
                lines.firstOrNull { it.startsWith("TracerPid:") }
                    ?.substringAfter(':')?.trim()?.toIntOrNull() ?: 0
            }
        } catch (_: Exception) {
            0
        }
    }

    private fun suspiciousInstrumentation(): Boolean {
        return try {
            File("/proc/self/maps").useLines { lines ->
                lines.any { line ->
                    val lower = line.lowercase()
                    suspiciousMapTokens.any { lower.contains(it) }
                }
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun sha256Hex(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(data)
        return digest.joinToString("") { "%02X".format(it) }
    }
}
