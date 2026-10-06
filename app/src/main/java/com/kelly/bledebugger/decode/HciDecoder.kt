package com.kelly.bledebugger.decode

import com.kelly.bledebugger.model.Decoded
import com.kelly.bledebugger.model.HciType
import com.kelly.bledebugger.model.Layer

/**
 * Best-effort, single-packet decoder for H4 HCI traffic. It doesn't reassemble ACL fragments,
 * so an L2CAP PDU split across several ACL packets is only decoded from its first fragment.
 */
object HciDecoder {

    /** Not a real connection: Qualcomm chips stream firmware logs over this ACL handle. */
    private const val QCOM_DEBUG_HANDLE = 0x0EDC

    fun decode(data: ByteArray): Decoded {
        if (data.isEmpty()) return Decoded("(empty)", emptyList(), Layer.HCI, null)
        val r = Reader(data, 1)
        return try {
            when (HciType.of(data[0].u8)) {
                HciType.COMMAND -> decodeCommand(r)
                HciType.EVENT -> decodeEvent(r)
                HciType.ACL -> decodeAcl(r)
                HciType.SCO -> decodeSco(r)
                HciType.ISO -> decodeIso(r)
                HciType.UNKNOWN -> Decoded("Unknown H4 type 0x%02x".format(data[0].u8), emptyList(), Layer.HCI, null)
            }
        } catch (e: IndexOutOfBoundsException) {
            Decoded("Truncated packet", listOf("Packet ended before all fields were read"), Layer.HCI, null)
        }
    }

    // region HCI command

    private fun decodeCommand(r: Reader): Decoded {
        val opcode = r.u16()
        val len = r.u8()
        val details = mutableListOf(
            "Opcode: ${opcodeString(opcode)}",
            "OGF 0x%02x, OCF 0x%03x".format(opcode shr 10, opcode and 0x3FF),
            "Parameter length: $len",
        )
        var handle: Int? = null
        var extra = ""
        when (opcode) {
            0x0406 -> { // Disconnect
                handle = r.u16() and 0x0FFF
                val reason = r.u8()
                extra = " handle=0x%04x reason=%s".format(handle, statusName(reason))
            }
            0x200C -> extra = if (r.u8() == 1) " enable" else " disable"
            0x2042 -> extra = if (r.u8() == 1) " enable" else " disable"
            0x200A -> extra = if (r.u8() == 1) " enable" else " disable"
            0x200D -> { // LE Create Connection
                r.skip(5)
                r.u8() // peer address type
                extra = " peer=${r.addr()}"
            }
            0x2013, 0x2016, 0x2019, 0x201A, 0x201B, 0x2022, 0x2030, 0x2032, 0x1405 -> {
                handle = r.u16() and 0x0FFF
                extra = " handle=0x%04x".format(handle)
            }
        }
        return Decoded(commandName(opcode) + extra, details, Layer.HCI, handle)
    }

    // endregion

    // region HCI event

