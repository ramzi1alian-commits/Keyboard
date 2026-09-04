package com.securekeyboard.app

import java.util.Arrays

/** Small, auditable helpers for best-effort clearing of sensitive buffers. */
object SecureMemory {
    fun wipe(bytes: ByteArray?) { if (bytes != null) Arrays.fill(bytes, 0) }
    fun wipe(chars: CharArray?) { if (chars != null) Arrays.fill(chars, '\u0000') }
}
