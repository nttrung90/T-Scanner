package com.tscanner.app

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.tscanner.app.data.model.DocumentItem
import com.tscanner.app.data.model.UserProfile
import com.tscanner.app.data.model.VipTier
import com.tscanner.app.data.repository.DocumentRepo
import com.tscanner.app.utils.AppAuthManager
import com.tscanner.app.utils.CloudBackupManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DocumentRepoAccountIsolationTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var testContext: TestContext
    private lateinit var fakePrefs: FakeSharedPreferences

    @Before
    fun setUp() {
        androidx.arch.core.executor.ArchTaskExecutor.getInstance().setDelegate(object : androidx.arch.core.executor.TaskExecutor() {
            override fun executeOnDiskIO(runnable: Runnable) = runnable.run()
            override fun postToMainThread(runnable: Runnable) = runnable.run()
            override fun isMainThread(): Boolean = true
        })
        fakePrefs = FakeSharedPreferences()
        testContext = TestContext(tempFolder.root, fakePrefs)
        AppAuthManager.resetForTesting()
        DocumentRepo.resetInstanceForTesting()
    }

    @After
    fun tearDown() {
        AppAuthManager.resetForTesting()
        DocumentRepo.resetInstanceForTesting()
        androidx.arch.core.executor.ArchTaskExecutor.getInstance().setDelegate(null)
    }

    @Test
    fun testGuestDocumentClaimedByFirstUser_cannotBeReclaimedBySecondUser() {
        val repo = DocumentRepo.getInstance(testContext)
        val guestDoc1 = DocumentItem(id = "doc-guest-1", title = "Guest Invoice", ownerId = null)
        val guestDoc2 = DocumentItem(id = "doc-guest-2", title = "Guest Receipt", ownerId = null)
        repo.addDocument(guestDoc1)
        repo.addDocument(guestDoc2)

        val userAId = "user_canonical_A"
        val userBId = "user_canonical_B"

        // 1. User A logs in and claims unowned guest documents
        val claimedByA = repo.claimGuestDocuments(userAId)
        assertEquals("User A should claim exactly 2 guest docs", 2, claimedByA)
        assertEquals(userAId, repo.getDocument("doc-guest-1")?.ownerId)
        assertEquals(userAId, repo.getDocument("doc-guest-2")?.ownerId)

        // 2. User A logs out and User B logs in
        val claimedByB = repo.claimGuestDocuments(userBId)
        assertEquals("User B must NOT reclaim documents already claimed by A", 0, claimedByB)
        assertEquals(userAId, repo.getDocument("doc-guest-1")?.ownerId)
        assertEquals(userAId, repo.getDocument("doc-guest-2")?.ownerId)
    }

    @Test
    fun testAccountIsolation_userBCannotReadUserADocuments() {
        val repo = DocumentRepo.getInstance(testContext)
        val userAId = "user_canonical_A"
        val userBId = "user_canonical_B"

        val docA = DocumentItem(id = "doc-A", title = "A's Private Tax Report", ownerId = userAId)
        val docB = DocumentItem(id = "doc-B", title = "B's Study Notes", ownerId = userBId)
        val guestDoc = DocumentItem(id = "doc-G", title = "Unowned Guest File", ownerId = null)

        repo.addDocument(docA)
        repo.addDocument(docB)
        repo.addDocument(guestDoc)

        // Querying docs for User B:
        val docsForB = repo.getDocumentsForUser(userBId, includeGuest = false)
        assertEquals(1, docsForB.size)
        assertEquals("doc-B", docsForB[0].id)

        // Querying docs for User A:
        val docsForA = repo.getDocumentsForUser(userAId, includeGuest = false)
        assertEquals(1, docsForA.size)
        assertEquals("doc-A", docsForA[0].id)

        // Querying for Guest (logged out):
        val docsForGuest = repo.getDocumentsForUser(null)
        assertEquals(1, docsForGuest.size)
        assertEquals("doc-G", docsForGuest[0].id)

        // Direct getDocumentForUser: User B accessing A's doc must return null
        assertNull("User B must not be able to get User A's document", repo.getDocumentForUser("doc-A", userBId))
        assertNotNull("User B must be able to get their own document", repo.getDocumentForUser("doc-B", userBId))
        assertNull("Guest must not be able to get User A's document", repo.getDocumentForUser("doc-A", null))
    }

    @Test
    fun testCrossUserDownloadAborted_doesNotLeakCachedLocalFile() {
        val userAId = "user_canonical_A"
        val userBId = "user_canonical_B"

        // Create dummy physical PDF
        val pdfDir = File(tempFolder.root, "files/documents").apply { mkdirs() }
        val dummyPdf = File(pdfDir, "private_doc_A.pdf").apply { writeText("%PDF-1.4 User A Private Data") }

        val docA = DocumentItem(
            id = "doc-A-100",
            title = "A Private Document",
            pdfPath = dummyPdf.absolutePath,
            ownerId = userAId
        )
        DocumentRepo.getInstance(testContext).addDocument(docA)

        // User B is currently logged in
        fakePrefs.edit().putString("key_user_profile", """
            {"id":"$userBId","email":"b@example.com","displayName":"User B","isVip":true,"tier":"vip"}
        """.trimIndent()).apply()
        AppAuthManager.init(testContext)
        assertEquals(userBId, AppAuthManager.getCurrentUser()?.id)

        // User B tries to download or retrieve User A's document
        var downloadedFile: File? = File("should_not_exist")
        CloudBackupManager.downloadDocument(testContext, docA) { file ->
            downloadedFile = file
        }

        assertNull("downloadDocument must reject access when document owner does not match current user", downloadedFile)
    }

    @Test
    fun testUnsyncedDocuments_scopedToActiveUser() {
        val userAId = "user_canonical_A"
        val userBId = "user_canonical_B"

        val pdfDir = File(tempFolder.root, "files/documents").apply { mkdirs() }
        val fileA = File(pdfDir, "unsynced_a.pdf").apply { writeText("%PDF-1.4 Unsynced A") }
        val fileB = File(pdfDir, "unsynced_b.pdf").apply { writeText("%PDF-1.4 Unsynced B") }

        val repo = DocumentRepo.getInstance(testContext)
        val docA = DocumentItem(id = "doc-unsynced-A", title = "Unsynced A", pdfPath = fileA.absolutePath, ownerId = userAId, isSynced = false)
        val docB = DocumentItem(id = "doc-unsynced-B", title = "Unsynced B", pdfPath = fileB.absolutePath, ownerId = userBId, isSynced = false)

        repo.addDocument(docA)
        repo.addDocument(docB)

        // Query unsynced for User A
        val unsyncedA = repo.getUnsyncedDocuments(userAId)
        assertEquals(1, unsyncedA.size)
        assertEquals("doc-unsynced-A", unsyncedA[0].id)

        // Query unsynced for User B
        val unsyncedB = repo.getUnsyncedDocuments(userBId)
        assertEquals(1, unsyncedB.size)
        assertEquals("doc-unsynced-B", unsyncedB[0].id)
    }

    @Test
    fun testRestartPreservesOwnerPersistently() {
        val pdfDir = File(tempFolder.root, "files/documents").apply { mkdirs() }
        val dummyFile = File(pdfDir, "doc_persist.pdf").apply { writeText("%PDF-1.4 dummy") }

        val repo = DocumentRepo.getInstance(testContext)
        val userAId = "user_canonical_A"
        val guestDoc = DocumentItem(id = "doc-persist", title = "Persistent Doc", pdfPath = dummyFile.absolutePath, ownerId = null)
        repo.addDocument(guestDoc)

        // User A claims guest doc
        val claimResult = repo.claimGuestDocuments(userAId)
        assertEquals("User A should claim exactly 1 guest doc", 1, claimResult)
        assertEquals(userAId, repo.getDocument("doc-persist")?.ownerId)

        // Simulate application restart / reloading from disk
        DocumentRepo.resetInstanceForTesting()
        val reloadedRepo = DocumentRepo.getInstance(testContext)
        val reloadedDoc = reloadedRepo.getDocument("doc-persist")

        assertNotNull(reloadedDoc)
        assertEquals("Owner ID must survive restart", userAId, reloadedDoc?.ownerId)
    }

    @Test
    fun testCrossUserMutationBlocked_deleteRenameMoveModify() {
        val repo = DocumentRepo.getInstance(testContext)
        val userAId = "user_canonical_A"
        val userBId = "user_canonical_B"

        val docA = DocumentItem(
            id = "doc-A-mut",
            title = "A Original Title",
            ownerId = userAId,
            sizeBytes = 500L,
            contentRevision = 1L
        )
        repo.addDocument(docA)

        // 1. User B attempts to delete A's doc
        val deleteByB = repo.deleteDocument("doc-A-mut", userBId)
        assertFalse("User B must NOT be able to delete User A's document", deleteByB)
        assertNotNull("Document A must still exist", repo.getDocument("doc-A-mut"))

        // 2. User B attempts to rename A's doc
        val renameByB = repo.renameDocument("doc-A-mut", "Hacked Title", userBId)
        assertFalse("User B must NOT be able to rename User A's document", renameByB)
        assertEquals("Title must remain unchanged", "A Original Title", repo.getDocument("doc-A-mut")?.title)

        // 3. User B attempts to move A's doc
        val moveByB = repo.moveDocumentToFolder("doc-A-mut", "folder-B", userBId)
        assertFalse("User B must NOT be able to move User A's document", moveByB)
        assertNull("Folder must remain null", repo.getDocument("doc-A-mut")?.folderId)

        // 4. User B attempts to mark modified A's doc
        val modifyByB = repo.markDocumentModified("doc-A-mut", newSizeBytes = 9999L, userId = userBId)
        assertFalse("User B must NOT be able to modify User A's document", modifyByB)
        assertEquals(500L, repo.getDocument("doc-A-mut")?.sizeBytes)
        assertEquals(1L, repo.getDocument("doc-A-mut")?.contentRevision)

        // 5. User A can successfully rename and delete
        val renameByA = repo.renameDocument("doc-A-mut", "A Renamed Title", userAId)
        assertTrue("User A should be able to rename their own document", renameByA)
        assertEquals("A Renamed Title", repo.getDocument("doc-A-mut")?.title)

        val deleteByA = repo.deleteDocument("doc-A-mut", userAId)
        assertTrue("User A should be able to delete their own document", deleteByA)
        assertNull("Document must be deleted", repo.getDocument("doc-A-mut"))
    }

    @Test
    fun testManagedFilesAndStorageStats_isolatedByUser() {
        val repo = DocumentRepo.getInstance(testContext)
        val userAId = "user_canonical_A"
        val userBId = "user_canonical_B"

        val pdfDir = File(tempFolder.root, "files/documents").apply { mkdirs() }
        val fileA = File(pdfDir, "doc_A_stat.pdf").apply { writeText("AAAAA") }
        val fileB = File(pdfDir, "doc_B_stat.pdf").apply { writeText("BBBBBBBBBB") }

        val docA = DocumentItem(
            id = "doc-A-stat",
            title = "A Stat Doc",
            pdfPath = fileA.absolutePath,
            sizeBytes = 100L,
            ownerId = userAId
        )
        val docB = DocumentItem(
            id = "doc-B-stat",
            title = "B Stat Doc",
            pdfPath = fileB.absolutePath,
            sizeBytes = 200L,
            ownerId = userBId
        )
        repo.addDocument(docA)
        repo.addDocument(docB)

        // Storage stats
        val statsA = repo.getStorageStats(userAId)
        assertEquals(1, statsA.totalDocuments)
        assertEquals(100L, statsA.totalSizeBytes)

        val statsB = repo.getStorageStats(userBId)
        assertEquals(1, statsB.totalDocuments)
        assertEquals(200L, statsB.totalSizeBytes)

        // Managed files
        val filesA = repo.getAllManagedFiles(userAId)
        assertTrue("A must see A's file", filesA.any { it.path == fileA.absolutePath })
        assertFalse("A must NOT see B's file", filesA.any { it.path == fileB.absolutePath })

        val filesB = repo.getAllManagedFiles(userBId)
        assertTrue("B must see B's file", filesB.any { it.path == fileB.absolutePath })
        assertFalse("B must NOT see A's file", filesB.any { it.path == fileA.absolutePath })

        val filesGuest = repo.getAllManagedFiles(null)
        assertFalse("Guest must NOT see A's file", filesGuest.any { it.path == fileA.absolutePath })
        assertFalse("Guest must NOT see B's file", filesGuest.any { it.path == fileB.absolutePath })
    }

    @Test
    fun testAddDocument_automaticallyAttachesLoggedInUser() {
        val userAId = "user_canonical_A"
        fakePrefs.edit().putString("key_user_profile", """
            {"id":"$userAId","email":"a@example.com","displayName":"User A","isVip":false,"tier":"free"}
        """.trimIndent()).apply()
        AppAuthManager.init(testContext)
        assertEquals(userAId, AppAuthManager.getCurrentUser()?.id)

        val repo = DocumentRepo.getInstance(testContext)
        val doc = DocumentItem(id = "doc-auto-owner", title = "Auto Owner Doc", ownerId = null)
        repo.addDocument(doc)

        val savedDoc = repo.getDocument("doc-auto-owner")
        assertEquals("Document added while logged in as A must automatically have ownerId = A", userAId, savedDoc?.ownerId)
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
