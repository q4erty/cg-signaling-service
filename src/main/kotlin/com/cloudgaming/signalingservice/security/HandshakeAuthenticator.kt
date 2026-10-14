package com.cloudgaming.signalingservice.security

import jakarta.servlet.http.HttpServletRequest

interface HandshakeAuthenticator {
    fun authenticate(request: HttpServletRequest): AuthenticationResult
}