    private fun decodeEvent(r: Reader): Decoded {
        val code = r.u8()
        val len = r.u8()
        val details = mutableListOf("Event code: 0x%02x (%s)".format(code, eventName(code)), "Parameter length: $len")
        var handle: Int? = null
        val summary = when (code) {
            0x0E -> { // Command Complete
                r.u8() // num HCI command packets
                val opcode = r.u16()
                val status = if (r.remaining > 0) r.u8() else 0
                details += "Command: ${opcodeString(opcode)}"
                details += "Status: ${statusName(status)}"
                "Command Complete: ${commandName(opcode)}" + if (status != 0) " [${statusName(status)}]" else ""
            }
            0x0F -> { // Command Status
                val status = r.u8()
                r.u8()
                val opcode = r.u16()
                details += "Command: ${opcodeString(opcode)}"
                details += "Status: ${statusName(status)}"
                "Command Status: ${commandName(opcode)} [${statusName(status)}]"
            }
            0x05 -> { // Disconnection Complete
                val status = r.u8()
                handle = r.u16() and 0x0FFF
                val reason = r.u8()
                details += "Status: ${statusName(status)}"
                details += "Reason: ${statusName(reason)}"
                "Disconnection Complete handle=0x%04x reason=%s".format(handle, statusName(reason))
            }
            0x03 -> { // Connection Complete (BR/EDR)
                val status = r.u8()
                handle = r.u16() and 0x0FFF
                val addr = r.addr()
                "Connection Complete handle=0x%04x %s [%s]".format(handle, addr, statusName(status))
            }
            0x08 -> { // Encryption Change
                val status = r.u8()
                handle = r.u16() and 0x0FFF
                val enabled = r.u8()
                "Encryption Change handle=0x%04x %s [%s]".format(
                    handle, if (enabled != 0) "on" else "off", statusName(status)
                )
            }
            0x13 -> { // Number Of Completed Packets
                val n = r.u8()
                val parts = (0 until n).map {
                    val h = r.u16() and 0x0FFF
                    val c = r.u16()
                    "0x%04x:%d".format(h, c)
                }
                details += parts.map { "Handle/count: $it" }
                "Number Of Completed Packets ${parts.joinToString(" ")}"
            }
            0x3E -> {
                val (s, h) = decodeLeMeta(r, details)
                handle = h
                s
            }
            else -> eventName(code)
        }
        return Decoded(summary, details, Layer.HCI, handle)
    }

    private fun decodeLeMeta(r: Reader, details: MutableList<String>): Pair<String, Int?> {
        val sub = r.u8()
        val name = leSubeventName(sub)
        details += "LE subevent: 0x%02x (%s)".format(sub, name)
        return when (sub) {
            0x01, 0x0A -> { // (Enhanced) Connection Complete
                val status = r.u8()
                val handle = r.u16() and 0x0FFF
                val role = r.u8()
                r.u8() // peer address type
                val addr = r.addr()
                details += "Role: ${if (role == 0) "central" else "peripheral"}"
                details += "Peer: $addr"
                "LE $name handle=0x%04x %s [%s]".format(handle, addr, statusName(status)) to handle
            }
            0x02 -> { // Advertising Report
                val n = r.u8()
                val evtType = r.u8()
                r.u8()
                val addr = r.addr()
                val dataLen = r.u8()
                val ad = r.bytes(dataLen)
                val rssi = r.s8()
                val adName = advName(ad)
                details += "Reports: $n"
                details += "Event type: 0x%02x".format(evtType)
                details += "Address: $addr"
                details += "RSSI: $rssi dBm"
                details += describeAd(ad)
                "LE Adv Report $addr ${rssi}dBm" + (adName?.let { " \"$it\"" } ?: "") to null
            }
            0x0D -> { // Extended Advertising Report
                val n = r.u8()
                val evtType = r.u16()
                r.u8()
                val addr = r.addr()
                r.skip(3) // primary PHY, secondary PHY, SID
                val tx = r.s8()
                val rssi = r.s8()
                r.skip(2 + 1 + 6) // periodic interval, direct addr type, direct addr
                val dataLen = r.u8()
                val ad = r.bytes(dataLen)
                val adName = advName(ad)
                details += "Reports: $n"
                details += "Event type: 0x%04x".format(evtType)
                details += "Address: $addr"
                details += "TX power: $tx dBm, RSSI: $rssi dBm"
                details += describeAd(ad)
                "LE Ext Adv Report $addr ${rssi}dBm" + (adName?.let { " \"$it\"" } ?: "") to null
            }
            0x03 -> { // Connection Update Complete
                val status = r.u8()
                val handle = r.u16() and 0x0FFF
                val interval = r.u16()
                val latency = r.u16()
                val timeout = r.u16()
                "LE Connection Update Complete handle=0x%04x interval=%.2fms latency=%d timeout=%dms [%s]".format(
                    handle, interval * 1.25, latency, timeout * 10, statusName(status)
                ) to handle
            }
            0x07 -> { // Data Length Change
                val handle = r.u16() and 0x0FFF
                val maxTx = r.u16()
                r.u16()
                val maxRx = r.u16()
                "LE Data Length Change handle=0x%04x tx=%d rx=%d".format(handle, maxTx, maxRx) to handle
            }
            0x0C -> { // PHY Update Complete
                val status = r.u8()
                val handle = r.u16() and 0x0FFF
                val tx = r.u8()
                val rx = r.u8()
                "LE PHY Update Complete handle=0x%04x tx=%s rx=%s [%s]".format(
                    handle, phyName(tx), phyName(rx), statusName(status)
                ) to handle
            }
            0x04, 0x05, 0x06, 0x14 -> {
                if (sub == 0x04) r.u8() // status precedes the handle only here
                val handle = r.u16() and 0x0FFF
                "LE $name handle=0x%04x".format(handle) to handle
            }
            else -> "LE $name" to null
        }
    }

