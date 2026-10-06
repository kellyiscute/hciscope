package com.kelly.bledebugger.snoop

import com.kelly.bledebugger.IPrivilegedService
import com.kelly.bledebugger.model.SnoopRecord
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

sealed interface TailEvent {
    /** The log file was (re)opened from offset 0. */
    data class Opened(val path: String) : TailEvent
    data class Waiting(val path: String) : TailEvent
    class Records(val records: List<SnoopRecord>) : TailEvent
    data class Error(val message: String) : TailEvent
}

/**
 * Follows the btsnoop log through the privileged service, like `tail -f`.
 *
 * The Bluetooth stack rotates the file (to `.last`) every time it restarts, so a new inode or
 * a size smaller than what we've already read means "start over at offset 0".
 */
class SnoopTailer(
    private val service: IPrivilegedService,
    private val pollIntervalMs: Long = 200,
) {
    fun tail(): Flow<TailEvent> = flow {
        val parser = BtSnoopParser()
        var path = service.findSnoopLogPath()
        var inode = Long.MIN_VALUE
        var offset = 0L
        var waiting = false
        var idlePolls = 0

        while (true) {
            val (size, ino) = service.stat(path).let { it[0] to it[1] }
            if (size < 0) {
                if (!waiting) emit(TailEvent.Waiting(path))
                waiting = true
                inode = Long.MIN_VALUE
                delay(1000)
                path = service.findSnoopLogPath()
                continue
            }
            waiting = false

            if (ino != inode || size < offset) {
                inode = ino
                offset = 0
                parser.reset()
                emit(TailEvent.Opened(path))
            }

            if (size > offset) {
                val chunk = service.read(path, offset, CHUNK)
                if (chunk.isEmpty()) {
                    delay(pollIntervalMs)
                    continue
                }
                offset += chunk.size
                val records = try {
                    parser.feed(chunk)
                } catch (e: BtSnoopParser.FormatException) {
                    emit(TailEvent.Error(e.message ?: "Malformed btsnoop data"))
                    // Wait for the file to be replaced rather than spinning on bad data.
                    while (service.stat(path)[1] == inode && service.findSnoopLogPath() == path) delay(1000)
                    path = service.findSnoopLogPath()
                    inode = Long.MIN_VALUE
                    continue
                }
                if (records.isNotEmpty()) emit(TailEvent.Records(records))
                // Keep reading without delay while we're behind.
                if (offset < size) continue
            }
            // Caught up: occasionally check whether the stack has started a newer log file.
            if (++idlePolls % PATH_RECHECK_POLLS == 0) {
                val newest = service.findSnoopLogPath()
                if (newest != path && service.stat(newest)[0] >= 0) {
                    path = newest
                    inode = Long.MIN_VALUE
                    continue
                }
            }
            delay(pollIntervalMs)
        }
    }

    private companion object {
        const val CHUNK = 256 * 1024
        const val PATH_RECHECK_POLLS = 10
    }
}
