package com.shilapi.xcertplay.transport

import java.io.ByteArrayOutputStream
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class LegacyUsbWriteTest {
    @Test fun preservesFramesAcrossKitKatLimitAndPartialWrites() {
        val input = ByteArray(65000) { (it % 251).toByte() }
        val output = ByteArrayOutputStream()
        writeLegacyUsb(input, 1000) { chunk, timeout ->
            assertTrue(chunk.size <= 16384)
            assertTrue(timeout in 1..1000)
            val count = minOf(chunk.size, 8000)
            output.write(chunk, 0, count)
            count
        }
        assertArrayEquals(input, output.toByteArray())
    }
    @Test(expected = IOException::class) fun zeroWriteCannotLoop() {
        writeLegacyUsb(byteArrayOf(1), 1000) { _, _ -> 0 }
    }
    @Test(expected = IOException::class) fun negativeWriteFailsImmediately() {
        writeLegacyUsb(byteArrayOf(1), 1000) { _, _ -> -1 }
    }
}