    // endregion

    // region ACL / L2CAP / ATT / SMP

    private fun decodeAcl(r: Reader): Decoded {
        val hdr = r.u16()
        val handle = hdr and 0x0FFF
        val pb = (hdr shr 12) and 0x3
        val aclLen = r.u16()
        val details = mutableListOf(
            "Handle: 0x%04x".format(handle),
            "Packet boundary: %s".format(
                when (pb) { 0 -> "first (non-flushable)"; 1 -> "continuation"; 2 -> "first (flushable)"; else -> "complete" }
            ),
            "ACL length: $aclLen",
        )
        val prefix = "[0x%04x] ".format(handle)
        if (handle == QCOM_DEBUG_HANDLE) {
            details += "Qualcomm controllers send firmware debug logs over this ACL handle"
            return Decoded(prefix + "Controller debug log (${aclLen} bytes)", details, Layer.HCI, handle)
        }
        if (pb == 1) {
            return Decoded(prefix + "ACL continuation fragment (${aclLen} bytes)", details, Layer.HCI, handle)
        }
        if (r.remaining < 4) {
            return Decoded(prefix + "ACL (${aclLen} bytes)", details, Layer.HCI, handle)
        }
        val l2Len = r.u16()
        val cid = r.u16()
        details += "L2CAP length: $l2Len"
        details += "L2CAP CID: 0x%04x".format(cid)
        if (r.remaining < l2Len) {
            details += "Fragmented: ${r.remaining} of $l2Len bytes in this packet"
        }
        return when (cid) {
            0x0004 -> decodeAtt(r, details, prefix, handle)
            0x0006, 0x0007 -> decodeSmp(r, details, prefix, handle)
            0x0005, 0x0001 -> {
                val code = r.u8()
                val ident = r.u8()
                details += "Signaling code: 0x%02x (%s), identifier %d".format(code, l2capSignalName(code), ident)
                Decoded(prefix + "L2CAP ${l2capSignalName(code)}", details, Layer.L2CAP, handle)
            }
            else -> Decoded(
                prefix + "L2CAP CID 0x%04x (%d bytes)".format(cid, l2Len), details, Layer.L2CAP, handle
            )
        }
    }

    private fun decodeAtt(r: Reader, details: MutableList<String>, prefix: String, handle: Int): Decoded {
        val op = r.u8()
        val name = attOpName(op)
        details += "ATT opcode: 0x%02x (%s)".format(op, name)
        val extra = when (op) {
            0x01 -> {
                val reqOp = r.u8()
                val attHandle = r.u16()
                val err = r.u8()
                " ${attOpName(reqOp)} handle=0x%04x: %s".format(attHandle, attErrorName(err))
            }
            0x02, 0x03 -> " mtu=${r.u16()}"
            0x04 -> " 0x%04x..0x%04x".format(r.u16(), r.u16())
            0x08, 0x10 -> {
                val start = r.u16()
                val end = r.u16()
                " 0x%04x..0x%04x type=%s".format(start, end, uuidString(r.bytes(r.remaining)))
            }
            0x0A -> " handle=0x%04x".format(r.u16())
            0x0C -> " handle=0x%04x offset=%d".format(r.u16(), r.u16())
            0x12, 0x52, 0x1B, 0x1D -> {
                val attHandle = r.u16()
                val value = r.bytes(r.remaining)
                details += "Attribute handle: 0x%04x".format(attHandle)
                details += "Value: ${value.toHex()}"
                " handle=0x%04x value=%s".format(attHandle, value.toHex(limit = 32))
            }
            0x16, 0x17 -> {
                val attHandle = r.u16()
                val offset = r.u16()
                val value = r.bytes(r.remaining)
                " handle=0x%04x offset=%d value=%s".format(attHandle, offset, value.toHex(limit = 32))
            }
            0x0B, 0x0D -> {
                val value = r.bytes(r.remaining)
                details += "Value: ${value.toHex()}"
                " value=${value.toHex(limit = 32)}"
            }
            else -> ""
        }
        return Decoded(prefix + "ATT $name$extra", details, Layer.ATT, handle)
    }

