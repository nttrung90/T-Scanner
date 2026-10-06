package com.tscanner.app

import com.tscanner.app.utils.BillingManager
import com.tscanner.app.utils.billing.BillingEntitlement
import com.tscanner.app.utils.billing.EntitlementSource
import com.tscanner.app.utils.billing.EntitlementState
import com.tscanner.app.utils.billing.PlayPurchaseVerifier
import com.tscanner.app.utils.billing.RejectionReason
import com.tscanner.app.utils.billing.VerificationRequest
import com.tscanner.app.utils.billing.VerificationResult
import com.tscanner.app.utils.billing.VerifierHttpResponse
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

class PlayPurchaseVerifierHttpTest {

    private val VALID_TEST_JWT = "e30.eyJzdWIiOiJ1c2VyLTEyMyIsImV4cCI6NDEwMjQ0NDgwMH0.synthetic"

    @Test
    fun testUnconfiguredVerifier_returnsMissingBackendGate() = runBlocking {
        val verifier = PlayPurchaseVerifier(backendUrl = null, allowLocalFallback = false)
        val request = VerificationRequest(
            ownerAppUserId = "user-1",
            productId = BillingManager.PRODUCT_VIP_YEARLY,
            productType = "subs",
            purchaseToken = "tok-gate-test"
        )
        val result = verifier.verifyPurchase(request)
        assertTrue("Verifier without URL or fallback must return MissingBackendGate", result is VerificationResult.MissingBackendGate)
    }

    @Test
    fun testRemoteVerifier_sendsAuthAndCorrectJsonPayload() = runBlocking {
        var capturedEndpoint = ""
        var capturedAuthHeader: String? = null
        var capturedBodyJson: JSONObject? = null

        val verifier = PlayPurchaseVerifier(
            backendUrl = "https://backend.example.test",
            tokenProvider = { VALID_TEST_JWT },
            allowLocalFallback = false,
            httpTransport = { url, _, headers, body ->
                capturedEndpoint = url
                capturedAuthHeader = headers["Authorization"]
                capturedBodyJson = if (body != null) JSONObject(body) else null

                val successBody = JSONObject().apply {
                    put("status", "SUCCESS")
                    put("entitlement", JSONObject().apply {
                        put("id", "tok-sub-1")
                        put("ownerAppUserId", "user-123")
                        put("productId", BillingManager.PRODUCT_VIP_YEARLY)
                        put("productType", "subs")
                        put("purchaseToken", "tok-sub-1")
                        put("orderId", "GPA.1234-5678")
                        put("source", "GOOGLE_PLAY_SUBSCRIPTION")
                        put("state", "VERIFIED_ACTIVE")
                        put("purchaseTimeMillis", 1700000000000L)
                        put("expiryTimeMillis", 1731536000000L)
                        put("autoRenewing", true)
                        put("verifiedAtMillis", 1700000001000L)
                        put("snapshotVersion", 2L)
                    })
                }.toString()
                VerifierHttpResponse(200, successBody)
            }
        )

        val request = VerificationRequest(
            ownerAppUserId = "user-123",
            productId = BillingManager.PRODUCT_VIP_YEARLY,
            productType = "subs",
            purchaseToken = "tok-sub-1",
            orderId = "GPA.1234-5678",
            obfuscatedAccountId = "hash-123",
            clientPurchaseTimeMillis = 1700000000000L
        )

        val result = verifier.verifyPurchase(request)

        assertEquals("https://backend.example.test/api/v1/billing/verify", capturedEndpoint)
        assertEquals("Bearer $VALID_TEST_JWT", capturedAuthHeader)
        assertNotNull(capturedBodyJson)
        assertEquals("user-123", capturedBodyJson!!.getString("ownerAppUserId"))
        assertEquals(BillingManager.PRODUCT_VIP_YEARLY, capturedBodyJson!!.getString("productId"))
        assertEquals("subs", capturedBodyJson!!.getString("productType"))
        assertEquals("tok-sub-1", capturedBodyJson!!.getString("purchaseToken"))
        assertEquals("GPA.1234-5678", capturedBodyJson!!.getString("orderId"))
        assertEquals("hash-123", capturedBodyJson!!.getString("obfuscatedAccountId"))
        assertEquals("com.tscanner.app", capturedBodyJson!!.getString("packageName"))

        assertTrue("Verification must succeed", result is VerificationResult.Success)
        val ent = (result as VerificationResult.Success).entitlement
        assertEquals("user-123", ent.ownerAppUserId)
        assertEquals(BillingManager.PRODUCT_VIP_YEARLY, ent.productId)
        assertEquals(EntitlementState.VERIFIED_ACTIVE, ent.state)
        assertEquals(1731536000000L, ent.expiryTimeMillis)
        assertEquals(true, ent.autoRenewing)
        assertEquals(2L, ent.snapshotVersion)
    }

