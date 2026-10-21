package com.cloudgaming.signalingservice.websocket

import com.cloudgaming.signalingservice.client.WorkerSignalingClient
import com.cloudgaming.signalingservice.config.WorkerProperties
import com.cloudgaming.signalingservice.dto.ClientMessage
import com.cloudgaming.signalingservice.dto.ErrorCode
import com.cloudgaming.signalingservice.dto.ServerMessage
import com.cloudgaming.signalingservice.exception.WorkerProtocolException
import com.cloudgaming.signalingservice.exception.WorkerRejectedOfferException
import com.cloudgaming.signalingservice.exception.WorkerTimeoutException
import com.cloudgaming.signalingservice.exception.WorkerUnreachableException
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketSession
import org.springframework.web.socket.handler.TextWebSocketHandler
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException

@Component
class SignalingWebSocketHandler(
    private val workerSignalingClient: WorkerSignalingClient,
    private val objectMapper: ObjectMapper,
    private val workerCallExecutor: ExecutorService,
    private val workerProperties: WorkerProperties,
) : TextWebSocketHandler() {

    private val log = LoggerFactory.getLogger(SignalingWebSocketHandler::class.java)
    private val sessions = ConcurrentHashMap<String, SessionState>()

    override fun afterConnectionEstablished(session: WebSocketSession) {
        val state = SessionState(
            session = session,
            sendTimeLimit = SEND_TIME_LIMIT_MS,
            bufferSizeLimit = BUFFER_SIZE_LIMIT_BYTES,
        )
        sessions[session.id] = state
        log.info("WS {}: connected (room={})", session.id, state.roomId ?: "unknown")
    }

    public override fun handleTextMessage(session: WebSocketSession, message: TextMessage) {
        try {
            val state = sessions[session.id]
            if (state == null) {
                log.warn("WS {}: no SessionState found, ignoring message", session.id)
                return
            }

            val workerBaseUrl = session.attributes[SignalingHandshakeInterceptor.ATTR_WORKER_BASE_URL] as? String
            if (workerBaseUrl == null) {
                log.error(
                    "WS {}: missing workerBaseUrl in session attributes - interceptor bug",
                    session.id,
                )
                sendSafely(state, ServerMessage.Error(ErrorCode.INTERNAL_ERROR, "Server configuration error"))
                return
            }

            val clientMessage = try {
                objectMapper.readValue(message.payload, ClientMessage::class.java)
            } catch (e: Exception) {
                log.warn("WS {}: malformed message: {}", session.id, e.toString())
                sendSafely(state, ServerMessage.Error(ErrorCode.INVALID_MESSAGE, "Malformed message: ${e.message}"))
                return
            }

            when (clientMessage) {
                is ClientMessage.Offer -> handleOffer(state, clientMessage, workerBaseUrl)
            }
        } catch (e: Exception) {
            log.error("WS {}: unexpected error in handleTextMessage", session.id, e)
            val state = sessions[session.id]
            if (state != null) {
                sendSafely(state, ServerMessage.Error(ErrorCode.INTERNAL_ERROR, "Unexpected server error"))
            }
        }
    }

    override fun handleTransportError(session: WebSocketSession, exception: Throwable) {
        log.warn("WS {}: transport error: {}", session.id, exception.toString())
        sessions.remove(session.id)
    }

    override fun afterConnectionClosed(session: WebSocketSession, closeStatus: CloseStatus) {
        sessions.remove(session.id)
        log.info("WS {}: closed ({})", session.id, closeStatus)
    }

    private fun handleOffer(
        state: SessionState,
        offer: ClientMessage.Offer,
        workerBaseUrl: String,
    ) {
        val sessionId = state.decoratedSession.id

        if (!state.tryStartOffer()) {
            log.info("WS {}: offer rejected - previous offer still in progress", sessionId)
            sendSafely(state, ServerMessage.Error(ErrorCode.OFFER_IN_PROGRESS, "Previous offer is still being processed"))
            return
        }

        try {
            workerCallExecutor.execute {
                try {
                    processOffer(state, offer, workerBaseUrl)
                } finally {
                    state.finishOffer()
                }
            }
        } catch (e: RejectedExecutionException) {
            log.warn("WS {}: executor pool exhausted", sessionId)
            state.finishOffer()
            sendSafely(state, ServerMessage.Error(ErrorCode.SERVER_BUSY, "Server is busy, try again later"))
        } catch (e: Exception) {
            log.error("WS {}: unexpected error submitting task to executor", sessionId, e)
            state.finishOffer()
            sendSafely(state, ServerMessage.Error(ErrorCode.INTERNAL_ERROR, "Unexpected server error"))
        }
    }

    private fun processOffer(
        state: SessionState,
        offer: ClientMessage.Offer,
        workerBaseUrl: String,
    ) {
        val sessionId = state.decoratedSession.id

        try {
            if (!workerSignalingClient.isReady(workerBaseUrl)) {
                log.info("WS {}: worker {} not ready", sessionId, workerBaseUrl)
                sendSafely(state, ServerMessage.Error(ErrorCode.WORKER_NOT_READY, "Worker is not ready"))
                return
            }

            val answer = workerSignalingClient.exchangeSdp(workerBaseUrl, offer.sdp)
            sendSafely(state, ServerMessage.Answer(sdp = answer.sdp))
        } catch (e: WorkerTimeoutException) {
            log.warn("WS {}: {}", sessionId, e.message)
            sendSafely(state, ServerMessage.Error(ErrorCode.WORKER_TIMEOUT, e.message ?: "Worker timed out"))
        } catch (e: WorkerUnreachableException) {
            log.warn("WS {}: {}", sessionId, e.message)
            sendSafely(state, ServerMessage.Error(ErrorCode.WORKER_UNREACHABLE, e.message ?: "Worker unreachable"))
        } catch (e: WorkerRejectedOfferException) {
            log.info("WS {}: worker rejected offer: {}", sessionId, e.workerMessage)
            sendSafely(state, ServerMessage.Error(ErrorCode.WORKER_REJECTED_OFFER, e.workerMessage))
        } catch (e: WorkerProtocolException) {
            log.warn("WS {}: worker protocol error: {}", sessionId, e.message)
            sendSafely(state, ServerMessage.Error(ErrorCode.WORKER_ERROR, e.message ?: "Worker protocol error"))
        } catch (e: Exception) {
            log.error("WS {}: unexpected error processing offer", sessionId, e)
            sendSafely(state, ServerMessage.Error(ErrorCode.INTERNAL_ERROR, "Unexpected server error"))
        }
    }

    private fun sendSafely(state: SessionState, message: ServerMessage) {
        val session = state.decoratedSession

        if (!session.isOpen) {
            log.info(
                "WS {}: session closed, dropping {} response (room={})",
                session.id,
                message.type,
                state.roomId ?: "unknown",
            )
            return
        }

        try {
            session.sendMessage(TextMessage(objectMapper.writeValueAsString(message)))
        } catch (e: Exception) {
            log.info(
                "WS {}: failed to send {} message: {} (room={})",
                session.id,
                message.type,
                e.toString(),
                state.roomId ?: "unknown",
            )
        }
    }

    private companion object {
        const val SEND_TIME_LIMIT_MS = 10_000
        const val BUFFER_SIZE_LIMIT_BYTES = 512 * 1024
    }
}
