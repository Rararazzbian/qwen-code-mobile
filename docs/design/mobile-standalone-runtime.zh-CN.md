# 独立 Android 运行时（第一阶段概念验证）

[English](mobile-standalone-runtime.md) | [简体中文](mobile-standalone-runtime.zh-CN.md)

状态：概念验证，已在 `packages/mobile-app` 中实现。尚未在实体设备上验证；参见[验证证据](#验证证据)。

## 问题与目标

[Android 移动外壳](mobile-android-shell.zh-CN.md)是连接到其他地方运行的守护进程的客户端。`packages/mobile-app` 的目标是在 Android 上完全独立运行 Qwen Code：智能体、其工具以及用户的项目都位于手机上，只有模型 API 调用会离开设备。最终形态是一个原生移动 UI，包含项目列表、每个项目下的多个对话，并且在屏幕关闭时智能体仍能继续工作。

第一阶段验证最困难的部分，即设备上的运行时：

1. 无需 root，在应用内运行 Node.js 22+、POSIX shell、git 和 ripgrep。
2. 在回环地址上以 bearer 认证运行本仓库的 `qwen serve` 守护进程。
3. 在屏幕关闭时保持守护进程和智能体会话继续运行。
4. 从应用访问守护进程；守护进程自带的 Web Shell 作为临时对话 UI。

## 第一阶段非目标

- 原生项目与对话 UI（第二阶段）。
- iOS。iOS 不允许应用启动任意子进程或运行下载的可执行文件，而带 shell 工具的智能体需要这些能力。
- 设备端模型。模型推理仍使用远程 OpenAI 兼容 API。
- Play 商店分发、发布签名以及基于 Keystore 的密钥存储。

## 架构

```
Android app (com.qwen.mobile)
  MainActivity / WebShellActivity      UI; WebView to http://127.0.0.1:4170/#token=…
  AgentService (foreground, wake lock)
    libproot.so (Termux proot)  →  Alpine Linux aarch64 root filesystem
      /usr/bin/node /opt/qwen-code/cli.js serve --hostname 127.0.0.1 --port 4170 --require-auth
      /root/projects/                  user projects
```

### 运行时

- 来自 Termux Android 构建的 **proot** 通过 `ptrace` 提供用户态 chroot，因此无需 root 即可运行标准 Linux 发行版。
- 客户系统为 **Alpine Linux 3.24 aarch64** minirootfs（约 4 MB）。其 `nodejs` 软件包为 Node 24，满足仓库 `>=22` 的要求。首次启动运行 `apk add nodejs bash git ripgrep ca-certificates`（约 60 MB 下载）。之后智能体可以自行用 `apk add` 安装更多工具。
- **Qwen Code** 使用本仓库 `npm run bundle` 的输出，随 APK 分发并解压到 `/opt/qwen-code`。仅当打包版本哈希变化时才替换。

### 在 Android 10+ 上执行二进制文件

目标 API 29+ 的应用不能执行写入其数据目录的文件（W^X）。应用唯一可执行的自有位置是 `nativeLibraryDir`，在提取原生库时由 Android 从 APK 的 `lib*.so` 条目填充。因此：

- `proot`、其 `loader`、`libtalloc` 和 `libandroid-shmem` 分别打包为 `libproot.so`、`libproot-loader.so`、`libtalloc.so` 和 `libandroid-shmem.so`。`patchelf` 改写它们的 `DT_NEEDED` 和 `SONAME` 条目以保持一致，并删除 Termux 硬编码的 `RUNPATH`。`LD_LIBRARY_PATH` 指向 `nativeLibraryDir`。
- `jniLibs.useLegacyPackaging = true` 与 `android:extractNativeLibs="true"` 强制提取原生库。
- Android 内核从不直接执行客户系统中的可执行文件：proot 执行 `PROOT_LOADER`，由它映射客户 ELF。因此可以保持 `targetSdk = 34`，而不是 Termux 的 `targetSdk = 28`。

`scripts/prepare-runtime.sh` 下载固定版本的 Termux 软件包和 Alpine 发行版，校验其 SHA-256 哈希，修补二进制文件，并与 Qwen Code bundle 一起放置到位。生成的文件已被 git 忽略。

### 屏幕关闭时运行

- `AgentService` 是类型为 `specialUse` 的前台服务，带有包含“停止”操作的常驻通知。Android 不会像终止后台应用那样终止前台服务。
- 运行时运行期间持有 `PARTIAL_WAKE_LOCK`，使 CPU 在屏幕关闭时继续运行。
- 用户可以授予电池优化（Doze）豁免，使对模型 API 的网络访问不会被推迟。
- 守护进程是该服务的子进程。关闭应用的 Activity 不会影响它。

### 安全

- 守护进程以 `--require-auth` 绑定 `127.0.0.1`。首次使用时生成随机 256 位令牌。设备上的其他应用可以访问该端口，但无法通过认证。
- 令牌和模型 API 密钥通过进程环境变量传入客户系统，绝不通过 argv。`env -u` 在客户命令启动前移除仅供宿主使用的 proot 变量。
- 令牌通过 `#token=` 片段传给 Web Shell，WebView 不会在请求中发送该片段。
- 仅允许到 `127.0.0.1` 的明文流量。备份已禁用。
- 归档解压拒绝绝对路径和 `..` 路径。递归删除从不跟随符号链接。

### Android `/proc` 限制

Android 的 SELinux 策略禁止应用访问部分 `/proc` 文件，例如 `/proc/stat` 和 `/proc/loadavg`。Node 的 `os.cpus()` 和 `os.loadavg()` 会读取它们。如果启动时某个文件不可读，服务会在其上绑定一个生成的替代文件。守护进程使用 `--no-load-admission` 运行，因为基于负载的准入依赖应用无法可靠观察到的宿主负载数据。

## 设计决策

- **使用 proot 与 Alpine，而不是为本应用包名重新构建 Termux 软件包。** Termux 二进制文件硬编码 `/data/data/com.termux` 且目标 API 为 28。重新构建它们需要大量工具链工作。proot 搭配未修改的发行版可立即为智能体提供完整的包管理器。代价是系统调用上的 `ptrace` 开销，对于延迟主要由模型决定的智能体来说可以接受。
- **使用 Alpine 而不是 Debian。** Alpine 稳定版直接提供 Node 24，其 rootfs 约 4 MB。Debian 打包的 Node 版本低于 22。风险在于原生 Node 模块的 musl 兼容性，将在验证中跟踪。
- **首次运行时下载软件包，而不是打包进 APK。** 这使 APK 保持较小（bundle 加约 4 MB），并可应用 Alpine 安全更新。离线首次运行列为后续工作。
- **以 Web Shell 作为临时 UI。** 在设计原生 UI 之前，它可以端到端地覆盖完整的守护进程与会话 API。

## 风险

- **Android 12+ 幽灵进程终止机制。** Android 限制应用启动的进程（所有应用合计 32 个），并可能终止 CPU 占用高的子进程。前台服务可降低该风险。Android 14+ 在开发者选项中提供“停用子进程限制”。Android 12 和 13 需要执行 `adb shell device_config put activity_manager max_phantom_processes 2147483647`。
- 部分厂商的**电池管理器**仍可能停止该服务。用户可能需要在厂商设置中允许应用不受限制地运行。
- 特定内核和 seccomp 策略下的 **proot 兼容性**。Termux 的 proot 被广泛使用，降低了该风险。
- **电量消耗。** 只要运行时在运行就持有唤醒锁，而不仅在处理提示时持有。按空闲状态管理唤醒锁列为后续工作。

## 第二阶段概要

基于守护进程 API 的原生 Jetpack Compose UI：

- **项目：** 每个项目是 `/root/projects` 下注册为守护进程工作区的目录。支持创建（空目录或 `git clone`）、重命名和删除。
- **对话：** 该工作区中的守护进程会话。支持创建、恢复、重命名和删除。显示流式响应、工具调用和审批提示。
- **设置：** 模型提供方以及经 Keystore 加密的 API 密钥。

## 评审测试计划

1. 使用 `scripts/prepare-runtime.sh` 和 `./gradlew :app:assembleDebug` 构建，然后安装到 arm64 的 Android 8+ 设备上。
2. 输入 OpenAI 兼容的 base URL、API 密钥和模型，然后点击 **Start**。预期依次看到 `installing`、Alpine 解压、apk 输出，最后为 `running`。
3. 点击 **Open Web Shell**。预期 Web Shell 加载且不要求输入令牌。让智能体创建一个文件并运行 `node --version` 和 `git init`。
4. 点击 **Allow running in background** 并同意。启动一个长任务，关闭屏幕几分钟后再打开。预期任务已有进展且通知仍在。
5. 在通知中点击 **Stop**。预期状态为 `stopped` 且通知被移除。
6. 重启应用并点击 **Start**。预期启动时不会重新安装软件包。

## 验证证据

- 单元测试（`./gradlew :app:testDebugUnitTest`）覆盖 proot 调用和归档解压。
- 调试版 APK 可在类 CI 的 Linux 环境中构建。
- 上述测试计划中的设备端行为尚未验证。

## 后续工作

- 原生项目与对话 UI（第二阶段）。
- 经 Keystore 加密的 API 密钥存储。
- 仅在会话活跃时持有唤醒锁。
- 离线首次运行，打包或缓存 apk 软件包。
- 为 `packages/mobile-app` 添加 CI 工作流。
