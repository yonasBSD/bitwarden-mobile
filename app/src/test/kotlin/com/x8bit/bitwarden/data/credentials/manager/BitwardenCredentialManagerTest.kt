package com.x8bit.bitwarden.data.credentials.manager

import android.content.pm.Signature
import android.content.pm.SigningInfo
import android.net.Uri
import android.util.Base64
import androidx.core.graphics.drawable.IconCompat
import androidx.credentials.CreatePublicKeyCredentialRequest
import androidx.credentials.GetPublicKeyCredentialOption
import androidx.credentials.exceptions.GetCredentialUnknownException
import androidx.credentials.provider.BeginGetPublicKeyCredentialOption
import androidx.credentials.provider.CallingAppInfo
import androidx.credentials.provider.CredentialEntry
import androidx.credentials.provider.ProviderGetCredentialRequest
import androidx.credentials.provider.PublicKeyCredentialEntry
import com.bitwarden.core.data.repository.model.DataState
import com.bitwarden.core.data.util.asSuccess
import com.bitwarden.core.data.util.decodeFromStringOrNull
import com.bitwarden.data.datasource.disk.base.FakeDispatcherManager
import com.bitwarden.fido.ClientData
import com.bitwarden.fido.Origin
import com.bitwarden.fido.PublicKeyCredentialAuthenticatorAssertionResponse
import com.bitwarden.fido.UnverifiedAssetLink
import com.bitwarden.sdk.BitwardenException
import com.bitwarden.sdk.Fido2CredentialStore
import com.bitwarden.vault.CipherView
import com.x8bit.bitwarden.data.credentials.builder.CredentialEntryBuilder
import com.x8bit.bitwarden.data.credentials.model.Fido2AttestationResponse
import com.x8bit.bitwarden.data.credentials.model.Fido2CredentialAssertionResult
import com.x8bit.bitwarden.data.credentials.model.Fido2PublicKeyCredential
import com.x8bit.bitwarden.data.credentials.model.Fido2RegisterCredentialResult
import com.x8bit.bitwarden.data.credentials.model.GetCredentialsRequest
import com.x8bit.bitwarden.data.credentials.model.PasskeyAssertionOptions
import com.x8bit.bitwarden.data.credentials.model.PasskeyAttestationOptions
import com.x8bit.bitwarden.data.credentials.model.UserVerificationRequirement
import com.x8bit.bitwarden.data.platform.util.getAppSigningSignatureFingerprint
import com.x8bit.bitwarden.data.vault.datasource.sdk.VaultSdkSource
import com.x8bit.bitwarden.data.vault.datasource.sdk.model.AuthenticateFido2CredentialRequest
import com.x8bit.bitwarden.data.vault.datasource.sdk.model.RegisterFido2CredentialRequest
import com.x8bit.bitwarden.data.vault.datasource.sdk.model.createMockCipherView
import com.x8bit.bitwarden.data.vault.datasource.sdk.model.createMockFido2CredentialAutofillView
import com.x8bit.bitwarden.data.vault.datasource.sdk.model.createMockLoginView
import com.x8bit.bitwarden.data.vault.datasource.sdk.model.createMockPublicKeyAssertionResponse
import com.x8bit.bitwarden.data.vault.datasource.sdk.model.createMockPublicKeyAttestationResponse
import com.x8bit.bitwarden.data.vault.datasource.sdk.model.createMockSdkFido2CredentialList
import com.x8bit.bitwarden.data.vault.datasource.sdk.util.toAndroidFido2PublicKeyCredential
import com.x8bit.bitwarden.data.vault.repository.VaultRepository
import com.x8bit.bitwarden.data.vault.repository.model.DecryptFido2CredentialAutofillViewResult
import com.x8bit.bitwarden.ui.vault.feature.addedit.util.createMockPasskeyAssertionOptions
import com.x8bit.bitwarden.ui.vault.feature.addedit.util.createMockPasskeyAttestationOptions
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkConstructor
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.security.MessageDigest

@Suppress("LargeClass")
class BitwardenCredentialManagerTest {

    private lateinit var bitwardenCredentialManager: BitwardenCredentialManager

    private val mutableCipherStateFlow =
        MutableStateFlow<DataState<List<CipherView>>>(DataState.Loading)

    private val json = mockk<Json> {
        every {
            decodeFromStringOrNull<PasskeyAttestationOptions>(any())
        } returns createMockPasskeyAttestationOptions(
            number = 1,
            relyingPartyId = DEFAULT_HOST,
        )
        every {
            decodeFromStringOrNull<PasskeyAssertionOptions>(any())
        } returns createMockPasskeyAssertionOptions(number = 1)
        every {
            decodeFromStringOrNull<PasskeyAssertionOptions>(DEFAULT_FIDO2_AUTH_REQUEST_JSON)
        } returns createMockPasskeyAssertionOptions(number = 1)
    }
    private val mockSigningInfo = mockk<SigningInfo> {
        every { apkContentsSigners } returns arrayOf(Signature(DEFAULT_APP_SIGNATURE))
        every { hasMultipleSigners() } returns false
    }
    private val mockMessageDigest = mockk<MessageDigest> {
        every { digest(any()) } returns DEFAULT_APP_SIGNATURE.toByteArray()
    }
    val mockCallingAppInfo = mockk<CallingAppInfo> {
        every { isOriginPopulated() } returns true
        every { packageName } returns "com.x8bit.bitwarden"
        every { signingInfo } returns mockSigningInfo
    }
    val mockCreatePublicKeyCredentialRequest = mockk<CreatePublicKeyCredentialRequest> {
        every { requestJson } returns DEFAULT_FIDO2_CREATE_REQUEST_JSON
        every { clientDataHash } returns byteArrayOf()
    }
    val mockGetPublicKeyCredentialOption = mockk<GetPublicKeyCredentialOption> {
        every { requestJson } returns DEFAULT_FIDO2_AUTH_REQUEST_JSON
        every { clientDataHash } returns byteArrayOf()
    }
    val mockProviderGetCredentialRequest = mockk<ProviderGetCredentialRequest> {
        every { callingAppInfo } returns mockCallingAppInfo
        every { credentialOptions } returns listOf(mockGetPublicKeyCredentialOption)
    }
    private val mockVaultSdkSource = mockk<VaultSdkSource>()
    private val mockFido2CredentialStore = mockk<Fido2CredentialStore>()
    private val mockVaultRepository = mockk<VaultRepository> {
        every { ciphersStateFlow } returns mutableCipherStateFlow
    }
    private val mockCredentialEntryBuilder = mockk<CredentialEntryBuilder>()

