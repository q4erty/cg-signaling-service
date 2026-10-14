package com.cloudgaming.signalingservice.security

import com.cloudgaming.signalingservice.config.AuthProperties
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import java.time.Duration

class AudienceValidationTest {

    private lateinit var jwtHelper: JwtTestHelper
    private lateinit var jwksServer: JwksStubServer

    @BeforeEach
    fun setUp() {
        jwtHelper = JwtTestHelper(
            issuer = "https://test-issuer.example.com",
            audience = "signaling-service",
        )
        jwksServer = JwksStubServer(listOf(jwtHelper.publicKey))
        jwksServer.start()
    }

    @AfterEach
    fun tearDown() {
        jwksServer.stop()
    }

    private fun buildAuthenticator(audience: String?): JwtHandshakeAuthenticator {
        val authProperties = AuthProperties(
            issuerUri = jwtHelper.issuer,
            jwkSetUri = jwksServer.jwksUri,
            userIdClaim = "sub",
            clockSkew = Duration.ofSeconds(30),
            allowedAlgorithms = listOf("RS256"),
            audience = audience ?: "",
        )
        val decoder = SecurityConfig(authProperties).jwtDecoder()
        return JwtHandshakeAuthenticator(decoder, authProperties)
    }

    @Test
    fun `token with correct audience is accepted`() {
        val authenticator = buildAuthenticator("signaling-service")
        val token = jwtHelper.validToken()
        val request = MockHttpServletRequest().apply {
            addHeader("Authorization", "Bearer $token")
        }

        val result = authenticator.authenticate(request)
        assertTrue(result is AuthenticationResult.Authenticated)
    }

    @Test
    fun `token with wrong audience is rejected when audience is configured`() {
        val authenticator = buildAuthenticator("signaling-service")
        val token = jwtHelper.wrongAudienceToken(wrongAudience = "other-service")
        val request = MockHttpServletRequest().apply {
            addHeader("Authorization", "Bearer $token")
        }

        val result = authenticator.authenticate(request)
        assertTrue(result is AuthenticationResult.Invalid)
    }
}