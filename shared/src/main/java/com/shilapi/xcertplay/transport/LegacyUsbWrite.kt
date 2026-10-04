package com.shilapi.xcertplay.transport

import java.io.IOException

/** Android before P caps a bulk transfer at 16 KiB. One deadline covers all chunks. */
internal fun writeLegacyUsb(
    data: ByteArray,
    timeoutMillis: Int,
    transfer: (ByteArray, Int) -> Int,
) {
    require(timeoutMillis > 0)
    val deadline = System.nanoTime() + timeoutMillis.toLong() * 1_000_000
    var offset = 0
    while (offset < data.size) {
        val remaining = deadline - System.nanoTime()
        if (remaining <= 0) throw IOException("USB write deadline exceeded")
        val chunk = data.copyOfRange(offset, minOf(offset + 16 * 1024, data.size))
        val count = transfer(chunk, ((remaining + 999_999) / 1_000_000).coerceAtLeast(1).toInt())
        if (count <= 0 || count > chunk.size) throw IOException("USB write failed after $offset bytes")
        offset += count
    }
}