    private fun decodeSmp(r: Reader, details: MutableList<String>, prefix: String, handle: Int): Decoded {
        val code = r.u8()
        val name = smpName(code)
        details += "SMP code: 0x%02x (%s)".format(code, name)
        val extra = when (code) {
            0x01, 0x02 -> {
                val io = r.u8()
                val oob = r.u8()
                val auth = r.u8()
                val keySize = r.u8()
                details += "IO capability: ${ioCapName(io)}"
                details += "OOB: ${oob != 0}"
                details += "AuthReq: 0x%02x (bonding=%b MITM=%b SC=%b)".format(
                    auth, auth and 0x03 == 1, auth and 0x04 != 0, auth and 0x08 != 0
                )
                details += "Max key size: $keySize"
                " io=${ioCapName(io)} auth=0x%02x".format(auth)
            }
            0x05 -> " reason=${smpFailName(r.u8())}"
            0x0B -> " auth=0x%02x".format(r.u8())
            else -> ""
        }
        return Decoded(prefix + "SMP $name$extra", details, Layer.SMP, handle)
    }

    // endregion

    private fun decodeSco(r: Reader): Decoded {
        val handle = r.u16() and 0x0FFF
        val len = r.u8()
        return Decoded("[0x%04x] SCO %d bytes".format(handle, len), emptyList(), Layer.HCI, handle)
    }

    private fun decodeIso(r: Reader): Decoded {
        val handle = r.u16() and 0x0FFF
        val len = r.u16() and 0x3FFF
        return Decoded("[0x%04x] ISO %d bytes".format(handle, len), emptyList(), Layer.HCI, handle)
    }

    // region Advertising data

    private fun forEachAd(ad: ByteArray, block: (type: Int, value: ByteArray) -> Unit) {
        var i = 0
        while (i < ad.size) {
            val len = ad[i].u8
            if (len == 0 || i + 1 + len > ad.size) break
            block(ad[i + 1].u8, ad.copyOfRange(i + 2, i + 1 + len))
            i += 1 + len
        }
    }

    private fun advName(ad: ByteArray): String? {
        var name: String? = null
        forEachAd(ad) { type, value ->
            if (type == 0x08 || type == 0x09) name = value.decodeToString()
        }
        return name
    }

    private fun describeAd(ad: ByteArray): List<String> {
        val out = mutableListOf<String>()
        forEachAd(ad) { type, value ->
            val desc = when (type) {
                0x01 -> "Flags: 0x%02x".format(value.firstOrNull()?.u8 ?: 0)
                0x02, 0x03 -> "16-bit UUIDs: " + value.toList().chunked(2)
                    .joinToString { "0x%04x".format((it[0].u8) or (it.getOrElse(1) { 0 }.u8 shl 8)) }
                0x06, 0x07 -> "128-bit UUIDs: " + value.toList().chunked(16).joinToString { uuidString(it.toByteArray()) }
                0x08, 0x09 -> "Name: ${value.decodeToString()}"
                0x0A -> "TX power: ${value.firstOrNull()?.toInt() ?: 0} dBm"
                0x16 -> "Service data: ${value.toHex()}"
                0xFF -> "Manufacturer data: ${value.toHex()}"
                else -> "AD 0x%02x: %s".format(type, value.toHex())
            }
            out += desc
        }
        return out
    }

