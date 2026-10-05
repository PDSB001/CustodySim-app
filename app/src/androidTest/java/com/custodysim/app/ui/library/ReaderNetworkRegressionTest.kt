package com.custodysim.app.ui.library

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.custodysim.app.CustodySimApp
import com.custodysim.app.data.net.ApiResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Optional read-only checks against the account already signed in on the connected device. */
class ReaderNetworkRegressionTest {
    @Test fun actualServerDocumentAndItsCoverRemainUsableAfterReopeningTheDiskCache() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val container = (context.applicationContext as CustodySimApp).container
        assumeTrue("Requires an existing device login", container.authRepository.restoreSession() != null)
        val catalog = container.apiClient.get("/api/library")
        assertTrue("Device catalog must be available", catalog is ApiResult.Ok)
        val books = (catalog as ApiResult.Ok).data.getJSONArray("books")
        val book = (0 until books.length()).map { books.getJSONObject(it) }.firstOrNull { it.optString("format") == "EPUB" }
        assumeTrue("Requires an EPUB in the device catalog", book != null)
        val id = book!!.getString("id")
        val directory = File(context.cacheDir, "reader-network-regression")
        val repository = LibraryReaderRepository(container.apiClient, container.endpoint.baseUrl, directory)
        val response = repository.document(id)
        assertTrue("Device document endpoint must return structured content", response is ApiResult.Ok)
        val json = (response as ApiResult.Ok).data
        val document = ReadingDocument.from(json)
        assertTrue("No linear section can be an empty navigation shell",
            document.chapters.filter { it.linear }.all { it.text.isNotBlank() || it.html.contains("<img") })
        val chapter = document.chapters.firstOrNull { it.html.contains("<img") }
        assertNotNull("The real EPUB fixture contains a cover image", chapter)
        val path = Regex("src=\"([^\"]+)\"").find(chapter!!.html)!!.groupValues[1].replace("&amp;", "&")
        val first = repository.resource(path, document.readerKey, document.revision)
        assertTrue("Cover image must be delivered to the actual device", first is ApiResult.Ok)
        assertTrue((first as ApiResult.Ok).data.isNotEmpty())
        val reopened = LibraryReaderRepository(container.apiClient, container.endpoint.baseUrl, directory)
        val second = reopened.resource(path, document.readerKey, document.revision)
        assertTrue(second is ApiResult.Ok)
        assertArrayEquals(first.data, (second as ApiResult.Ok).data)
        Log.i("ReaderRegression", "Device EPUB: ${document.chapters.size} sections, cover ${first.data.size} bytes; persistent cache reopened successfully")
        Unit
    }
}
