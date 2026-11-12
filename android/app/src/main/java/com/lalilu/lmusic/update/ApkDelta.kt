package com.lalilu.lmusic.update

import java.io.DataInputStream
import java.io.File
import java.io.OutputStream
import java.io.RandomAccessFile
import java.util.zip.GZIPInputStream

internal object ApkDelta {
    private const val MAGIC = 0x41524431 // ARD1
    private const val COPY = 0
    private const val LITERAL = 1

    fun apply(base: File, patch: File, target: OutputStream, targetSize: Long) {
        RandomAccessFile(base, "r").use { old ->
            DataInputStream(GZIPInputStream(patch.inputStream().buffered())).use { delta ->
                require(delta.readInt() == MAGIC)
                var remaining = targetSize
                val buffer = ByteArray(64 * 1024)
                while (remaining > 0) {
                    val operation = delta.readUnsignedByte()
                    val offset = if (operation == COPY) delta.readInt().toLong() else 0L
                    var length = delta.readInt()
                    require(length > 0 && length <= remaining)
                    remaining -= length

                    if (operation == COPY) {
                        require(offset >= 0 && offset + length <= old.length())
                        old.seek(offset)
                    } else {
                        require(operation == LITERAL)
                    }

                    while (length > 0) {
                        val count = minOf(length, buffer.size)
                        if (operation == COPY) old.readFully(buffer, 0, count)
                        else delta.readFully(buffer, 0, count)
                        target.write(buffer, 0, count)
                        length -= count
                    }
                }
                require(delta.read() == -1)
            }
        }
    }
}
