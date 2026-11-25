# Qwen Code Mobile (Android, standalone)

Runs Qwen Code entirely on an Android phone: an Alpine Linux environment inside the app (through proot, no root needed) hosts Node.js, git, ripgrep and the `qwen serve` daemon. Only model API calls leave the device. A foreground service and wake lock keep the agent working with the screen off.

This is a phase 1 proof of concept. The daemon's Web Shell is the interim chat UI; a native projects and chats UI is planned. See the [design](../../docs/design/mobile-standalone-runtime.md).

## Build

Requires JDK 17+, Android SDK platform and build tools 34 (`ANDROID_HOME` or `local.properties`), and `patchelf` (`pip install patchelf`).

```bash
# from the repository root
npm run build && npm run bundle
# from this directory
scripts/prepare-runtime.sh
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

The APK is `app/build/outputs/apk/debug/app-debug.apk`. It supports arm64 (`arm64-v8a`) devices running Android 8.0 or later.

## Use

1. Enter an OpenAI-compatible API base URL, API key and model (for example DashScope's `https://dashscope-intl.aliyuncs.com/compatible-mode/v1` with `qwen3-coder-plus`).
2. Tap **Start**. The first start unpacks Alpine and installs about 100 MB of packages, so it needs a network connection. With the unpacked Qwen Code bundle, expect about 300 MB of storage in total.
3. Tap **Allow running in background** once, so Android does not defer network access while the screen is off.
4. When the status is `running`, tap **Open Web Shell**. Projects live under `/root/projects` inside the guest.
5. Stop the runtime from the app or the notification. **Copy log** copies the runtime log for bug reports.

On Android 12 and 13, a process limit can kill agent subprocesses. If that happens, run `adb shell device_config put activity_manager max_phantom_processes 2147483647`. On Android 14 and later, enable **Disable child process restrictions** in developer options instead.
