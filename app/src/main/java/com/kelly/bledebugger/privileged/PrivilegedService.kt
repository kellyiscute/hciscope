package com.kelly.bledebugger.privileged

import android.system.Os
import com.kelly.bledebugger.IPrivilegedService
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/**
 * Shizuku UserService. Shizuku instantiates this by class name in a separate process running
 * with Shizuku's uid (0 when Shizuku was started with root), so everything here runs as root.
 */
class PrivilegedService : IPrivilegedService.Stub() {

    override fun destroy() {
        exitProcess(0)
    }

    override fun exec(cmd: String): String {
        val process = ProcessBuilder("sh", "-c", cmd).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroy()
            return "exit=timeout\n$output"
        }
        return "exit=${process.exitValue()}\n$output"
    }

    override fun findSnoopLogPath(): String {
        val configured = exec("getprop persist.bluetooth.btsnooppath")
            .substringAfter('\n').trim()
        val candidates = listOfNotNull(
            configured.takeIf { it.isNotEmpty() },
            DEFAULT_PATH,
            "/data/log/bt/btsnoop_hci.log",
            "/sdcard/btsnoop_hci.log",
            "/data/media/0/btsnoop_hci.log",
        )
        // Prefer the newest log in the standard folder: some ROMs (e.g. Xiaomi) add a timestamp
        // to the name and start a new file on every Bluetooth restart.
        val logDirs = listOfNotNull(configured.takeIf { it.isNotEmpty() }?.let { File(it).parent }, File(DEFAULT_PATH).parent)
        logDirs.asSequence()
            .mapNotNull { dir -> File(dir).listFiles()?.filter { it.isSnoopLog() }?.maxByOrNull { it.lastModified() } }
            .firstOrNull()?.let { return it.absolutePath }

        candidates.firstOrNull { File(it).exists() }?.let { return it }

        // Some OEM ROMs write the log elsewhere; pick the most recently written btsnoop file.
        val found = exec(
            "find $SEARCH_DIRS -maxdepth 5 -type f -iname '*btsnoop*' ! -iname '*.last' 2>/dev/null"
        ).lines().drop(1).map { it.trim() }.filter { it.startsWith("/") }
        return found.maxByOrNull { File(it).lastModified() } ?: configured.ifEmpty { DEFAULT_PATH }
    }

    override fun diagnose(): String = exec(
        """
        echo "id: ${'$'}(id)"
        echo "btsnooplogmode=${'$'}(getprop persist.bluetooth.btsnooplogmode) btsnooppath=${'$'}(getprop persist.bluetooth.btsnooppath)"
        ls -la /data/misc/bluetooth/logs/ 2>&1 | head -20
        echo "search:"
        find $SEARCH_DIRS -maxdepth 5 -iname '*snoop*' 2>/dev/null | head -20
        """.trimIndent()
    ).substringAfter('\n')

    override fun stat(path: String): LongArray = try {
        val st = Os.stat(path)
        longArrayOf(st.st_size, st.st_ino)
    } catch (e: Exception) {
        longArrayOf(-1, -1)
    }

    override fun read(path: String, offset: Long, maxLen: Int): ByteArray = try {
        RandomAccessFile(path, "r").use { f ->
            val available = f.length() - offset
            if (available <= 0) return ByteArray(0)
            val len = minOf(available, maxLen.toLong(), MAX_CHUNK.toLong()).toInt()
            val buf = ByteArray(len)
            f.seek(offset)
            f.readFully(buf)
            buf
        }
    } catch (e: Exception) {
        ByteArray(0)
    }

    private fun File.isSnoopLog() =
        isFile && name.startsWith("btsnoop_hci") && name.endsWith(".log")

    companion object {
        const val DEFAULT_PATH = "/data/misc/bluetooth/logs/btsnoop_hci.log"
        const val SEARCH_DIRS = "/data/misc /data/log /data/vendor /data/media/0"

        // Keep well under the 1 MB binder transaction limit.
        const val MAX_CHUNK = 256 * 1024
    }
}