    @Test
    fun testRemoteVerifier_parsesLifetimeInAppProductWithNullExpiry() = runBlocking {
        val verifier = PlayPurchaseVerifier(
            backendUrl = "https://backend.example.test",
            allowLocalFallback = false,
            httpTransport = { _, _, _, _ ->
                val body = JSONObject().apply {
                    put("status", "SUCCESS")
                    put("entitlement", JSONObject().apply {
                        put("id", "tok-inapp-1")
                        put("ownerAppUserId", "user-life")
                        put("productId", BillingManager.PRODUCT_VIP_LIFETIME)
                        put("productType", "inapp")
                        put("purchaseToken", "tok-inapp-1")
                        put("source", "GOOGLE_PLAY_INAPP")
                        put("state", "VERIFIED_ACTIVE")
                        put("purchaseTimeMillis", 1700000000000L)
                        put("expiryTimeMillis", JSONObject.NULL)
                        put("autoRenewing", false)
                        put("verifiedAtMillis", 1700000001000L)
                        put("snapshotVersion", 1L)
                    })
                }.toString()
                VerifierHttpResponse(200, body)
            }
        )

        val request = VerificationRequest(
            ownerAppUserId = "user-life",
            productId = BillingManager.PRODUCT_VIP_LIFETIME,
            productType = "inapp",
            purchaseToken = "tok-inapp-1"
        )

        val result = verifier.verifyPurchase(request)
        assertTrue(result is VerificationResult.Success)
        val ent = (result as VerificationResult.Success).entitlement
        assertEquals(BillingManager.PRODUCT_VIP_LIFETIME, ent.productId)
        assertEquals(EntitlementSource.GOOGLE_PLAY_INAPP, ent.source)
        assertNull("Lifetime product must have null expiryTimeMillis", ent.expiryTimeMillis)
    }

    @Test
    fun testRemoteVerifier_mapsRejectedOwnershipConflict() = runBlocking {
        val verifier = PlayPurchaseVerifier(
            backendUrl = "https://backend.example.test",
            allowLocalFallback = false,
            httpTransport = { _, _, _, _ ->
                val body = JSONObject().apply {
                    put("status", "REJECTED")
                    put("reason", "OWNERSHIP_CONFLICT")
                    put("message", "This purchase receipt is permanently linked to another user account.")
                }.toString()
                VerifierHttpResponse(400, body)
            }
        )

        val result = verifier.verifyPurchase(
            VerificationRequest(
                ownerAppUserId = "user-charlie",
                productId = BillingManager.PRODUCT_VIP_YEARLY,
                productType = "subs",
                purchaseToken = "tok-conflict"
            )
        )

        assertTrue(result is VerificationResult.Rejected)
        val rejected = result as VerificationResult.Rejected
        assertEquals(RejectionReason.OWNERSHIP_CONFLICT, rejected.reason)
    }

    @Test
    fun testRemoteVerifier_mapsPendingPaymentState() = runBlocking {
        val verifier = PlayPurchaseVerifier(
            backendUrl = "https://backend.example.test",
            allowLocalFallback = false,
            httpTransport = { _, _, _, _ ->
                val body = JSONObject().apply {
                    put("status", "PENDING")
                    put("message", "Payment is currently pending.")
                }.toString()
                VerifierHttpResponse(200, body)
            }
        )

        val result = verifier.verifyPurchase(
            VerificationRequest(
                ownerAppUserId = "user-pending",
                productId = BillingManager.PRODUCT_VIP_YEARLY,
                productType = "subs",
                purchaseToken = "tok-pending"
            )
        )

        assertTrue(result is VerificationResult.Pending)
        assertEquals("tok-pending", (result as VerificationResult.Pending).purchaseToken)
    }