    @BeforeEach
    fun setUp() {
        mockkStatic(
            MessageDigest::class,
            Base64::class,
        )
        every { MessageDigest.getInstance(any()) } returns mockMessageDigest
        every { Base64.encodeToString(any(), any()) } returns DEFAULT_APP_SIGNATURE

        bitwardenCredentialManager = BitwardenCredentialManagerImpl(
            vaultSdkSource = mockVaultSdkSource,
            fido2CredentialStore = mockFido2CredentialStore,
            json = json,
            dispatcherManager = FakeDispatcherManager(),
            vaultRepository = mockVaultRepository,
            credentialEntryBuilder = mockCredentialEntryBuilder,
        )
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic(
            Base64::class,
            IconCompat::class,
            MessageDigest::class,
            Uri::class,
        )
        unmockkStatic(
            PublicKeyCredentialAuthenticatorAssertionResponse::toAndroidFido2PublicKeyCredential,
            CallingAppInfo::getAppSigningSignatureFingerprint,
        )
        unmockkConstructor(PublicKeyCredentialEntry.Builder::class)
    }

    @Test
    fun `getPasskeyAttestationOptionsOrNull should return passkey options when deserialized`() =
        runTest {
            assertEquals(
                createMockPasskeyAttestationOptions(number = 1, relyingPartyId = DEFAULT_HOST),
                bitwardenCredentialManager.getPasskeyAttestationOptionsOrNull(
                    requestJson = DEFAULT_FIDO2_AUTH_REQUEST_JSON,
                ),
            )
        }

    @Suppress("MaxLineLength")
    @Test
    fun `getPasskeyAttestationOptionsOrNull should return null when decoding fails`() {
        every {
            json.decodeFromStringOrNull<PasskeyAttestationOptions>(any())
        } returns null

        assertNull(bitwardenCredentialManager.getPasskeyAttestationOptionsOrNull(requestJson = ""))
    }

    @Suppress("MaxLineLength")
    @Test
    fun `registerFido2Credential should construct correct RegisterFido2CredentialRequest when callingAppInfo origin is populated`() =
        runTest {
            mockkStatic(CallingAppInfo::getAppSigningSignatureFingerprint)

            val selectedCipherView = createMockCipherView(number = 1)
            val slot = slot<RegisterFido2CredentialRequest>()

            every {
                mockCallingAppInfo.getAppSigningSignatureFingerprint()
            } returns DEFAULT_APP_SIGNATURE.toByteArray()
            every { mockCreatePublicKeyCredentialRequest.origin } returns DEFAULT_WEB_ORIGIN.v1
            coEvery {
                mockVaultSdkSource.registerFido2Credential(
                    request = capture(slot),
                    fido2CredentialStore = any(),
                )
            } returns createMockPublicKeyAttestationResponse(number = 1).asSuccess()

            bitwardenCredentialManager.registerFido2Credential(
                userId = "mockUserId",
                createPublicKeyCredentialRequest = mockCreatePublicKeyCredentialRequest,
                selectedCipherView = selectedCipherView,
                callingAppInfo = mockCallingAppInfo,
            )

            verify { mockCallingAppInfo.getAppSigningSignatureFingerprint() }
            assertEquals(
                "mockUserId",
                slot.captured.userId,
            )
            assertEquals(
                DEFAULT_WEB_ORIGIN,
                slot.captured.origin,
            )
            assertEquals(
                """{"publicKey": $DEFAULT_FIDO2_CREATE_REQUEST_JSON}""",
                slot.captured.requestJson,
            )
            assertEquals(
                DEFAULT_APP_SIGNATURE,
                (slot.captured.clientData as ClientData.DefaultWithCustomHash)
                    .hash
                    .decodeToString(),
            )
            assertEquals(selectedCipherView, slot.captured.selectedCipherView)
            assertTrue(slot.captured.isUserVerificationSupported)
        }