    // endregion

    // region Name tables

    fun opcodeString(opcode: Int) = "0x%04x %s".format(opcode, commandName(opcode))

    fun commandName(opcode: Int): String = COMMANDS[opcode] ?: when (opcode shr 10) {
        0x3F -> "Vendor 0x%03x".format(opcode and 0x3FF)
        else -> "Command 0x%04x".format(opcode)
    }

    private val COMMANDS = mapOf(
        0x0401 to "Inquiry", 0x0402 to "Inquiry Cancel", 0x0405 to "Create Connection",
        0x0406 to "Disconnect", 0x0408 to "Create Connection Cancel", 0x0409 to "Accept Connection Request",
        0x040B to "Link Key Request Reply", 0x040C to "Link Key Request Negative Reply",
        0x0411 to "Authentication Requested", 0x0413 to "Set Connection Encryption",
        0x0419 to "Remote Name Request", 0x041B to "Read Remote Supported Features",
        0x042B to "IO Capability Request Reply", 0x042C to "User Confirmation Request Reply",
        0x0C01 to "Set Event Mask", 0x0C03 to "Reset", 0x0C05 to "Set Event Filter",
        0x0C13 to "Write Local Name", 0x0C14 to "Read Local Name", 0x0C1A to "Write Scan Enable",
        0x0C24 to "Write Class Of Device", 0x0C45 to "Write Inquiry Mode", 0x0C52 to "Write Extended Inquiry Response",
        0x0C56 to "Write Simple Pairing Mode", 0x0C6D to "Write LE Host Support",
        0x0C7A to "Write Secure Connections Host Support",
        0x1001 to "Read Local Version Information", 0x1002 to "Read Local Supported Commands",
        0x1003 to "Read Local Supported Features", 0x1004 to "Read Local Extended Features",
        0x1005 to "Read Buffer Size", 0x1009 to "Read BD_ADDR", 0x1405 to "Read RSSI",
        0x2001 to "LE Set Event Mask", 0x2002 to "LE Read Buffer Size",
        0x2003 to "LE Read Local Supported Features", 0x2005 to "LE Set Random Address",
        0x2006 to "LE Set Advertising Parameters", 0x2007 to "LE Read Advertising Channel TX Power",
        0x2008 to "LE Set Advertising Data", 0x2009 to "LE Set Scan Response Data",
        0x200A to "LE Set Advertising Enable", 0x200B to "LE Set Scan Parameters",
        0x200C to "LE Set Scan Enable", 0x200D to "LE Create Connection",
        0x200E to "LE Create Connection Cancel", 0x200F to "LE Read Filter Accept List Size",
        0x2010 to "LE Clear Filter Accept List", 0x2011 to "LE Add Device To Filter Accept List",
        0x2012 to "LE Remove Device From Filter Accept List", 0x2013 to "LE Connection Update",
        0x2014 to "LE Set Host Channel Classification", 0x2016 to "LE Read Remote Features",
        0x2017 to "LE Encrypt", 0x2018 to "LE Rand", 0x2019 to "LE Enable Encryption",
        0x201A to "LE Long Term Key Request Reply", 0x201B to "LE Long Term Key Request Negative Reply",
        0x201C to "LE Read Supported States", 0x2020 to "LE Remote Connection Parameter Request Reply",
        0x2021 to "LE Remote Connection Parameter Request Negative Reply", 0x2022 to "LE Set Data Length",
        0x2023 to "LE Read Suggested Default Data Length", 0x2024 to "LE Write Suggested Default Data Length",
        0x2027 to "LE Add Device To Resolving List", 0x2028 to "LE Remove Device From Resolving List",
        0x2029 to "LE Clear Resolving List", 0x202D to "LE Set Address Resolution Enable",
        0x202E to "LE Set Resolvable Private Address Timeout", 0x202F to "LE Read Maximum Data Length",
        0x2030 to "LE Read PHY", 0x2031 to "LE Set Default PHY", 0x2032 to "LE Set PHY",
        0x2035 to "LE Set Advertising Set Random Address", 0x2036 to "LE Set Extended Advertising Parameters",
        0x2037 to "LE Set Extended Advertising Data", 0x2038 to "LE Set Extended Scan Response Data",
        0x2039 to "LE Set Extended Advertising Enable", 0x203A to "LE Read Maximum Advertising Data Length",
        0x203B to "LE Read Number Of Supported Advertising Sets", 0x203C to "LE Remove Advertising Set",
        0x203D to "LE Clear Advertising Sets", 0x2041 to "LE Set Extended Scan Parameters",
        0x2042 to "LE Set Extended Scan Enable", 0x2043 to "LE Extended Create Connection",
        0x204E to "LE Set Privacy Mode",
    )

