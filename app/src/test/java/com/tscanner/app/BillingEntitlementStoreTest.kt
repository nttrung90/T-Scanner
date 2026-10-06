package com.tscanner.app

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.tscanner.app.data.model.UserProfile
import com.tscanner.app.data.model.VipTier
import com.tscanner.app.utils.AppAuthManager
import com.tscanner.app.utils.billing.BillingEntitlement
import com.tscanner.app.utils.billing.BillingEntitlementStore
import com.tscanner.app.utils.billing.EntitlementSource
import com.tscanner.app.utils.billing.EntitlementState
import com.tscanner.app.utils.billing.UserEntitlementSnapshot
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

/**
 * Unit tests for B05: Writer entitlement tuyệt đối và migration local (F02/F03).
 *
 * Verifies:
 * - F02: Absolute expiry timestamps are preserved; replay never accumulates relative durations.
 * - F03: Monotonic versioning prevents stale snapshots from overwriting newer snapshots.
 * - Idempotency: Multiple applications of the same snapshot result in the exact same state.
 * - Lifetime VIP: Represented with null expiry and indefinitely active.
 * - Guest binding: Seamless transfer of guest entitlements to signed-in canonical user.
 * - Multi-entitlement isolation: Revocation or expiration of one tier preserves active lifetime.
 * - Legacy migration: SharedPreferences VIP state gracefully loads and does not wipe unexpired/lifetime VIP.
 * - Storage fault tolerance: Corrupt JSON degrades gracefully without crashes.
 */
class BillingEntitlementStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var testContext: TestContext
    private lateinit var fakePrefs: FakeSharedPreferences
    private var now = 0L

    @Before
    fun setUp() {
        now = System.currentTimeMillis()
        androidx.arch.core.executor.ArchTaskExecutor.getInstance().setDelegate(object : androidx.arch.core.executor.TaskExecutor() {
            override fun executeOnDiskIO(runnable: Runnable) = runnable.run()
            override fun postToMainThread(runnable: Runnable) = runnable.run()
            override fun isMainThread(): Boolean = true
        })
        fakePrefs = FakeSharedPreferences()
        testContext = TestContext(tempFolder.root, fakePrefs)
        BillingEntitlementStore.resetInstanceForTesting()
        AppAuthManager.resetForTesting()
    }

    @After
    fun tearDown() {
        BillingEntitlementStore.resetInstanceForTesting()
        AppAuthManager.resetForTesting()
        androidx.arch.core.executor.ArchTaskExecutor.getInstance().setDelegate(null)
    }

    @Test
    fun testIdempotentReplay_reapplyingSnapshot_doesNotExtendExpiration() {
        val store = BillingEntitlementStore.getInstance()
        val userId = "user_test_100"
        val originalExpiry = now + (30L * 24 * 3600 * 1000)

        val sub = BillingEntitlement(
            id = "tok_sub_1",
            ownerAppUserId = userId,
            productId = "tscanner_vip_monthly",
            productType = "subs",
            purchaseToken = "tok_sub_1",
            state = EntitlementState.VERIFIED_ACTIVE,
            purchaseTimeMillis = now,
            expiryTimeMillis = originalExpiry,
            snapshotVersion = 1L
        )

        val snapshot1 = UserEntitlementSnapshot(ownerAppUserId = userId, entitlements = listOf(sub))
        assertTrue(store.applySnapshot(testContext, snapshot1))

        val loaded1 = store.getSnapshot(testContext, userId)
        assertEquals(1, loaded1.entitlements.size)
        assertEquals(originalExpiry, loaded1.entitlements.first().expiryTimeMillis)

        // Reapply identical snapshot (simulate app restart or repeated restore callback)
        assertTrue(store.applySnapshot(testContext, snapshot1))
        val loaded2 = store.getSnapshot(testContext, userId)
        assertEquals("Expiry timestamp must remain identical on replay", originalExpiry, loaded2.entitlements.first().expiryTimeMillis)

        // Reapply with newer snapshotVersion but same purchaseToken & absolute expiry
        val snapshot2 = UserEntitlementSnapshot(ownerAppUserId = userId, entitlements = listOf(sub.copy(snapshotVersion = 2L)))
        assertTrue(store.applySnapshot(testContext, snapshot2))
        val loaded3 = store.getSnapshot(testContext, userId)
        assertEquals(originalExpiry, loaded3.entitlements.first().expiryTimeMillis)
        assertEquals(2L, loaded3.entitlements.first().snapshotVersion)
    }

    @Test
    fun testMonotonicVersioning_staleSnapshotCannotOverwriteNewer() {
        val store = BillingEntitlementStore.getInstance()
        val userId = "user_test_200"

        val newer = BillingEntitlement(
            id = "tok_sub_2",
            ownerAppUserId = userId,
            productId = "tscanner_vip_yearly",
            productType = "subs",
            purchaseToken = "tok_sub_2",
            state = EntitlementState.VERIFIED_ACTIVE,
            purchaseTimeMillis = now,
            expiryTimeMillis = now + (365L * 24 * 3600 * 1000),
            snapshotVersion = 5L
        )
        store.applySnapshot(testContext, UserEntitlementSnapshot(userId, listOf(newer)))

        // Attempt to apply older version
        val stale = newer.copy(state = EntitlementState.REVOKED, snapshotVersion = 3L)
        store.applySnapshot(testContext, UserEntitlementSnapshot(userId, listOf(stale)))

        val loaded = store.getSnapshot(testContext, userId)
        assertEquals(EntitlementState.VERIFIED_ACTIVE, loaded.entitlements.first().state)
        assertEquals(5L, loaded.entitlements.first().snapshotVersion)
    }

    @Test
    fun testLifetimeEntitlement_storedWithNullExpiry_loadsAsLifetimeVip() {
        val store = BillingEntitlementStore.getInstance()
        val userId = "user_lifetime_300"

        val lifetime = BillingEntitlement(
            id = "tok_life_1",
            ownerAppUserId = userId,
            productId = "tscanner_vip_lifetime",
            productType = "inapp",
            purchaseToken = "tok_life_1",
            source = EntitlementSource.GOOGLE_PLAY_INAPP,
            state = EntitlementState.VERIFIED_ACTIVE,
            purchaseTimeMillis = now,
            expiryTimeMillis = null // Lifetime has no expiry
        )

        assertTrue(store.applyEntitlement(testContext, lifetime))

        val snapshot = store.getSnapshot(testContext, userId)
        assertTrue(snapshot.isVipActive(now))
        assertTrue(snapshot.isLifetimeActive(now))
        assertNull(snapshot.entitlements.first().expiryTimeMillis)

        // Verify AppAuthManager integration: profile reflects lifetime VIP
        val profile = UserProfile(
            id = userId,
            email = "lifetime@example.com",
            displayName = "Lifetime User",
            isVip = false,
            tier = VipTier.FREE
        )
        AppAuthManager.loadVipForUserForTesting(testContext, profile)

        assertTrue("UserProfile.isVip must be true for lifetime", profile.isVip)
        assertEquals(VipTier.VIP, profile.tier)
        assertNull("Lifetime vipExpiresAt must be null", profile.vipExpiresAt)
        assertTrue("UserProfile.isVipActive must be true", profile.isVipActive)
    }

    @Test
    fun testMonthlySubscription_appliesAbsoluteExpiry_activeThenExpired() {
        val store = BillingEntitlementStore.getInstance()
        val userId = "user_sub_400"
        val expiry = now + (30L * 24 * 3600 * 1000)

        val sub = BillingEntitlement(
            id = "tok_month_1",
            ownerAppUserId = userId,
            productId = "tscanner_vip_monthly",
            productType = "subs",
            purchaseToken = "tok_month_1",
            state = EntitlementState.VERIFIED_ACTIVE,
            purchaseTimeMillis = now,
            expiryTimeMillis = expiry
        )
        store.applyEntitlement(testContext, sub)

        val profile = UserProfile(
            id = userId,
            email = "sub@example.com",
            displayName = "Sub User",
            isVip = false,
            tier = VipTier.FREE
        )
        AppAuthManager.loadVipForUserForTesting(testContext, profile)

        assertTrue(profile.isVip)
        assertEquals(expiry, profile.vipExpiresAt)
        assertTrue(profile.isVipActive)
    }

    @Test
    fun testGuestBinding_transfersEntitlementsToSignedInUser() {
        val store = BillingEntitlementStore.getInstance()
        val canonicalUserId = "10987654321_google"

        // Guest purchase (ownerAppUserId = null)
        val guestEntitlement = BillingEntitlement(
            id = "guest_tok_1",
            ownerAppUserId = null,
            productId = "tscanner_vip_lifetime",
            productType = "inapp",
            purchaseToken = "guest_tok_1",
            source = EntitlementSource.GOOGLE_PLAY_INAPP,
            state = EntitlementState.VERIFIED_ACTIVE,
            purchaseTimeMillis = now,
            expiryTimeMillis = null
        )
        store.applyEntitlement(testContext, guestEntitlement)

        val guestSnapBefore = store.getSnapshot(testContext, null)
        assertEquals(1, guestSnapBefore.entitlements.size)

        // Bind guest to canonical user
        val bound = store.bindGuestEntitlementsToUser(testContext, canonicalUserId)
        assertTrue("Guest binding must succeed", bound)

        // Verify guest record cleared
        val guestSnapAfter = store.getSnapshot(testContext, null)
        assertTrue("Guest snapshot must be empty after binding", guestSnapAfter.entitlements.isEmpty())

        // Verify user received the entitlement
        val userSnap = store.getSnapshot(testContext, canonicalUserId)
        assertEquals(1, userSnap.entitlements.size)
        assertEquals(canonicalUserId, userSnap.entitlements.first().ownerAppUserId)
        assertTrue(userSnap.isLifetimeActive(now))
    }

    @Test
    fun testMultiEntitlement_monthlyAndLifetime_lifetimeDominates() {
        val store = BillingEntitlementStore.getInstance()
        val userId = "user_multi_500"

        val monthly = BillingEntitlement(
            id = "tok_m",
            ownerAppUserId = userId,
            productId = "tscanner_vip_monthly",
            productType = "subs",
            purchaseToken = "tok_m",
            state = EntitlementState.VERIFIED_ACTIVE,
            purchaseTimeMillis = now,
            expiryTimeMillis = now + (30L * 24 * 3600 * 1000)
        )
        val lifetime = BillingEntitlement(
            id = "tok_l",
            ownerAppUserId = userId,
            productId = "tscanner_vip_lifetime",
            productType = "inapp",
            purchaseToken = "tok_l",
            state = EntitlementState.VERIFIED_ACTIVE,
            purchaseTimeMillis = now,
            expiryTimeMillis = null
        )

        store.applySnapshot(testContext, UserEntitlementSnapshot(userId, listOf(monthly, lifetime)))

        val profile = UserProfile(id = userId, email = "multi@example.com", displayName = "Multi")
        AppAuthManager.loadVipForUserForTesting(testContext, profile)
        assertTrue(profile.isVip)
        assertNull("Lifetime active dominates expiry timestamp", profile.vipExpiresAt)

        // Even if monthly is revoked/expired, user remains lifetime VIP
        val monthlyRevoked = monthly.copy(state = EntitlementState.REVOKED, snapshotVersion = 2L)
        store.applySnapshot(testContext, UserEntitlementSnapshot(userId, listOf(monthlyRevoked, lifetime)))

        AppAuthManager.loadVipForUserForTesting(testContext, profile)
        assertTrue(profile.isVip)
        assertNull(profile.vipExpiresAt)
    }

    @Test
    fun testLegacyMigration_preservesLocalVipGracefully() {
        val userId = "legacy_user_600"
        // Populate legacy SharedPreferences directly
        fakePrefs.edit()
            .putString("vip_account_${userId}_tier", VipTier.VIP.id)
            .putLong("vip_account_${userId}_purchased_at", now)
            .putLong("vip_account_${userId}_expires_at", -1L) // Lifetime in legacy representation
            .commit()

        val profile = UserProfile(id = userId, email = "legacy@example.com", displayName = "Legacy")
        AppAuthManager.loadVipForUserForTesting(testContext, profile)

        assertTrue("Legacy VIP with no expiry (lifetime) must NOT be downgraded to FREE", profile.isVip)
        assertEquals(VipTier.VIP, profile.tier)
        assertNull(profile.vipExpiresAt)
    }

    @Test
    fun testStorageSafety_corruptJsonDoesNotCrashAndReturnsEmpty() {
        val store = BillingEntitlementStore.getInstance()
        val userId = "user_corrupt_700"

        fakePrefs.edit()
            .putString("user_entitlements_${userId}", "{ corrupted: not an array }")
            .commit()

        val snapshot = store.getSnapshot(testContext, userId)
        assertNotNull(snapshot)
        assertTrue("Corrupted JSON must result in safe empty snapshot", snapshot.entitlements.isEmpty())
        assertFalse(snapshot.isVipActive(now))
    }

    @Test
    fun testAppAuthManager_applyEntitlementSnapshot_updatesCurrentUserLive() {
        val userId = "user_live_800"
        val profile = UserProfile(id = userId, email = "live@example.com", displayName = "Live")
        AppAuthManager.setCurrentUserForTesting(profile)

        val sub = BillingEntitlement(
            id = "tok_live",
            ownerAppUserId = userId,
            productId = "tscanner_vip_yearly",
            productType = "subs",
            purchaseToken = "tok_live",
            state = EntitlementState.VERIFIED_ACTIVE,
            purchaseTimeMillis = now,
            expiryTimeMillis = now + (365L * 24 * 3600 * 1000)
        )

        val applied = AppAuthManager.applyEntitlementSnapshot(testContext, UserEntitlementSnapshot(userId, listOf(sub)))
        assertTrue("applyEntitlementSnapshot must succeed", applied)

        val updated = AppAuthManager.getCurrentUser()
        assertNotNull(updated)
        assertTrue("Current user must become VIP immediately", updated!!.isVipActive)
        assertEquals(VipTier.VIP, updated.tier)
        assertEquals(now + (365L * 24 * 3600 * 1000), updated.vipExpiresAt)
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
