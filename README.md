# 📦 ShellBox

A standalone terminal emulator for Android with real PTY, rootless Linux containers, and Shizuku elevation. Three shells in one app — no root required.

## Shells

| Shell | UID | Description |
|-------|-----|-------------|
| **Android** | App | `/system/bin/sh` — always available |
| **Ubuntu** | root (proot) | Full Ubuntu 24.04 via PRoot — apt, python, git, node, everything |
| **Shizuku** | 2000 (shell) | ADB-level access — pm, am, settings, dumpsys, logcat |

## Features

- **Real PTY** — full terminal emulator with escape sequences, colors, cursor, resize (Termux terminal library)
- **Tabs** — multiple concurrent sessions, long-press to close
- **Sticky modifier keys** — CTRL/ALT with tri-state: tap once = single-shot (green), tap again = locked (red), tap again = off
- **Extra keys** — ESC, TAB, CTRL, ALT, arrows, HOME, END, DEL
- **Pinch to zoom** — smooth density-aware text scaling
- **Ubuntu bootstrap** — downloads Box rootfs on first run (~17MB), patches for Android compatibility
- **Shizuku integration** — auto-detects Shizuku, copies rish binary, elevated shell via `app_process`
- **Background service** — foreground service with wakelock keeps sessions alive

## How It Works

### Ubuntu (PRoot)

ShellBox uses [Box](https://github.com/xnet-admin-1/box) native binaries to run a rootless Ubuntu 24.04 container via PRoot. No root, no kernel modules — PRoot uses `ptrace` to translate filesystem paths and fake root permissions.

```
TerminalSession (PTY) → libproot-xed.so → Ubuntu 24.04 rootfs
                         ├── ptrace-based chroot
                         ├── --link2symlink (Android hardlink workaround)
                         ├── --sysvipc (System V IPC emulation)
                         └── Mirrored networking (host stack shared)
```

On first launch, ShellBox:
1. Downloads Ubuntu 24.04 rootfs from Box GitHub releases
2. Extracts with `libbsdtar.so` (handles Android hardlink limitations)
3. Patches DNS, locale, permissions, dpkg stubs, Android GIDs
4. Runs `dpkg --configure -a` for clean package state

### Shizuku (ADB Shell)

Uses the `rish` binary from Shizuku to spawn an interactive shell as UID 2000 (adb/shell user). This gives access to:
- Package management (`pm install`, `pm uninstall`)
- Activity management (`am start`, `am force-stop`)
- System settings (`settings put`)
- Debug tools (`dumpsys`, `logcat`, `cmd`)

ShellBox auto-copies `rish` + `rish_shizuku.dex` from Shizuku's storage directory and handles the Android 14+ `chmod 400` dex requirement.

## Architecture

```
com.shellbox/
├── MainActivity.kt              # Terminal UI, tabs, sticky keys, shell picker
├── ShellBoxApp.kt               # Application + Shizuku binder listener
├── service/
│   └── ShellBoxService.kt       # Foreground service + wakelock
├── shell/
│   ├── ProotBootstrap.kt        # Rootfs download, extraction, patching
│   ├── ProotExecutor.kt         # One-shot proot command execution
│   ├── RishExecutor.kt          # Shizuku API shell execution
│   └── ShellExecutor.kt         # Android sh execution
└── terminal/
    ├── ShellDiscovery.kt         # Shell backend detection (sh/ubuntu/rish)
    ├── RishTerminalSession.kt    # Shizuku PTY session (UID 2000)
    ├── backend/                  # Terminal emulator (NeoTerm/Termux fork)
    │   ├── TerminalSession.java  # PTY session management (non-final for subclassing)
    │   ├── TerminalEmulator.java # VT100/xterm state machine
    │   └── JNI.java              # Native bridge to libtermux.so (forkpty)
    └── view/
        ├── TerminalView.java     # Terminal rendering widget
        └── TerminalViewClient.java
```

## Native Binaries

Bundled in `jniLibs/arm64-v8a/`, cross-compiled from [Box](https://github.com/xnet-admin-1/box):

| Binary | Source | Purpose |
|--------|--------|---------|
| `libproot-xed.so` | Termux PRoot | The proot binary |
| `libproot.so` | PRoot loader | W^X bypass (64-bit) |
| `libproot32.so` | PRoot loader | W^X bypass (32-bit) |
| `libtalloc.so` | talloc 2.4.4 | PRoot dependency |
| `libbsdtar.so` | libarchive 3.8.6 | Rootfs extraction (with liblzma) |

## Build

```bash
# In xnet-dev container (or any env with Android SDK + JDK 17):
cd shellbox
./gradlew :app:assembleDebug

# APK at: app/build/outputs/apk/debug/app-debug.apk
```

### Requirements

- Android SDK 34, NDK not required (native binaries pre-built)
- JDK 17
- Gradle 8.4

## Install

```bash
adb install shellbox-debug.apk
```

For Shizuku shell: install [Shizuku](https://shizuku.rikka.app/) and start it via ADB or wireless debugging.

## Dependencies

| Library | Version | Purpose |
|---------|---------|---------|
| Termux terminal-emulator | v0.118.0 | Native PTY (libtermux.so) |
| Shizuku API | 13.1.5 | Elevated shell access |
| AndroidX AppCompat | 1.6.1 | UI compatibility |
| Material Components | 1.11.0 | Theme |

Terminal backend is a local fork of Termux/NeoTerm with `TerminalSession` made non-final to support `RishTerminalSession` subclassing.

## Related Projects

| Project | Description |
|---------|-------------|
| [Box](https://github.com/xnet-admin-1/box) | Rootless container runtime — provides native binaries and rootfs |
| [MCPShell](https://github.com/xnet-admin-1/mcpshell) | MCP server for Android — shares ProotBootstrap code |

## License

GPL-2.0 (PRoot), various for dependencies.