    @Suppress("MaxLineLength")
    @Test
    fun `registerFido2Credential should construct correct RegisterFido2CredentialRequest when callingAppInfo origin is null`() =
        runTest {
            val mockCipherView = createMockCipherView(number = 1)
            val mockRegistrationResponse = createMockPublicKeyAttestationResponse(number = 1)
            val slot = slot<RegisterFido2CredentialRequest>()

            every { json.encodeToString<Fido2AttestationResponse>(any(), any()) } returns ""
            every { mockCallingAppInfo.isOriginPopulated() } returns false
            every { mockCreatePublicKeyCredentialRequest.origin } returns null
            coEvery {
                mockVaultSdkSource.registerFido2Credential(
                    request = capture(slot),
                    fido2CredentialStore = any(),
                )
            } returns mockRegistrationResponse.asSuccess()

            bitwardenCredentialManager.registerFido2Credential(
                userId = "mockUserId",
                createPublicKeyCredentialRequest = mockCreatePublicKeyCredentialRequest,
                selectedCipherView = mockCipherView,
                callingAppInfo = mockCallingAppInfo,
            )

            assertEquals(
                "mockUserId",
                slot.captured.userId,
            )
            assertEquals(
                DEFAULT_ANDROID_ORIGIN,
                slot.captured.origin,
            )
            assertEquals(
                """{"publicKey": ${DEFAULT_FIDO2_CREATE_REQUEST_JSON}}""",
                slot.captured.requestJson,
            )
            assertEquals(
                DEFAULT_PACKAGE_NAME,
                (slot.captured.clientData as ClientData.DefaultWithExtraData)
                    .androidPackageName,
            )
            assertEquals(mockCipherView, slot.captured.selectedCipherView)
            assertTrue(slot.captured.isUserVerificationSupported)
        }

    @Test
    fun `registerFido2Credential should wrap request in webauthn json object`() =
        runTest {
            val mockRegistrationResponse = createMockPublicKeyAttestationResponse(number = 1)

            every { Base64.encodeToString(any(), any()) } returns DEFAULT_APP_SIGNATURE
            every { json.encodeToString<Fido2AttestationResponse>(any(), any()) } returns ""
            every { mockCreatePublicKeyCredentialRequest.origin } returns DEFAULT_WEB_ORIGIN.v1
            val requestCaptureSlot = slot<RegisterFido2CredentialRequest>()
            coEvery {
                mockVaultSdkSource.registerFido2Credential(
                    request = capture(requestCaptureSlot),
                    fido2CredentialStore = any(),
                )
            } returns mockRegistrationResponse.asSuccess()

            bitwardenCredentialManager.registerFido2Credential(
                userId = "mockUserId",
                createPublicKeyCredentialRequest = mockCreatePublicKeyCredentialRequest,
                selectedCipherView = createMockCipherView(number = 1),
                callingAppInfo = mockCallingAppInfo,
            )

            assertEquals(
                """{"publicKey": ${mockCreatePublicKeyCredentialRequest.requestJson}}""",
                requestCaptureSlot.captured.requestJson,
            )
        }

    @Test
    fun `registerFido2Credential should register FIDO 2 credential to active user ID`() =
        runTest {

            val mockCipherView = createMockCipherView(1)
            val mockRegistrationResponse = createMockPublicKeyAttestationResponse(number = 1)

            every { Base64.encodeToString(any(), any()) } returns DEFAULT_APP_SIGNATURE
            every { json.encodeToString<Fido2AttestationResponse>(any(), any()) } returns ""
            every { mockCreatePublicKeyCredentialRequest.origin } returns DEFAULT_WEB_ORIGIN.v1
            val requestCaptureSlot = slot<RegisterFido2CredentialRequest>()
            coEvery {
                mockVaultSdkSource.registerFido2Credential(
                    request = capture(requestCaptureSlot),
                    fido2CredentialStore = any(),
                )
            } returns mockRegistrationResponse.asSuccess()

            bitwardenCredentialManager.registerFido2Credential(
                userId = "mockUserId",
                createPublicKeyCredentialRequest = mockCreatePublicKeyCredentialRequest,
                selectedCipherView = mockCipherView,
                callingAppInfo = mockCallingAppInfo,
            )

            assertEquals(
                "mockUserId",
                requestCaptureSlot.captured.userId,
            )
        }

    @Suppress("MaxLineLength")
    @Test
    fun `registerFido2Credential should return Error when getAppSigningSignatureFingerprint is null`() =
        runTest {
            val mockSigningInfo = mockk<SigningInfo> {
                every { hasMultipleSigners() } returns true
            }
            every { mockCallingAppInfo.signingInfo } returns mockSigningInfo
            val result = bitwardenCredentialManager.registerFido2Credential(
                userId = "mockUserId",
                createPublicKeyCredentialRequest = mockCreatePublicKeyCredentialRequest,
                selectedCipherView = createMockCipherView(number = 1),
                callingAppInfo = mockCallingAppInfo,
            )

            assertEquals(
                Fido2RegisterCredentialResult.Error.InvalidAppSignature,
                result,
            )
        }

    @Suppress("MaxLineLength")
    @Test
    fun `registerFido2Credential should return Error when getSignatureFingerprintAsHexString is null`() =
        runTest {
            val mockSigningInfo = mockk<SigningInfo> {
                every { hasMultipleSigners() } returns true
            }
            every { mockCallingAppInfo.signingInfo } returns mockSigningInfo

            val result = bitwardenCredentialManager.registerFido2Credential(
                userId = "mockUserId",
                createPublicKeyCredentialRequest = mockCreatePublicKeyCredentialRequest,
                selectedCipherView = createMockCipherView(number = 1),
                callingAppInfo = mockCallingAppInfo,
            )

            assertEquals(
                Fido2RegisterCredentialResult.Error.InvalidAppSignature,
                result,
            )
        }

