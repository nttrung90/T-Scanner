package com.tscanner.app

import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.tscanner.app.utils.GoogleCredentialRequestFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GoogleCredentialRequestFactoryTest {

    private val testClientId = "284912111014-test.apps.googleusercontent.com"

    @Test
    fun testCreateExplicitSignInRequest_hasExactlyOneCredentialOption() {
        val request = GoogleCredentialRequestFactory.createExplicitSignInRequest(testClientId)

        assertNotNull("Request must not be null", request)
        assertEquals("Explicit-button request must contain exactly 1 credential option", 1, request.credentialOptions.size)
    }

    @Test
    fun testCreateExplicitSignInRequest_isGetSignInWithGoogleOption() {
        val request = GoogleCredentialRequestFactory.createExplicitSignInRequest(testClientId)
        val option = request.credentialOptions[0]

        assertTrue(
            "Option must be GetSignInWithGoogleOption",
            option is GetSignInWithGoogleOption
        )
        val signInWithGoogleOption = option as GetSignInWithGoogleOption
        assertEquals(testClientId, signInWithGoogleOption.serverClientId)
    }

    @Test
    fun testCreateExplicitSignInRequest_doesNotContainGetGoogleIdOption() {
        val request = GoogleCredentialRequestFactory.createExplicitSignInRequest(testClientId)

        val hasGoogleIdOption = request.credentialOptions.any { it is GetGoogleIdOption }
        assertFalse(
            "Explicit-button request MUST NOT combine GetGoogleIdOption with GetSignInWithGoogleOption",
            hasGoogleIdOption
        )
    }

    @Test
    fun testCreateExplicitSignInRequest_withNonceAndFilter() {
        val request = GoogleCredentialRequestFactory.createExplicitSignInRequest(
            serverClientId = testClientId,
            nonce = "secure_random_nonce_123",
            hostedDomainFilter = "example.com"
        )

        assertEquals(1, request.credentialOptions.size)
        val option = request.credentialOptions[0] as GetSignInWithGoogleOption
        assertEquals("secure_random_nonce_123", option.nonce)
        assertEquals("example.com", option.hostedDomainFilter)
    }
}
