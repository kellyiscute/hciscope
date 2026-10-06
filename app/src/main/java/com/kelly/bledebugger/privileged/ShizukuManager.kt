package com.kelly.bledebugger.privileged

import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import com.kelly.bledebugger.BuildConfig
import com.kelly.bledebugger.IPrivilegedService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import rikka.shizuku.Shizuku

sealed interface ShizukuState {
    data object NotRunning : ShizukuState
    data object Unsupported : ShizukuState
    data object NoPermission : ShizukuState
    data class NotRoot(val uid: Int) : ShizukuState
    data object Binding : ShizukuState
    data class Ready(val service: IPrivilegedService) : ShizukuState
}

/** Tracks the Shizuku binder, our permission, and the root UserService connection. */
class ShizukuManager {

    private val _state = MutableStateFlow<ShizukuState>(ShizukuState.NotRunning)
    val state: StateFlow<ShizukuState> = _state.asStateFlow()

    private val serviceArgs = Shizuku.UserServiceArgs(
        ComponentName(BuildConfig.APPLICATION_ID, PrivilegedService::class.java.name)
    )
        .daemon(false)
        .processNameSuffix("privileged")
        .debuggable(BuildConfig.DEBUG)
        .version(BuildConfig.VERSION_CODE)

    private var bound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (binder != null && binder.pingBinder()) {
                _state.value = ShizukuState.Ready(IPrivilegedService.Stub.asInterface(binder))
            } else {
                bound = false
                refresh()
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            bound = false
            refresh()
        }
    }

    private val binderReceived = Shizuku.OnBinderReceivedListener { refresh() }
    private val binderDead = Shizuku.OnBinderDeadListener {
        bound = false
        _state.value = ShizukuState.NotRunning
    }
    private val permissionResult = Shizuku.OnRequestPermissionResultListener { _, _ -> refresh() }

    fun start() {
        Shizuku.addBinderReceivedListenerSticky(binderReceived)
        Shizuku.addBinderDeadListener(binderDead)
        Shizuku.addRequestPermissionResultListener(permissionResult)
    }

    fun stop() {
        Shizuku.removeBinderReceivedListener(binderReceived)
        Shizuku.removeBinderDeadListener(binderDead)
        Shizuku.removeRequestPermissionResultListener(permissionResult)
        if (bound) {
            runCatching { Shizuku.unbindUserService(serviceArgs, connection, true) }
            bound = false
        }
    }

    fun requestPermission() {
        if (Shizuku.pingBinder() && !Shizuku.shouldShowRequestPermissionRationale()) {
            Shizuku.requestPermission(PERMISSION_REQUEST_CODE)
        }
    }

    fun refresh() {
        _state.value = when {
            !Shizuku.pingBinder() -> ShizukuState.NotRunning
            Shizuku.isPreV11() -> ShizukuState.Unsupported
            Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED -> ShizukuState.NoPermission
            Shizuku.getUid() != 0 -> ShizukuState.NotRoot(Shizuku.getUid())
            else -> {
                val current = _state.value
                if (current is ShizukuState.Ready && current.service.asBinder().pingBinder()) return
                if (!bound) {
                    bound = true
                    Shizuku.bindUserService(serviceArgs, connection)
                }
                ShizukuState.Binding
            }
        }
    }

    private companion object {
        const val PERMISSION_REQUEST_CODE = 1001
    }
}
