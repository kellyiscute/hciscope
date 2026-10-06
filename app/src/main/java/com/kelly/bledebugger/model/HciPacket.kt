package com.kelly.bledebugger.model

/** H4 packet indicator, the first byte of each record in a datalink-1002 btsnoop file. */
enum class HciType(val indicator: Int, val label: String) {
    COMMAND(0x01, "CMD"),
    ACL(0x02, "ACL"),
    SCO(0x03, "SCO"),
    EVENT(0x04, "EVT"),
    ISO(0x05, "ISO"),
    UNKNOWN(-1, "???");

    companion object {
        fun of(indicator: Int) = entries.firstOrNull { it.indicator == indicator } ?: UNKNOWN
    }
}

/** Highest protocol layer the decoder recognised; used for filtering. */
enum class Layer { HCI, L2CAP, ATT, SMP }

/** A raw btsnoop record. */
class SnoopRecord(
    val originalLength: Int,
    val flags: Int,
    val drops: Int,
    /** Microseconds since the Unix epoch. */
    val timestampUs: Long,
    /** Starts with the H4 type byte. */
    val data: ByteArray,
) {
    /** btsnoop flag bit 0: 0 = sent (host → controller), 1 = received. */
    val received: Boolean get() = flags and 0x01 != 0
}

class Decoded(
    val summary: String,
    val details: List<String>,
    val layer: Layer,
    val connectionHandle: Int?,
)

class HciPacket(
    val index: Int,
    val record: SnoopRecord,
    val decoded: Decoded,
) {
    val type: HciType get() = HciType.of(record.data.firstOrNull()?.toInt()?.and(0xFF) ?: -1)
    val received: Boolean get() = record.received
    val timestampUs: Long get() = record.timestampUs
}
