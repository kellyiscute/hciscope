package com.kelly.bledebugger.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kelly.bledebugger.model.HciPacket
import com.kelly.bledebugger.model.HciType
import com.kelly.bledebugger.privileged.ShizukuState
import com.kelly.bledebugger.ui.Direction
import com.kelly.bledebugger.ui.MainViewModel
import com.kelly.bledebugger.ui.PacketFilter
import com.kelly.bledebugger.ui.SnoopStatus
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val TIME_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault())
private val FILE_TIME_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.systemDefault())

internal val SENT_COLOR = Color(0xFF1E88E5)
internal val RECEIVED_COLOR = Color(0xFF43A047)

internal fun formatTime(us: Long): String = TIME_FORMAT.format(Instant.ofEpochMilli(us / 1000))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(vm: MainViewModel) {
    val shizukuState by vm.shizukuState.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val packets by vm.packets.collectAsStateWithLifecycle()
    val total by vm.totalCount.collectAsStateWithLifecycle()
    val filter by vm.filter.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()

    var follow by rememberSaveable { mutableStateOf(true) }
    var statusExpanded by rememberSaveable { mutableStateOf(true) }
    var confirmCapture by remember { mutableStateOf<Boolean?>(null) }
    var selected by remember { mutableStateOf<HciPacket?>(null) }

    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            vm.consumeMessage()
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri -> uri?.let(vm::export) }

    val listState = rememberLazyListState()
    LaunchedEffect(packets.size, follow) {
        if (follow && packets.isNotEmpty()) listState.scrollToItem(packets.lastIndex)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("BLE Debugger")
                        Text(
                            "${packets.size} shown / $total captured",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                },
                actions = {
                    TextButton(onClick = { follow = !follow }) { Text(if (follow) "Following" else "Follow") }
                    IconButton(onClick = {
                        exportLauncher.launch("hci-${FILE_TIME_FORMAT.format(Instant.now())}.btsnoop")
                    }, enabled = total > 0) {
                        Icon(Icons.Default.Share, contentDescription = "Export btsnoop")
                    }
                    IconButton(onClick = vm::clear) {
                        Icon(Icons.Default.Delete, contentDescription = "Clear")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            StatusCard(
                state = shizukuState,
                status = status,
                busy = busy,
                expanded = statusExpanded,
                onToggleExpanded = { statusExpanded = !statusExpanded },
                onRequestPermission = vm::requestShizukuPermission,
                onRetry = vm::refreshShizuku,
                onCapture = { confirmCapture = it },
                onRestartBluetooth = vm::restartBluetooth,
            )
            FilterBar(filter, onChange = vm::setFilter)
            HorizontalDivider()
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                items(packets, key = { it.index }) { p ->
                    PacketRow(p, onClick = { selected = p })
                    HorizontalDivider(thickness = 0.5.dp)
                }
            }
        }
    }

    confirmCapture?.let { enable ->
        AlertDialog(
            onDismissRequest = { confirmCapture = null },
            title = { Text(if (enable) "Start capture?" else "Stop capture?") },
            text = {
                Text(
                    (if (enable) "Sets the HCI snoop log to \"full\" mode. " else "Disables the HCI snoop log. ") +
                        "Bluetooth will be restarted, which drops all active connections."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.setCapture(enable)
                    confirmCapture = null
                }) { Text(if (enable) "Start" else "Stop") }
            },
            dismissButton = { TextButton(onClick = { confirmCapture = null }) { Text("Cancel") } },
        )
    }

    selected?.let { PacketDetailSheet(it, onDismiss = { selected = null }) }
}