    @Test
    fun `registerFido2Credential should return Error when deserialization fails`() =
        runTest {
            every { mockCallingAppInfo.signingInfo } returns mockSigningInfo
            val mockRegistrationResponse = createMockPublicKeyAttestationResponse(number = 1)

            every { Base64.encodeToString(any(), any()) } returns DEFAULT_APP_SIGNATURE
            every {
                json.encodeToString<Fido2AttestationResponse>(
                    any(),
                    any(),
                )
            } throws IllegalArgumentException()
            every { mockCreatePublicKeyCredentialRequest.origin } returns DEFAULT_WEB_ORIGIN.v1
            coEvery {
                mockVaultSdkSource.registerFido2Credential(
                    request = any(),
                    fido2CredentialStore = any(),
                )
            } returns mockRegistrationResponse.asSuccess()

            val result = bitwardenCredentialManager.registerFido2Credential(
                userId = "mockUserId",
                createPublicKeyCredentialRequest = mockCreatePublicKeyCredentialRequest,
                selectedCipherView = createMockCipherView(number = 1),
                callingAppInfo = mockCallingAppInfo,
            )

            assertEquals(
                Fido2RegisterCredentialResult.Error.InternalError,
                result,
            )
        }

    @Test
    fun `registerFido2Credential should return Error when origin is null`() = runTest {
        every { Base64.encodeToString(any(), any()) } returns DEFAULT_APP_SIGNATURE
        every {
            json.decodeFromStringOrNull<PasskeyAttestationOptions>(any())
        } returns null
        every { mockCallingAppInfo.isOriginPopulated() } returns false
        every { mockCreatePublicKeyCredentialRequest.origin } returns null

        val result = bitwardenCredentialManager.registerFido2Credential(
            userId = "mockUserId",
            createPublicKeyCredentialRequest = mockCreatePublicKeyCredentialRequest,
            selectedCipherView = createMockCipherView(number = 1),
            callingAppInfo = mockCallingAppInfo,
        )

        coVerify(exactly = 0) {
            mockVaultSdkSource.registerFido2Credential(
                request = any(),
                fido2CredentialStore = any(),
            )
        }

        assertEquals(
            Fido2RegisterCredentialResult.Error.MissingHostUrl,
            result,
        )
    }

    @Suppress("MaxLineLength")
    @Test
    fun `registerFido2Credential should return MissingHostUrl when calling app if privileged and origin is missing`() =
        runTest {
            every { mockCreatePublicKeyCredentialRequest.origin } returns null

            val result = bitwardenCredentialManager.registerFido2Credential(
                userId = "mockUserId",
                callingAppInfo = mockCallingAppInfo,
                createPublicKeyCredentialRequest = mockCreatePublicKeyCredentialRequest,
                selectedCipherView = createMockCipherView(number = 1),
            )

            assertEquals(
                Fido2RegisterCredentialResult.Error.MissingHostUrl,
                result,
            )
        }

    @Suppress("MaxLineLength")
    @Test
    fun `hasAuthenticationAttemptsRemaining returns true when authenticationAttempts is less than 5`() {
        assertTrue(bitwardenCredentialManager.hasAuthenticationAttemptsRemaining())
    }

    @Suppress("MaxLineLength")
    @Test
    fun `hasAuthenticationAttemptsRemaining returns false when authenticationAttempts is greater than 5`() {
        bitwardenCredentialManager.authenticationAttempts = 6
        assertFalse(bitwardenCredentialManager.hasAuthenticationAttemptsRemaining())
    }

    @Suppress("MaxLineLength")
    @Test
    fun `authenticateFido2Credential should construct ClientData DefaultWithCustomHash when clientDataHash is not null`() =
        runTest {
            every { Base64.encodeToString(any(), any()) } returns ""
            val mockCipherView = createMockCipherView(number = 1)
            val requestCaptureSlot = slot<AuthenticateFido2CredentialRequest>()
            val mockSdkResponse =
                mockk<PublicKeyCredentialAuthenticatorAssertionResponse>(relaxed = true)
            every { mockCallingAppInfo.isOriginPopulated() } returns true
            every { mockGetPublicKeyCredentialOption.clientDataHash } returns byteArrayOf(1, 2, 3)
            coEvery {
                mockVaultSdkSource.authenticateFido2Credential(
                    request = any(),
                    fido2CredentialStore = any(),
                )
            } returns mockSdkResponse.asSuccess()

            bitwardenCredentialManager.authenticateFido2Credential(
                userId = "activeUserId",
                request = mockGetPublicKeyCredentialOption,
                selectedCipherView = mockCipherView,
                callingAppInfo = mockCallingAppInfo,
                origin = DEFAULT_WEB_ORIGIN.v1,
            )

            coVerify {
                mockVaultSdkSource.authenticateFido2Credential(
                    request = capture(requestCaptureSlot),
                    fido2CredentialStore = any(),
                )
            }
            assertEquals(
                AuthenticateFido2CredentialRequest(
                    userId = "activeUserId",
                    origin = DEFAULT_WEB_ORIGIN,
                    requestJson = """{"publicKey": ${mockGetPublicKeyCredentialOption.requestJson}}""",
                    clientData = ClientData.DefaultWithCustomHash(
                        hash = mockGetPublicKeyCredentialOption.clientDataHash!!,
                    ),
                    selectedCipherView = mockCipherView,
                    isUserVerificationSupported = true,
                ),
                requestCaptureSlot.captured,
            )
        }

