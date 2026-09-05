package com.securekeyboard.app

import java.io.File
import java.io.RandomAccessFile
import java.util.Arrays

/**
 * Small, auditable helpers for best-effort clearing of sensitive buffers and
 * temporary plaintext files. Android/flash storage may remap physical blocks,
 * so secureDelete() is explicitly best-effort and is not a guarantee of
 * physical media sanitization.
 */
object SecureMemory {
    private const val FILE_WIPE_BUFFER_SIZE = 64 * 1024

    fun wipe(bytes: ByteArray?) {
        if (bytes != null) Arrays.fill(bytes, 0)
    }

    fun wipe(chars: CharArray?) {
        if (chars != null) Arrays.fill(chars, '\u0000')
    }

    /**
     * Best-effort overwrite-and-delete for temporary plaintext files.
     * The file is wiped in bounded chunks and the file descriptor is synced
     * before deletion when the underlying filesystem permits it.
     */
    fun secureDelete(file: File?) {
        if (file == null || !file.exists()) return
        try {
            if (file.isFile && file.length() > 0L) {
                RandomAccessFile(file, "rw").use { raf ->
                    val zeros = ByteArray(FILE_WIPE_BUFFER_SIZE)
                    try {
                        raf.seek(0L)
                        var remaining = raf.length()
                        while (remaining > 0L) {
                            val count = minOf(remaining, zeros.size.toLong()).toInt()
                            raf.write(zeros, 0, count)
                            remaining -= count
                        }
                        raf.fd.sync()
                    } finally {
                        Arrays.fill(zeros, 0)
                    }
                }
            }
        } catch (_: Exception) {
            // Deletion is still attempted below. Physical sanitization cannot
            // be guaranteed on flash storage even when overwrite succeeds.
        } finally {
            try { file.delete() } catch (_: Exception) { }
        }
    }
}
