package com.tscanner.app

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.tscanner.app.data.model.UserProfile
import com.tscanner.app.data.model.VipTier
import com.tscanner.app.utils.AppAuthManager
import com.tscanner.app.utils.WatermarkHelper
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class VipWatermarkPolicyTest {

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
    }

    @After
    fun tearDown() {
        AppAuthManager.resetForTesting()
        androidx.arch.core.executor.ArchTaskExecutor.getInstance().setDelegate(null)
    }

    @Test
    fun testFreeUser_alwaysHasWatermarkApplied() {
        // Non-VIP user wants watermark removed
        val applyWithRemovalRequest = WatermarkHelper.shouldApplyWatermark(userWantsRemoved = true, isVipActive = false)
        assertTrue("Watermark MUST be applied for free users even if UI toggle is true", applyWithRemovalRequest)

        // Non-VIP user wants watermark kept
        val applyWithoutRemovalRequest = WatermarkHelper.shouldApplyWatermark(userWantsRemoved = false, isVipActive = false)
        assertTrue("Watermark MUST be applied for free users", applyWithoutRemovalRequest)
    }

    @Test
    fun testActiveVipUser_respectsUserChoice() {
        // VIP user requests watermark removed
        val watermarkRemoved = WatermarkHelper.shouldApplyWatermark(userWantsRemoved = true, isVipActive = true)
        assertFalse("Watermark MUST NOT be applied when active VIP requests removal", watermarkRemoved)

        // VIP user chooses to keep watermark
        val watermarkKept = WatermarkHelper.shouldApplyWatermark(userWantsRemoved = false, isVipActive = true)
        assertTrue("Watermark MUST be applied when VIP user intentionally leaves it on", watermarkKept)
    }

    @Test
    fun testExpiredVip_enforcesWatermark() {
        val now = System.currentTimeMillis()
        val expiredUser = UserProfile(
            id = "user_exp",
            email = "exp@test.com",
            displayName = "Expired Tester",
            isVip = true,
            tier = VipTier.VIP,
            vipPurchasedAt = now - 100000L,
            vipExpiresAt = now - 1000L // Already expired!
        )

        assertFalse("isVipActive must be false when expiry timestamp has elapsed", expiredUser.isVipActive)

        // Policy check with expired entitlement
        val shouldApply = WatermarkHelper.shouldApplyWatermark(userWantsRemoved = true, isVipActive = expiredUser.isVipActive)
        assertTrue("Watermark must immediately be enforced when VIP subscription expires", shouldApply)
    }

    @Test
    fun testAppAuthManagerIntegration_dynamicallyReflectsEntitlement() {
        // 1. Initial guest state: watermark applied
        assertTrue("Default logged-out state requires watermark", WatermarkHelper.shouldApplyWatermark(userWantsRemoved = true))

        // 2. Active VIP login
        val activeUser = UserProfile(
            id = "user_vip_active",
            email = "vip@test.com",
            displayName = "Active VIP",
            isVip = true,
            tier = VipTier.VIP,
            vipExpiresAt = System.currentTimeMillis() + 86400000L
        )
        AppAuthManager.setVipForUserForTesting(testContext, "user_vip_active", VipTier.VIP, System.currentTimeMillis(), activeUser.vipExpiresAt)
        fakePrefs.edit().putString(
            "key_user_profile",
            """{"id":"user_vip_active","email":"vip@test.com","displayName":"Active VIP","isVip":true,"tier":"vip","vipExpiresAt":${activeUser.vipExpiresAt}}"""
        ).apply()
        AppAuthManager.init(testContext)

        assertTrue("User must be active VIP", AppAuthManager.isUserVip())
        assertFalse("Active VIP must remove watermark", WatermarkHelper.shouldApplyWatermark(userWantsRemoved = true))
        assertTrue("Active VIP can choose to keep watermark", WatermarkHelper.shouldApplyWatermark(userWantsRemoved = false))

        // 3. User session ends: immediately re-enforces watermark without restart
        AppAuthManager.resetForTesting()
        assertFalse("User is no longer VIP after session reset", AppAuthManager.isUserVip())
        assertTrue("Logged out user cannot bypass watermark", WatermarkHelper.shouldApplyWatermark(userWantsRemoved = true))
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
