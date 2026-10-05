package com.custodysim.app.ui.library

import java.nio.file.Files
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class ReaderDiskCacheTest {
    @Test fun survivesRepositoryReplacementAndSeparatesAccountsAndRevisions() {
        val directory = Files.createTempDirectory("reader-cache-test").toFile()
        try {
            ReaderDiskCache(directory).write("server|account-a|revision-1|cover", byteArrayOf(1, 2, 3))
            val reopened = ReaderDiskCache(directory)
            assertArrayEquals(byteArrayOf(1, 2, 3), reopened.read("server|account-a|revision-1|cover"))
            assertNull(reopened.read("server|account-b|revision-1|cover"))
            assertNull(reopened.read("server|account-a|revision-2|cover"))
        } finally { directory.deleteRecursively() }
    }
    @Test fun rejectsCorruptedEntriesAndKeepsDiskUseBounded() {
        val directory = Files.createTempDirectory("reader-cache-test").toFile()
        try {
            val cache = ReaderDiskCache(directory, 100)
            cache.write("first", ByteArray(40) { 1 })
            directory.listFiles()!!.single().writeBytes(ByteArray(72))
            assertNull(cache.read("first"))
            cache.write("first", ByteArray(40) { 1 })
            cache.write("second", ByteArray(40) { 2 })
            assertTrue(directory.listFiles()!!.sumOf { it.length() } <= 100)
            assertArrayEquals(ByteArray(40) { 2 }, cache.read("second"))
        } finally { directory.deleteRecursively() }
    }

    @Test fun discardsTruncatedAndOversizedFilesBeforeReplacingThem() {
        val directory = Files.createTempDirectory("reader-cache-test").toFile()
        try {
            val cache = ReaderDiskCache(directory, 100)
            for (size in listOf(8, 120)) {
                cache.write("cover", byteArrayOf(1, 2, 3))
                directory.listFiles()!!.single().writeBytes(ByteArray(size))
                assertNull(cache.read("cover"))
                assertTrue(directory.listFiles()!!.isEmpty())
            }
            cache.write("cover", byteArrayOf(4, 5, 6))
            assertArrayEquals(byteArrayOf(4, 5, 6), cache.read("cover"))
        } finally { directory.deleteRecursively() }
    }

    @Test fun removesInterruptedWritesAndDoesNotFailWhenTheDirectoryIsUnavailable() {
        val directory = Files.createTempDirectory("reader-cache-test").toFile()
        try {
            File(directory, "pending-old.tmp").writeBytes(ByteArray(120))
            val cache = ReaderDiskCache(directory, 100)
            cache.write("cover", byteArrayOf(1, 2, 3))
            assertEquals(listOf("cache"), directory.listFiles()!!.map { it.extension })
            val blockedDirectory = File(directory, "blocked").apply { writeText("not a directory") }
            ReaderDiskCache(blockedDirectory).write("cover", byteArrayOf(1))
            assertNull(ReaderDiskCache(blockedDirectory).read("cover"))
        } finally { directory.deleteRecursively() }
    }

    @Test fun overlappingRepositoriesCannotExposePartialFilesOrExceedTheLimit() {
        val directory = Files.createTempDirectory("reader-cache-test").toFile()
        val executor = Executors.newFixedThreadPool(2)
        try {
            val caches = List(2) { ReaderDiskCache(directory, 512) }
            val start = CountDownLatch(1)
            val futures = caches.mapIndexed { writer, cache ->
                executor.submit {
                    check(start.await(5, TimeUnit.SECONDS))
                    repeat(40) { sequence ->
                        val bytes = ByteArray(64) { writer.toByte() }
                        val key = "$writer/$sequence"
                        cache.write(key, bytes)
                        cache.read(key)?.let { assertArrayEquals(bytes, it) }
                    }
                }
            }
            start.countDown()
            futures.forEach { it.get(10, TimeUnit.SECONDS) }
            assertTrue(directory.listFiles()!!.sumOf { it.length() } <= 512)
            assertTrue(directory.listFiles()!!.all { it.extension == "cache" })
        } finally { executor.shutdownNow(); directory.deleteRecursively() }
    }
}
