package com.tscanner.app.utils

import android.app.Activity
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential

/**
 * Interface abstracting Credential Manager client interactions for production and testing seams.
 */
interface GoogleCredentialClient {
    suspend fun getCredential(
        activity: Activity,
        request: GetCredentialRequest
    ): GoogleSignInAccountData?
}

/**
 * Default production implementation delegating to AndroidX Credential Manager.
 */
class DefaultGoogleCredentialClient : GoogleCredentialClient {
    override suspend fun getCredential(
        activity: Activity,
        request: GetCredentialRequest
    ): GoogleSignInAccountData? {
        val credentialManager = CredentialManager.create(activity)
        val result = credentialManager.getCredential(activity, request)
        val credential = result.credential

        if (credential is CustomCredential &&
            credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
        ) {
            return try {
                val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(credential.data)
                android.util.Log.i("CredentialClient", "[AuthLifecycle] stage=PARSE status=SUCCESS type=${credential.type}")
                GoogleSignInAccountData(
                    id = null,
                    email = googleIdTokenCredential.id,
                    displayName = googleIdTokenCredential.displayName
                        ?: googleIdTokenCredential.givenName
                        ?: "Google User",
                    givenName = googleIdTokenCredential.givenName,
                    familyName = googleIdTokenCredential.familyName,
                    photoUrl = googleIdTokenCredential.profilePictureUri?.toString(),
                    idToken = googleIdTokenCredential.idToken
                )
            } catch (e: Exception) {
                android.util.Log.w("CredentialClient", "[AuthLifecycle] stage=PARSE status=FAILURE type=${credential.type} error=${AppAuthManager.sanitizeForLog(e.message)}")
                null
            }
        } else {
            android.util.Log.w("CredentialClient", "[AuthLifecycle] stage=PARSE status=UNSUPPORTED_TYPE type=${credential.type}")
            return null
        }
    }
}

/**
 * Factory for creating modern AndroidX Credential Manager sign-in requests.
 *
 * Per Google Identity guidelines (and codelabs/sign-in-with-google-android):
 * Explicit interactive button flows must specify ONLY a single GetSignInWithGoogleOption.
 * They must NOT combine GetGoogleIdOption or other options into the same request.
 */
object GoogleCredentialRequestFactory {

    fun createExplicitSignInRequest(
        serverClientId: String,
        nonce: String? = null,
        hostedDomainFilter: String? = null
    ): GetCredentialRequest {
        val optionBuilder = GetSignInWithGoogleOption.Builder(serverClientId)
        if (!nonce.isNullOrBlank()) {
            optionBuilder.setNonce(nonce)
        }
        if (!hostedDomainFilter.isNullOrBlank()) {
            optionBuilder.setHostedDomainFilter(hostedDomainFilter)
        }
        val option = optionBuilder.build()

        return GetCredentialRequest.Builder()
            .addCredentialOption(option)
            .build()
    }
}