    fun eventName(code: Int): String = when (code) {
        0x01 -> "Inquiry Complete"
        0x02 -> "Inquiry Result"
        0x03 -> "Connection Complete"
        0x04 -> "Connection Request"
        0x05 -> "Disconnection Complete"
        0x06 -> "Authentication Complete"
        0x07 -> "Remote Name Request Complete"
        0x08 -> "Encryption Change"
        0x0B -> "Read Remote Supported Features Complete"
        0x0C -> "Read Remote Version Information Complete"
        0x0E -> "Command Complete"
        0x0F -> "Command Status"
        0x10 -> "Hardware Error"
        0x13 -> "Number Of Completed Packets"
        0x14 -> "Mode Change"
        0x16 -> "PIN Code Request"
        0x17 -> "Link Key Request"
        0x18 -> "Link Key Notification"
        0x1A -> "Data Buffer Overflow"
        0x22 -> "Inquiry Result With RSSI"
        0x23 -> "Read Remote Extended Features Complete"
        0x2F -> "Extended Inquiry Result"
        0x30 -> "Encryption Key Refresh Complete"
        0x31 -> "IO Capability Request"
        0x32 -> "IO Capability Response"
        0x33 -> "User Confirmation Request"
        0x36 -> "Simple Pairing Complete"
        0x3E -> "LE Meta"
        0x57 -> "Authenticated Payload Timeout Expired"
        0xFF -> "Vendor Specific"
        else -> "Event 0x%02x".format(code)
    }

    private fun leSubeventName(sub: Int): String = when (sub) {
        0x01 -> "Connection Complete"
        0x02 -> "Advertising Report"
        0x03 -> "Connection Update Complete"
        0x04 -> "Read Remote Features Complete"
        0x05 -> "Long Term Key Request"
        0x06 -> "Remote Connection Parameter Request"
        0x07 -> "Data Length Change"
        0x08 -> "Read Local P-256 Public Key Complete"
        0x09 -> "Generate DHKey Complete"
        0x0A -> "Enhanced Connection Complete"
        0x0B -> "Directed Advertising Report"
        0x0C -> "PHY Update Complete"
        0x0D -> "Extended Advertising Report"
        0x0E -> "Periodic Advertising Sync Established"
        0x0F -> "Periodic Advertising Report"
        0x10 -> "Periodic Advertising Sync Lost"
        0x11 -> "Scan Timeout"
        0x12 -> "Advertising Set Terminated"
        0x13 -> "Scan Request Received"
        0x14 -> "Channel Selection Algorithm"
        0x19 -> "CIS Established"
        0x1A -> "CIS Request"
        else -> "Subevent 0x%02x".format(sub)
    }

