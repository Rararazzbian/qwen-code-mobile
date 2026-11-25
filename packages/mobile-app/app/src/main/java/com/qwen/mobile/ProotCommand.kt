package com.qwen.mobile

import java.io.File

class Invocation(val argv: List<String>, val env: Map<String, String>)

/** Builds the proot invocation that runs a command inside the Alpine root filesystem. */
class ProotCommand(
    private val nativeLibDir: File,
    private val rootfs: File,
    private val tmpDir: File,
) {
    /**
     * Guest variables travel in the process environment, not argv, so secrets never
     * appear in a command line. `env -u` drops the host-only proot variables.
     */
    fun build(
        command: List<String>,
        guestEnv: Map<String, String> = emptyMap(),
        workDir: String = "/root",
        procOverrides: Map<String, File> = emptyMap(),
    ): Invocation {
        val argv = mutableListOf(
            File(nativeLibDir, "libproot.so").path,
            "--kill-on-exit",
            "--link2symlink",
            "-0",
            "-r", rootfs.path,
            "-b", "/dev",
            "-b", "/proc",
            "-b", "/sys",
            "-b", "${File(rootfs, "tmp").path}:/dev/shm",
        )
        procOverrides.forEach { (guestPath, file) -> argv += listOf("-b", "${file.path}:$guestPath") }
        argv += listOf("-w", workDir, "/usr/bin/env")
        HOST_ONLY_VARS.forEach { argv += listOf("-u", it) }
        argv += command

        val env = linkedMapOf(
            "HOME" to "/root",
            "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "LANG" to "C.UTF-8",
            "TERM" to "xterm-256color",
            "TMPDIR" to "/tmp",
        )
        env += guestEnv
        env["LD_LIBRARY_PATH"] = nativeLibDir.path
        env["PROOT_LOADER"] = File(nativeLibDir, "libproot-loader.so").path
        env["PROOT_TMP_DIR"] = tmpDir.path
        return Invocation(argv, env)
    }

    private companion object {
        val HOST_ONLY_VARS = listOf("LD_LIBRARY_PATH", "PROOT_LOADER", "PROOT_TMP_DIR")
    }
}
