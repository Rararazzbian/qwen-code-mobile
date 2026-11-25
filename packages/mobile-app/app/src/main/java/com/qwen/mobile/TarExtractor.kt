package com.qwen.mobile

import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermission

object TarExtractor {
    fun extractTarGz(input: InputStream, dest: File) {
        dest.mkdirs()
        TarArchiveInputStream(GzipCompressorInputStream(BufferedInputStream(input))).use { tar ->
            while (true) {
                val entry = tar.nextEntry ?: break
                val out = File(dest, safeName(entry.name))
                out.parentFile?.mkdirs()
                when {
                    entry.isDirectory -> out.mkdirs()
                    entry.isSymbolicLink -> {
                        deleteTree(out)
                        Files.createSymbolicLink(out.toPath(), Paths.get(entry.linkName))
                    }
                    // Android denies app hard links, so archive hard links become copies.
                    entry.isLink -> File(dest, safeName(entry.linkName)).copyTo(out, overwrite = true)
                    else -> out.outputStream().use { tar.copyTo(it) }
                }
                if (!entry.isSymbolicLink) {
                    // Keep owner write access so later entries can still be written.
                    val ownerBits = if (entry.isDirectory) 0b111_000_000 else 0b110_000_000
                    Files.setPosixFilePermissions(out.toPath(), permissions(entry.mode or ownerBits))
                }
            }
        }
    }

    fun deleteTree(file: File) {
        if (!Files.isSymbolicLink(file.toPath()) && file.isDirectory) {
            file.listFiles()?.forEach(::deleteTree)
        }
        file.delete()
    }

    internal fun safeName(name: String): String {
        val trimmed = name.removePrefix("./")
        require(!trimmed.startsWith("/") && trimmed.split('/').none { it == ".." }) {
            "Unsafe archive path: $name"
        }
        return trimmed
    }

    private fun permissions(mode: Int): Set<PosixFilePermission> {
        val order = listOf(
            PosixFilePermission.OTHERS_EXECUTE, PosixFilePermission.OTHERS_WRITE, PosixFilePermission.OTHERS_READ,
            PosixFilePermission.GROUP_EXECUTE, PosixFilePermission.GROUP_WRITE, PosixFilePermission.GROUP_READ,
            PosixFilePermission.OWNER_EXECUTE, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_READ,
        )
        return order.filterIndexed { bit, _ -> mode and (1 shl bit) != 0 }.toSet()
    }
}