    fun statusName(status: Int): String = when (status) {
        0x00 -> "Success"
        0x01 -> "Unknown HCI Command"
        0x02 -> "Unknown Connection Identifier"
        0x03 -> "Hardware Failure"
        0x04 -> "Page Timeout"
        0x05 -> "Authentication Failure"
        0x06 -> "PIN or Key Missing"
        0x07 -> "Memory Capacity Exceeded"
        0x08 -> "Connection Timeout"
        0x09 -> "Connection Limit Exceeded"
        0x0B -> "ACL Connection Already Exists"
        0x0C -> "Command Disallowed"
        0x0D -> "Rejected: Limited Resources"
        0x11 -> "Unsupported Feature or Parameter Value"
        0x12 -> "Invalid HCI Command Parameters"
        0x13 -> "Remote User Terminated Connection"
        0x14 -> "Remote Device Terminated: Low Resources"
        0x15 -> "Remote Device Terminated: Power Off"
        0x16 -> "Connection Terminated By Local Host"
        0x1A -> "Unsupported Remote Feature"
        0x1F -> "Unspecified Error"
        0x22 -> "LL Response Timeout"
        0x23 -> "LL Procedure Collision"
        0x28 -> "Instant Passed"
        0x3A -> "Controller Busy"
        0x3B -> "Unacceptable Connection Parameters"
        0x3C -> "Advertising Timeout"
        0x3D -> "Terminated: MIC Failure"
        0x3E -> "Failed To Establish Connection"
        else -> "0x%02x".format(status)
    }

    private fun attOpName(op: Int): String = when (op) {
        0x01 -> "Error Response"
        0x02 -> "Exchange MTU Request"
        0x03 -> "Exchange MTU Response"
        0x04 -> "Find Information Request"
        0x05 -> "Find Information Response"
        0x06 -> "Find By Type Value Request"
        0x07 -> "Find By Type Value Response"
        0x08 -> "Read By Type Request"
        0x09 -> "Read By Type Response"
        0x0A -> "Read Request"
        0x0B -> "Read Response"
        0x0C -> "Read Blob Request"
        0x0D -> "Read Blob Response"
        0x0E -> "Read Multiple Request"
        0x0F -> "Read Multiple Response"
        0x10 -> "Read By Group Type Request"
        0x11 -> "Read By Group Type Response"
        0x12 -> "Write Request"
        0x13 -> "Write Response"
        0x16 -> "Prepare Write Request"
        0x17 -> "Prepare Write Response"
        0x18 -> "Execute Write Request"
        0x19 -> "Execute Write Response"
        0x1B -> "Handle Value Notification"
        0x1D -> "Handle Value Indication"
        0x1E -> "Handle Value Confirmation"
        0x20 -> "Read Multiple Variable Request"
        0x21 -> "Read Multiple Variable Response"
        0x23 -> "Multiple Handle Value Notification"
        0x52 -> "Write Command"
        0xD2 -> "Signed Write Command"
        else -> "Opcode 0x%02x".format(op)
    }

    private fun attErrorName(err: Int): String = when (err) {
        0x01 -> "Invalid Handle"
        0x02 -> "Read Not Permitted"
        0x03 -> "Write Not Permitted"
        0x04 -> "Invalid PDU"
        0x05 -> "Insufficient Authentication"
        0x06 -> "Request Not Supported"
        0x07 -> "Invalid Offset"
        0x08 -> "Insufficient Authorization"
        0x09 -> "Prepare Queue Full"
        0x0A -> "Attribute Not Found"
        0x0B -> "Attribute Not Long"
        0x0C -> "Insufficient Encryption Key Size"
        0x0D -> "Invalid Attribute Value Length"
        0x0E -> "Unlikely Error"
        0x0F -> "Insufficient Encryption"
        0x10 -> "Unsupported Group Type"
        0x11 -> "Insufficient Resources"
        else -> "Error 0x%02x".format(err)
    }

    private fun smpName(code: Int): String = when (code) {
        0x01 -> "Pairing Request"
        0x02 -> "Pairing Response"
        0x03 -> "Pairing Confirm"
        0x04 -> "Pairing Random"
        0x05 -> "Pairing Failed"
        0x06 -> "Encryption Information"
        0x07 -> "Central Identification"
        0x08 -> "Identity Information"
        0x09 -> "Identity Address Information"
        0x0A -> "Signing Information"
        0x0B -> "Security Request"
        0x0C -> "Pairing Public Key"
        0x0D -> "Pairing DHKey Check"
        0x0E -> "Keypress Notification"
        else -> "Code 0x%02x".format(code)
    }

