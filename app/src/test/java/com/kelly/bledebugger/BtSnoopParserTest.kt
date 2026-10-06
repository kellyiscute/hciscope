package com.kelly.bledebugger

import com.kelly.bledebugger.export.BtSnoopWriter
import com.kelly.bledebugger.model.SnoopRecord
import com.kelly.bledebugger.snoop.BtSnoopParser
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class BtSnoopParserTest {

    private val records = listOf(
        // HCI Reset command, sent
        SnoopRecord(4, 0x02, 0, 1_700_000_000_000_000L, hex("01 03 0c 00")),
        // Command Complete for Reset, received
        SnoopRecord(7, 0x03, 0, 1_700_000_000_001_234L, hex("04 0e 04 01 03 0c 00")),
    )

    private fun file(): ByteArray = ByteArrayOutputStream().also { BtSnoopWriter.write(it, records) }.toByteArray()

    @Test
    fun roundTripsWrittenFile() {
        val parsed = BtSnoopParser().feed(file())
        assertEquals(2, parsed.size)
        assertEquals(1_700_000_000_000_000L, parsed[0].timestampUs)
        assertEquals(1_700_000_000_001_234L, parsed[1].timestampUs)
        assertArrayEquals(records[1].data, parsed[1].data)
        assertTrue(parsed[1].received)
        assertTrue(!parsed[0].received)
    }

    @Test
    fun handlesByteByByteFeeding() {
        val parser = BtSnoopParser()
        val out = file().flatMap { b -> parser.feed(byteArrayOf(b)) }
        assertEquals(2, out.size)
        assertArrayEquals(records[0].data, out[0].data)
    }

    @Test
    fun timestampUsesBtsnoopEpoch() {
        val bytes = file()
        // First record's timestamp field sits at 16 (file header) + 16 (record header fields).
        val ts = (0 until 8).fold(0L) { acc, i -> (acc shl 8) or (bytes[32 + i].toLong() and 0xFF) }
        assertEquals(1_700_000_000_000_000L + 0x00dcddb30f2f8000L, ts)
    }

    @Test(expected = BtSnoopParser.FormatException::class)
    fun rejectsNonBtsnoop() {
        BtSnoopParser().feed(ByteArray(32) { 0x41 })
    }
}

internal fun hex(s: String): ByteArray =
    s.split(' ').filter { it.isNotBlank() }.map { it.toInt(16).toByte() }.toByteArray()
