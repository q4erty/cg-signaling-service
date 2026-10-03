package com.cloudgaming.signalingservice.signaling

import com.cloudgaming.signalingservice.config.WorkerProperties
import com.cloudgaming.signalingservice.dto.ClientMessage
import com.cloudgaming.signalingservice.dto.ErrorCode
import com.cloudgaming.signalingservice.dto.ServerMessage
import com.cloudgaming.signalingservice.worker.WorkerProtocolException
import com.cloudgaming.signalingservice.worker.WorkerRejectedOfferException
import com.cloudgaming.signalingservice.worker.WorkerSignalingClient
import com.cloudgaming.signalingservice.worker.WorkerTimeoutException
import com.cloudgaming.signalingservice.worker.WorkerUnreachableException
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketSession
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator
import org.springframework.web.socket.handler.TextWebSocketHandler
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import kotlin.jvm.java

@Component
class SignalingWebSocketHandler(
    private val workerSignalingClient: WorkerSignalingClient,
    private val workerProperties: WorkerProperties,
    private val objectMapper: ObjectMapper,
    private val workerCallExecutor: ExecutorService,
) : TextWebSocketHandler() {

    private val log = LoggerFactory.getLogger(SignalingWebSocketHandler::class.java)

    private val sessions = ConcurrentHashMap<String, WebSocketSession>()

    override fun afterConnectionEstablished(session: WebSocketSession) {
        sessions[session.id] = ConcurrentWebSocketSessionDecorator(
            session,
            SEND_TIME_LIMIT_MS,
            BUFFER_SIZE_LIMIT_BYTES,
        )
        log.info("WS {}: connected", session.id)
    }

    public override fun handleTextMessage(session: WebSocketSession, message: TextMessage) {
        val safeSession = sessions[session.id] ?: session

        val clientMessage = try {
            objectMapper.readValue(message.payload, ClientMessage::class.java)
        } catch (e: Exception) {
            log.warn("WS {}: malformed message: {}", session.id, e.toString())
            send(safeSession, ServerMessage.Error(ErrorCode.INVALID_MESSAGE, "Malformed message: ${e.message}"))
            return
        }

        when (clientMessage) {
            is ClientMessage.Offer -> handleOffer(safeSession, clientMessage)
        }
    }

    override fun handleTransportError(session: WebSocketSession, exception: Throwable) {
        log.warn("WS {}: transport error: {}", session.id, exception.toString())
    }

    override fun afterConnectionClosed(session: WebSocketSession, closeStatus: CloseStatus) {
        sessions.remove(session.id)
        log.info("WS {}: closed ({})", session.id, closeStatus)
    }

    private fun handleOffer(session: WebSocketSession, offer: ClientMessage.Offer) {
        val sessionId = session.id
        val workerBaseUrl = resolveWorkerBaseUrl()

        workerCallExecutor.execute {
            try {
                if (!workerSignalingClient.isReady(workerBaseUrl)) {
                    log.info("WS {}: worker {} not ready yet", sessionId, workerBaseUrl)
                    send(session, ServerMessage.Error(ErrorCode.WORKER_NOT_READY, "Worker is not ready yet"))
                    return@execute
                }

                val answer = workerSignalingClient.exchangeSdp(workerBaseUrl, offer.sdp)
                send(session, ServerMessage.Answer(sdp = answer.sdp))
            } catch (e: WorkerTimeoutException) {
                log.warn("WS {}: {}", sessionId, e.message)
                send(session, ServerMessage.Error(ErrorCode.WORKER_TIMEOUT, e.message ?: "Worker timed out"))
            } catch (e: WorkerUnreachableException) {
                log.warn("WS {}: {}", sessionId, e.message)
                send(session, ServerMessage.Error(ErrorCode.WORKER_UNREACHABLE, e.message ?: "Worker unreachable"))
            } catch (e: WorkerRejectedOfferException) {
                log.info("WS {}: worker rejected offer: {}", sessionId, e.workerMessage)
                send(session, ServerMessage.Error(ErrorCode.WORKER_REJECTED_OFFER, e.workerMessage))
            } catch (e: WorkerProtocolException) {
                log.error("WS {}: unexpected worker protocol error", sessionId, e)
                send(session, ServerMessage.Error(ErrorCode.INTERNAL_ERROR, "Unexpected error talking to the worker"))
            } catch (e: Exception) {
                log.error("WS {}: unexpected error handling offer", sessionId, e)
                send(session, ServerMessage.Error(ErrorCode.INTERNAL_ERROR, "Unexpected server error"))
            }
        }
    }

    private fun resolveWorkerBaseUrl(): String = workerProperties.baseUrl

    private fun send(session: WebSocketSession, message: ServerMessage) {
        if (!session.isOpen) {
            log.debug("WS {}: session already closed, dropping {} response", session.id, message.type)
            return
        }
        try {
            session.sendMessage(TextMessage(objectMapper.writeValueAsString(message)))
        } catch (e: Exception) {
            log.warn("WS {}: failed to send {} message: {}", session.id, message.type, e.toString())
        }
    }

    private companion object {
        const val SEND_TIME_LIMIT_MS = 10_000
        const val BUFFER_SIZE_LIMIT_BYTES = 512 * 1024
    }
}
