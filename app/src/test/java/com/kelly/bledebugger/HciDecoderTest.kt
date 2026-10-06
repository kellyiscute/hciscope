package com.kelly.bledebugger

import com.kelly.bledebugger.decode.HciDecoder
import com.kelly.bledebugger.model.Layer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HciDecoderTest {

    @Test
    fun leSetScanEnable() {
        val d = HciDecoder.decode(hex("01 0c 20 02 01 00"))
        assertEquals("LE Set Scan Enable enable", d.summary)
        assertEquals(Layer.HCI, d.layer)
    }

    @Test
    fun commandComplete() {
        val d = HciDecoder.decode(hex("04 0e 04 01 03 0c 00"))
        assertEquals("Command Complete: Reset", d.summary)
    }

    @Test
    fun leAdvertisingReportWithName() {
        // 1 report, ADV_IND, public addr 11:22:33:44:55:66, AD = flags + complete name "Hi", RSSI -60
        val d = HciDecoder.decode(
            hex("04 3e 13 02 01 00 00 66 55 44 33 22 11 07 02 01 06 03 09 48 69 c4")
        )
        assertEquals("LE Adv Report 11:22:33:44:55:66 -60dBm \"Hi\"", d.summary)
        assertTrue(d.details.contains("Name: Hi"))
    }

    @Test
    fun attReadResponse() {
        // ACL handle 0x0040, L2CAP len 4, CID 4, ATT Read Response value 01 02 03
        val d = HciDecoder.decode(hex("02 40 20 08 00 04 00 04 00 0b 01 02 03"))
        assertEquals("[0x0040] ATT Read Response value=01 02 03", d.summary)
        assertEquals(Layer.ATT, d.layer)
        assertEquals(0x0040, d.connectionHandle)
    }

    @Test
    fun attWriteRequest() {
        val d = HciDecoder.decode(hex("02 41 00 09 00 05 00 04 00 12 2a 00 01 00"))
        assertEquals("[0x0041] ATT Write Request handle=0x002a value=01 00", d.summary)
    }

    @Test
    fun attErrorResponse() {
        val d = HciDecoder.decode(hex("02 40 20 09 00 05 00 04 00 01 0a 03 00 0a"))
        assertEquals("[0x0040] ATT Error Response Read Request handle=0x0003: Attribute Not Found", d.summary)
    }

    @Test
    fun smpPairingRequest() {
        val d = HciDecoder.decode(hex("02 40 20 0b 00 07 00 06 00 01 03 00 2d 10 0f 0f"))
        assertEquals(Layer.SMP, d.layer)
        assertTrue(d.summary.startsWith("[0x0040] SMP Pairing Request io=NoInputNoOutput"))
    }

    @Test
    fun truncatedPacketDoesNotThrow() {
        assertEquals("Truncated packet", HciDecoder.decode(hex("04 3e 13 02 01")).summary)
    }

    @Test
    fun uuid128IsReversed() {
        val le = hex("fb 34 9b 5f 80 00 00 80 00 10 00 00 0f 18 00 00")
        assertEquals("0000180f-0000-1000-8000-00805f9b34fb", HciDecoder.uuidString(le))
    }

    @Test
    fun qualcommDebugHandleIsLabelled() {
        val d = HciDecoder.decode(hex("02 dc 2e 06 00 02 00 ff 97 aa bb"))
        assertEquals("[0x0edc] Controller debug log (6 bytes)", d.summary)
        assertEquals(Layer.HCI, d.layer)
    }
}
