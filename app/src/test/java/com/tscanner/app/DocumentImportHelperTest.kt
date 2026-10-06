package com.tscanner.app

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.net.TestUri
import com.tscanner.app.data.model.DocumentItem
import com.tscanner.app.data.repository.DocumentRepo
import com.tscanner.app.utils.DocumentImportDependencies
import com.tscanner.app.utils.DocumentImportHelper
import com.tscanner.app.utils.FileUtils
import com.tscanner.app.utils.ImportResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream

class DocumentImportHelperTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var testBaseDir: File
    private lateinit var testContext: TestContext

    private class TestContext(private val baseDir: File) : ContextWrapper(null) {
        override fun getFilesDir(): File = File(baseDir, "files").apply { mkdirs() }
        override fun getCacheDir(): File = File(baseDir, "cache").apply { mkdirs() }
        override fun getApplicationContext(): Context = this
    }

    private class FakeDocumentImportDependencies(
        var persistResult: Boolean = true,
        var persistException: Throwable? = null,
        var converterResult: Boolean = true,
        var renderThumbResult: Boolean = true,
        var validateImageResult: Boolean = true,
        val openStreamProvider: (Uri) -> InputStream? = { ByteArrayInputStream("fake image binary content".toByteArray()) }
    ) : DocumentImportDependencies {

        var persistCallCount = 0
        var lastPersistedDoc: DocumentItem? = null
        var converterCallCount = 0
        var lastConvertedImagePaths: List<String> = emptyList()
        var lastConvertedOutputFile: File? = null

        val shownToasts = mutableListOf<Pair<Int, List<Any>>>()
        val openedViewers = mutableListOf<Pair<String, String>>()

        override fun openInputStream(uri: Uri): InputStream? = openStreamProvider(uri)
        override fun validateImage(file: File): Boolean = validateImageResult

        override fun persist(doc: DocumentItem): Boolean {
            persistCallCount++
            lastPersistedDoc = doc
            persistException?.let { throw it }
            return persistResult
        }

        override suspend fun convertImagesToPdf(imagePaths: List<String>, outputFile: File, addWatermark: Boolean): Boolean {
            converterCallCount++
            lastConvertedImagePaths = imagePaths
            lastConvertedOutputFile = outputFile
            if (converterResult) {
                outputFile.parentFile?.mkdirs()
                outputFile.writeText("%PDF-1.4 mock content")
                return true
            }
            return false
        }

        override fun renderFirstPageThumbnail(pdfFile: File, thumbFile: File): Boolean {
            if (renderThumbResult) {
                thumbFile.parentFile?.mkdirs()
                thumbFile.writeText("mock thumbnail")
                return true
            }
            return false
        }

        override fun showToast(messageRes: Int, vararg formatArgs: Any) {
            shownToasts.add(Pair(messageRes, formatArgs.toList()))
        }

        override fun openViewer(pdfPath: String, title: String) {
            openedViewers.add(Pair(pdfPath, title))
        }

        override fun createDocumentTitle(): String = "Test Imported Doc"
    }

    @Before
    fun setUp() {
        testBaseDir = tempFolder.newFolder("import_test_root")
        testContext = TestContext(testBaseDir)
    }
    private fun createFakeUri(name: String): Uri {
        return TestUri.create("content://media/external/images/media/$name")
    }

    @Test
    fun testPersistFailure_cleansUpFilesAndDoesNotOpenViewer() = runBlocking {
        val fakeDeps = FakeDocumentImportDependencies(persistResult = false)
        val uris = listOf(createFakeUri("img1"), createFakeUri("img2"))

        val result = DocumentImportHelper.importImagesToPdf(
            context = testContext,
            uris = uris,
            deps = fakeDeps
        )

        // 1. Result must be PersistFailed
        assertTrue("Result must be Failure.PersistFailed", result is ImportResult.Failure.PersistFailed)
        val failure = result as ImportResult.Failure.PersistFailed
        assertEquals(2, failure.document.pageCount)

        // 2. persist must have been called exactly once
        assertEquals(1, fakeDeps.persistCallCount)

        // 3. Viewer must NOT be opened
        assertTrue("Viewer must NOT be opened when persist fails", fakeDeps.openedViewers.isEmpty())

        // 4. Success toast must NOT be shown; error toast must be shown
        assertTrue("Error toast R.string.pdf_save_error must be shown", fakeDeps.shownToasts.any { it.first == R.string.pdf_save_error })
        assertFalse("Success toast must not be shown", fakeDeps.shownToasts.any { it.first == R.string.imported_images_success_format })

        // 5. Output PDF and thumbnail must be cleaned up
        val createdPdf = fakeDeps.lastConvertedOutputFile
        assertNotNull(createdPdf)
        assertFalse("Unpersisted PDF file must be deleted", createdPdf!!.exists())

        val thumbsDir = FileUtils.getThumbnailsDir(testContext)
        val thumbFile = File(thumbsDir, "thumb_${failure.document.id}.jpg")
        assertFalse("Unpersisted thumbnail file must be deleted", thumbFile.exists())

        // 6. Temp scan session directory must be deleted
        val tempSessionDir = File(File(testContext.cacheDir, "temp_scan"), failure.document.id)
        assertFalse("Temp session directory must be cleaned up", tempSessionDir.exists())
    }

    @Test
    fun testPersistException_cleansUpFilesAndDoesNotOpenViewer() = runBlocking {
        val fakeDeps = FakeDocumentImportDependencies(
            persistException = RuntimeException("Database disk image is malformed")
        )
        val uris = listOf(createFakeUri("img1"))

        val result = DocumentImportHelper.importImagesToPdf(
            context = testContext,
            uris = uris,
            deps = fakeDeps
        )

        assertTrue("Result must be Failure.PersistFailed", result is ImportResult.Failure.PersistFailed)
        val failure = result as ImportResult.Failure.PersistFailed
        assertNotNull(failure.cause)
        assertEquals("Database disk image is malformed", failure.cause?.message)

        assertTrue("Viewer must NOT be opened when persist throws", fakeDeps.openedViewers.isEmpty())
        assertTrue("Error toast R.string.pdf_save_error must be shown", fakeDeps.shownToasts.any { it.first == R.string.pdf_save_error })

        val createdPdf = fakeDeps.lastConvertedOutputFile
        assertNotNull(createdPdf)
        assertFalse("Unpersisted PDF file must be deleted on exception", createdPdf!!.exists())
    }

    @Test
    fun testPersistSuccess_opensViewerAndLeavesFiles() = runBlocking {
        val fakeDeps = FakeDocumentImportDependencies(persistResult = true)
        val uris = listOf(createFakeUri("img1"), createFakeUri("img2"), createFakeUri("img3"))

        val result = DocumentImportHelper.importImagesToPdf(
            context = testContext,
            uris = uris,
            deps = fakeDeps
        )

        assertTrue("Result must be Success", result is ImportResult.Success)
        val success = result as ImportResult.Success
        assertEquals(3, success.document.pageCount)

        assertEquals(1, fakeDeps.persistCallCount)
        assertEquals(1, fakeDeps.openedViewers.size)
        assertEquals(success.document.pdfPath, fakeDeps.openedViewers.first().first)
        assertEquals(success.document.title, fakeDeps.openedViewers.first().second)

        assertTrue("Success toast must be shown", fakeDeps.shownToasts.any { it.first == R.string.imported_images_success_format })

        val createdPdf = fakeDeps.lastConvertedOutputFile
        assertNotNull(createdPdf)
        assertTrue("Persisted PDF file must exist", createdPdf!!.exists())
        assertTrue("PDF filename must contain docId", createdPdf.name.contains(success.document.id))

        val thumbsDir = FileUtils.getThumbnailsDir(testContext)
        val thumbFile = File(thumbsDir, "thumb_${success.document.id}.jpg")
        assertTrue("Thumbnail file must exist", thumbFile.exists())
    }

    @Test
    fun testConverterFailure_doesNotCallPersist() = runBlocking {
        val fakeDeps = FakeDocumentImportDependencies(converterResult = false)
        val uris = listOf(createFakeUri("img1"))

        val result = DocumentImportHelper.importImagesToPdf(
            context = testContext,
            uris = uris,
            deps = fakeDeps
        )

        assertTrue("Result must be ConversionFailed", result is ImportResult.Failure.ConversionFailed)
        assertEquals("persist must NOT be called when converter fails", 0, fakeDeps.persistCallCount)
        assertTrue("Viewer must NOT be opened", fakeDeps.openedViewers.isEmpty())
        assertTrue("Error toast R.string.pdf_create_failed must be shown", fakeDeps.shownToasts.any { it.first == R.string.pdf_create_failed })
    }

    @Test
    fun testEmptyUris_returnsNoImagesSelected() = runBlocking {
        val fakeDeps = FakeDocumentImportDependencies()

        val result = DocumentImportHelper.importImagesToPdf(
            context = testContext,
            uris = emptyList(),
            deps = fakeDeps
        )

        assertTrue("Result must be NoImagesSelected", result is ImportResult.NoImagesSelected)
        assertEquals(0, fakeDeps.converterCallCount)
        assertEquals(0, fakeDeps.persistCallCount)
        assertTrue(fakeDeps.openedViewers.isEmpty())
        assertTrue(fakeDeps.shownToasts.isEmpty())
    }

    @Test
    fun testExistingUserFilesPreservedWhenImportFails() = runBlocking {
        val docsDir = FileUtils.getDocumentsDir(testContext)
        val existingDoc = File(docsDir, "existing_contract.pdf").apply {
            writeText("%PDF-1.4 existing document content")
        }
        assertTrue(existingDoc.exists())

        val fakeDeps = FakeDocumentImportDependencies(persistResult = false)
        val uris = listOf(createFakeUri("img1"))

        val result = DocumentImportHelper.importImagesToPdf(
            context = testContext,
            uris = uris,
            deps = fakeDeps
        )

        assertTrue(result is ImportResult.Failure.PersistFailed)
        assertTrue("Existing user document must remain intact", existingDoc.exists())
        assertEquals("%PDF-1.4 existing document content", existingDoc.readText())
    }

    @Test
    fun testThreeValidImages_createsThreePagesInExactOrder() = runBlocking {
        val fakeDeps = FakeDocumentImportDependencies(persistResult = true)
        val uris = listOf(createFakeUri("page1"), createFakeUri("page2"), createFakeUri("page3"))

        val result = DocumentImportHelper.importImagesToPdf(
            context = testContext,
            uris = uris,
            deps = fakeDeps
        )

        assertTrue("Result must be Success", result is ImportResult.Success)
        val success = result as ImportResult.Success
        assertEquals(3, success.document.pageCount)
        assertEquals(1, fakeDeps.persistCallCount)
        assertEquals(1, fakeDeps.converterCallCount)
        assertEquals(3, fakeDeps.lastConvertedImagePaths.size)
        assertTrue(fakeDeps.lastConvertedImagePaths[0].endsWith("imported_1.jpg"))
        assertTrue(fakeDeps.lastConvertedImagePaths[1].endsWith("imported_2.jpg"))
        assertTrue(fakeDeps.lastConvertedImagePaths[2].endsWith("imported_3.jpg"))
        assertTrue(fakeDeps.shownToasts.any { it.first == R.string.imported_images_success_format && it.second == listOf(3) })
    }

    @Test
    fun testFirstImageStreamNull_abortsAndCleansUp() = runBlocking {
        val uris = listOf(createFakeUri("bad_first"), createFakeUri("good_mid"), createFakeUri("good_last"))
        val fakeDeps = FakeDocumentImportDependencies(
            openStreamProvider = { uri ->
                if (uri.toString().contains("bad_first")) null else ByteArrayInputStream("good image".toByteArray())
            }
        )

        val result = DocumentImportHelper.importImagesToPdf(
            context = testContext,
            uris = uris,
            deps = fakeDeps
        )

        assertTrue("Result must be ImageReadFailed", result is ImportResult.Failure.ImageReadFailed)
        val failure = result as ImportResult.Failure.ImageReadFailed
        assertEquals(0, failure.index)
        assertEquals(0, fakeDeps.converterCallCount)
        assertEquals(0, fakeDeps.persistCallCount)
        assertTrue(fakeDeps.openedViewers.isEmpty())
        assertTrue(fakeDeps.shownToasts.any { it.first == R.string.crop_image_read_error })

        val tempBase = File(testContext.cacheDir, "temp_scan")
        val sessionDirs = tempBase.listFiles()?.toList() ?: emptyList()
        assertTrue("Temp session directory must be deleted", sessionDirs.isEmpty())
    }

    @Test
    fun testMiddleImageStreamNull_abortsAndCleansUp() = runBlocking {
        val uris = listOf(createFakeUri("good_first"), createFakeUri("bad_mid"), createFakeUri("good_last"))
        val fakeDeps = FakeDocumentImportDependencies(
            openStreamProvider = { uri ->
                if (uri.toString().contains("bad_mid")) null else ByteArrayInputStream("good image".toByteArray())
            }
        )

        val result = DocumentImportHelper.importImagesToPdf(
            context = testContext,
            uris = uris,
            deps = fakeDeps
        )

        assertTrue("Result must be ImageReadFailed", result is ImportResult.Failure.ImageReadFailed)
        val failure = result as ImportResult.Failure.ImageReadFailed
        assertEquals(1, failure.index)
        assertEquals(0, fakeDeps.converterCallCount)
        assertEquals(0, fakeDeps.persistCallCount)
        assertTrue(fakeDeps.openedViewers.isEmpty())
        assertTrue(fakeDeps.shownToasts.any { it.first == R.string.crop_image_read_error })

        val tempBase = File(testContext.cacheDir, "temp_scan")
        val sessionDirs = tempBase.listFiles()?.toList() ?: emptyList()
        assertTrue("Temp session directory must be deleted", sessionDirs.isEmpty())
    }

    @Test
    fun testLastImageStreamNull_abortsAndCleansUp() = runBlocking {
        val uris = listOf(createFakeUri("good_first"), createFakeUri("good_mid"), createFakeUri("bad_last"))
        val fakeDeps = FakeDocumentImportDependencies(
            openStreamProvider = { uri ->
                if (uri.toString().contains("bad_last")) null else ByteArrayInputStream("good image".toByteArray())
            }
        )

        val result = DocumentImportHelper.importImagesToPdf(
            context = testContext,
            uris = uris,
            deps = fakeDeps
        )

        assertTrue("Result must be ImageReadFailed", result is ImportResult.Failure.ImageReadFailed)
        val failure = result as ImportResult.Failure.ImageReadFailed
        assertEquals(2, failure.index)
        assertEquals(0, fakeDeps.converterCallCount)
        assertEquals(0, fakeDeps.persistCallCount)
        assertTrue(fakeDeps.openedViewers.isEmpty())
        assertTrue(fakeDeps.shownToasts.any { it.first == R.string.crop_image_read_error })

        val tempBase = File(testContext.cacheDir, "temp_scan")
        val sessionDirs = tempBase.listFiles()?.toList() ?: emptyList()
        assertTrue("Temp session directory must be deleted", sessionDirs.isEmpty())
    }

    @Test
    fun testStreamEmpty_abortsAndCleansUp() = runBlocking {
        val uris = listOf(createFakeUri("empty_img"))
        val fakeDeps = FakeDocumentImportDependencies(
            openStreamProvider = { ByteArrayInputStream(ByteArray(0)) }
        )

        val result = DocumentImportHelper.importImagesToPdf(
            context = testContext,
            uris = uris,
            deps = fakeDeps
        )

        assertTrue("Result must be ImageReadFailed", result is ImportResult.Failure.ImageReadFailed)
        assertEquals(0, fakeDeps.converterCallCount)
        assertEquals(0, fakeDeps.persistCallCount)
        assertTrue(fakeDeps.openedViewers.isEmpty())
        assertTrue(fakeDeps.shownToasts.any { it.first == R.string.crop_image_read_error })
    }

    @Test
    fun testPartialReadIoException_abortsAndCleansUp() = runBlocking {
        val uris = listOf(createFakeUri("partial_read"))
        val brokenStream = object : InputStream() {
            private var count = 0
            override fun read(): Int {
                if (count++ >= 5) throw java.io.IOException("Connection reset / disk error midway")
                return 42
            }
        }
        val fakeDeps = FakeDocumentImportDependencies(
            openStreamProvider = { brokenStream }
        )

        val result = DocumentImportHelper.importImagesToPdf(
            context = testContext,
            uris = uris,
            deps = fakeDeps
        )

        assertTrue("Result must be ImageReadFailed", result is ImportResult.Failure.ImageReadFailed)
        assertEquals(0, fakeDeps.converterCallCount)
        assertEquals(0, fakeDeps.persistCallCount)
        assertTrue(fakeDeps.openedViewers.isEmpty())
        assertTrue(fakeDeps.shownToasts.any { it.first == R.string.crop_image_read_error })
    }

    @Test
    fun testCorruptedImageData_abortsAndCleansUp() = runBlocking {
        val uris = listOf(createFakeUri("corrupt_img"))
        val fakeDeps = FakeDocumentImportDependencies(
            validateImageResult = false
        )

        val result = DocumentImportHelper.importImagesToPdf(
            context = testContext,
            uris = uris,
            deps = fakeDeps
        )

        assertTrue("Result must be ImageReadFailed", result is ImportResult.Failure.ImageReadFailed)
        assertEquals(0, fakeDeps.converterCallCount)
        assertEquals(0, fakeDeps.persistCallCount)
        assertTrue(fakeDeps.openedViewers.isEmpty())
        assertTrue(fakeDeps.shownToasts.any { it.first == R.string.crop_image_read_error })
    }

    @Test
    fun testCancellationException_propagatesAndCleansUp() {
        val uris = listOf(createFakeUri("cancelled_img"))
        val cancellingStream = object : InputStream() {
            override fun read(): Int {
                throw kotlinx.coroutines.CancellationException("Import job cancelled by user")
            }
        }
        val fakeDeps = FakeDocumentImportDependencies(
            openStreamProvider = { cancellingStream }
        )

        var caughtCancellation = false
        try {
            runBlocking {
                DocumentImportHelper.importImagesToPdf(
                    context = testContext,
                    uris = uris,
                    deps = fakeDeps
                )
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            caughtCancellation = true
        }

        assertTrue("CancellationException must propagate out to caller", caughtCancellation)
        assertEquals(0, fakeDeps.persistCallCount)
        assertEquals(0, fakeDeps.converterCallCount)
        assertTrue(fakeDeps.openedViewers.isEmpty())

        val tempBase = File(testContext.cacheDir, "temp_scan")
        val sessionDirs = tempBase.listFiles()?.toList() ?: emptyList()
        assertTrue("Temp session directory must be deleted on cancellation", sessionDirs.isEmpty())
    }
}