    private fun smpFailName(reason: Int): String = when (reason) {
        0x01 -> "Passkey Entry Failed"
        0x02 -> "OOB Not Available"
        0x03 -> "Authentication Requirements"
        0x04 -> "Confirm Value Failed"
        0x05 -> "Pairing Not Supported"
        0x06 -> "Encryption Key Size"
        0x07 -> "Command Not Supported"
        0x08 -> "Unspecified Reason"
        0x09 -> "Repeated Attempts"
        0x0A -> "Invalid Parameters"
        0x0B -> "DHKey Check Failed"
        0x0C -> "Numeric Comparison Failed"
        else -> "0x%02x".format(reason)
    }

    private fun ioCapName(io: Int): String = when (io) {
        0x00 -> "DisplayOnly"
        0x01 -> "DisplayYesNo"
        0x02 -> "KeyboardOnly"
        0x03 -> "NoInputNoOutput"
        0x04 -> "KeyboardDisplay"
        else -> "0x%02x".format(io)
    }

    private fun l2capSignalName(code: Int): String = when (code) {
        0x01 -> "Command Reject"
        0x02 -> "Connection Request"
        0x03 -> "Connection Response"
        0x04 -> "Configuration Request"
        0x05 -> "Configuration Response"
        0x06 -> "Disconnection Request"
        0x07 -> "Disconnection Response"
        0x0A -> "Information Request"
        0x0B -> "Information Response"
        0x12 -> "Connection Parameter Update Request"
        0x13 -> "Connection Parameter Update Response"
        0x14 -> "LE Credit Based Connection Request"
        0x15 -> "LE Credit Based Connection Response"
        0x16 -> "Flow Control Credit"
        0x17 -> "Credit Based Connection Request"
        0x18 -> "Credit Based Connection Response"
        else -> "Signal 0x%02x".format(code)
    }

    private fun phyName(phy: Int) = when (phy) {
        1 -> "1M"
        2 -> "2M"
        3 -> "Coded"
        else -> phy.toString()
    }

    // endregion

    /** Bluetooth UUIDs are little-endian on the wire. */
    fun uuidString(le: ByteArray): String = when (le.size) {
        2 -> "0x%04x".format(le[0].u8 or (le[1].u8 shl 8))
        16 -> {
            val h = le.reversedArray().toHex(separator = "")
            "${h.substring(0, 8)}-${h.substring(8, 12)}-${h.substring(12, 16)}-${h.substring(16, 20)}-${h.substring(20)}"
        }
        else -> le.toHex()
    }
}

/** Little-endian cursor over HCI payloads. Throws IndexOutOfBoundsException on underflow. */
private class Reader(private val data: ByteArray, private var pos: Int) {
    val remaining: Int get() = (data.size - pos).coerceAtLeast(0)

    fun u8(): Int = data[pos++].u8
    fun s8(): Int = data[pos++].toInt()
    fun u16(): Int = u8() or (u8() shl 8)
    fun skip(n: Int) {
        if (pos + n > data.size) throw IndexOutOfBoundsException()
        pos += n
    }
    fun bytes(n: Int): ByteArray {
        if (pos + n > data.size) throw IndexOutOfBoundsException()
        return data.copyOfRange(pos, pos + n).also { pos += n }
    }

    /** BD_ADDR, stored little-endian, formatted most-significant byte first. */
    fun addr(): String = bytes(6).reversedArray().toHex(separator = ":").uppercase()
}

private val Byte.u8: Int get() = toInt() and 0xFF

fun ByteArray.toHex(separator: String = " ", limit: Int = Int.MAX_VALUE): String {
    val shown = if (size > limit) copyOf(limit) else this
    val s = shown.joinToString(separator) { "%02x".format(it.toInt() and 0xFF) }
    return if (size > limit) "$s…" else s
}
