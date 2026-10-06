package com.kelly.bledebugger.snoop

import com.kelly.bledebugger.model.SnoopRecord

/**
 * Incremental parser for the btsnoop format (RFC 1761 style, as written by Android's stack).
 *
 * File header (16 bytes): "btsnoop\0", version (u32 = 1), datalink (u32, 1002 = HCI UART/H4).
 * Each record: original length, included length, flags, cumulative drops (all u32),
 * timestamp (i64, microseconds since 0000-01-01), then `included length` bytes of data.
 * All integers are big-endian.
 *
 * Bytes can be fed in arbitrary chunks; incomplete records are held until more data arrives.
 */
class BtSnoopParser {

    class FormatException(message: String) : Exception(message)

    private var buffer = ByteArray(64 * 1024)
    private var length = 0
    private var headerParsed = false

    var datalink: Int = -1
        private set

    fun reset() {
        length = 0
        headerParsed = false
        datalink = -1
    }

    fun feed(bytes: ByteArray, count: Int = bytes.size): List<SnoopRecord> {
        append(bytes, count)
        val records = ArrayList<SnoopRecord>()
        var pos = 0

        if (!headerParsed) {
            if (length < FILE_HEADER_SIZE) return records
            for (i in MAGIC.indices) {
                if (buffer[i] != MAGIC[i]) throw FormatException("Not a btsnoop file")
            }
            datalink = readInt(12)
            if (datalink != DATALINK_H4) {
                throw FormatException("Unsupported btsnoop datalink $datalink (expected $DATALINK_H4)")
            }
            headerParsed = true
            pos = FILE_HEADER_SIZE
        }

        while (length - pos >= RECORD_HEADER_SIZE) {
            val included = readInt(pos + 4)
            if (included < 0 || included > MAX_RECORD) {
                throw FormatException("Corrupt record length $included at buffer offset $pos")
            }
            if (length - pos - RECORD_HEADER_SIZE < included) break
            val dataStart = pos + RECORD_HEADER_SIZE
            records += SnoopRecord(
                originalLength = readInt(pos),
                flags = readInt(pos + 8),
                drops = readInt(pos + 12),
                timestampUs = readLong(pos + 16) - EPOCH_DELTA_US,
                data = buffer.copyOfRange(dataStart, dataStart + included),
            )
            pos = dataStart + included
        }

        // Drop consumed bytes.
        if (pos > 0) {
            System.arraycopy(buffer, pos, buffer, 0, length - pos)
            length -= pos
        }
        return records
    }

    private fun append(bytes: ByteArray, count: Int) {
        if (length + count > buffer.size) {
            buffer = buffer.copyOf(maxOf(buffer.size * 2, length + count))
        }
        System.arraycopy(bytes, 0, buffer, length, count)
        length += count
    }

    private fun readInt(at: Int): Int =
        ((buffer[at].toInt() and 0xFF) shl 24) or
            ((buffer[at + 1].toInt() and 0xFF) shl 16) or
            ((buffer[at + 2].toInt() and 0xFF) shl 8) or
            (buffer[at + 3].toInt() and 0xFF)

    private fun readLong(at: Int): Long =
        (readInt(at).toLong() shl 32) or (readInt(at + 4).toLong() and 0xFFFFFFFFL)

    companion object {
        val MAGIC = byteArrayOf('b'.code.toByte(), 't'.code.toByte(), 's'.code.toByte(), 'n'.code.toByte(),
            'o'.code.toByte(), 'o'.code.toByte(), 'p'.code.toByte(), 0)
        const val FILE_HEADER_SIZE = 16
        const val RECORD_HEADER_SIZE = 24
        const val DATALINK_H4 = 1002

        /** Microseconds between 0000-01-01 and 1970-01-01, as used by btsnoop. */
        const val EPOCH_DELTA_US = 0x00dcddb30f2f8000L

        private const val MAX_RECORD = 64 * 1024
    }
}
