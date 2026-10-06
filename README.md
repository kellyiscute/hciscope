# BLE Debugger

Logs Bluetooth HCI packets live on a rooted Android phone, using Shizuku (in root mode) for privileged access.

## How it works
1. Turns on Android's HCI snoop log (`persist.bluetooth.btsnooplogmode=full`) and restarts Bluetooth.
2. A Shizuku UserService runs as root and reads `/data/misc/bluetooth/logs/btsnoop_hci.log` in chunks. It passes the bytes back over binder, because SELinux blocks the app from reading that file through a passed fd.
3. The app parses btsnoop records as they arrive and decodes HCI commands and events, LE advertising reports, L2CAP, ATT and SMP.
4. **Export** writes the captured packets to a `.btsnoop` file you can open in Wireshark.

## Usage
1. Install Shizuku and start it with **root**. ADB mode isn't enough to read the Bluetooth log.
2. Open BLE Debugger, tap **Grant access**, then **Start capture**. This restarts Bluetooth.
3. Tap a packet for its full decode and a hex dump. Use the chips and the search box to filter; the search matches summary text or a connection handle like `0x0040`.

## Build
Open the project in Android Studio, or run `./gradlew assembleDebug` with JDK 17/21 and an Android SDK (platform 35).
Run the unit tests with `./gradlew testDebugUnitTest`.

## Limitations
- Packets show up after a short delay because the stack buffers its writes to the log.
- The decoder doesn't reassemble ACL fragments, so a fragmented L2CAP PDU is only decoded from its first fragment.
- Some OEM ROMs move or filter the snoop log. The app looks at `persist.bluetooth.btsnooppath` and a few common paths.