    @Suppress("MaxLineLength")
    @Test
    fun `authenticateFido2Credential should construct ClientData DefaultWithExtraData when clientDataHash is null`() =
        runTest {
            every {
                mockSigningInfo.apkContentsSigners
            } returns arrayOf(Signature(DEFAULT_APP_SIGNATURE))
            every {
                mockSigningInfo.hasMultipleSigners()
            } returns false
            every { mockCallingAppInfo.isOriginPopulated() } returns true

            every { mockGetPublicKeyCredentialOption.clientDataHash } returns null
            val mockCipherView = createMockCipherView(number = 1)
            val requestCaptureSlot = slot<AuthenticateFido2CredentialRequest>()
            val mockSdkResponse =
                mockk<PublicKeyCredentialAuthenticatorAssertionResponse>(relaxed = true)
            every { Base64.encodeToString(any(), any()) } returns DEFAULT_APP_SIGNATURE
            coEvery {
                mockVaultSdkSource.authenticateFido2Credential(
                    request = any(),
                    fido2CredentialStore = any(),
                )
            } returns mockSdkResponse.asSuccess()

            bitwardenCredentialManager.authenticateFido2Credential(
                userId = "activeUserId",
                request = mockGetPublicKeyCredentialOption,
                selectedCipherView = mockCipherView,
                callingAppInfo = mockCallingAppInfo,
                origin = DEFAULT_WEB_ORIGIN.v1,
            )

            coVerify {
                mockVaultSdkSource.authenticateFido2Credential(
                    request = capture(requestCaptureSlot),
                    fido2CredentialStore = any(),
                )
            }
            assertEquals(
                AuthenticateFido2CredentialRequest(
                    userId = "activeUserId",
                    origin = DEFAULT_WEB_ORIGIN,
                    requestJson =
                        """{"publicKey": ${mockGetPublicKeyCredentialOption.requestJson}}""",
                    clientData = ClientData.DefaultWithExtraData(
                        androidPackageName = "android:apk-key-hash:$DEFAULT_APP_SIGNATURE",
                    ),
                    selectedCipherView = mockCipherView,
                    isUserVerificationSupported = true,
                ),
                requestCaptureSlot.captured,
            )
        }

    @Suppress("MaxLineLength")
    @Test
    fun `authenticateFido2Credential should construct correct assertion request when calling app is unprivileged`() =
        runTest {
            val mockAssertionOptions = createMockPasskeyAssertionOptions(number = 1)
            val mockSelectedCipher = createMockCipherView(number = 1)
            val requestCaptureSlot = slot<AuthenticateFido2CredentialRequest>()

            every { Base64.encodeToString(any(), any()) } returns DEFAULT_APP_SIGNATURE
            coEvery {
                mockVaultSdkSource.authenticateFido2Credential(
                    request = any(),
                    fido2CredentialStore = any(),
                )
            } returns createMockPublicKeyAssertionResponse(number = 1).asSuccess()

            bitwardenCredentialManager.authenticateFido2Credential(
                userId = "activeUserId",
                request = mockGetPublicKeyCredentialOption,
                selectedCipherView = mockSelectedCipher,
                callingAppInfo = mockCallingAppInfo,
                origin = null,
            )

            coVerify {
                mockVaultSdkSource.authenticateFido2Credential(
                    request = capture(requestCaptureSlot),
                    fido2CredentialStore = any(),
                )
            }

            assertEquals(
                Origin.Android(
                    UnverifiedAssetLink(
                        DEFAULT_PACKAGE_NAME,
                        DEFAULT_CERT_FINGERPRINT,
                        "https://${mockAssertionOptions.relyingPartyId!!}",
                        "https://${mockAssertionOptions.relyingPartyId}",
                    ),
                ),
                requestCaptureSlot.captured.origin,
            )
        }

    @Suppress("MaxLineLength")
    @Test
    fun `authenticateFido2Credential should return Error when origin is null`() = runTest {
        val mockSelectedCipher = createMockCipherView(number = 1)

        every { Base64.encodeToString(any(), any()) } returns DEFAULT_APP_SIGNATURE
        every {
            json.decodeFromStringOrNull<PasskeyAssertionOptions>(any())
        } returns null

        val result = bitwardenCredentialManager.authenticateFido2Credential(
            userId = "activeUserId",
            request = mockGetPublicKeyCredentialOption,
            selectedCipherView = mockSelectedCipher,
            callingAppInfo = mockCallingAppInfo,
            origin = null,
        )

        coVerify(exactly = 0) {
            mockVaultSdkSource.authenticateFido2Credential(
                request = any(),
                fido2CredentialStore = any(),
            )
        }

        assertEquals(
            Fido2CredentialAssertionResult.Error.MissingHostUrl,
            result,
        )
    }

    @Suppress("MaxLineLength")
    @Test
    fun `authenticateFidoCredential should convert SDK response to AndroidFido2PublicKeyCredential, deserialize the response to JSON, and return Success with response JSON`() =
        runTest {
            mockkStatic(
                PublicKeyCredentialAuthenticatorAssertionResponse::toAndroidFido2PublicKeyCredential,
            )
            every { Base64.encodeToString(any(), any()) } returns ""
            val mockPublicKeyCredential = mockk<Fido2PublicKeyCredential>()
            val mockSdkResponse =
                mockk<PublicKeyCredentialAuthenticatorAssertionResponse>(relaxed = true) {
                    every { toAndroidFido2PublicKeyCredential() } returns mockPublicKeyCredential
                }
            coEvery {
                mockVaultSdkSource.authenticateFido2Credential(
                    request = any(),
                    fido2CredentialStore = any(),
                )
            } returns mockSdkResponse.asSuccess()
            every { json.encodeToString(mockPublicKeyCredential) } returns "mockResponseJson"

            val authResult = bitwardenCredentialManager.authenticateFido2Credential(
                userId = "activeUserId",
                request = mockGetPublicKeyCredentialOption,
                selectedCipherView = createMockCipherView(number = 1),
                callingAppInfo = mockCallingAppInfo,
                origin = null,
            )

            coVerify {
                mockVaultSdkSource.authenticateFido2Credential(
                    request = any(),
                    fido2CredentialStore = any(),
                )
                mockSdkResponse.toAndroidFido2PublicKeyCredential()
                json.encodeToString(mockPublicKeyCredential)
            }

            assertEquals(
                Fido2CredentialAssertionResult.Success("mockResponseJson"),
                authResult,
            )
        }

