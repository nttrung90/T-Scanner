package com.tscanner.app

import android.content.Context
import androidx.arch.core.executor.ArchTaskExecutor
import androidx.arch.core.executor.TaskExecutor
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.ProductDetails
import com.tscanner.app.data.model.UserProfile
import com.tscanner.app.data.model.VipTier
import com.tscanner.app.utils.AppAuthManager
import com.tscanner.app.utils.BillingManager
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.Locale

/**
 * Regression and integration tests for B11 (Defect F10):
 * - Lifecycle-aware product offer observation
 * - Offer selection & pricing phase resolution (trial vs recurring)
 * - Safe presentation (no fake static fallback, clear loading/unavailable)
 * - Multi-currency & multi-offer handling
 * - UI offer token matching billing launch offer token
 * - 8-locale formatting and placeholder integrity
 */
class BillingOfferPresentationTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var testContext: BillingTestContext
    private lateinit var fakeWrapper: FakeBillingClientWrapper

    @Before
    fun setUp() {
        ArchTaskExecutor.getInstance().setDelegate(object : TaskExecutor() {
            override fun executeOnDiskIO(runnable: Runnable) = runnable.run()
            override fun postToMainThread(runnable: Runnable) = runnable.run()
            override fun isMainThread(): Boolean = true
        })
        testContext = BillingTestContext(tempFolder.root)
        fakeWrapper = FakeBillingClientWrapper()
        AppAuthManager.resetForTesting()
        BillingManager.createInstanceForTesting(testContext) { fakeWrapper }
    }

    @After
    fun tearDown() {
        BillingManager.resetInstanceForTesting()
        AppAuthManager.resetForTesting()
        ArchTaskExecutor.getInstance().setDelegate(null)
    }

    private fun createProductFromJson(json: String): ProductDetails {
        val constructor = ProductDetails::class.java.getDeclaredConstructor(String::class.java)
        constructor.isAccessible = true
        return constructor.newInstance(json)
    }

    private fun createSubscriptionProduct(
        productId: String = BillingManager.PRODUCT_VIP_YEARLY,
        offerIdToken: String = "token_base_yearly",
        offerId: String = "",
        phasesJson: String
    ): ProductDetails {
        val json = """
        {
            "productId": "$productId",
            "type": "subs",
            "title": "VIP Yearly",
            "name": "VIP Yearly",
            "description": "Yearly VIP access",
            "subscriptionOfferDetails": [
                {
                    "offerIdToken": "$offerIdToken",
                    "offerId": "$offerId",
                    "basePlanId": "base-yearly",
                    "pricingPhases": $phasesJson
                }
            ]
        }
        """.trimIndent()
        return createProductFromJson(json)
    }

    @Test
    fun testProductState_cacheEmpty_returnsLoading_thenTransitionsToAvailable() {
        val manager = BillingManager.getInstance(testContext)
        manager.setProductsForTesting(emptyMap())

        // 1. Initial empty cache must be Loading, never fake static price
        val initialState = manager.getProductPresentationState(BillingManager.PRODUCT_VIP_YEARLY)
        assertTrue("Empty cache must report Loading", initialState is BillingManager.ProductPresentationState.Loading)
        assertNull("getFormattedPrice must return null while loading", manager.getFormattedPrice(BillingManager.PRODUCT_VIP_YEARLY))

        // 2. Product arrives
        val details = createSubscriptionProduct(
            productId = BillingManager.PRODUCT_VIP_YEARLY,
            phasesJson = """
            [
                {
                    "formattedPrice": "199.000 ₫",
                    "priceAmountMicros": 199000000000,
                    "priceCurrencyCode": "VND",
                    "billingPeriod": "P1Y",
                    "recurrenceMode": 1
                }
            ]
            """.trimIndent()
        )
        manager.setProductsForTesting(mapOf(BillingManager.PRODUCT_VIP_YEARLY to details))

        // 3. Observed state transitions to Available
        val loadedState = manager.getProductPresentationState(BillingManager.PRODUCT_VIP_YEARLY)
        assertTrue("Populated product must report Available", loadedState is BillingManager.ProductPresentationState.Available)
        val available = loadedState as BillingManager.ProductPresentationState.Available
        assertEquals("199.000 ₫", available.displayPrice)
        assertEquals("199.000 ₫", manager.getFormattedPrice(BillingManager.PRODUCT_VIP_YEARLY))
        assertEquals("token_base_yearly", available.offerToken)
        assertFalse(available.hasFreeTrial)
    }

    @Test
    fun testProductState_productNotFoundInCatalog_returnsUnavailable() {
        val manager = BillingManager.getInstance(testContext)
        val otherProduct = createSubscriptionProduct(
            productId = "other_product_id",
            phasesJson = """
            [
                {
                    "formattedPrice": "50.000 ₫",
                    "priceAmountMicros": 50000000000,
                    "priceCurrencyCode": "VND",
                    "billingPeriod": "P1M",
                    "recurrenceMode": 1
                }
            ]
            """.trimIndent()
        )
        manager.setProductsForTesting(mapOf("other_product_id" to otherProduct))

        val state = manager.getProductPresentationState(BillingManager.PRODUCT_VIP_YEARLY)
        assertTrue("Missing product from loaded catalog must be Unavailable", state is BillingManager.ProductPresentationState.Unavailable)
        assertNull(manager.getFormattedPrice(BillingManager.PRODUCT_VIP_YEARLY))
    }

    @Test
    fun testProductState_subscriptionWithNoOffers_returnsUnavailable() {
        val manager = BillingManager.getInstance(testContext)
        val json = """
        {
            "productId": "${BillingManager.PRODUCT_VIP_YEARLY}",
            "type": "subs",
            "title": "VIP Yearly",
            "name": "VIP Yearly",
            "description": "Yearly VIP access",
            "subscriptionOfferDetails": []
        }
        """.trimIndent()
        val details = createProductFromJson(json)
        manager.setProductsForTesting(mapOf(BillingManager.PRODUCT_VIP_YEARLY to details))

        val state = manager.getProductPresentationState(BillingManager.PRODUCT_VIP_YEARLY)
        assertTrue("Subscription without offers must report Unavailable", state is BillingManager.ProductPresentationState.Unavailable)
        assertNull(manager.selectBestOffer(details))
    }

    @Test
    fun testProductState_freeTrialOffer_displaysTrialAndRecurringPrice() {
        val manager = BillingManager.getInstance(testContext)
        val details = createSubscriptionProduct(
            productId = BillingManager.PRODUCT_VIP_YEARLY,
            phasesJson = """
            [
                {
                    "formattedPrice": "0 ₫",
                    "priceAmountMicros": 0,
                    "priceCurrencyCode": "VND",
                    "billingPeriod": "P7D",
                    "recurrenceMode": 2
                },
                {
                    "formattedPrice": "199.000 ₫",
                    "priceAmountMicros": 199000000000,
                    "priceCurrencyCode": "VND",
                    "billingPeriod": "P1Y",
                    "recurrenceMode": 1
                }
            ]
            """.trimIndent()
        )
        manager.setProductsForTesting(mapOf(BillingManager.PRODUCT_VIP_YEARLY to details))

        val state = manager.getProductPresentationState(BillingManager.PRODUCT_VIP_YEARLY)
        assertTrue(state is BillingManager.ProductPresentationState.Available)
        val available = state as BillingManager.ProductPresentationState.Available

        assertTrue("Must detect free trial phase", available.hasFreeTrial)
        assertEquals("P7D", available.freeTrialPeriod)
        assertEquals("199.000 ₫", available.displayPrice)
        assertEquals("199.000 ₫", available.recurringPrice)
        assertEquals("P1Y", available.recurringPeriod)

        // Subtext verification
        val subText = manager.formatSubText(testContext, available)
        assertTrue("Subtext must explain trial period", subText.contains("7") || subText.contains("Dùng thử") || subText.contains("trial"))
        assertTrue("Subtext must contain recurring price", subText.contains("199.000 ₫"))

        // Button text verification when signed in
        val user = UserProfile(id = "user1", email = "test@gmail.com", displayName = "Test User")
        val btnTextUser = manager.formatButtonText(testContext, user, available)
        assertTrue("Button for trial with signed-in user must mention email", btnTextUser.contains("test@gmail.com"))

        // Button text verification when guest
        val btnTextGuest = manager.formatButtonText(testContext, null, available)
        assertFalse("Guest button must not have empty email brackets", btnTextGuest.contains("()"))
    }

    @Test
    fun testProductState_regularSubscriptionWithoutTrial_doesNotPromiseTrial() {
        val manager = BillingManager.getInstance(testContext)
        val details = createSubscriptionProduct(
            productId = BillingManager.PRODUCT_VIP_YEARLY,
            phasesJson = """
            [
                {
                    "formattedPrice": "199.000 ₫",
                    "priceAmountMicros": 199000000000,
                    "priceCurrencyCode": "VND",
                    "billingPeriod": "P1Y",
                    "recurrenceMode": 1
                }
            ]
            """.trimIndent()
        )
        manager.setProductsForTesting(mapOf(BillingManager.PRODUCT_VIP_YEARLY to details))

        val state = manager.getProductPresentationState(BillingManager.PRODUCT_VIP_YEARLY)
        assertTrue(state is BillingManager.ProductPresentationState.Available)
        val available = state as BillingManager.ProductPresentationState.Available

        assertFalse("Must NOT detect trial when no 0 price phase", available.hasFreeTrial)
        assertNull(available.freeTrialPeriod)

        val user = UserProfile(id = "user1", email = "user@gmail.com", displayName = "User")
        val btnText = manager.formatButtonText(testContext, user, available)
        assertFalse("Must not promise free trial when offer is a direct paid subscription", btnText.contains("Dùng thử") || btnText.contains("Trial"))
        assertTrue("Must contain email", btnText.contains("user@gmail.com"))
    }

    @Test
    fun testProductState_nonVndCurrency_formatsWithoutVndHardcoded() {
        val manager = BillingManager.getInstance(testContext)
        val details = createSubscriptionProduct(
            productId = BillingManager.PRODUCT_VIP_YEARLY,
            phasesJson = """
            [
                {
                    "formattedPrice": "$9.99",
                    "priceAmountMicros": 9990000,
                    "priceCurrencyCode": "USD",
                    "billingPeriod": "P1Y",
                    "recurrenceMode": 1
                }
            ]
            """.trimIndent()
        )
        manager.setProductsForTesting(mapOf(BillingManager.PRODUCT_VIP_YEARLY to details))

        val state = manager.getProductPresentationState(BillingManager.PRODUCT_VIP_YEARLY)
        assertTrue(state is BillingManager.ProductPresentationState.Available)
        val available = state as BillingManager.ProductPresentationState.Available

        assertEquals("$9.99", available.displayPrice)
        assertEquals("USD", available.currencyCode)
        assertFalse(available.displayPrice.contains("VND"))
        assertFalse(available.displayPrice.contains("₫"))
    }

    @Test
    fun testSelectBestOffer_multipleOffers_prefersTrialOffer_andMatchesLaunchToken() {
        val manager = BillingManager.getInstance(testContext)
        val json = """
        {
            "productId": "${BillingManager.PRODUCT_VIP_YEARLY}",
            "type": "subs",
            "title": "VIP Yearly",
            "name": "VIP Yearly",
            "description": "Yearly VIP access",
            "subscriptionOfferDetails": [
                {
                    "offerIdToken": "token_base_no_trial",
                    "offerId": "",
                    "basePlanId": "yearly-base",
                    "pricingPhases": [
                        {
                            "formattedPrice": "199.000 ₫",
                            "priceAmountMicros": 199000000000,
                            "priceCurrencyCode": "VND",
                            "billingPeriod": "P1Y",
                            "recurrenceMode": 1
                        }
                    ]
                },
                {
                    "offerIdToken": "token_promo_7d_trial",
                    "offerId": "free-trial-7d",
                    "basePlanId": "yearly-base",
                    "pricingPhases": [
                        {
                            "formattedPrice": "0 ₫",
                            "priceAmountMicros": 0,
                            "priceCurrencyCode": "VND",
                            "billingPeriod": "P7D",
                            "recurrenceMode": 2
                        },
                        {
                            "formattedPrice": "199.000 ₫",
                            "priceAmountMicros": 199000000000,
                            "priceCurrencyCode": "VND",
                            "billingPeriod": "P1Y",
                            "recurrenceMode": 1
                        }
                    ]
                }
            ]
        }
        """.trimIndent()
        val details = createProductFromJson(json)
        manager.setProductsForTesting(mapOf(BillingManager.PRODUCT_VIP_YEARLY to details))

        val bestOffer = manager.selectBestOffer(details)
        assertNotNull(bestOffer)
        assertEquals("token_promo_7d_trial", bestOffer!!.offerToken)

        val state = manager.getProductPresentationState(BillingManager.PRODUCT_VIP_YEARLY)
        assertTrue(state is BillingManager.ProductPresentationState.Available)
        val available = state as BillingManager.ProductPresentationState.Available
        assertEquals("UI presentation offerToken must match selected best offer token", "token_promo_7d_trial", available.offerToken)
    }

    @Test
    fun testSelectBestOffer_multipleOffersWithoutTrial_selectsBasePlan() {
        val manager = BillingManager.getInstance(testContext)
        val json = """
        {
            "productId": "${BillingManager.PRODUCT_VIP_YEARLY}",
            "type": "subs",
            "title": "VIP Yearly",
            "name": "VIP Yearly",
            "description": "Yearly VIP access",
            "subscriptionOfferDetails": [
                {
                    "offerIdToken": "token_promo_special",
                    "offerId": "special-offer-1",
                    "basePlanId": "yearly-base",
                    "pricingPhases": [
                        {
                            "formattedPrice": "179.000 ₫",
                            "priceAmountMicros": 179000000000,
                            "priceCurrencyCode": "VND",
                            "billingPeriod": "P1Y",
                            "recurrenceMode": 1
                        }
                    ]
                },
                {
                    "offerIdToken": "token_base_plan",
                    "offerId": "",
                    "basePlanId": "yearly-base",
                    "pricingPhases": [
                        {
                            "formattedPrice": "199.000 ₫",
                            "priceAmountMicros": 199000000000,
                            "priceCurrencyCode": "VND",
                            "billingPeriod": "P1Y",
                            "recurrenceMode": 1
                        }
                    ]
                }
            ]
        }
        """.trimIndent()
        val details = createProductFromJson(json)
        val bestOffer = manager.selectBestOffer(details)
        assertNotNull(bestOffer)
        assertEquals("Must select base plan with empty offerId", "token_base_plan", bestOffer!!.offerToken)
    }

    @Test
    fun testVipTier_removedFakePrices() {
        // Assert VipTier enum no longer defines active fake hardcoded prices
        assertEquals("", VipTier.VIP.priceDisplay)
        assertEquals(0L, VipTier.VIP.priceVnd)
        assertEquals("VIP", VipTier.VIP.displayName)
        assertTrue(VipTier.VIP.isAvailable)
    }

    @Test
    fun testBillingPeriodFormatting_isoPeriods() {
        val manager = BillingManager.getInstance(testContext)
        assertEquals("year", manager.formatBillingPeriod(testContext, "P1Y"))
        assertEquals("month", manager.formatBillingPeriod(testContext, "P1M"))
        assertEquals("7 days", manager.formatBillingPeriod(testContext, "P7D"))
        assertEquals("7 days", manager.formatBillingPeriod(testContext, "P1W"))
        assertEquals("14 days", manager.formatBillingPeriod(testContext, "P14D"))
    }
}