@Composable
private fun StatusCard(
    state: ShizukuState,
    status: SnoopStatus,
    busy: Boolean,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onRequestPermission: () -> Unit,
    onRetry: () -> Unit,
    onCapture: (Boolean) -> Unit,
    onRestartBluetooth: () -> Unit,
) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Column(Modifier.padding(12.dp)) {
            Row(
                Modifier.fillMaxWidth().clickable(onClick = onToggleExpanded),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val headline = when (state) {
                    is ShizukuState.Ready -> "Snoop: ${status.mode} · ${status.tailState}"
                    ShizukuState.Binding -> "Starting root service…"
                    ShizukuState.NoPermission -> "Shizuku permission needed"
                    is ShizukuState.NotRoot -> "Shizuku is not running as root"
                    ShizukuState.NotRunning -> "Shizuku is not running"
                    ShizukuState.Unsupported -> "Shizuku is too old"
                }
                Text(headline, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                if (busy || state == ShizukuState.Binding) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Icon(
                    if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                )
            }
            if (!expanded) return@Column

            val body = MaterialTheme.typography.bodySmall
            when (state) {
                ShizukuState.NotRunning -> {
                    Text("Open the Shizuku app and start it. On a rooted phone, use \"Start (root)\".", style = body)
                    OutlinedButton(onClick = onRetry) { Text("Retry") }
                }
                ShizukuState.Unsupported -> Text("Update Shizuku to v11 or newer.", style = body)
                ShizukuState.NoPermission -> {
                    Text("This app needs Shizuku access to run commands as root.", style = body)
                    Button(onClick = onRequestPermission) { Text("Grant access") }
                }
                is ShizukuState.NotRoot -> {
                    Text(
                        "Shizuku is running as uid ${state.uid} (ADB mode), which can't read the Bluetooth logs. " +
                            "Stop Shizuku and start it again with root.",
                        style = body,
                    )
                    OutlinedButton(onClick = onRetry) { Text("Retry") }
                }
                ShizukuState.Binding -> Unit
                is ShizukuState.Ready -> {
                    Text("Log: ${status.logPath ?: "?"}", style = body, fontFamily = FontFamily.Monospace)
                    status.diagnostics?.let {
                        Text(it.trim(), style = body, fontFamily = FontFamily.Monospace)
                    }
                    if (status.mode != "full") {
                        Text(
                            "Snoop mode isn't \"full\": ACL payloads may be truncated or missing.",
                            style = body,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { onCapture(true) }, enabled = !busy) { Text("Start capture") }
                        OutlinedButton(onClick = { onCapture(false) }, enabled = !busy) { Text("Stop") }
                        TextButton(onClick = onRestartBluetooth, enabled = !busy) { Text("Restart BT") }
                    }
                }
            }
        }
    }
}

@Composable
private fun FilterBar(filter: PacketFilter, onChange: ((PacketFilter) -> PacketFilter) -> Unit) {
    Column(Modifier.padding(horizontal = 12.dp)) {
        OutlinedTextField(
            value = filter.query,
            onValueChange = { q -> onChange { it.copy(query = q) } },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text("Search summary or handle (e.g. 0x0040)") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                if (filter.query.isNotEmpty()) {
                    IconButton(onClick = { onChange { it.copy(query = "") } }) {
                        Icon(Icons.Default.Clear, contentDescription = "Clear search")
                    }
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        )
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            for (type in listOf(HciType.COMMAND, HciType.EVENT, HciType.ACL)) {
                FilterChip(
                    selected = type in filter.types,
                    onClick = {
                        onChange { f -> f.copy(types = if (type in f.types) f.types - type else f.types + type) }
                    },
                    label = { Text(type.label) },
                )
            }
            FilterChip(
                selected = filter.direction != Direction.ALL,
                onClick = {
                    onChange { f -> f.copy(direction = Direction.entries[(f.direction.ordinal + 1) % Direction.entries.size]) }
                },
                label = {
                    Text(
                        when (filter.direction) {
                            Direction.ALL -> "↔ Both"
                            Direction.SENT -> "→ Sent"
                            Direction.RECEIVED -> "← Received"
                        }
                    )
                },
            )
            FilterChip(
                selected = filter.upperLayersOnly,
                onClick = { onChange { it.copy(upperLayersOnly = !it.upperLayersOnly) } },
                label = { Text("ATT/SMP/L2CAP") },
            )
            FilterChip(
                selected = filter.hideAdvReports,
                onClick = { onChange { it.copy(hideAdvReports = !it.hideAdvReports) } },
                label = { Text("Hide adv reports") },
            )
        }
    }
}

@Composable
private fun PacketRow(p: HciPacket, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (p.received) "←" else "→",
            color = if (p.received) RECEIVED_COLOR else SENT_COLOR,
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp,
            modifier = Modifier.width(20.dp),
        )
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    p.type.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = typeColor(p.type),
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.width(32.dp),
                )
                Text(
                    "#${p.index}  ${formatTime(p.timestampUs)}  ${p.record.data.size}B",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                p.decoded.summary,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

internal fun typeColor(type: HciType): Color = when (type) {
    HciType.COMMAND -> Color(0xFFFB8C00)
    HciType.EVENT -> Color(0xFF8E24AA)
    HciType.ACL -> Color(0xFF00897B)
    HciType.SCO, HciType.ISO -> Color(0xFF6D4C41)
    HciType.UNKNOWN -> Color.Gray
}
