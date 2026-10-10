package com.cloudgaming.signalingservice.websocket

import com.cloudgaming.signalingservice.model.RoomId
import com.cloudgaming.signalingservice.repository.RoomRegistry
import com.cloudgaming.signalingservice.model.RoomResolutionResult
import com.cloudgaming.signalingservice.model.RoomStatus
import org.slf4j.LoggerFactory
import org.springframework.http.server.ServerHttpRequest
import org.springframework.http.server.ServerHttpResponse
import org.springframework.http.server.ServletServerHttpRequest
import org.springframework.http.server.ServletServerHttpResponse
import org.springframework.stereotype.Component
import org.springframework.web.socket.WebSocketHandler
import org.springframework.web.socket.server.HandshakeInterceptor
import java.net.URI

@Component
class SignalingHandshakeInterceptor(
    private val roomRegistry: RoomRegistry,
) : HandshakeInterceptor {

    private val log = LoggerFactory.getLogger(SignalingHandshakeInterceptor::class.java)

    companion object {
        const val ATTR_ROOM_ID = "roomId"
        const val ATTR_WORKER_BASE_URL = "workerBaseUrl"
        private const val QUERY_PARAM_ROOM_ID = "room_id"
    }

    override fun beforeHandshake(
        request: ServerHttpRequest,
        response: ServerHttpResponse,
        wsHandler: WebSocketHandler,
        attributes: MutableMap<String, Any>,
    ): Boolean {
        val servletRequest = (request as ServletServerHttpRequest).servletRequest
        val servletResponse = (response as ServletServerHttpResponse).servletResponse

        val roomIdParam = extractRoomIdFromQuery(request.uri)
        val roomIdResult = RoomId.of(roomIdParam)

        if (roomIdResult.isFailure) {
            log.warn("Handshake rejected: {}", HandshakeResolution.INVALID_ROOM_ID.description)
            servletResponse.status = HandshakeResolution.INVALID_ROOM_ID.httpStatus
            return false
        }

        val roomId = roomIdResult.getOrThrow()
        val resolution = roomRegistry.resolve(roomId)

        val resolutionResult = when (resolution) {
            is RoomResolutionResult.Found -> {
                if (resolution.room.status != RoomStatus.ACTIVE) {
                    log.info(
                        "Handshake rejected for room {}: status is {}",
                        roomId.value,
                        resolution.room.status,
                    )
                    servletResponse.status = HandshakeResolution.ROOM_NOT_ACTIVE.httpStatus
                    return false
                }

                attributes[ATTR_ROOM_ID] = roomId.value
                attributes[ATTR_WORKER_BASE_URL] = resolution.room.workerBaseUrl()

                log.info("Handshake successful for room {}", roomId.value)
                true
            }

            is RoomResolutionResult.NotFound -> {
                log.info("Handshake rejected: room {} not found", roomId.value)
                servletResponse.status = HandshakeResolution.ROOM_NOT_FOUND.httpStatus
                false
            }

            is RoomResolutionResult.Malformed -> {
                log.error(
                    "Handshake rejected for room {}: malformed data - {}",
                    roomId.value,
                    resolution.reason,
                )
                servletResponse.status = HandshakeResolution.ROOM_MALFORMED.httpStatus
                false
            }

            is RoomResolutionResult.Unavailable -> {
                log.error(
                    "Handshake rejected for room {}: Redis unavailable",
                    roomId.value,
                    resolution.cause,
                )
                servletResponse.status = HandshakeResolution.REDIS_UNAVAILABLE.httpStatus
                false
            }
        }

        return resolutionResult
    }

    override fun afterHandshake(
        request: ServerHttpRequest,
        response: ServerHttpResponse,
        wsHandler: WebSocketHandler,
        exception: Exception?,
    ) {
        // No-op
    }

    private fun extractRoomIdFromQuery(uri: URI): String? {
        val query = uri.query ?: return null
        return query.split("&")
            .map { it.split("=", limit = 2) }
            .firstOrNull { it[0] == QUERY_PARAM_ROOM_ID }
            ?.getOrNull(1)
    }
}