    @Suppress("MaxLineLength")
    @Test
    fun `authenticateFido2Credential should return Error when response cannot be serialized`() =
        runTest {
            mockkStatic(PublicKeyCredentialAuthenticatorAssertionResponse::toAndroidFido2PublicKeyCredential)
            every { Base64.encodeToString(any(), any()) } returns ""
            val requestCaptureSlot = slot<AuthenticateFido2CredentialRequest>()
            val mockPublicKeyCredential = mockk<Fido2PublicKeyCredential>()
            val mockSdkResponse =
                mockk<PublicKeyCredentialAuthenticatorAssertionResponse>(relaxed = true) {
                    every { toAndroidFido2PublicKeyCredential() } returns mockPublicKeyCredential
                }
            coEvery {
                mockVaultSdkSource.authenticateFido2Credential(
                    request = capture(requestCaptureSlot),
                    fido2CredentialStore = any(),
                )
            } returns mockSdkResponse.asSuccess()
            every { json.encodeToString(mockPublicKeyCredential) } throws SerializationException()

            val authResult = bitwardenCredentialManager.authenticateFido2Credential(
                userId = "activeUserId",
                request = mockGetPublicKeyCredentialOption,
                selectedCipherView = createMockCipherView(number = 1),
                callingAppInfo = mockCallingAppInfo,
                origin = null,
            )

            coVerify {
                mockVaultSdkSource.authenticateFido2Credential(
                    request = any(),
                    fido2CredentialStore = any(),
                )
                mockSdkResponse.toAndroidFido2PublicKeyCredential()
                json.encodeToString(mockPublicKeyCredential)
            }

            assertEquals(
                Fido2CredentialAssertionResult.Error.InternalError,
                authResult,
            )
        }

    @Suppress("MaxLineLength")
    @Test
    fun `getUserVerificationRequirement should return correct verification required based on attestation options`() {
        // Verify REQUIRED is mapped correctly
        every {
            json.decodeFromStringOrNull<PasskeyAttestationOptions>(any())
        } returns createMockPasskeyAttestationOptions(
            number = 1,
            userVerificationRequirement = UserVerificationRequirement.REQUIRED,
        )

        assertEquals(
            UserVerificationRequirement.REQUIRED,
            bitwardenCredentialManager.getUserVerificationRequirement(
                request = mockCreatePublicKeyCredentialRequest,
                fallbackRequirement = UserVerificationRequirement.PREFERRED,
            ),
        )

        // Verify PREFERRED is mapped correctly
        every {
            json.decodeFromStringOrNull<PasskeyAttestationOptions>(any())
        } returns createMockPasskeyAttestationOptions(
            number = 1,
            userVerificationRequirement = UserVerificationRequirement.PREFERRED,
        )

        assertEquals(
            UserVerificationRequirement.PREFERRED,
            bitwardenCredentialManager.getUserVerificationRequirement(
                request = mockCreatePublicKeyCredentialRequest,
                fallbackRequirement = UserVerificationRequirement.REQUIRED,
            ),
        )

        // Verify DISCOURAGED is mapped correctly
        every {
            json.decodeFromStringOrNull<PasskeyAttestationOptions>(any())
        } returns createMockPasskeyAttestationOptions(
            number = 1,
            userVerificationRequirement = UserVerificationRequirement.DISCOURAGED,
        )

        assertEquals(
            UserVerificationRequirement.DISCOURAGED,
            bitwardenCredentialManager.getUserVerificationRequirement(
                request = mockCreatePublicKeyCredentialRequest,
                fallbackRequirement = UserVerificationRequirement.PREFERRED,
            ),
        )
    }

    @Suppress("MaxLineLength")
    @Test
    fun `getUserVerificationRequirement should return fallback requirement when attestation options are invalid`() {
        every {
            json.decodeFromStringOrNull<PasskeyAttestationOptions>(any())
        } returns null
        assertEquals(
            UserVerificationRequirement.PREFERRED,
            bitwardenCredentialManager.getUserVerificationRequirement(
                request = mockCreatePublicKeyCredentialRequest,
                fallbackRequirement = UserVerificationRequirement.PREFERRED,
            ),
        )
    }

    @Suppress("MaxLineLength")
    @Test
    fun `getUserVerificationRequirement should return fallback requirement based on assertion options`() =
        runTest {
            // Verify REQUIRED is mapped correctly
            every {
                json.decodeFromStringOrNull<PasskeyAssertionOptions>(any())
            } returns createMockPasskeyAssertionOptions(
                number = 1,
                userVerificationRequirement = UserVerificationRequirement.REQUIRED,
            )
            assertEquals(
                UserVerificationRequirement.REQUIRED,
                bitwardenCredentialManager.getUserVerificationRequirement(
                    request = mockProviderGetCredentialRequest,
                    fallbackRequirement = UserVerificationRequirement.PREFERRED,
                ),
            )

            // Verify PREFERRED is mapped correctly
            every {
                json.decodeFromStringOrNull<PasskeyAssertionOptions>(any())
            } returns createMockPasskeyAssertionOptions(
                number = 1,
                userVerificationRequirement = UserVerificationRequirement.REQUIRED,
            )
            assertEquals(
                UserVerificationRequirement.REQUIRED,
                bitwardenCredentialManager.getUserVerificationRequirement(
                    request = mockProviderGetCredentialRequest,
                    fallbackRequirement = UserVerificationRequirement.PREFERRED,
                ),
            )

            // Verify DISCOURAGED is mapped correctly
            every {
                json.decodeFromStringOrNull<PasskeyAssertionOptions>(any())
            } returns createMockPasskeyAssertionOptions(
                number = 1,
                userVerificationRequirement = UserVerificationRequirement.DISCOURAGED,
            )
            assertEquals(
                UserVerificationRequirement.DISCOURAGED,
                bitwardenCredentialManager.getUserVerificationRequirement(
                    request = mockProviderGetCredentialRequest,
                    fallbackRequirement = UserVerificationRequirement.PREFERRED,
                ),
            )
        }

