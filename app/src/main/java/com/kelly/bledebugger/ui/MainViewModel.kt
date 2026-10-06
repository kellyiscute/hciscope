package com.kelly.bledebugger.ui

import android.app.Application
import android.net.Uri
import android.os.RemoteException
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kelly.bledebugger.IPrivilegedService
import com.kelly.bledebugger.decode.HciDecoder
import com.kelly.bledebugger.export.BtSnoopWriter
import com.kelly.bledebugger.model.HciPacket
import com.kelly.bledebugger.model.HciType
import com.kelly.bledebugger.model.Layer
import com.kelly.bledebugger.privileged.ShizukuManager
import com.kelly.bledebugger.privileged.ShizukuState
import com.kelly.bledebugger.snoop.SnoopTailer
import com.kelly.bledebugger.snoop.TailEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Direction { ALL, SENT, RECEIVED }

data class PacketFilter(
    val types: Set<HciType> = setOf(HciType.COMMAND, HciType.EVENT, HciType.ACL, HciType.SCO, HciType.ISO),
    val direction: Direction = Direction.ALL,
    /** Only ATT/SMP/L2CAP packets, i.e. hide raw HCI command/event chatter. */
    val upperLayersOnly: Boolean = false,
    val hideAdvReports: Boolean = false,
    val query: String = "",
)

