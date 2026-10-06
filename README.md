# HciScope

**A live Bluetooth HCI packet viewer for rooted Android phones.**

HciScope switches on Android's Bluetooth HCI snoop log, reads it as it grows, and decodes every packet on the phone itself: HCI commands and events, advertising reports, L2CAP, ATT and SMP. You don't need a PC, a bug report, or a trip through Wireshark just to see what your BLE device is doing. When you do want Wireshark, one tap exports the capture as a `.btsnoop` file.

<p align="center"><img src="docs/icon.png" width="96" alt="HciScope icon"></p>

## Features

- **Live capture:** packets show up a moment after they cross the HCI.
- **On-device decoding:**
  - HCI commands, with names for common Link Control, Controller and LE commands
  - HCI events: Command Complete/Status, connection, disconnection and encryption events, and LE Meta subevents
  - LE advertising reports (legacy and extended), with address, RSSI, device name, service UUIDs and manufacturer data
  - L2CAP signaling, including connection parameter updates and credit-based channels
  - ATT: MTU exchange, discovery, reads, writes, notifications, indications and error responses, with readable error names
  - SMP: pairing request/response (IO capabilities, auth flags, key size) and pairing failure reasons
- **Filters:** by packet type (CMD / EVT / ACL), direction, "upper layers only" (ATT/SMP/L2CAP) and hiding advertising reports. You can also search the summary text or a connection handle such as `0x0040`.
- **Packet details:** the full decode plus a hex dump. The text is selectable, so you can copy it.
- **Export:** saves a standard btsnoop file (H4, datalink 1002) that opens directly in Wireshark.
- **Vendor quirks:** finds snoop logs that OEM ROMs rename (e.g. Xiaomi's `btsnoop_hci_<timestamp>.log`), follows them across Bluetooth restarts, and labels Qualcomm controller debug traffic (ACL handle `0x0EDC`) so it doesn't look like a real connection.

## Requirements

- An Android 8.0+ (API 26) phone with **root** (Magisk, KernelSU, APatch, …)
- A [Shizuku](https://shizuku.rikka.app/) API provider running **as root**. That's either the Shizuku app started with "Start (root)", or a root manager with a built-in Shizuku-compatible service such as KernelSU. Shizuku in ADB/wireless mode is **not** enough, because the shell user can't read `/data/misc/bluetooth`.

## Usage

1. Install the APK from [Releases](../../releases) and open **HciScope**.
2. Tap **Grant access** to give the app Shizuku permission.
3. Tap **Start capture**. This sets `persist.bluetooth.btsnooplogmode=full` and restarts Bluetooth. Active Bluetooth connections drop briefly.
4. Use your Bluetooth device as normal; packets stream into the list.
5. Tap a packet for details. Tap the share icon to export a `.btsnoop` file.
6. Tap **Stop** when you're done to turn the snoop log off again.

The snoop log is written by the system, not by the app, so capture continues even when HciScope isn't open. When you come back, the app reads the current log file from the beginning.

## How it works

```
┌──────────── HciScope app (untrusted_app) ────────────┐
│ Compose UI ◄── ViewModel ◄── SnoopTailer ◄── parser  │
└───────────────────────────────▲──────────────────────┘
                                │ binder (AIDL): exec / stat / read
┌───────────────────────────────┴──────────────────────┐
│ PrivilegedService: Shizuku UserService, runs as root │
│   reads /data/misc/bluetooth/logs/btsnoop_hci*.log   │
└──────────────────────────────────────────────────────┘
```

- **`PrivilegedService`** runs inside Shizuku's root process. It sets the snoop properties, restarts Bluetooth, finds the newest log file, and returns its bytes in chunks of up to 256 KB. It returns bytes rather than a file descriptor because SELinux won't let an app read a `bluetooth_data_file` fd, even one that root opened.
- **`SnoopTailer`** polls the file like `tail -f`. It starts over when the file is replaced (new inode, smaller size, or a newer timestamped file).
- **`BtSnoopParser`** is an incremental btsnoop parser that copes with records split across reads.
- **`HciDecoder`** is a best-effort single-packet decoder that turns each packet into a summary line and detail fields.
- **`BtSnoopWriter`** writes captured packets back out as a btsnoop file for Wireshark.

## Building

You need JDK 17 or 21 and the Android SDK (platform 35).

```sh
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest    # parser and decoder unit tests
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Or open the project in Android Studio. Local release builds are unsigned; signed APKs come from CI.

## Releases

Signed APKs are published on the [Releases](../../releases) page. CI runs `.github/workflows/build.yml` on every push and pull request, then uploads the APK as a workflow artifact. When you push a `v*` tag, it also creates a GitHub Release with the signed APK attached:

```sh
git tag v1.0.0 && git push origin v1.0.0
```

Signing uses the repository secrets `SIGNING_KEYSTORE_BASE64`, `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS` and `SIGNING_KEY_PASSWORD`.

## Limitations

- **Delay:** the Bluetooth stack writes the log in batches, so packets can appear a moment late.
- **Fragments:** ACL fragments aren't reassembled. An L2CAP PDU split across several ACL packets is only decoded from its first fragment, and Wireshark handles these fully.
- **Mode:** to see ATT payloads you need "full" mode, which **Start capture** sets for you. In "filtered" mode the stack trims ACL payloads.
- **Restarts:** turning capture on or off restarts Bluetooth.
- **ROM support:** it has been tested on a Xiaomi phone running Android 16 with KernelSU. Other OEMs may store the log somewhere else. If the app can't find it, the status card shows what it searched.

## Tech

Kotlin · Jetpack Compose (Material 3) · Coroutines/Flow · AIDL · [Shizuku API](https://github.com/RikkaApps/Shizuku-API)

## License

HciScope is free software: you can redistribute it and/or modify it under the terms of the [GNU General Public License](LICENSE) as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
