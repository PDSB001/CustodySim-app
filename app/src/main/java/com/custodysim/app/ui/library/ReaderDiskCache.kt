package com.custodysim.app.ui.library

import java.io.File
import java.io.DataInputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Private, bounded, atomically written cache. A checksum rejects partial or corrupt entries. */
internal class ReaderDiskCache(private val directory: File, private val limit: Long = 128L * 1024 * 1024) {
    // A replacement repository can overlap an old WebView's final resource requests. Share
    // a bounded set of locks so both instances trim and replace the same directory safely.
    private val lock = directoryLocks[(directory.absoluteFile.normalize().path.hashCode() and Int.MAX_VALUE) % directoryLocks.size]

    fun read(key: String): ByteArray? = lock.withLock {
        try {
            val file = entry(key)
            if (!file.isFile) return null
            val length = file.length()
            if (length !in 33..limit || length - 32 > Int.MAX_VALUE) { file.delete(); return null }
            // Read the payload once: copying a large cached PDF out of a second full-size
            // array can exceed the app's heap even though the entry fits the disk budget.
            val checksum = ByteArray(32)
            val bytes = ByteArray((length - 32).toInt())
            val exactLength = DataInputStream(file.inputStream()).use { input ->
                input.readFully(checksum)
                input.readFully(bytes)
                input.read() == -1
            }
            if (!exactLength || !MessageDigest.isEqual(checksum, digest(bytes))) { file.delete(); return null }
            file.setLastModified(System.currentTimeMillis())
            bytes
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        }
    }

    fun remove(key: String) = lock.withLock {
        try { entry(key).delete() } catch (_: SecurityException) { false }
    }

    fun write(key: String, bytes: ByteArray) {
        if (bytes.isEmpty() || bytes.size + 32L > limit) return
        lock.withLock {
            try {
                if (!directory.isDirectory && !directory.mkdirs()) return
                // All writes in this process hold the directory lock. Leftovers therefore
                // belong to interrupted writes, rather than an active download.
                directory.listFiles()?.filter { it.name.startsWith("pending-") && it.extension == "tmp" }
                    ?.forEach { it.delete() }
                val temporary = File.createTempFile("pending-", ".tmp", directory)
                try {
                    temporary.outputStream().use { it.write(digest(bytes)); it.write(bytes); it.fd.sync() }
                    try {
                        Files.move(temporary.toPath(), entry(key).toPath(),
                            StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                    } catch (_: AtomicMoveNotSupportedException) {
                        Files.move(temporary.toPath(), entry(key).toPath(), StandardCopyOption.REPLACE_EXISTING)
                    }
                } finally { temporary.delete() }
                val entries = directory.listFiles()?.filter { it.extension == "cache" }.orEmpty().sortedBy { it.lastModified() }
                var total = entries.sumOf { it.length() }
                for (file in entries) {
                    if (total <= limit) break
                    val size = file.length()
                    if (file.delete()) total -= size
                }
            } catch (_: IOException) {
                // Caching is optional; a full disk must not turn a successful read into an error.
            } catch (_: SecurityException) {
                // The same applies when Android reclaims or makes the cache unavailable.
            }
        }
    }
    private fun entry(key: String) = File(directory, digest(key.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) } + ".cache")
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)

    private companion object {
        val directoryLocks = Array(16) { ReentrantLock() }
    }
}
