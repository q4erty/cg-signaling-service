package com.cloudgaming.signalingservice.security

import com.cloudgaming.signalingservice.config.AuthProperties
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.security.oauth2.jwt.BadJwtException
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtException
import org.springframework.stereotype.Component

@Component
class JwtHandshakeAuthenticator(
    private val jwtDecoder: JwtDecoder,
    private val authProperties: AuthProperties,
) : HandshakeAuthenticator {

    private val log = LoggerFactory.getLogger(JwtHandshakeAuthenticator::class.java)

    companion object {
        private const val AUTHORIZATION_HEADER = "Authorization"
        private const val BEARER_PREFIX = "bearer"
        private const val QUERY_PARAM_TOKEN = "token"
    }

    override fun authenticate(request: HttpServletRequest): AuthenticationResult {
        val rawToken = extractToken(request) ?: return AuthenticationResult.Missing

        val jwt = try {
            jwtDecoder.decode(rawToken)
        } catch (e: BadJwtException) {
            log.info("JWT validation failed (bad): {}", e.message)
            return AuthenticationResult.Invalid(e.message ?: "Invalid JWT")
        } catch (e: JwtException) {
            log.warn("JWT validation unavailable: {}", e.message)
            return AuthenticationResult.Unavailable(e)
        } catch (e: Exception) {
            log.error("Unexpected error during JWT validation", e)
            return AuthenticationResult.Unavailable(e)
        }

        val userId = jwt.claims[authProperties.userIdClaim] as? String
        if (userId.isNullOrBlank()) {
            log.info("JWT missing required claim: {}", authProperties.userIdClaim)
            return AuthenticationResult.Invalid("Missing claim: ${authProperties.userIdClaim}")
        }

        return AuthenticationResult.Authenticated(userId)
    }

    private fun extractToken(request: HttpServletRequest): String? {
        // Priority: Authorization header
        val authHeader = request.getHeader(AUTHORIZATION_HEADER)
        if (!authHeader.isNullOrBlank()) {
            val parts = authHeader.split(" ", limit = 2)
            if (parts.size == 2 && parts[0].equals(BEARER_PREFIX, ignoreCase = true)) {
                val token = parts[1].trim()
                if (token.isNotEmpty()) return token
            }
        }

        // Fallback: query parameter
        val queryToken = request.getParameter(QUERY_PARAM_TOKEN)
        if (!queryToken.isNullOrBlank()) {
            return queryToken
        }

        return null
    }
}
