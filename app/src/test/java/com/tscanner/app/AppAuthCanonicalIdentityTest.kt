package com.tscanner.app

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.tscanner.app.data.model.DocumentItem
import com.tscanner.app.data.model.UserProfile
import com.tscanner.app.data.model.VipTier
import com.tscanner.app.data.repository.DocumentRepo
import com.tscanner.app.utils.AppAuthManager
import org.json.JSONObject
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
import java.util.Base64

class AppAuthCanonicalIdentityTest {

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

    private fun createDummyJwt(sub: String, email: String): String {
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val headerJson = JSONObject().apply {
            put("alg", "RS256")
            put("typ", "JWT")
        }.toString()
        val payloadJson = JSONObject().apply {
            put("sub", sub)
            put("email", email)
            put("iss", "https://accounts.google.com")
        }.toString()

        val headerEncoded = encoder.encodeToString(headerJson.toByteArray(Charsets.UTF_8))
        val payloadEncoded = encoder.encodeToString(payloadJson.toByteArray(Charsets.UTF_8))
        val signatureEncoded = encoder.encodeToString("fake_signature_bytes".toByteArray(Charsets.UTF_8))
        return "$headerEncoded.$payloadEncoded.$signatureEncoded"
    }

    // -------------------------------------------------------------------------
    // V01a: Canonical Identity resolution between Classic and Credential Manager
    // -------------------------------------------------------------------------

    @Test
    fun testCanonicalIdentity_classicAndCredentialManagerProduceSameId() {
        val googleSubId = "108736152412345678901"
        val userEmail = "alice@gmail.com"
        val idToken = createDummyJwt(sub = googleSubId, email = userEmail)

        // Classic sign-in: account.id is numeric subject ID
        val classicId = AppAuthManager.resolveCanonicalGoogleId(
            accountId = googleSubId,
            idToken = idToken,
            fallbackId = userEmail
        )

        // Credential Manager: googleIdTokenCredential.id is email address
        val credentialManagerId = AppAuthManager.resolveCanonicalGoogleId(
            accountId = null,
            idToken = idToken,
            fallbackId = userEmail
        )

        assertEquals("Canonical ID from Classic sign-in must match sub", googleSubId, classicId)
        assertEquals("Canonical ID from Credential Manager must extract sub from JWT", googleSubId, credentialManagerId)
        assertEquals("Both providers MUST resolve to the exact same canonical ID", classicId, credentialManagerId)
    }

    @Test
    fun testCanonicalIdentity_fallbackWhenIdTokenUnparseable() {
        val accountId = "108736152412345678901"
        val userEmail = "alice@gmail.com"

        // If idToken is null or unparseable, classic falls back to accountId
        val classicFallback = AppAuthManager.resolveCanonicalGoogleId(
            accountId = accountId,
            idToken = "invalid.token",
            fallbackId = userEmail
        )
        assertEquals(accountId, classicFallback)

        // Credential Manager without valid sub falls back to email/fallbackId
        val credManFallback = AppAuthManager.resolveCanonicalGoogleId(
            accountId = null,
            idToken = null,
            fallbackId = userEmail
        )
        assertEquals(userEmail, credManFallback)
    }

    @Test
    fun testExtractSubFromIdToken_robustness() {
        val validSub = "998877665544332211"
        val token = createDummyJwt(sub = validSub, email = "test@example.com")
        assertEquals(validSub, AppAuthManager.extractSubFromIdToken(token))

        // Robustness: null, blank, malformed tokens must return null gracefully
        assertNull(AppAuthManager.extractSubFromIdToken(null))
        assertNull(AppAuthManager.extractSubFromIdToken(""))
        assertNull(AppAuthManager.extractSubFromIdToken("no_dots_here"))
        assertNull(AppAuthManager.extractSubFromIdToken("single.dot"))
        assertNull(AppAuthManager.extractSubFromIdToken("part1.part2_not_valid_base64!!!.part3"))
    }

    // -------------------------------------------------------------------------
    // V01b: VIP Entitlement Migration across Provider Switch
    // -------------------------------------------------------------------------

    @Test
    fun testVipMigration_preservesEntitlementOnProviderSwitch() {
        val userEmail = "alice@gmail.com"
        val canonicalSub = "108736152412345678901"
        val futureExpiry = System.currentTimeMillis() + 365 * 86_400_000L
        val purchasedAt = System.currentTimeMillis()

        // 1. Simulate user previously activated VIP using Credential Manager (stored under email)
        AppAuthManager.setVipForUserForTesting(
            context = testContext,
            userId = userEmail,
            tier = VipTier.VIP_PRO,
            purchasedAt = purchasedAt,
            expiresAt = futureExpiry
        )

        // 2. User signs in next time with canonical ID (or classic sign in)
        val profileWithCanonicalId = UserProfile(
            id = canonicalSub,
            email = userEmail,
            displayName = "Alice Wonderland",
            tier = VipTier.FREE,
            isVip = false
        )

        // 3. Load VIP for profile; must automatically detect legacy key under email and migrate
        AppAuthManager.loadVipForUserForTesting(testContext, profileWithCanonicalId)

        // Invariants:
        assertTrue("VIP status must NOT be dropped to FREE upon switching provider", profileWithCanonicalId.isVip)
        assertTrue("VIP must be currently active", profileWithCanonicalId.isVipActive)
        assertEquals(VipTier.VIP_PRO, profileWithCanonicalId.tier)
        assertEquals(purchasedAt, profileWithCanonicalId.vipPurchasedAt)
        assertEquals(futureExpiry, profileWithCanonicalId.vipExpiresAt)

        // Check that preferences now have the canonical key
        assertEquals("vip_pro", fakePrefs.getString("vip_account_${canonicalSub}_tier", null))
        assertEquals(futureExpiry, fakePrefs.getLong("vip_account_${canonicalSub}_expires_at", -1L))
    }

