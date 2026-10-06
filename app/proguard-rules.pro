# The Shizuku user service is instantiated by class name inside Shizuku's process.
-keep class com.kelly.bledebugger.privileged.PrivilegedService { *; }
-keep class com.kelly.bledebugger.IPrivilegedService* { *; }
