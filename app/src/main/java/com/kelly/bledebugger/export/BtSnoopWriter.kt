package com.kelly.bledebugger.export

import com.kelly.bledebugger.model.SnoopRecord
import com.kelly.bledebugger.snoop.BtSnoopParser
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.OutputStream

/** Writes records as a btsnoop (H4) file that Wireshark can open. */
object BtSnoopWriter {

    fun write(out: OutputStream, records: List<SnoopRecord>) {
        DataOutputStream(BufferedOutputStream(out)).use { data ->
            data.write(BtSnoopParser.MAGIC)
            data.writeInt(1)
            data.writeInt(BtSnoopParser.DATALINK_H4)
            for (r in records) {
                data.writeInt(r.originalLength)
                data.writeInt(r.data.size)
                data.writeInt(r.flags)
                data.writeInt(r.drops)
                data.writeLong(r.timestampUs + BtSnoopParser.EPOCH_DELTA_US)
                data.write(r.data)
            }
        }
    }
}
