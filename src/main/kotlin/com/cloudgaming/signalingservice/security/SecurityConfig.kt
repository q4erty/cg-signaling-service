package com.cloudgaming.signalingservice.security

import com.cloudgaming.signalingservice.config.AuthProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator
import org.springframework.security.oauth2.core.OAuth2TokenValidator
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtClaimNames
import org.springframework.security.oauth2.jwt.JwtClaimValidator
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtTimestampValidator
import org.springframework.security.oauth2.jwt.JwtValidators
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.security.web.SecurityFilterChain

@Configuration
@EnableWebSecurity
class SecurityConfig(
    private val authProperties: AuthProperties,
) {

    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests { auth ->
                auth
                    .requestMatchers("/api/v1/signaling/**").permitAll()
                    .requestMatchers("/internal/**").permitAll()
                    .requestMatchers("/actuator/health").permitAll()
                    .anyRequest().denyAll()
            }
            .oauth2ResourceServer { it.disable() }

        return http.build()
    }

    @Bean
    fun jwtDecoder(): JwtDecoder {
        val algorithms: List<SignatureAlgorithm> = authProperties.allowedAlgorithms.map { alg ->
            requireNotNull(SignatureAlgorithm.from(alg)) {
                "Unsupported JWT algorithm: '$alg'. Supported: RS256, RS384, RS512, ES256, ES384, ES512, PS256, PS384, PS512"
            }
        }

        val decoder = NimbusJwtDecoder
            .withJwkSetUri(authProperties.jwkSetUri)
            .jwsAlgorithms { configs ->
                configs.clear()
                algorithms.forEach { alg -> configs.add(alg) }
            }
            .build()

        val timestampValidator = JwtTimestampValidator(authProperties.clockSkew)
        val issuerValidator = JwtClaimValidator<String>(JwtClaimNames.ISS) { iss ->
            iss == authProperties.issuerUri
        }

        val defaultWithCustom = JwtValidators.createDefaultWithValidators(issuerValidator, timestampValidator)

        val validators = mutableListOf<OAuth2TokenValidator<Jwt>>()
        validators.add(defaultWithCustom)

        val audience = authProperties.audienceOrNull
        if (audience != null) {
            validators.add(JwtClaimValidator<List<String>>(JwtClaimNames.AUD) { audList ->
                audList != null && audience in audList
            })
        }

        decoder.setJwtValidator(DelegatingOAuth2TokenValidator(validators))

        return decoder
    }
}