data class SnoopStatus(
    val mode: String = "unknown",
    val logPath: String? = null,
    val tailState: String = "Idle",
    val diagnostics: String? = null,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val shizuku = ShizukuManager()
    val shizukuState: StateFlow<ShizukuState> = shizuku.state

    private val _status = MutableStateFlow(SnoopStatus())
    val status: StateFlow<SnoopStatus> = _status.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _messages = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _messages.asStateFlow()

    private val _filter = MutableStateFlow(PacketFilter())
    val filter: StateFlow<PacketFilter> = _filter.asStateFlow()

    private val lock = Any()
    private val buffer = ArrayDeque<HciPacket>()
    private var nextIndex = 1
    private val _all = MutableStateFlow<List<HciPacket>>(emptyList())

    val totalCount: StateFlow<Int> = _all.map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val packets: StateFlow<List<HciPacket>> = combine(_all, _filter) { all, f -> all.filter { f.matches(it) } }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private var tailJob: Job? = null

    init {
        shizuku.start()
        viewModelScope.launch {
            shizuku.state.collect { state ->
                tailJob?.cancel()
                tailJob = null
                if (state is ShizukuState.Ready) {
                    refreshSnoopMode(state.service)
                    tailJob = launch(Dispatchers.IO) { tail(state.service) }
                } else {
                    _status.update { it.copy(tailState = "Idle") }
                }
            }
        }
    }

    override fun onCleared() {
        shizuku.stop()
    }

    fun requestShizukuPermission() = shizuku.requestPermission()

    fun refreshShizuku() = shizuku.refresh()

    fun setFilter(transform: (PacketFilter) -> PacketFilter) = _filter.update(transform)

    fun consumeMessage() {
        _messages.value = null
    }

    fun clear() {
        synchronized(lock) {
            buffer.clear()
            _all.value = emptyList()
        }
    }

    /** Enables or disables the HCI snoop log and restarts Bluetooth so the stack picks it up. */
    fun setCapture(enabled: Boolean) {
        val service = (shizuku.state.value as? ShizukuState.Ready)?.service ?: return
        viewModelScope.launch(Dispatchers.IO) {
            _busy.value = true
            try {
                val mode = if (enabled) "full" else "disabled"
                val flag = if (enabled) 1 else 0
                val result = service.exec(
                    """
                    setprop persist.bluetooth.btsnooplogmode $mode
                    setprop persist.bluetooth.btsnoopenable ${enabled}
                    settings put secure bluetooth_hci_log $flag
                    $RESTART_BLUETOOTH
                    """.trimIndent()
                )
                if (!result.startsWith("exit=0")) {
                    _messages.value = "Command failed: ${result.take(300)}"
                } else {
                    _messages.value = if (enabled) "Snoop log enabled (full). Bluetooth restarted." else "Snoop log disabled."
                }
                refreshSnoopMode(service)
            } catch (e: RemoteException) {
                _messages.value = "Privileged service died: ${e.message}"
                shizuku.refresh()
            } finally {
                _busy.value = false
            }
        }
    }

    fun restartBluetooth() {
        val service = (shizuku.state.value as? ShizukuState.Ready)?.service ?: return
        viewModelScope.launch(Dispatchers.IO) {
            _busy.value = true
            try {
                service.exec(RESTART_BLUETOOTH)
            } catch (e: RemoteException) {
                _messages.value = "Privileged service died: ${e.message}"
            } finally {
                _busy.value = false
            }
        }
    }

    fun export(uri: Uri) {
        val records = synchronized(lock) { buffer.map { it.record } }
        viewModelScope.launch(Dispatchers.IO) {
            _messages.value = try {
                getApplication<Application>().contentResolver.openOutputStream(uri)?.use {
                    BtSnoopWriter.write(it, records)
                } ?: error("Could not open destination")
                "Exported ${records.size} packets"
            } catch (e: Exception) {
                "Export failed: ${e.message}"
            }
        }
    }

    private suspend fun refreshSnoopMode(service: IPrivilegedService) = withContext(Dispatchers.IO) {
        try {
            val mode = service.exec("getprop persist.bluetooth.btsnooplogmode").substringAfter('\n').trim()
            val legacy = service.exec("settings get secure bluetooth_hci_log").substringAfter('\n').trim()
            _status.update {
                it.copy(
                    mode = mode.ifEmpty { if (legacy == "1") "enabled (legacy)" else "disabled" },
                    logPath = service.findSnoopLogPath(),
                )
            }
        } catch (e: RemoteException) {
            shizuku.refresh()
        }
    }

    private suspend fun tail(service: IPrivilegedService) {
        try {
            SnoopTailer(service).tail().collect { event ->
                when (event) {
                    is TailEvent.Opened -> _status.update {
                        it.copy(logPath = event.path, tailState = "Reading", diagnostics = null)
                    }
                    is TailEvent.Waiting -> {
                        val report = runCatching { service.diagnose() }.getOrNull()
                        _status.update {
                            it.copy(
                                logPath = event.path,
                                tailState = "Waiting for log file (enable capture?)",
                                diagnostics = report,
                            )
                        }
                    }
                    is TailEvent.Error -> _status.update { it.copy(tailState = "Error: ${event.message}") }
                    is TailEvent.Records -> {
                        val decoded = event.records.map { rec -> rec to HciDecoder.decode(rec.data) }
                        synchronized(lock) {
                            for ((rec, dec) in decoded) {
                                buffer.addLast(HciPacket(nextIndex++, rec, dec))
                                if (buffer.size > MAX_PACKETS) buffer.removeFirst()
                            }
                            _all.value = buffer.toList()
                        }
                        _status.update { it.copy(tailState = "Live") }
                    }
                }
            }
        } catch (e: RemoteException) {
            _status.update { it.copy(tailState = "Service disconnected") }
            shizuku.refresh()
        }
    }

    private fun PacketFilter.matches(p: HciPacket): Boolean {
        if (p.type !in types && p.type != HciType.UNKNOWN) return false
        when (direction) {
            Direction.SENT -> if (p.received) return false
            Direction.RECEIVED -> if (!p.received) return false
            Direction.ALL -> Unit
        }
        if (upperLayersOnly && p.decoded.layer == Layer.HCI) return false
        if (hideAdvReports && p.decoded.summary.startsWith("LE ") && p.decoded.summary.contains("Adv Report")) return false
        if (query.isNotBlank()) {
            val q = query.trim()
            val handleMatch = q.removePrefix("0x").toIntOrNull(16)?.let { it == p.decoded.connectionHandle } ?: false
            if (!handleMatch && !p.decoded.summary.contains(q, ignoreCase = true)) return false
        }
        return true
    }

    companion object {
        const val MAX_PACKETS = 50_000

        // `cmd bluetooth_manager` exists on Android 13+; older releases only have `svc bluetooth`.
        private const val RESTART_BLUETOOTH = """
if cmd bluetooth_manager disable >/dev/null 2>&1; then
  sleep 3
  cmd bluetooth_manager enable
else
  svc bluetooth disable
  sleep 3
  svc bluetooth enable
fi
"""
    }
}
