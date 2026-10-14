package com.cloudgaming.signalingservice.security

sealed class AuthenticationResult {
    data class Authenticated(val userId: String) : AuthenticationResult()
    data object Missing : AuthenticationResult()
    data class Invalid(val reason: String) : AuthenticationResult()
    data class Unavailable(val cause: Throwable) : AuthenticationResult()
}
