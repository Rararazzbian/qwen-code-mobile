# Standalone Android Runtime (Phase 1 Proof of Concept)

[English](mobile-standalone-runtime.md) | [简体中文](mobile-standalone-runtime.zh-CN.md)

Status: proof of concept, implemented in `packages/mobile-app`. Not validated on a physical device yet; see [Validation Evidence](#validation-evidence).

## Problem and Goals

The [Android mobile shell](mobile-android-shell.md) is a client for a daemon running elsewhere. The goal of `packages/mobile-app` is a fully standalone Qwen Code on Android: the agent, its tools and the user's projects live on the phone, and only model API calls leave the device. The end state is a native mobile UI with a list of projects, multiple chats per project, and agent work that continues while the screen is off.

Phase 1 proves the hardest part, the on-device runtime:

1. Run Node.js 22+, a POSIX shell, git and ripgrep inside the app without root.
2. Run this repository's `qwen serve` daemon on loopback with bearer authentication.
3. Keep the daemon and agent sessions running with the screen off.
4. Reach the daemon from the app; the daemon's Web Shell serves as the interim chat UI.

## Non-goals for Phase 1

- The native projects and chats UI (phase 2).
- iOS. iOS does not allow apps to start arbitrary subprocesses or run downloaded executables, which an agent with shell tools needs.
- On-device models. Model inference stays with a remote OpenAI-compatible API.
- Play Store distribution, release signing and Keystore-backed secret storage.

## Architecture

```
Android app (com.qwen.mobile)
  MainActivity / WebShellActivity      UI; WebView to http://127.0.0.1:4170/#token=…
  AgentService (foreground, wake lock)
    libproot.so (Termux proot)  →  Alpine Linux aarch64 root filesystem
      /usr/bin/node /opt/qwen-code/cli.js serve --hostname 127.0.0.1 --port 4170 --require-auth
      /root/projects/                  user projects
```

### Runtime

- **proot** from Termux's Android build provides a user-space chroot through `ptrace`, so a standard Linux distribution runs without root.
- **Alpine Linux 3.24 aarch64** minirootfs (≈4 MB) is the guest. Its `nodejs` package is Node 24, which satisfies the repository's `>=22` requirement. The first launch runs `apk add nodejs bash git ripgrep ca-certificates` (≈60 MB download). Later, the agent can install more tools itself with `apk add`.
- **Qwen Code** is this repository's `npm run bundle` output, shipped in the APK and unpacked to `/opt/qwen-code`. It is replaced only when the bundled version hash changes.

### Executing binaries on Android 10+

Apps targeting API 29+ cannot execute files written to their data directory (W^X). The only app-owned executable location is `nativeLibraryDir`, which Android populates from the APK's `lib*.so` entries when native libraries are extracted. Therefore:

- `proot`, its `loader`, `libtalloc` and `libandroid-shmem` are packaged as `libproot.so`, `libproot-loader.so`, `libtalloc.so` and `libandroid-shmem.so`. `patchelf` rewrites their `DT_NEEDED` and `SONAME` entries to match and removes Termux's hard-coded `RUNPATH`. `LD_LIBRARY_PATH` points at `nativeLibraryDir`.
- `jniLibs.useLegacyPackaging = true` and `android:extractNativeLibs="true"` force extraction.
- Guest executables are never executed by the Android kernel directly: proot executes `PROOT_LOADER`, which maps the guest ELF. This keeps `targetSdk = 34` instead of Termux's `targetSdk = 28`.

`scripts/prepare-runtime.sh` downloads the pinned Termux packages and Alpine release, verifies their SHA-256 hashes, patches the binaries and stages them with the Qwen Code bundle. Generated files are git-ignored.

### Running with the screen off

- `AgentService` is a foreground service of type `specialUse` with an ongoing notification that has a Stop action. Android does not kill foreground services the way it kills background apps.
- It holds a `PARTIAL_WAKE_LOCK` while the runtime is running, so the CPU keeps running with the screen off.
- The user can grant an exemption from battery optimization (Doze), so network access to the model API is not deferred.
- The daemon process is a child of the service. The app's activities can be closed without affecting it.

### Security

- The daemon binds `127.0.0.1` with `--require-auth`. A random 256-bit token is generated on first use. Other apps on the device can reach the port but not authenticate.
- The token and model API key reach the guest through the process environment, never through argv. `env -u` removes the host-only proot variables before the guest command starts.
- The token reaches the Web Shell through the `#token=` fragment, which the WebView does not send in the request.
- Cleartext is allowed only to `127.0.0.1`. Backups are disabled.
- Archive extraction rejects absolute and `..` paths. Recursive deletion never follows symbolic links.

### Android `/proc` restrictions

Android's SELinux policy denies apps access to some `/proc` files, such as `/proc/stat` and `/proc/loadavg`. Node's `os.cpus()` and `os.loadavg()` read them. If a file is unreadable at startup, the service binds a generated replacement over it. The daemon runs with `--no-load-admission`, because load-based admission depends on host load figures that the app cannot observe reliably.

## Design Decisions

- **proot with Alpine instead of Termux packages rebuilt for our package name.** Termux binaries hard-code `/data/data/com.termux` and target API 28. Rebuilding them is a large toolchain effort. proot with an unmodified distribution gives the agent a complete package manager immediately. The cost is `ptrace` overhead on system calls, which is acceptable for an agent whose latency is dominated by the model.
- **Alpine instead of Debian.** Alpine's stable release ships Node 24 directly, and its rootfs is about 4 MB. Debian's packaged Node is older than 22. The risk is musl compatibility for native Node modules, to be tracked in validation.
- **Download packages on first run instead of bundling them.** This keeps the APK small (bundle plus ≈4 MB) and lets Alpine security updates apply. Offline first-run is a follow-up.
- **Web Shell as the interim UI.** It exercises the full daemon and session API end to end before the native UI is designed.

## Risks

- **Android 12+ phantom process killer.** Android limits processes started by apps (32 across all apps) and can kill CPU-heavy child processes. A foreground service reduces the risk. Android 14+ offers "Disable child process restrictions" in developer options. Android 12 and 13 need `adb shell device_config put activity_manager max_phantom_processes 2147483647`.
- **Vendor battery managers** on some manufacturers can still stop the service. Users may need to allow the app to run unrestricted in the vendor settings.
- **proot compatibility** on specific kernels and seccomp policies. Termux's proot is widely used, which lowers this risk.
- **Battery use.** The wake lock is held for as long as the runtime runs, not just while a prompt is active. Idle-aware locking is a follow-up.

## Phase 2 Outline

A native Jetpack Compose UI over the daemon API:

- **Projects:** each project is a directory under `/root/projects` registered as a daemon workspace. Create (empty or `git clone`), rename and delete.
- **Chats:** daemon sessions in that workspace. Create, resume, rename and delete. Show streaming responses, tool calls and approval prompts.
- **Settings:** model providers and Keystore-encrypted API keys.

## Reviewer Test Plan

1. Build with `scripts/prepare-runtime.sh` and `./gradlew :app:assembleDebug`, then install on an arm64 Android 8+ device.
2. Enter an OpenAI-compatible base URL, API key and model, then tap **Start**. Expect `installing`, Alpine unpacking, apk output, then `running`.
3. Tap **Open Web Shell**. Expect the Web Shell to load without asking for a token. Ask the agent to create a file and run `node --version` and `git init`.
4. Tap **Allow running in background** and accept. Start a long task, turn the screen off for several minutes, then turn it on. Expect the task to have progressed and the notification still present.
5. Tap **Stop** in the notification. Expect the status `stopped` and the notification removed.
6. Restart the app and tap **Start**. Expect a start without reinstalling packages.

## Validation Evidence

- Unit tests (`./gradlew :app:testDebugUnitTest`) cover the proot invocation and archive extraction.
- The debug APK builds in CI-like Linux environments.
- On-device behavior in the test plan above is not yet verified.

## Follow-ups

- Native projects and chats UI (phase 2).
- Keystore-encrypted API key storage.
- Hold the wake lock only while a session is active.
- Offline first run, with apk packages bundled or cached.
- A CI workflow for `packages/mobile-app`.
