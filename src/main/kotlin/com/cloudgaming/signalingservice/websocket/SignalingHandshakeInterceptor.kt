package com.cloudgaming.signalingservice.websocket

import com.cloudgaming.signalingservice.observability.LogContext
import com.cloudgaming.signalingservice.model.RoomId
import com.cloudgaming.signalingservice.model.RoomStatus
import com.cloudgaming.signalingservice.repository.RoomRegistry
import com.cloudgaming.signalingservice.model.RoomResolutionResult
import com.cloudgaming.signalingservice.security.AuthenticationResult
import com.cloudgaming.signalingservice.security.HandshakeAuthenticator
import jakarta.servlet.http.HttpServletRequest
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

        val servletRequest = (request as ServletServerHttpRequest).servletRequest
        val servletResponse = (response as ServletServerHttpResponse).servletResponse

        val roomIdParam = extractRoomIdFromQuery(request.uri)
        val roomIdResult = RoomId.of(roomIdParam)

        if (roomIdResult.isFailure) {
            logHandshakeRejected(connectionId, roomId = null, HandshakeResolution.INVALID_ROOM_ID)
            reject(servletResponse, HandshakeResolution.INVALID_ROOM_ID)
            LogContext.clear()
            return false
        }

        val roomId = roomIdResult.getOrThrow()
        LogContext.set(roomId = roomId.value)

        val authResult = authenticator.authenticate(servletRequest)
        val userId = when (authResult) {
            is AuthenticationResult.Authenticated -> authResult.userId
            is AuthenticationResult.Missing -> {
                logHandshakeRejected(connectionId, roomId.value, HandshakeResolution.UNAUTHORIZED)
                reject(servletResponse, HandshakeResolution.UNAUTHORIZED, addWwwAuthenticate = true)
                LogContext.clear()
                return false
            }
            is AuthenticationResult.Invalid -> {
                logHandshakeRejected(connectionId, roomId.value, HandshakeResolution.UNAUTHORIZED)
                reject(servletResponse, HandshakeResolution.UNAUTHORIZED, addWwwAuthenticate = true)
                LogContext.clear()
                return false
            }
            is AuthenticationResult.Unavailable -> {
                logHandshakeRejected(connectionId, roomId.value, HandshakeResolution.AUTH_UNAVAILABLE)
                reject(servletResponse, HandshakeResolution.AUTH_UNAVAILABLE)
                LogContext.clear()
                return false
            }
        }

        LogContext.set(userId = userId)

        val resolution = roomRegistry.resolve(roomId)
        val room = when (resolution) {
            is RoomResolutionResult.Found -> resolution.room
            is RoomResolutionResult.NotFound -> {
                logHandshakeRejected(connectionId, roomId.value, HandshakeResolution.ROOM_NOT_FOUND)
                reject(servletResponse, HandshakeResolution.ROOM_NOT_FOUND)
                LogContext.clear()
                return false
            }
            is RoomResolutionResult.Malformed -> {
                logHandshakeRejected(connectionId, roomId.value, HandshakeResolution.ROOM_MALFORMED)
                reject(servletResponse, HandshakeResolution.ROOM_MALFORMED)
                LogContext.clear()
                return false
            }
            is RoomResolutionResult.Unavailable -> {
                logHandshakeRejected(connectionId, roomId.value, HandshakeResolution.REDIS_UNAVAILABLE)
                reject(servletResponse, HandshakeResolution.REDIS_UNAVAILABLE)
                LogContext.clear()
                return false
            }
        }

        if (room.status != RoomStatus.ACTIVE) {
            logHandshakeRejected(connectionId, roomId.value, HandshakeResolution.ROOM_NOT_ACTIVE)
            reject(servletResponse, HandshakeResolution.ROOM_NOT_ACTIVE)
            LogContext.clear()
            return false
        }

        if (room.ownerId != userId) {
            logHandshakeRejected(connectionId, roomId.value, HandshakeResolution.FORBIDDEN)
            reject(servletResponse, HandshakeResolution.FORBIDDEN)
            LogContext.clear()
            return false
        }

        val workerBaseUrl = room.workerBaseUrl()
        LogContext.set(worker = workerBaseUrl)

        attributes[ATTR_ROOM_ID] = roomId.value
        attributes[ATTR_WORKER_BASE_URL] = workerBaseUrl
        attributes[ATTR_USER_ID] = userId
        attributes[ATTR_CONNECTION_ID] = connectionId

        log.info("room_resolved worker={}", workerBaseUrl)

        return true
    }

    override fun afterHandshake(
        request: ServerHttpRequest,
        response: ServerHttpResponse,
        wsHandler: WebSocketHandler,
        exception: Exception?,
    ) {
        LogContext.clear()
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

    private fun logHandshakeRejected(connectionId: String, roomId: String?, resolution: HandshakeResolution) {
        LogContext.set(connectionId = connectionId, roomId = roomId)
        log.info(
            "handshake_rejected httpStatus={} reason={}",
            resolution.httpStatus,
            resolution.name,
        )
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