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
import com.cloudgaming.signalingservice.observability.LogContext
import com.cloudgaming.signalingservice.observability.ObservabilityService
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
    private val observabilityService: ObservabilityService,
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
        observabilityService.sessionOpened(session.id)

        LogContext.set(
            connectionId = state.connectionId,
            roomId = state.roomId,
            userId = state.userId,
        )
        log.info("ws_connected")
        LogContext.clear()
    }

    public override fun handleTextMessage(session: WebSocketSession, message: TextMessage) {
        try {
            val state = sessions[session.id]
            if (state == null) {
                log.warn("WS {}: no SessionState found, ignoring message", session.id)
                return
            }

            LogContext.set(
                connectionId = state.connectionId,
                roomId = state.roomId,
                userId = state.userId,
                worker = state.workerBaseUrl,
            )

            val workerBaseUrl = session.attributes[SignalingHandshakeInterceptor.ATTR_WORKER_BASE_URL] as? String
            if (workerBaseUrl == null) {
                log.error(
                    "WS {}: missing workerBaseUrl in session attributes - interceptor bug",
                    session.id,
                )
                sendSafely(state, ServerMessage.Error(ErrorCode.INTERNAL_ERROR, "Server configuration error"))
                LogContext.clear()
                return
            }

            val clientMessage = try {
                objectMapper.readValue(message.payload, ClientMessage::class.java)
            } catch (e: Exception) {
                log.warn("WS {}: malformed message: {}", session.id, e.toString())
                sendSafely(state, ServerMessage.Error(ErrorCode.INVALID_MESSAGE, "Malformed message: ${e.message}"))
                LogContext.clear()
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
        } finally {
            LogContext.clear()
        }
    }

    override fun handleTransportError(session: WebSocketSession, exception: Throwable) {
        val state = sessions[session.id]
        if (state != null) {
            LogContext.set(
                connectionId = state.connectionId,
                roomId = state.roomId,
                userId = state.userId,
            )
        }

        log.warn("WS {}: transport error: {}", session.id, exception.toString())
        sessions.remove(session.id)
        observabilityService.sessionClosed(session.id)

        if (state != null) {
            val sessionDurationMs = java.time.Duration.between(state.createdAt, java.time.Instant.now()).toMillis()
            log.info("ws_closed closeCode=TRANSPORT_ERROR sessionDurationMs={}", sessionDurationMs)
        }

        LogContext.clear()
    }

    override fun afterConnectionClosed(session: WebSocketSession, closeStatus: CloseStatus) {
        val state = sessions[session.id]
        if (state != null) {
            LogContext.set(
                connectionId = state.connectionId,
                roomId = state.roomId,
                userId = state.userId,
            )
        }

        sessions.remove(session.id)
        observabilityService.sessionClosed(session.id)

        if (state != null) {
            val sessionDurationMs = java.time.Duration.between(state.createdAt, java.time.Instant.now()).toMillis()
            log.info("ws_closed closeCode={} sessionDurationMs={}", closeStatus.code, sessionDurationMs)
        }

        LogContext.clear()
    }

    private fun handleOffer(
        state: SessionState,
        offer: ClientMessage.Offer,
        workerBaseUrl: String,
    ) {
        val sessionId = state.decoratedSession.id

        LogContext.set(
            connectionId = state.connectionId,
            roomId = state.roomId,
            userId = state.userId,
            worker = workerBaseUrl,
        )

        log.info("offer_received")

        if (!state.tryStartOffer()) {
            log.info("WS {}: offer rejected - previous offer still in progress", sessionId)
            sendSafely(state, ServerMessage.Error(ErrorCode.OFFER_IN_PROGRESS, "Previous offer is still being processed"))
            log.info("offer_rejected errorCode={}", ErrorCode.OFFER_IN_PROGRESS.name)
            LogContext.clear()
            return
        }

        try {
            workerCallExecutor.execute {
                try {
                    processOffer(state, offer, workerBaseUrl)
                } finally {
                    state.finishOffer()
                    LogContext.clear()
                }
            }
        } catch (e: RejectedExecutionException) {
            log.warn("WS {}: executor pool exhausted", sessionId)
            state.finishOffer()
            sendSafely(state, ServerMessage.Error(ErrorCode.SERVER_BUSY, "Server is busy, try again later"))
            log.info("offer_rejected errorCode={}", ErrorCode.SERVER_BUSY.name)
            LogContext.clear()
        } catch (e: Exception) {
            log.error("WS {}: unexpected error submitting task to executor", sessionId, e)
            state.finishOffer()
            sendSafely(state, ServerMessage.Error(ErrorCode.INTERNAL_ERROR, "Unexpected server error"))
            LogContext.clear()
        }
    }

    private fun processOffer(
        state: SessionState,
        offer: ClientMessage.Offer,
        workerBaseUrl: String,
    ) {
        val sessionId = state.decoratedSession.id

        LogContext.set(
            connectionId = state.connectionId,
            roomId = state.roomId,
            userId = state.userId,
            worker = workerBaseUrl,
        )

        val startTime = System.nanoTime()
        var outcome = "SUCCESS"

        try {
            if (!workerSignalingClient.isReady(workerBaseUrl)) {
                outcome = ErrorCode.WORKER_NOT_READY.name
                log.info("WS {}: worker {} not ready", sessionId, workerBaseUrl)
                sendSafely(state, ServerMessage.Error(ErrorCode.WORKER_NOT_READY, "Worker is not ready"))
                return
            }

            log.info("sdp_call_started")
            val answer = workerSignalingClient.exchangeSdp(workerBaseUrl, offer.sdp)
            sendSafely(state, ServerMessage.Answer(sdp = answer.sdp))
        } catch (e: WorkerTimeoutException) {
            outcome = ErrorCode.WORKER_TIMEOUT.name
            log.warn("WS {}: {}", sessionId, e.message)
            sendSafely(state, ServerMessage.Error(ErrorCode.WORKER_TIMEOUT, e.message ?: "Worker timed out"))
        } catch (e: WorkerUnreachableException) {
            outcome = ErrorCode.WORKER_UNREACHABLE.name
            log.warn("WS {}: {}", sessionId, e.message)
            sendSafely(state, ServerMessage.Error(ErrorCode.WORKER_UNREACHABLE, e.message ?: "Worker unreachable"))
        } catch (e: WorkerRejectedOfferException) {
            outcome = ErrorCode.WORKER_REJECTED_OFFER.name
            log.info("WS {}: worker rejected offer: {}", sessionId, e.workerMessage)
            sendSafely(state, ServerMessage.Error(ErrorCode.WORKER_REJECTED_OFFER, e.workerMessage))
        } catch (e: WorkerProtocolException) {
            outcome = ErrorCode.WORKER_ERROR.name
            log.warn("WS {}: worker protocol error: {}", sessionId, e.message)
            sendSafely(state, ServerMessage.Error(ErrorCode.WORKER_ERROR, e.message ?: "Worker protocol error"))
        } catch (e: Exception) {
            outcome = ErrorCode.INTERNAL_ERROR.name
            log.error("WS {}: unexpected error processing offer", sessionId, e)
            sendSafely(state, ServerMessage.Error(ErrorCode.INTERNAL_ERROR, "Unexpected server error"))
        } finally {
            val durationMs = (System.nanoTime() - startTime) / 1_000_000
            observabilityService.recordSdpDuration(outcome, durationMs)
            LogContext.clear()
        }
    }

    private fun sendSafely(state: SessionState, message: ServerMessage) {
        val session = state.decoratedSession

        if (!session.isOpen) {
            LogContext.set(
                connectionId = state.connectionId,
                roomId = state.roomId,
            )
            log.info("answer_discarded")
            LogContext.clear()
            return
        }

        try {
            session.sendMessage(TextMessage(objectMapper.writeValueAsString(message)))
        } catch (e: Exception) {
            LogContext.set(
                connectionId = state.connectionId,
                roomId = state.roomId,
            )
            log.info(
                "WS {}: failed to send {} message: {}",
                session.id,
                message.type,
                e.toString(),
            )
            LogContext.clear()
        }
    }

    private companion object {
        const val SEND_TIME_LIMIT_MS = 10_000
        const val BUFFER_SIZE_LIMIT_BYTES = 512 * 1024
    }
}
