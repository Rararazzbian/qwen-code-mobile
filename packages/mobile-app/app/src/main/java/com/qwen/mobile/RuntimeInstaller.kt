package com.qwen.mobile

import android.content.Context
import java.io.File

/**
 * Prepares the guest root filesystem: unpacks Alpine, installs Node and tools with
 * apk on first run, and replaces the Qwen Code bundle whenever the APK carries a new one.
 */
class RuntimeInstaller(
    private val context: Context,
    private val rootfs: File,
    private val runGuest: (List<String>) -> Int,
) {
    fun ensureInstalled() {
        if (!File(rootfs, "bin/busybox").exists()) {
            RuntimeState.log("Unpacking Alpine Linux…")
            extractAsset("runtime/rootfs.tgz", rootfs)
            File(rootfs, "etc/resolv.conf").writeText("nameserver 8.8.8.8\nnameserver 1.1.1.1\n")
            File(rootfs, "etc/hosts").writeText("127.0.0.1 localhost\n::1 localhost\n")
        }
        if (!File(rootfs, "usr/bin/node").exists()) {
            RuntimeState.log("Installing Node.js, git, bash and ripgrep (first run, needs network)…")
            val code = runGuest(listOf("/bin/sh", "-c", "apk update && apk add --no-progress $PACKAGES"))
            check(code == 0) { "Package installation failed with exit code $code" }
        }
        val version = context.assets.open("runtime/qwen-code.version").bufferedReader().use { it.readText().trim() }
        val qwenDir = File(rootfs, "opt/qwen-code")
        val marker = File(qwenDir, ".bundle-version")
        if (!marker.exists() || marker.readText() != version) {
            RuntimeState.log("Installing Qwen Code bundle $version…")
            extractAsset("runtime/qwen-code.tgz", qwenDir)
            marker.writeText(version)
        }
        File(rootfs, "root/projects").mkdirs()
    }

    /** Extracts beside the target and swaps it in, so an interrupted run never leaves a partial tree. */
    private fun extractAsset(asset: String, target: File) {
        val staging = File(target.parentFile, "${target.name}.partial")
        TarExtractor.deleteTree(staging)
        context.assets.open(asset).use { TarExtractor.extractTarGz(it, staging) }
        TarExtractor.deleteTree(target)
        check(staging.renameTo(target)) { "Could not move $staging into place" }
    }

    private companion object {
        const val PACKAGES = "nodejs bash git ripgrep ca-certificates"
    }
}
