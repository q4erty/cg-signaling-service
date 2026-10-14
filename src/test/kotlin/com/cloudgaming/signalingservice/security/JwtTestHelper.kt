package com.cloudgaming.signalingservice.security

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.JWSSigner
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import java.time.Instant
import java.util.Date

class JwtTestHelper(
    val issuer: String = "https://test-issuer.example.com",
    val audience: String? = null,
) {
    val rsaKey: RSAKey = RSAKeyGenerator(2048)
        .keyID("test-key-1")
        .generate()

    val publicKey: RSAKey = rsaKey.toPublicJWK()

    private val signer: JWSSigner = RSASSASigner(rsaKey)

    fun validToken(
        subject: String = "user-123",
        additionalClaims: Map<String, Any> = emptyMap(),
        expiresInSeconds: Long = 3600,
        notBeforeSecondsAgo: Long = 60,
    ): String {
        val now = Instant.now()
        val claims = JWTClaimsSet.Builder()
            .issuer(issuer)
            .subject(subject)
            .issueTime(Date.from(now))
            .expirationTime(Date.from(now.plusSeconds(expiresInSeconds)))
            .notBeforeTime(Date.from(now.minusSeconds(notBeforeSecondsAgo)))
            .apply {
                if (audience != null) {
                    audience(listOf(audience))
                }
                additionalClaims.forEach { (key, value) -> claim(key, value) }
            }
            .build()

        val signedJwt = SignedJWT(
            JWSHeader.Builder(JWSAlgorithm.RS256).keyID(rsaKey.keyID).build(),
            claims,
        )
        signedJwt.sign(signer)
        return signedJwt.serialize()
    }

    fun expiredToken(subject: String = "user-123"): String {
        val now = Instant.now()
        val claims = JWTClaimsSet.Builder()
            .issuer(issuer)
            .subject(subject)
            .issueTime(Date.from(now.minusSeconds(7200)))
            .expirationTime(Date.from(now.minusSeconds(3600)))
            .notBeforeTime(Date.from(now.minusSeconds(7200)))
            .build()

        val signedJwt = SignedJWT(
            JWSHeader.Builder(JWSAlgorithm.RS256).keyID(rsaKey.keyID).build(),
            claims,
        )
        signedJwt.sign(signer)
        return signedJwt.serialize()
    }

    fun notYetValidToken(subject: String = "user-123"): String {
        val now = Instant.now()
        val claims = JWTClaimsSet.Builder()
            .issuer(issuer)
            .subject(subject)
            .issueTime(Date.from(now))
            .expirationTime(Date.from(now.plusSeconds(7200)))
            .notBeforeTime(Date.from(now.plusSeconds(3600)))
            .build()

        val signedJwt = SignedJWT(
            JWSHeader.Builder(JWSAlgorithm.RS256).keyID(rsaKey.keyID).build(),
            claims,
        )
        signedJwt.sign(signer)
        return signedJwt.serialize()
    }

    fun wrongIssuerToken(subject: String = "user-123", wrongIssuer: String = "https://evil.example.com"): String {
        val now = Instant.now()
        val claims = JWTClaimsSet.Builder()
            .issuer(wrongIssuer)
            .subject(subject)
            .issueTime(Date.from(now))
            .expirationTime(Date.from(now.plusSeconds(3600)))
            .notBeforeTime(Date.from(now.minusSeconds(60)))
            .build()

        val signedJwt = SignedJWT(
            JWSHeader.Builder(JWSAlgorithm.RS256).keyID(rsaKey.keyID).build(),
            claims,
        )
        signedJwt.sign(signer)
        return signedJwt.serialize()
    }

    fun noSubjectToken(): String {
        val now = Instant.now()
        val claims = JWTClaimsSet.Builder()
            .issuer(issuer)
            .issueTime(Date.from(now))
            .expirationTime(Date.from(now.plusSeconds(3600)))
            .notBeforeTime(Date.from(now.minusSeconds(60)))
            .build()

        val signedJwt = SignedJWT(
            JWSHeader.Builder(JWSAlgorithm.RS256).keyID(rsaKey.keyID).build(),
            claims,
        )
        signedJwt.sign(signer)
        return signedJwt.serialize()
    }

    fun wrongSignatureToken(subject: String = "user-123"): String {
        val otherKey = RSAKeyGenerator(2048).keyID("evil-key").generate()
        val otherSigner = RSASSASigner(otherKey)

        val now = Instant.now()
        val claims = JWTClaimsSet.Builder()
            .issuer(issuer)
            .subject(subject)
            .issueTime(Date.from(now))
            .expirationTime(Date.from(now.plusSeconds(3600)))
            .notBeforeTime(Date.from(now.minusSeconds(60)))
            .build()

        val signedJwt = SignedJWT(
            JWSHeader.Builder(JWSAlgorithm.RS256).keyID(rsaKey.keyID).build(),
            claims,
        )
        signedJwt.sign(otherSigner)
        return signedJwt.serialize()
    }

    fun wrongAudienceToken(subject: String = "user-123", wrongAudience: String = "wrong-aud"): String {
        val now = Instant.now()
        val claims = JWTClaimsSet.Builder()
            .issuer(issuer)
            .subject(subject)
            .audience(listOf(wrongAudience))
            .issueTime(Date.from(now))
            .expirationTime(Date.from(now.plusSeconds(3600)))
            .notBeforeTime(Date.from(now.minusSeconds(60)))
            .build()

        val signedJwt = SignedJWT(
            JWSHeader.Builder(JWSAlgorithm.RS256).keyID(rsaKey.keyID).build(),
            claims,
        )
        signedJwt.sign(signer)
        return signedJwt.serialize()
    }
}