    @Test
    fun testRemoteVerifier_mapsHttp503AndTimeoutToTransientError() = runBlocking {
        // Case 1: HTTP 503
        val verifier503 = PlayPurchaseVerifier(
            backendUrl = "https://backend.example.test",
            allowLocalFallback = false,
            httpTransport = { _, _, _, _ ->
                VerifierHttpResponse(503, "Service Unavailable")
            }
        )
        val res503 = verifier503.verifyPurchase(
            VerificationRequest("u", BillingManager.PRODUCT_VIP_YEARLY, "subs", "tok-503")
        )
        assertTrue("HTTP 503 must be TransientError", res503 is VerificationResult.TransientError)

        // Case 2: SocketTimeoutException
        val verifierTimeout = PlayPurchaseVerifier(
            backendUrl = "https://backend.example.test",
            allowLocalFallback = false,
            httpTransport = { _, _, _, _ ->
                throw SocketTimeoutException("Connection timed out")
            }
        )
        val resTimeout = verifierTimeout.verifyPurchase(
            VerificationRequest("u", BillingManager.PRODUCT_VIP_YEARLY, "subs", "tok-timeout")
        )
        assertTrue("Timeout must be TransientError", resTimeout is VerificationResult.TransientError)

        // Case 3: Network IOException
        val verifierIo = PlayPurchaseVerifier(
            backendUrl = "https://backend.example.test",
            allowLocalFallback = false,
            httpTransport = { _, _, _, _ ->
                throw IOException("Network unreachable")
            }
        )
        val resIo = verifierIo.verifyPurchase(
            VerificationRequest("u", BillingManager.PRODUCT_VIP_YEARLY, "subs", "tok-io")
        )
        assertTrue("IOException must be TransientError", resIo is VerificationResult.TransientError)
    }

    @Test
    fun testRemoteVerifier_mapsMalformedJsonToTransientError() = runBlocking {
        val verifier = PlayPurchaseVerifier(
            backendUrl = "https://backend.example.test",
            allowLocalFallback = false,
            httpTransport = { _, _, _, _ ->
                VerifierHttpResponse(200, "<html><head><title>502 Bad Gateway</title></head></html>")
            }
        )
        val res = verifier.verifyPurchase(
            VerificationRequest("u", BillingManager.PRODUCT_VIP_YEARLY, "subs", "tok-malformed")
        )
        assertTrue("Malformed JSON must be TransientError", res is VerificationResult.TransientError)
    }

    @Test
    fun testSessionBoundToken_missingToken_rejectsWithReauth() = runBlocking {
        val verifier = PlayPurchaseVerifier(
            backendUrl = "https://backend.example.test",
            tokenProvider = { null },
            allowLocalFallback = false,
            httpTransport = { _, _, _, _ -> VerifierHttpResponse(200, "{}") }
        )
        val res = verifier.verifyPurchase(
            VerificationRequest("u", BillingManager.PRODUCT_VIP_YEARLY, "subs", "tok-session-test")
        )
        assertTrue("Missing token must require authentication", res is VerificationResult.AuthRequired)
    }

    @Test
    fun testSessionBoundToken_ownerMismatch_rejectsWithConflict() = runBlocking {
        val verifier = PlayPurchaseVerifier(
            backendUrl = "https://backend.example.test",
            tokenProvider = { "token-alice" },
            ownerProvider = { "usr_alice" },
            allowLocalFallback = false,
            httpTransport = { _, _, _, _ -> VerifierHttpResponse(200, "{}") }
        )
        val res = verifier.verifyPurchase(
            VerificationRequest("usr_bob", BillingManager.PRODUCT_VIP_YEARLY, "subs", "tok-bob-test")
        )
        assertTrue("Owner mismatch must reject with conflict", res is VerificationResult.Rejected)
        assertEquals(RejectionReason.OWNERSHIP_CONFLICT, (res as VerificationResult.Rejected).reason)
    }

    @Test
    fun testSessionBoundToken_sessionChangedDuringAcquisition_returnsTransientError() = runBlocking {
        var generation = 1L
        val verifier = PlayPurchaseVerifier(
            backendUrl = "https://backend.example.test",
            tokenProvider = {
                generation++ // User logged out/switched during token acquisition
                "token-after-switch"
            },
            sessionGenerationProvider = { generation },
            allowLocalFallback = false,
            httpTransport = { _, _, _, _ -> VerifierHttpResponse(200, "{}") }
        )
        val res = verifier.verifyPurchase(
            VerificationRequest("u", BillingManager.PRODUCT_VIP_YEARLY, "subs", "tok-switch-test")
        )
        assertTrue("Session change must abort with TransientError", res is VerificationResult.TransientError)
    }

    @Test
    fun testHttpsStrict_httpAndMalformedUrls_notConfigured() {
        org.junit.Assert.assertFalse(PlayPurchaseVerifier("http://insecure.test").isConfigured())
        org.junit.Assert.assertFalse(PlayPurchaseVerifier("http://localhost:8080").isConfigured())
        org.junit.Assert.assertFalse(PlayPurchaseVerifier("ftp://files.test").isConfigured())
        org.junit.Assert.assertFalse(PlayPurchaseVerifier("").isConfigured())
        org.junit.Assert.assertFalse(PlayPurchaseVerifier("   ").isConfigured())
        org.junit.Assert.assertTrue(PlayPurchaseVerifier("https://verifier.tscanner.app").isConfigured())
    }
}
