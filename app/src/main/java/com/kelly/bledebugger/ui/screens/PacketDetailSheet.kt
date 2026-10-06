package com.kelly.bledebugger.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.kelly.bledebugger.model.HciPacket

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PacketDetailSheet(p: HciPacket, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        SelectionContainer {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text(p.decoded.summary, style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))

                Section("Packet")
                Line("Index: #${p.index}")
                Line("Time: ${formatTime(p.timestampUs)} (${p.timestampUs} µs)")
                Line("Direction: " + if (p.received) "controller → host (received)" else "host → controller (sent)")
                Line("Type: ${p.type.label}")
                Line("Length: ${p.record.data.size} bytes" +
                    if (p.record.originalLength != p.record.data.size) " (original ${p.record.originalLength}, truncated)" else "")
                if (p.record.drops != 0) Line("Cumulative drops: ${p.record.drops}")

                if (p.decoded.details.isNotEmpty()) {
                    Section("Decoded")
                    p.decoded.details.forEach { Line(it) }
                }

                Section("Hex (including H4 type byte)")
                Text(
                    hexDump(p.record.data),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                )
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun Section(title: String) {
    Spacer(Modifier.height(12.dp))
    Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun Line(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
}

internal fun hexDump(data: ByteArray): String = buildString {
    for (offset in data.indices step 16) {
        val row = data.copyOfRange(offset, minOf(offset + 16, data.size))
        append("%04x  ".format(offset))
        for (i in 0 until 16) {
            append(if (i < row.size) "%02x ".format(row[i].toInt() and 0xFF) else "   ")
            if (i == 7) append(' ')
        }
        append(' ')
        row.forEach { b -> append(if (b in 0x20..0x7E) b.toInt().toChar() else '.') }
        if (offset + 16 < data.size) append('\n')
    }
}