    @Suppress("MaxLineLength")
    @Test
    fun `getUserVerificationRequirement should return fallback requirement when assertion options are invalid`() =
        runTest {
            every {
                mockProviderGetCredentialRequest.credentialOptions
            } returns emptyList()
            assertEquals(
                UserVerificationRequirement.REQUIRED,
                bitwardenCredentialManager.getUserVerificationRequirement(
                    request = mockProviderGetCredentialRequest,
                    fallbackRequirement = UserVerificationRequirement.REQUIRED,
                ),
            )
        }

    @Suppress("MaxLineLength")
    @Test
    fun `getCredentialEntries with public key credential options should return empty list when no ciphers have FIDO 2 credentials`() =
        runTest {
            val mockBeginGetPublicKeyCredentialOption = mockk<BeginGetPublicKeyCredentialOption>()
            val mockGetCredentialsRequest = mockk<GetCredentialsRequest> {
                every { callingAppInfo } returns mockCallingAppInfo
                every {
                    beginGetPublicKeyCredentialOptions
                } returns listOf(mockBeginGetPublicKeyCredentialOption)
            }
            every {
                mockBeginGetPublicKeyCredentialOption.requestJson
            } returns DEFAULT_FIDO2_AUTH_REQUEST_JSON
            mutableCipherStateFlow.value = DataState.Loaded(emptyList())
            val result = bitwardenCredentialManager.getCredentialEntries(mockGetCredentialsRequest)
            assertEquals(emptyList<CredentialEntry>(), result.getOrNull())
        }

    @Suppress("MaxLineLength")
    @Test
    fun `getCredentialEntries with public key credential options should return error when FIDO 2 credential decryption fails`() =
        runTest {
            val mockBeginGetPublicKeyCredentialOption = mockk<BeginGetPublicKeyCredentialOption>()
            val mockGetCredentialsRequest = mockk<GetCredentialsRequest> {
                every { callingAppInfo } returns mockCallingAppInfo
                every {
                    beginGetPublicKeyCredentialOptions
                } returns listOf(mockBeginGetPublicKeyCredentialOption)
                every { beginGetPasswordOptions } returns emptyList()
                every { userId } returns "mockUserId"
            }
            every {
                mockBeginGetPublicKeyCredentialOption.requestJson
            } returns DEFAULT_FIDO2_AUTH_REQUEST_JSON
            mutableCipherStateFlow.value = DataState.Loaded(
                listOf(
                    createMockCipherView(
                        number = 1,
                        fido2Credentials = createMockSdkFido2CredentialList(number = 1),
                    ),
                ),
            )
            coEvery {
                mockVaultRepository.getDecryptedFido2CredentialAutofillViews(any())
            } returns DecryptFido2CredentialAutofillViewResult.Error(
                BitwardenException.E("Error decrypting credentials."),
            )
            val result = bitwardenCredentialManager.getCredentialEntries(mockGetCredentialsRequest)
            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull() is GetCredentialUnknownException)
        }

    @Suppress("MaxLineLength")
    @Test
    fun `getCredentialEntries with public key credential options should return error when passkey assertion options are null`() =
        runTest {
            val mockOption = mockk<BeginGetPublicKeyCredentialOption> {
                every { requestJson } returns ""
            }
            val mockGetCredentialsRequest = mockk<GetCredentialsRequest> {
                every { callingAppInfo } returns mockCallingAppInfo
                every {
                    beginGetPublicKeyCredentialOptions
                } returns listOf(mockOption)
                every { beginGetPasswordOptions } returns emptyList()
                every { userId } returns "mockUserId"
            }
            mutableCipherStateFlow.value = DataState.Loaded(
                    listOf(
                        createMockCipherView(
                            number = 1,
                            login = createMockLoginView(number = 1, hasUris = false),
                            fido2Credentials = createMockSdkFido2CredentialList(number = 1),
                        ),
                    ),
            )
            coEvery {
                mockVaultRepository.getDecryptedFido2CredentialAutofillViews(any())
            } returns DecryptFido2CredentialAutofillViewResult.Success(
                listOf(
                    createMockFido2CredentialAutofillView(
                        number = 1,
                        cipherId = "mockId-1",
                        rpId = "mockRelyingPartyId-1",
                    ),
                ),
            )
            every {
                json.decodeFromStringOrNull<PasskeyAssertionOptions>(any())
            } returns null
            val result = bitwardenCredentialManager.getCredentialEntries(mockGetCredentialsRequest)
            assertTrue(
                result.exceptionOrNull() is GetCredentialUnknownException,
            )
        }

    @Suppress("MaxLineLength")
    @Test
    fun `getCredentialEntries with public key credential options should return error when FIDO 2 relyingPartyId is null`() =
        runTest {
            val mockOption = mockk<BeginGetPublicKeyCredentialOption> {
                every { requestJson } returns ""
            }
            val mockGetCredentialsRequest = mockk<GetCredentialsRequest> {
                every { callingAppInfo } returns mockCallingAppInfo
                every {
                    beginGetPublicKeyCredentialOptions
                } returns listOf(mockOption)
                every { beginGetPasswordOptions } returns emptyList()
                every { userId } returns "mockUserId"
            }
            mutableCipherStateFlow.value = DataState.Loaded(
                    listOf(
                        createMockCipherView(
                            number = 1,
                            login = createMockLoginView(number = 1, hasUris = false),
                            fido2Credentials = createMockSdkFido2CredentialList(number = 1),
                        ),
                    ),
            )
            coEvery {
                mockVaultRepository.getDecryptedFido2CredentialAutofillViews(any())
            } returns DecryptFido2CredentialAutofillViewResult.Success(
                listOf(
                    createMockFido2CredentialAutofillView(
                        number = 1,
                        cipherId = "mockId-1",
                        rpId = "mockRelyingPartyId-1",
                    ),
                ),
            )
            every {
                json.decodeFromStringOrNull<PasskeyAssertionOptions>(any())
            } returns createMockPasskeyAssertionOptions(number = 1, relyingPartyId = null)
            val result = bitwardenCredentialManager
                .getCredentialEntries(mockGetCredentialsRequest)
            assertTrue(
                result.exceptionOrNull() is GetCredentialUnknownException,
            )
        }

    @Suppress("MaxLineLength")
    @Test
    fun `getCredentialEntries should build public key credential entries when decryption succeeds`() =
        runTest {
            val mockBeginGetPublicKeyCredentialOption = mockk<BeginGetPublicKeyCredentialOption>()
            val mockGetCredentialsRequest = mockk<GetCredentialsRequest> {
                every { callingAppInfo } returns mockCallingAppInfo
                every {
                    beginGetPublicKeyCredentialOptions
                } returns listOf(mockBeginGetPublicKeyCredentialOption)
                every { userId } returns "mockUserId"
            }
            val fido2CredentialAutofillViews = listOf(
                createMockFido2CredentialAutofillView(
                    number = 1,
                    cipherId = "mockId-1",
                    rpId = "mockRelyingPartyId-1",
                ),
            )
            every {
                mockBeginGetPublicKeyCredentialOption.requestJson
            } returns DEFAULT_FIDO2_AUTH_REQUEST_JSON
            mutableCipherStateFlow.value = DataState.Loaded(
                listOf(
                    createMockCipherView(
                        number = 1,
                        login = createMockLoginView(number = 1, hasUris = false),
                        fido2Credentials = createMockSdkFido2CredentialList(number = 1),
                    ),
                ),
            )
            coEvery {
                mockVaultRepository.getDecryptedFido2CredentialAutofillViews(any())
            } returns DecryptFido2CredentialAutofillViewResult.Success(
                fido2CredentialAutofillViews,
            )
            every {
                mockCredentialEntryBuilder.buildPublicKeyCredentialEntries(
                    userId = "mockUserId",
                    fido2CredentialAutofillViews = fido2CredentialAutofillViews,
                    beginGetPublicKeyCredentialOptions = listOf(
                        mockBeginGetPublicKeyCredentialOption,
                    ),
                    isUserVerified = false,
                )
            } returns listOf(mockk<PublicKeyCredentialEntry>())

            val result = bitwardenCredentialManager.getCredentialEntries(mockGetCredentialsRequest)
            assertTrue(result.isSuccess)
            assertEquals(1, result.getOrNull()?.size)
            assertTrue(result.getOrNull()?.first() is PublicKeyCredentialEntry)
        }
}

