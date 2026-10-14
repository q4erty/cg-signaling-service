package com.cloudgaming.signalingservice.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties(prefix = "auth")
data class AuthProperties(
    val issuerUri: String = "",
    val jwkSetUri: String = "",
    val userIdClaim: String = "sub",
    val clockSkew: Duration = Duration.ofSeconds(30),
    val allowedAlgorithms: List<String> = listOf("RS256"),
    val audience: String = "",
) {
    init {
        require(issuerUri.isNotBlank()) {
            "auth.issuer-uri must not be blank. Set AUTH_ISSUER_URI environment variable."
        }
        require(jwkSetUri.isNotBlank()) {
            "auth.jwk-set-uri must not be blank. Set AUTH_JWK_SET_URI environment variable."
        }
        require(userIdClaim.isNotBlank()) {
            "auth.user-id-claim must not be blank."
        }
        require(allowedAlgorithms.isNotEmpty()) {
            "auth.allowed-algorithms must contain at least one algorithm."
        }
    }

    val audienceOrNull: String?
        get() = audience.takeIf { it.isNotBlank() }
}
