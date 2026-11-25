package com.qwen.mobile

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files

class TarExtractorTest {
    private fun archive(build: (TarArchiveOutputStream) -> Unit): ByteArrayInputStream {
        val bytes = ByteArrayOutputStream()
        TarArchiveOutputStream(GzipCompressorOutputStream(bytes)).use(build)
        return ByteArrayInputStream(bytes.toByteArray())
    }

    private fun TarArchiveOutputStream.file(name: String, content: String, mode: Int) {
        val data = content.toByteArray()
        putArchiveEntry(TarArchiveEntry(name).apply { size = data.size.toLong(); this.mode = mode })
        write(data)
        closeArchiveEntry()
    }

    @Test
    fun extractsFilesSymlinksHardLinksAndModes() {
        val dest = Files.createTempDirectory("tar").toFile()
        TarExtractor.extractTarGz(archive { tar ->
            tar.file("./bin/busybox", "binary", 0b111_101_101)
            tar.putArchiveEntry(TarArchiveEntry("bin/sh", TarArchiveEntry.LF_SYMLINK).apply { linkName = "busybox" })
            tar.closeArchiveEntry()
            tar.putArchiveEntry(TarArchiveEntry("bin/ash", TarArchiveEntry.LF_LINK).apply { linkName = "./bin/busybox" })
            tar.closeArchiveEntry()
        }, dest)

        val busybox = dest.resolve("bin/busybox")
        assertEquals("binary", busybox.readText())
        assertTrue(busybox.canExecute())
        assertEquals("busybox", Files.readSymbolicLink(dest.resolve("bin/sh").toPath()).toString())
        assertEquals("binary", dest.resolve("bin/ash").readText())
        TarExtractor.deleteTree(dest)
        assertFalse(dest.exists())
    }

    @Test
    fun rejectsPathsThatEscapeTheDestination() {
        val dest = Files.createTempDirectory("tar").toFile()
        assertThrows(IllegalArgumentException::class.java) {
            TarExtractor.extractTarGz(archive { it.file("../escape", "x", 0b110_100_100) }, dest)
        }
        assertFalse(dest.resolveSibling("escape").exists())
    }

    @Test
    fun deleteTreeDoesNotFollowSymlinks() {
        val outside = Files.createTempDirectory("outside").toFile().apply { resolve("keep").writeText("x") }
        val dest = Files.createTempDirectory("tar").toFile()
        Files.createSymbolicLink(dest.resolve("link").toPath(), outside.toPath())
        TarExtractor.deleteTree(dest)
        assertTrue(outside.resolve("keep").exists())
    }
}
