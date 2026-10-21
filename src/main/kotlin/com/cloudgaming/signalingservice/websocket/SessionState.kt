package com.cloudgaming.signalingservice.websocket

import org.springframework.web.socket.WebSocketSession
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator
import java.util.concurrent.atomic.AtomicBoolean

class SessionState(
    session: WebSocketSession,
    sendTimeLimit: Int,
    bufferSizeLimit: Int,
) {
    val decoratedSession: WebSocketSession = ConcurrentWebSocketSessionDecorator(
        session,
        sendTimeLimit,
        bufferSizeLimit,
    )

    val offerInProgress: AtomicBoolean = AtomicBoolean(false)

    val roomId: String? = session.attributes[SignalingHandshakeInterceptor.ATTR_ROOM_ID] as? String

    fun tryStartOffer(): Boolean = offerInProgress.compareAndSet(false, true)

    fun finishOffer() {
        offerInProgress.set(false)
    }
}