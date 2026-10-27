package com.aaos.issuedaemon.monitor

/**
 * Just enough protobuf decoding to pull the process name out of a binary tombstone
 * (system/core/debuggerd/proto/tombstone.proto), so SYSTEM_TOMBSTONE_PROTO* entries dedupe
 * against the matching text tombstone / native crash report.
 *
 * SYSTEM_TOMBSTONE_PROTO is a Tombstone message; SYSTEM_TOMBSTONE_PROTO_WITH_HEADERS wraps it in field 1.
 */
internal object TombstoneProto {
    private const val FIELD_WRAPPED_TOMBSTONE = 1
    private const val FIELD_COMMAND_LINE = 9

    fun processName(bytes: ByteArray): String? =
        commandLine(bytes, 0, bytes.size)
            ?: lengthDelimited(bytes, 0, bytes.size, FIELD_WRAPPED_TOMBSTONE)?.let { (start, end) -> commandLine(bytes, start, end) }

    private fun commandLine(b: ByteArray, from: Int, to: Int): String? =
        lengthDelimited(b, from, to, FIELD_COMMAND_LINE)?.let { (start, end) ->
            String(b, start, end - start, Charsets.UTF_8).takeIf { it.isNotBlank() }
        }

    /** Returns the [start, end) payload range of the first length-delimited [field] in b[from, to). */
    private fun lengthDelimited(b: ByteArray, from: Int, to: Int, field: Int): Pair<Int, Int>? {
        var pos = from
        try {
            while (pos < to) {
                val (key, afterKey) = varint(b, pos)
                pos = afterKey
                val wireType = (key and 7).toInt()
                when (wireType) {
                    0 -> pos = varint(b, pos).second
                    1 -> pos += 8
                    5 -> pos += 4
                    2 -> {
                        val (len, start) = varint(b, pos)
                        val end = start + len.toInt()
                        if (len < 0 || end > to) return null
                        if ((key ushr 3).toInt() == field) return start to end
                        pos = end
                    }
                    else -> return null // groups / malformed: give up
                }
            }
        } catch (_: IndexOutOfBoundsException) {
        }
        return null
    }

    private fun varint(b: ByteArray, at: Int): Pair<Long, Int> {
        var result = 0L
        var shift = 0
        var pos = at
        while (shift < 64) {
            val byte = b[pos++].toInt()
            result = result or ((byte and 0x7F).toLong() shl shift)
            if (byte and 0x80 == 0) return result to pos
            shift += 7
        }
        throw IndexOutOfBoundsException("varint too long")
    }
}