private const val DEFAULT_PACKAGE_NAME = "com.x8bit.bitwarden"
private const val DEFAULT_APP_SIGNATURE = "0987654321ABCDEF"
private const val DEFAULT_CERT_FINGERPRINT = "30:39:38:37:36:35:34:33:32:31:41:42:43:44:45:46"
private const val DEFAULT_HOST = "bitwarden.com"
private val DEFAULT_ANDROID_ORIGIN = Origin.Android(
    UnverifiedAssetLink(
        packageName = DEFAULT_PACKAGE_NAME,
        sha256CertFingerprint = DEFAULT_CERT_FINGERPRINT,
        host = "https://$DEFAULT_HOST",
        assetLinkUrl = "https://$DEFAULT_HOST",
    ),
)
private val DEFAULT_WEB_ORIGIN = Origin.Web("bitwarden.com")
private const val DEFAULT_FIDO2_AUTH_REQUEST_JSON = """
{
  "allowCredentials": [
    {
      "id": "mockCredentialId-1",
      "transports": [
        "internal"
      ],
      "type": "public-key"
    },
    {
      "id": "mockCredentialId-2",
      "transports": [
        "internal"
      ],
      "type": "public-key"
    }
  ],
  "challenge": "mockChallenge",
  "rpId": "bitwarden.com",
  "userVerification": "preferred"
}
"""
private const val DEFAULT_FIDO2_CREATE_REQUEST_JSON = """
{
  "authenticatorSelection": { "residentKey":"required", "userVerification":"preferred" },
  "challenge":"TrvS2AJTFN877BNxAisWaMRvi_2wlT83ZD8IMry2QQs",
  "excludeCredentials":[],
  "extensions": { "credProps":true },
  "pubKeyCredParams":[
      { "alg":-7, "type":"public-key" },
      { "alg":-257, "type":"public-key" }
  ],
  "rp": { "id":"learnpasskeys.io", "name":"Learn Passkeys" },
  "user": {
      "displayName":"Clyde O'Hara",
      "id":"TE8tc3VWR184emowN3liOVpwbDN6azA4SnA3cTUtUmo",
      "name":"overjoyed.clyde.0553"
  }
}
"""
