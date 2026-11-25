package com.qwen.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ProotCommandTest {
    private val command = ProotCommand(File("/lib"), File("/data/rootfs"), File("/data/tmp"))

    @Test
    fun runsCommandThroughEnvInsideRootfs() {
        val invocation = command.build(listOf("/usr/bin/node", "cli.js"), workDir = "/root/projects")
        val argv = invocation.argv
        assertEquals("/lib/libproot.so", argv.first())
        assertEquals("/data/rootfs", argv[argv.indexOf("-r") + 1])
        assertEquals("/root/projects", argv[argv.indexOf("-w") + 1])
        assertEquals(listOf("/usr/bin/node", "cli.js"), argv.takeLast(2))
        assertTrue(argv.containsAll(listOf("-u", "LD_LIBRARY_PATH", "PROOT_LOADER", "PROOT_TMP_DIR")))
    }

    @Test
    fun keepsSecretsOutOfArgvAndPointsProotAtItsLoader() {
        val invocation = command.build(listOf("true"), guestEnv = mapOf("OPENAI_API_KEY" to "sk-secret"))
        assertFalse(invocation.argv.any { it.contains("sk-secret") })
        assertEquals("sk-secret", invocation.env["OPENAI_API_KEY"])
        assertEquals("/lib/libproot-loader.so", invocation.env["PROOT_LOADER"])
        assertEquals("/lib", invocation.env["LD_LIBRARY_PATH"])
    }

    @Test
    fun bindsProcOverrides() {
        val invocation = command.build(listOf("true"), procOverrides = mapOf("/proc/stat" to File("/data/fake/stat")))
        val argv = invocation.argv
        assertTrue(argv.windowed(2).contains(listOf("-b", "/data/fake/stat:/proc/stat")))
    }
}
