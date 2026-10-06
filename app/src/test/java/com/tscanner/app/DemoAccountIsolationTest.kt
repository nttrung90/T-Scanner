package com.tscanner.app

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.tscanner.app.data.model.DocumentItem
import com.tscanner.app.data.model.VipTier
import com.tscanner.app.data.repository.DocumentRepo
import com.tscanner.app.utils.AppAuthManager
import com.tscanner.app.utils.GoogleSignInAccountData
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

class DemoAccountIsolationTest {

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
    fun testSignInWithDemoAccount_doesNotClaimGuestDocuments() {
        val repo = DocumentRepo.getInstance(testContext)
        val guestPdf = File(testContext.filesDir, "guest_file.pdf").apply { writeText("guest bytes content") }
        val guestDoc = DocumentItem(
            id = "doc_guest_1",
            title = "Guest Document",
            ownerId = null,
            pdfPath = guestPdf.absolutePath,
            isSynced = false
        )
        repo.addDocument(guestDoc)

        // Verify precondition: guest document has ownerId == null
        assertNull("Precondition: guest doc ownerId must be null", repo.getDocument("doc_guest_1")?.ownerId)

        // Sign in with demo account
        var demoCompleted = false
        AppAuthManager.signInWithDemoAccount(testContext) { user ->
            demoCompleted = true
            assertEquals("google_user_demo_1001", user.id)
            assertEquals("demo.scanner@gmail.com", user.email)
            assertFalse(user.isVip)
            assertEquals(VipTier.FREE, user.tier)
        }

        assertTrue("Demo sign in callback must be executed", demoCompleted)

        // INVARIANT V04: Guest document must strictly remain ownerId == null
        val docAfterDemo = repo.getDocument("doc_guest_1")
        assertNotNull("Guest document must exist", docAfterDemo)
        assertNull("Guest document must NOT be claimed by demo account; ownerId must remain null", docAfterDemo?.ownerId)
        assertEquals("guest bytes content", File(docAfterDemo?.pdfPath!!).readText())
    }

    @Test
    fun testSignInWithDemoAccount_doesNotMutateUserADocuments() {
        val repo = DocumentRepo.getInstance(testContext)
        val alicePdf = File(testContext.filesDir, "alice_file.pdf").apply { writeText("alice secret data") }
        val aliceDoc = DocumentItem(
            id = "doc_alice_1",
            title = "Alice Confidential Doc",
            ownerId = "user_alice_canonical",
            pdfPath = alicePdf.absolutePath,
            isSynced = false
        )
        repo.addDocument(aliceDoc)

        // Sign in with demo account
        AppAuthManager.signInWithDemoAccount(testContext) {}

        // INVARIANT V04: Alice's document must strictly remain ownerId == "user_alice_canonical"
        val docAfter = repo.getDocument("doc_alice_1")
        assertNotNull("Alice document must exist", docAfter)
        assertEquals("user_alice_canonical", docAfter?.ownerId)
        assertEquals("alice secret data", File(docAfter?.pdfPath!!).readText())
    }

    @Test
    fun testSignInWithDemoAccount_doesNotMutateUserBDocuments() {
        val repo = DocumentRepo.getInstance(testContext)
        val bobPdf = File(testContext.filesDir, "bob_file.pdf").apply { writeText("bob financial statement") }
        val bobDoc = DocumentItem(
            id = "doc_bob_1",
            title = "Bob Financial Doc",
            ownerId = "user_bob_canonical",
            pdfPath = bobPdf.absolutePath,
            isSynced = false
        )
        repo.addDocument(bobDoc)

        // Sign in with demo account
        AppAuthManager.signInWithDemoAccount(testContext) {}

        // INVARIANT V04: Bob's document must remain intact
        val docAfter = repo.getDocument("doc_bob_1")
        assertNotNull("Bob document must exist", docAfter)
        assertEquals("user_bob_canonical", docAfter?.ownerId)
        assertEquals("bob financial statement", File(docAfter?.pdfPath!!).readText())
    }

    @Test
    fun testRealGoogleSignIn_claimsGuestDocumentsEvenAfterDemoLoginWasTriggered() {
        val repo = DocumentRepo.getInstance(testContext)
        val guestPdf = File(testContext.filesDir, "guest_receipt.pdf").apply { writeText("receipt content") }
        val guestDoc = DocumentItem(
            id = "doc_guest_receipt",
            title = "Guest Receipt",
            ownerId = null,
            pdfPath = guestPdf.absolutePath,
            isSynced = false
        )
        repo.addDocument(guestDoc)

        // 1. User attempts demo sign-in
        AppAuthManager.signInWithDemoAccount(testContext) {}
        assertEquals("null", repo.getDocument("doc_guest_receipt")?.ownerId ?: "null")

        // 2. Real user logs in with actual Google credentials
        val realGoogleAccount = GoogleSignInAccountData(
            id = "112233445566",
            email = "real.user@gmail.com",
            displayName = "Real Google User",
            givenName = "Real",
            familyName = "User",
            photoUrl = null,
            idToken = null
        )
        AppAuthManager.processSignedInAccount(testContext, realGoogleAccount)

        // INVARIANT V04: The guest document was NOT stolen by demo, so the real Google account successfully claims it!
        val docAfterRealLogin = repo.getDocument("doc_guest_receipt")
        assertNotNull("Receipt document must exist", docAfterRealLogin)
        assertEquals("112233445566", docAfterRealLogin?.ownerId)
        assertEquals("receipt content", File(docAfterRealLogin?.pdfPath!!).readText())
    }

    @Test
    fun testRealGoogleSignIn_doesNotClaimOrStealExistingDemoDocuments() {
        val repo = DocumentRepo.getInstance(testContext)
        val demoPdf = File(testContext.filesDir, "demo_legacy.pdf").apply { writeText("demo sample template") }
        val demoDoc = DocumentItem(
            id = "doc_demo_sample",
            title = "Demo Sample",
            ownerId = "google_user_demo_1001",
            pdfPath = demoPdf.absolutePath,
            isSynced = false
        )
        repo.addDocument(demoDoc)

        // Real user logs in
        val realGoogleAccount = GoogleSignInAccountData(
            id = "998877665544",
            email = "another.real@gmail.com",
            displayName = "Another Real User",
            givenName = "Another",
            familyName = "User",
            photoUrl = null,
            idToken = null
        )
        AppAuthManager.processSignedInAccount(testContext, realGoogleAccount)

        // INVARIANT V04: Existing documents that already carry demo owner are NOT silently stolen
        // or reassigned to a real Google account.
        val docAfterLogin = repo.getDocument("doc_demo_sample")
        assertNotNull("Demo document must exist", docAfterLogin)
        assertEquals("google_user_demo_1001", docAfterLogin?.ownerId)
        assertEquals("demo sample template", File(docAfterLogin?.pdfPath!!).readText())
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
