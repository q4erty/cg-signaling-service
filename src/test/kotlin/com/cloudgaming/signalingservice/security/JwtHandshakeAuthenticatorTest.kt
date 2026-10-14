package com.cloudgaming.signalingservice.security

import com.cloudgaming.signalingservice.config.AuthProperties
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import java.time.Duration

class JwtHandshakeAuthenticatorTest {

    private lateinit var jwtHelper: JwtTestHelper
    private lateinit var jwksServer: JwksStubServer
    private lateinit var authenticator: JwtHandshakeAuthenticator
    private lateinit var authProperties: AuthProperties

    @BeforeEach
    fun setUp() {
        jwtHelper = JwtTestHelper(issuer = "https://test-issuer.example.com")
        jwksServer = JwksStubServer(listOf(jwtHelper.publicKey))
        jwksServer.start()

        authProperties = AuthProperties(
            issuerUri = jwtHelper.issuer,
            jwkSetUri = jwksServer.jwksUri,
            userIdClaim = "sub",
            clockSkew = Duration.ofSeconds(30),
            allowedAlgorithms = listOf("RS256"),
            audience = "",
        )

        val config = SecurityConfig(authProperties)
        val jwtDecoder = config.jwtDecoder()
        authenticator = JwtHandshakeAuthenticator(jwtDecoder, authProperties)
    }

    @AfterEach
    fun tearDown() {
        jwksServer.stop()
    }

    private fun requestWithAuthHeader(token: String): MockHttpServletRequest {
        return MockHttpServletRequest().apply {
            addHeader("Authorization", "Bearer $token")
        }
    }

    private fun requestWithQueryToken(token: String): MockHttpServletRequest {
        return MockHttpServletRequest().apply {
            addParameter("token", token)
        }
    }

    @Test
    fun `valid token via Authorization header returns Authenticated`() {
        val token = jwtHelper.validToken(subject = "user-42")
        val request = requestWithAuthHeader(token)

        val result = authenticator.authenticate(request)

        assertTrue(result is AuthenticationResult.Authenticated)
        assertEquals("user-42", (result as AuthenticationResult.Authenticated).userId)
    }

    @Test
    fun `valid token via query parameter returns Authenticated`() {
        val token = jwtHelper.validToken(subject = "user-99")
        val request = requestWithQueryToken(token)

        val result = authenticator.authenticate(request)

        assertTrue(result is AuthenticationResult.Authenticated)
        assertEquals("user-99", (result as AuthenticationResult.Authenticated).userId)
    }

    @Test
    fun `Authorization header takes priority over query token`() {
        val headerToken = jwtHelper.validToken(subject = "header-user")
        val queryToken = jwtHelper.validToken(subject = "query-user")
        val request = MockHttpServletRequest().apply {
            addHeader("Authorization", "Bearer $headerToken")
            addParameter("token", queryToken)
        }

        val result = authenticator.authenticate(request)

        assertTrue(result is AuthenticationResult.Authenticated)
        assertEquals("header-user", (result as AuthenticationResult.Authenticated).userId)
    }

    @Test
    fun `no token returns Missing`() {
        val request = MockHttpServletRequest()

        val result = authenticator.authenticate(request)

        assertTrue(result is AuthenticationResult.Missing)
    }

    @Test
    fun `empty Authorization header returns Missing`() {
        val request = MockHttpServletRequest().apply {
            addHeader("Authorization", "")
        }

        val result = authenticator.authenticate(request)

        assertTrue(result is AuthenticationResult.Missing)
    }

    @Test
    fun `Bearer with empty token returns Missing`() {
        val request = MockHttpServletRequest().apply {
            addHeader("Authorization", "Bearer ")
        }

        val result = authenticator.authenticate(request)

        assertTrue(result is AuthenticationResult.Missing)
    }

    @Test
    fun `case-insensitive Bearer scheme is accepted`() {
        val token = jwtHelper.validToken(subject = "user-1")
        val request = MockHttpServletRequest().apply {
            addHeader("Authorization", "bearer $token")
        }

        val result = authenticator.authenticate(request)

        assertTrue(result is AuthenticationResult.Authenticated)
    }

    @Test
    fun `garbage token returns Invalid`() {
        val request = requestWithAuthHeader("not.a.jwt")

        val result = authenticator.authenticate(request)

        assertTrue(result is AuthenticationResult.Invalid)
    }

    @Test
    fun `expired token returns Invalid`() {
        val token = jwtHelper.expiredToken()
        val request = requestWithAuthHeader(token)

        val result = authenticator.authenticate(request)

        assertTrue(result is AuthenticationResult.Invalid)
    }

    @Test
    fun `not-yet-valid token returns Invalid`() {
        val token = jwtHelper.notYetValidToken()
        val request = requestWithAuthHeader(token)

        val result = authenticator.authenticate(request)

        assertTrue(result is AuthenticationResult.Invalid)
    }

    @Test
    fun `wrong issuer returns Invalid`() {
        val token = jwtHelper.wrongIssuerToken()
        val request = requestWithAuthHeader(token)

        val result = authenticator.authenticate(request)

        assertTrue(result is AuthenticationResult.Invalid)
    }

    @Test
    fun `wrong signature returns Invalid`() {
        val token = jwtHelper.wrongSignatureToken()
        val request = requestWithAuthHeader(token)

        val result = authenticator.authenticate(request)

        assertTrue(result is AuthenticationResult.Invalid)
    }

    @Test
    fun `missing subject claim returns Invalid`() {
        val token = jwtHelper.noSubjectToken()
        val request = requestWithAuthHeader(token)

        val result = authenticator.authenticate(request)

        assertTrue(result is AuthenticationResult.Invalid)
        assertTrue((result as AuthenticationResult.Invalid).reason.contains("sub"))
    }

    @Test
    fun `JWKS server down returns Unavailable not Invalid`() {
        jwksServer.stop()

        val token = jwtHelper.validToken()
        val request = requestWithAuthHeader(token)

        val result = authenticator.authenticate(request)

        assertTrue(result is AuthenticationResult.Unavailable, "Expected Unavailable, got: $result")
    }
}