    // -------------------------------------------------------------------------
    // V01b: Document Catalog Ownership Migration
    // -------------------------------------------------------------------------

    @Test
    fun testOwnershipMigration_updatesDocumentOwnerAtomically() {
        val userEmail = "alice@gmail.com"
        val canonicalSub = "108736152412345678901"

        val repo = DocumentRepo.getInstance(testContext)
        val doc1 = DocumentItem(
            id = "doc-1",
            title = "Doc 1",
            ownerId = userEmail
        )
        val doc2 = DocumentItem(
            id = "doc-2",
            title = "Doc 2",
            ownerId = userEmail
        )
        val docOther = DocumentItem(
            id = "doc-other",
            title = "Doc Other",
            ownerId = "other_user_id"
        )

        repo.addDocument(doc1)
        repo.addDocument(doc2)
        repo.addDocument(docOther)

        // Execute migration from legacy email to canonical ID
        AppAuthManager.migrateLegacyIdentity(testContext, canonicalSub, userEmail)

        // Verify that doc1 and doc2 have been migrated to canonical ID
        assertEquals(canonicalSub, repo.getDocument("doc-1")?.ownerId)
        assertEquals(canonicalSub, repo.getDocument("doc-2")?.ownerId)

        // Verify other user's document was untouched
        assertEquals("other_user_id", repo.getDocument("doc-other")?.ownerId)

        // Re-running migration should be idempotent and return 0
        val countSecondTime = repo.migrateOwnerId(userEmail, canonicalSub)
        assertEquals("Idempotent migration should touch 0 items on rerun", 0, countSecondTime)
    }

    // -------------------------------------------------------------------------
    // Account Isolation: User B cannot claim User A's VIP or documents
    // -------------------------------------------------------------------------

    @Test
    fun testAccountIsolation_userBCannotInheritUserAVipOrDocuments() {
        val aliceEmail = "alice@gmail.com"
        val aliceCanonicalSub = "108736152412345678901"
        val bobEmail = "bob@gmail.com"
        val bobCanonicalSub = "209876543210987654321"

        val futureExpiry = System.currentTimeMillis() + 365 * 86_400_000L

        // Alice has VIP
        AppAuthManager.setVipForUserForTesting(
            context = testContext,
            userId = aliceEmail,
            tier = VipTier.VIP_PRO_MAX,
            purchasedAt = System.currentTimeMillis(),
            expiresAt = futureExpiry
        )

        val repo = DocumentRepo.getInstance(testContext)
        repo.addDocument(DocumentItem(id = "alice-doc", title = "Alice Private", ownerId = aliceCanonicalSub))

        // Bob signs in
        val bobProfile = UserProfile(
            id = bobCanonicalSub,
            email = bobEmail,
            displayName = "Bob",
            tier = VipTier.FREE,
            isVip = false
        )

        // Bob loads VIP
        AppAuthManager.loadVipForUserForTesting(testContext, bobProfile)

        // Bob MUST NOT get Alice's VIP
        assertFalse("Bob must remain FREE", bobProfile.isVip)
        assertFalse("Bob must not be VIP active", bobProfile.isVipActive)
        assertEquals(VipTier.FREE, bobProfile.tier)

        // Bob tries migration
        AppAuthManager.migrateLegacyIdentity(testContext, bobCanonicalSub, bobEmail)

        // Alice's document is still strictly Alice's
        assertEquals(aliceCanonicalSub, repo.getDocument("alice-doc")?.ownerId)
    }

    // -------------------------------------------------------------------------
    // Cold Start / Init Migration: Legacy profile in JSON upgraded seamlessly
    // -------------------------------------------------------------------------

    @Test
    fun testAppAuthManagerInit_migratesLegacyProfileInPreferences() {
        val userEmail = "alice@gmail.com"
        val canonicalSub = "108736152412345678901"
        val futureExpiry = System.currentTimeMillis() + 365 * 86_400_000L
        val idToken = createDummyJwt(sub = canonicalSub, email = userEmail)

        // Pre-populate SharedPreferences with legacy profile format where id == email
        val legacyProfileJson = JSONObject().apply {
            put("id", userEmail) // Legacy format
            put("email", userEmail)
            put("displayName", "Alice")
            put("idToken", idToken)
            put("isVip", true)
            put("tier", "vip")
            put("vipExpiresAt", futureExpiry)
        }.toString()

        fakePrefs.edit()
            .putString("key_user_profile", legacyProfileJson)
            .putString("vip_account_${userEmail}_tier", "vip")
            .putLong("vip_account_${userEmail}_expires_at", futureExpiry)
            .apply()

        // Init AppAuthManager
        AppAuthManager.init(testContext)

        val restoredUser = AppAuthManager.getCurrentUser()
        assertNotNull("User profile must be restored", restoredUser)
        assertEquals("Profile ID must be upgraded to canonical sub", canonicalSub, restoredUser?.id)
        assertEquals(userEmail, restoredUser?.email)
        assertTrue("VIP status must be preserved", restoredUser?.isVipActive == true)
        assertEquals(VipTier.VIP, restoredUser?.tier)
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
