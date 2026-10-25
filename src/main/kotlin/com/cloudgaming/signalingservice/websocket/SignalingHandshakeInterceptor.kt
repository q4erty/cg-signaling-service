package com.cloudgaming.signalingservice.websocket

import com.cloudgaming.signalingservice.model.RoomId
import com.cloudgaming.signalingservice.model.RoomResolutionResult
import com.cloudgaming.signalingservice.model.RoomStatus
import com.cloudgaming.signalingservice.observability.LogContext
import com.cloudgaming.signalingservice.repository.RoomRegistry
import com.cloudgaming.signalingservice.security.AuthenticationResult
import com.cloudgaming.signalingservice.security.HandshakeAuthenticator
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.http.server.ServerHttpRequest
import org.springframework.http.server.ServerHttpResponse
import org.springframework.http.server.ServletServerHttpRequest
import org.springframework.http.server.ServletServerHttpResponse
import org.springframework.stereotype.Component
import org.springframework.web.socket.WebSocketHandler
import org.springframework.web.socket.server.HandshakeInterceptor
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

@Component
class SignalingHandshakeInterceptor(
    private val roomRegistry: RoomRegistry,
    private val authenticator: HandshakeAuthenticator,
) : HandshakeInterceptor {

    private val log = LoggerFactory.getLogger(SignalingHandshakeInterceptor::class.java)

    companion object {
        const val ATTR_ROOM_ID = "roomId"
        const val ATTR_WORKER_BASE_URL = "workerBaseUrl"
        const val ATTR_USER_ID = "userId"
        const val ATTR_CONNECTION_ID = "connectionId"

        private const val QUERY_PARAM_ROOM_ID = "room_id"
        private const val WWW_AUTHENTICATE_HEADER = "WWW-Authenticate"
        private const val BEARER_CHALLENGE = "Bearer"
    }

    override fun beforeHandshake(
        request: ServerHttpRequest,
        response: ServerHttpResponse,
        wsHandler: WebSocketHandler,
        attributes: MutableMap<String, Any>,
    ): Boolean {
        val connectionId = LogContext.generateConnectionId()
        LogContext.set(connectionId = connectionId)

        try {
            val servletRequest = (request as ServletServerHttpRequest).servletRequest
            val servletResponse = (response as ServletServerHttpResponse).servletResponse

            // Шаг 1: валидация room_id
            val roomIdParam = extractRoomIdFromQuery(request.uri)
            val roomIdResult = RoomId.of(roomIdParam)
            if (roomIdResult.isFailure) {
                log.warn("Handshake rejected: {}", HandshakeResolution.INVALID_ROOM_ID.description)
                reject(servletResponse, HandshakeResolution.INVALID_ROOM_ID)
                return false
            }
            val roomId = roomIdResult.getOrThrow()
            LogContext.set(roomId = roomId.value)

            // Шаг 2: аутентификация
            val authResult = authenticator.authenticate(servletRequest)
            val userId = when (authResult) {
                is AuthenticationResult.Authenticated -> authResult.userId
                is AuthenticationResult.Missing -> {
                    log.info("Handshake rejected (UNAUTHORIZED) for room {}: missing token", roomId.value)
                    reject(servletResponse, HandshakeResolution.UNAUTHORIZED, addWwwAuthenticate = true)
                    return false
                }
                is AuthenticationResult.Invalid -> {
                    log.info("Handshake rejected (UNAUTHORIZED) for room {}: invalid token - {}", roomId.value, authResult.reason)
                    reject(servletResponse, HandshakeResolution.UNAUTHORIZED, addWwwAuthenticate = true)
                    return false
                }
                is AuthenticationResult.Unavailable -> {
                    log.error(
                        "Handshake rejected (AUTH_UNAVAILABLE) for room {}: auth service unavailable",
                        roomId.value,
                        authResult.cause,
                    )
                    reject(servletResponse, HandshakeResolution.AUTH_UNAVAILABLE)
                    return false
                }
            }
            LogContext.set(userId = userId)

            val resolution = roomRegistry.resolve(roomId)
            val room = when (resolution) {
                is RoomResolutionResult.Found -> resolution.room
                is RoomResolutionResult.NotFound -> {
                    log.info("Handshake rejected: room {} not found", roomId.value)
                    reject(servletResponse, HandshakeResolution.ROOM_NOT_FOUND)
                    return false
                }
                is RoomResolutionResult.Malformed -> {
                    log.error(
                        "Handshake rejected for room {}: malformed data - {}",
                        roomId.value,
                        resolution.reason,
                    )
                    reject(servletResponse, HandshakeResolution.ROOM_MALFORMED)
                    return false
                }
                is RoomResolutionResult.Unavailable -> {
                    log.error(
                        "Handshake rejected for room {}: Redis unavailable",
                        roomId.value,
                        resolution.cause,
                    )
                    reject(servletResponse, HandshakeResolution.REDIS_UNAVAILABLE)
                    return false
                }
            }

            if (room.status != RoomStatus.ACTIVE) {
                log.info(
                    "Handshake rejected for room {}: status is {}",
                    roomId.value,
                    room.status,
                )
                reject(servletResponse, HandshakeResolution.ROOM_NOT_ACTIVE)
                return false
            }

            if (room.ownerId != userId) {
                log.info("Handshake rejected for room {}: user {} is not the owner", roomId.value, userId)
                reject(servletResponse, HandshakeResolution.FORBIDDEN)
                return false
            }

            attributes[ATTR_CONNECTION_ID] = connectionId
            attributes[ATTR_ROOM_ID] = roomId.value
            attributes[ATTR_WORKER_BASE_URL] = room.workerBaseUrl()
            attributes[ATTR_USER_ID] = userId

            log.info("Handshake successful for room {} (user={})", roomId.value, userId)

            return true
        } finally {
            LogContext.clear()
        }
    }

    override fun afterHandshake(
        request: ServerHttpRequest,
        response: ServerHttpResponse,
        wsHandler: WebSocketHandler,
        exception: Exception?,
    ) {

    }

    private fun reject(
        response: HttpServletResponse,
        resolution: HandshakeResolution,
        addWwwAuthenticate: Boolean = false,
    ) {
        response.status = resolution.httpStatus
        if (addWwwAuthenticate) {
            response.setHeader(WWW_AUTHENTICATE_HEADER, BEARER_CHALLENGE)
        }
    }

    private fun extractRoomIdFromQuery(uri: URI): String? {
        val rawQuery = uri.rawQuery ?: return null
        return rawQuery.split("&")
            .asSequence()
            .map { it.split("=", limit = 2) }
            .filter { it.isNotEmpty() }
            .firstOrNull { pair ->
                decodeComponent(pair[0]) == QUERY_PARAM_ROOM_ID
            }
            ?.getOrNull(1)
            ?.let(::decodeComponent)
    }

    private fun decodeComponent(raw: String): String =
        URLDecoder.decode(raw, StandardCharsets.UTF_8)
}