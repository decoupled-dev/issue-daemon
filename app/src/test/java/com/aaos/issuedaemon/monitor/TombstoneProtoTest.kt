package com.aaos.issuedaemon.monitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream

class TombstoneProtoTest {

    private fun ByteArrayOutputStream.varint(v: Int) {
        var x = v
        while (x >= 0x80) {
            write((x and 0x7F) or 0x80)
            x = x ushr 7
        }
        write(x)
    }

    private fun ByteArrayOutputStream.string(field: Int, s: String) {
        varint(field shl 3 or 2)
        val b = s.toByteArray()
        varint(b.size)
        write(b)
    }

    private fun tombstone(): ByteArray = ByteArrayOutputStream().apply {
        varint(1 shl 3); varint(3) // arch = 3 (varint)
        string(2, "google/sdk_gphone64_x86_64/emu64xa:14/UE1A/1:userdebug/dev-keys")
        string(8, "u:r:system_app:s0")
        string(9, "com.android.settings")
    }.toByteArray()

    @Test
    fun plainTombstone() = assertEquals("com.android.settings", TombstoneProto.processName(tombstone()))

    @Test
    fun tombstoneWithHeadersWrapper() {
        val inner = tombstone()
        val wrapped = ByteArrayOutputStream().apply {
            varint(1 shl 3 or 2); varint(inner.size); write(inner)
            string(2, "some header")
        }.toByteArray()
        assertEquals("com.android.settings", TombstoneProto.processName(wrapped))
    }

    @Test
    fun garbageReturnsNull() = assertNull(TombstoneProto.processName(byteArrayOf(0x0a, 0x7f, 0x01)))
}
