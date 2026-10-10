package com.cloudgaming.signalingservice.filter

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.security.MessageDigest

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class InternalAuthFilter(
    @Value("\${internal.secret:}") private val internalSecret: String?,
) : OncePerRequestFilter() {

    private val log = LoggerFactory.getLogger(InternalAuthFilter::class.java)

    companion object {
        private const val HEADER_NAME = "X-Internal-Secret"
    }

    override fun shouldNotFilter(request: HttpServletRequest): Boolean {
        val path = request.servletPath
        return !path.startsWith("/internal/")
    }

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        if (internalSecret.isNullOrBlank()) {
            log.warn("Internal endpoint accessed but internal.secret is not configured")
            response.status = HttpServletResponse.SC_FORBIDDEN
            return
        }

        val providedSecret = request.getHeader(HEADER_NAME)

        if (providedSecret == null) {
            log.warn("Internal endpoint accessed without {} header", HEADER_NAME)
            response.status = HttpServletResponse.SC_UNAUTHORIZED
            return
        }

        if (!constantTimeEquals(internalSecret, providedSecret)) {
            log.warn("Internal endpoint accessed with invalid secret")
            response.status = HttpServletResponse.SC_FORBIDDEN
            return
        }

        filterChain.doFilter(request, response)
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        return MessageDigest.isEqual(a.toByteArray(), b.toByteArray())
    }
}