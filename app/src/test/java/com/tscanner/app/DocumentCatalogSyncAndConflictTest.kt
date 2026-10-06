package com.tscanner.app

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.tscanner.app.data.model.DocumentItem
import com.tscanner.app.data.model.SyncStatus
import com.tscanner.app.data.repository.DocumentRepo
import com.tscanner.app.utils.GoogleDriveService
import com.tscanner.app.utils.QueryFolderResult
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DocumentCatalogSyncAndConflictTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var testContext: TestContext
    private lateinit var fakePrefs: FakeSharedPreferences
    private lateinit var repo: DocumentRepo

    @Before
    fun setUp() {
        androidx.arch.core.executor.ArchTaskExecutor.getInstance().setDelegate(object : androidx.arch.core.executor.TaskExecutor() {
            override fun executeOnDiskIO(runnable: Runnable) = runnable.run()
            override fun postToMainThread(runnable: Runnable) = runnable.run()
            override fun isMainThread(): Boolean = true
        })

        fakePrefs = FakeSharedPreferences()
        testContext = TestContext(tempFolder.root, fakePrefs)

        DocumentRepo.resetInstanceForTesting()
        repo = DocumentRepo.getInstance(testContext)
    }

    @After
    fun tearDown() {
        GoogleDriveService.setTestClient(null, null, null)
        DocumentRepo.resetInstanceForTesting()
        androidx.arch.core.executor.ArchTaskExecutor.getInstance().setDelegate(null)
    }

    @Test
    fun testUpsertFromDrive_idempotencyPreventsDuplicateDocuments() {
        val owner = "user_alpha"
        val driveId = "drive_doc_unique_001"

        // First upsert: document is new
        val res1 = repo.upsertFromDrive(
            ownerId = owner,
            driveFileId = driveId,
            title = "Alpha Contract.pdf",
            sizeBytes = 1024L,
            modifiedTime = 10000L
        )

        assertNotNull("Upsert result must not be null", res1)
        assertTrue("First upsert must mark isNew as true", res1!!.isNew)
        assertEquals("Alpha Contract", res1.document.title)
        assertEquals(driveId, res1.document.driveFileId)

        // Second upsert with same driveFileId: should update without adding new item
        val res2 = repo.upsertFromDrive(
            ownerId = owner,
            driveFileId = driveId,
            title = "Alpha Contract.pdf",
            sizeBytes = 1024L,
            modifiedTime = 10000L
        )

        assertNotNull("Second upsert result must not be null", res2)
        assertFalse("Second upsert must NOT mark isNew as true", res2!!.isNew)
        assertEquals(res1.document.id, res2.document.id)

        // Total documents in repo for this user must be exactly 1
        val userDocs = repo.getDocumentsForUser(owner, includeGuest = false)
        assertEquals(1, userDocs.size)
    }

    @Test
    fun testUpsertFromDrive_remoteNewerCleanLocalRefreshesMetadata() {
        val owner = "user_beta"
        val driveId = "drive_doc_clean_002"
        val localPdf = File(testContext.filesDir, "old.pdf").apply { writeText("old content") }

        // Initial synced document
        val initialDoc = DocumentItem(
            id = "doc_clean_1",
            title = "Invoice Old",
            pdfPath = localPdf.absolutePath,
            sizeBytes = 100L,
            createdAt = 5000L,
            isSynced = true,
            driveFileId = driveId,
            lastSyncedAt = 5000L,
            syncStatus = SyncStatus.SYNCED,
            ownerId = owner
        )
        repo.addDocument(initialDoc)

        // Remote has newer modifiedTime (8000L > 5000L) and local is clean (SYNCED)
        val upsertRes = repo.upsertFromDrive(
            ownerId = owner,
            driveFileId = driveId,
            title = "Invoice New.pdf",
            sizeBytes = 250L,
            modifiedTime = 8000L
        )

        assertNotNull("Upsert result must not be null", upsertRes)
        assertFalse("Must not be marked as new document", upsertRes!!.isNew)
        val updated = upsertRes.document
        assertEquals("Invoice New", updated.title)
        assertEquals(8000L, updated.lastSyncedAt)
        assertEquals(250L, updated.sizeBytes)
        // Local cache must be invalidated for lazy re-download
        assertEquals(null, updated.pdfPath)
        assertFalse("Must not be marked as conflict", updated.isConflict)
    }

    @Test
    fun testUpsertFromDrive_remoteNewerDirtyLocalFlagsConflict() {
        val owner = "user_gamma"
        val driveId = "drive_doc_dirty_003"
        val localEditFile = File(testContext.filesDir, "local_edited.pdf").apply { writeText("important local changes") }

        // Initial document with local dirty edits (LOCAL_ONLY)
        val dirtyDoc = DocumentItem(
            id = "doc_dirty_1",
            title = "Notes Local Edit",
            pdfPath = localEditFile.absolutePath,
            sizeBytes = 300L,
            createdAt = 5000L,
            isSynced = false,
            driveFileId = driveId,
            lastSyncedAt = 5000L,
            syncStatus = SyncStatus.LOCAL_ONLY,
            ownerId = owner
        )
        repo.addDocument(dirtyDoc)

        // Remote is newer (9000L > 5000L) while local is modified
        val upsertRes = repo.upsertFromDrive(
            ownerId = owner,
            driveFileId = driveId,
            title = "Notes Remote Edit.pdf",
            sizeBytes = 500L,
            modifiedTime = 9000L
        )

        assertNotNull("Upsert result must not be null", upsertRes)
        val resultDoc = upsertRes!!.document
        assertTrue("Conflict must be detected when local is dirty", resultDoc.isConflict)
        // Local file must be PRESERVED and NOT overwritten/deleted
        assertEquals(localEditFile.absolutePath, resultDoc.pdfPath)
        assertTrue("Local file must still exist", localEditFile.exists())
    }

    @Test
    fun testQueryFolderFiles_distinguishesPartialOnPaginationError() = runBlocking {
        var callCount = 0
        val testClient = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                callCount++
                if (callCount == 1) {
                    // Page 1 succeeds with 1 item and nextPageToken
                    val json = """
                        {
                            "nextPageToken": "token_page_2",
                            "files": [{"id": "drive_page1_doc", "name": "Doc1.pdf", "size": "100", "modifiedTime": "2026-09-22T00:00:00Z"}]
                        }
                    """.trimIndent()
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(json.toResponseBody("application/json".toMediaTypeOrNull()))
                        .build()
                } else {
                    // Page 2 fails with 500 Internal Server Error
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(500)
                        .message("Server Error")
                        .body("{\"error\": \"backend failed\"}".toResponseBody("application/json".toMediaTypeOrNull()))
                        .build()
                }
            })
            .build()

        GoogleDriveService.setTestClient(testClient)

        val result = GoogleDriveService.queryFolderFiles("dummy_token", "folder_xyz")
        assertTrue("Result must be Partial when page 2 fails", result is QueryFolderResult.Partial)
        val partial = result as QueryFolderResult.Partial
        assertEquals(1, partial.files.size)
        assertEquals("drive_page1_doc", partial.files[0].id)
        assertTrue("Partial result must contain error message", partial.error.contains("500"))
        assertFalse("Partial is not full success", result.isFullSuccess)
    }

    @Test
    fun testQueryFolderFiles_reportsAuthRequiredOnPage1AuthFailure() = runBlocking {
        val testClient = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(401)
                    .message("Unauthorized")
                    .body("{\"error\": \"invalid credentials\"}".toResponseBody("application/json".toMediaTypeOrNull()))
                    .build()
            })
            .build()

        GoogleDriveService.setTestClient(testClient)

        val result = GoogleDriveService.queryFolderFiles("dummy_token", "folder_xyz")
        assertTrue("Result must be AuthRequired on 401", result is QueryFolderResult.AuthRequired)
        assertFalse("AuthRequired is not full success", result.isFullSuccess)
        assertTrue(result.filesOrEmpty.isEmpty())
    }

    // -------------------------------------------------------------------------
    // Test Helpers
    // -------------------------------------------------------------------------

    private class TestContext(
        private val baseDir: File,
        private val prefs: SharedPreferences
    ) : ContextWrapper(null) {
        override fun getFilesDir(): File = File(baseDir, "files").apply { mkdirs() }
        override fun getCacheDir(): File = File(baseDir, "cache").apply { mkdirs() }
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = prefs
    }

    private class FakeSharedPreferences : SharedPreferences {
        private val map = mutableMapOf<String, Any>()

        override fun getAll(): MutableMap<String, *> = map.toMutableMap()
        override fun getString(key: String?, defValue: String?): String? = map[key] as? String ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            (map[key] as? Set<*>)?.mapNotNull { it?.toString() }?.toMutableSet() ?: defValues

        override fun getInt(key: String?, defValue: Int): Int = (map[key] as? Number)?.toInt() ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = (map[key] as? Number)?.toLong() ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = (map[key] as? Number)?.toFloat() ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = map[key] as? Boolean ?: defValue
        override fun contains(key: String?): Boolean = map.containsKey(key)

        override fun edit(): SharedPreferences.Editor = FakeEditor(this)

        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

        private class FakeEditor(private val prefs: FakeSharedPreferences) : SharedPreferences.Editor {
            private val pending = mutableMapOf<String, Any?>()
            private val removes = mutableSetOf<String>()
            private var clearFlag = false

            override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                if (key != null) {
                    if (value != null) pending[key] = value else removes.add(key)
                }
                return this
            }

            override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor {
                if (key != null) {
                    if (values != null) pending[key] = values else removes.add(key)
                }
                return this
            }

            override fun putInt(key: String?, value: Int): SharedPreferences.Editor {
                if (key != null) pending[key] = value
                return this
            }

            override fun putLong(key: String?, value: Long): SharedPreferences.Editor {
                if (key != null) pending[key] = value
                return this
            }

            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor {
                if (key != null) pending[key] = value
                return this
            }

            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor {
                if (key != null) pending[key] = value
                return this
            }

            override fun remove(key: String?): SharedPreferences.Editor {
                if (key != null) removes.add(key)
                return this
            }

            override fun clear(): SharedPreferences.Editor {
                clearFlag = true
                return this
            }

            override fun commit(): Boolean {
                apply()
                return true
            }

            override fun apply() {
                if (clearFlag) {
                    prefs.map.clear()
                }
                removes.forEach { prefs.map.remove(it) }
                pending.forEach { (k, v) ->
                    if (v != null) prefs.map[k] = v else prefs.map.remove(k)
                }
            }
        }
    }
}
