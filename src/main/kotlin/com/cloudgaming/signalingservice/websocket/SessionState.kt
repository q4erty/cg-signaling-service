package com.cloudgaming.signalingservice.websocket

import com.cloudgaming.signalingservice.observability.LogContext
import org.springframework.web.socket.WebSocketSession
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

class SessionState(
    session: WebSocketSession,
    sendTimeLimit: Int,
    bufferSizeLimit: Int,
) {
    val connectionId: String = LogContext.generateConnectionId()
    val decoratedSession: WebSocketSession = ConcurrentWebSocketSessionDecorator(
        session,
        sendTimeLimit,
        bufferSizeLimit,
    )
    val offerInProgress: AtomicBoolean = AtomicBoolean(false)
    val roomId: String? = session.attributes[SignalingHandshakeInterceptor.ATTR_ROOM_ID] as? String
    val userId: String? = session.attributes[SignalingHandshakeInterceptor.ATTR_USER_ID] as? String
    val workerBaseUrl: String? = session.attributes[SignalingHandshakeInterceptor.ATTR_WORKER_BASE_URL] as? String
    val createdAt: Instant = Instant.now()

    fun tryStartOffer(): Boolean = offerInProgress.compareAndSet(false, true)

    fun finishOffer() {
        offerInProgress.set(false)
    }
}
