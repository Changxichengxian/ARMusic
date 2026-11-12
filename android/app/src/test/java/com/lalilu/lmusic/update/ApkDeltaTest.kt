package com.lalilu.lmusic.update

import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFails

class ApkDeltaTest {
    @get:Rule
    val files = TemporaryFolder()

    @Test
    fun restoresCopiedAndChangedBytesInOrder() {
        val result = apply(9) {
            writeByte(0)
            writeInt(4)
            writeInt(3)
            writeByte(1)
            writeInt(2)
            writeBytes("XY")
            writeByte(0)
            writeInt(0)
            writeInt(4)
        }
        assertContentEquals("efgXYabcd".encodeToByteArray(), result)
    }

    @Test
    fun rejectsReadOutsideInstalledApk() {
        assertFails {
            apply(3) {
                writeByte(0)
                writeInt(5)
                writeInt(3)
            }
        }
    }

    @Test
    fun rejectsTruncatedLiteralAndTrailingData() {
        assertFails {
            apply(2) {
                writeByte(1)
                writeInt(2)
                writeBytes("X")
            }
        }
        assertFails {
            apply(1) {
                writeByte(1)
                writeInt(1)
                writeBytes("XY")
            }
        }
    }

    private fun apply(size: Long, operations: DataOutputStream.() -> Unit): ByteArray {
        val base = files.newFile().apply { writeText("abcdefg") }
        val patch = files.newFile()
        DataOutputStream(GZIPOutputStream(patch.outputStream())).use {
            it.writeBytes("ARD1")
            it.operations()
        }
        val result = ByteArrayOutputStream()
        ApkDelta.apply(base, patch, result, size)
        return result.toByteArray()
    }
}
