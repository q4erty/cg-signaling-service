package com.cloudgaming.signalingservice.websocket

import com.cloudgaming.signalingservice.dto.ErrorCode
import com.cloudgaming.signalingservice.client.SdpAnswer
import com.cloudgaming.signalingservice.client.WorkerSignalingClient
import com.cloudgaming.signalingservice.exception.WorkerTimeoutException
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.mockk.CapturingSlot
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketSession
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit

private class DirectExecutorService : AbstractExecutorService() {
    private var terminated = false
    override fun execute(command: Runnable) = command.run()
    override fun shutdown() { terminated = true }
    override fun shutdownNow(): MutableList<Runnable> { terminated = true; return mutableListOf() }
    override fun isShutdown(): Boolean = terminated
    override fun isTerminated(): Boolean = terminated
    override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean = true
}

class SignalingWebSocketHandlerTest {

    private val workerSignalingClient = mockk<WorkerSignalingClient>()
    private val objectMapper = jacksonObjectMapper()
    private val workerBaseUrl = "http://worker.local:9090"

    private lateinit var handler: SignalingWebSocketHandler
    private lateinit var session: WebSocketSession
    private lateinit var sentMessage: CapturingSlot<TextMessage>

    @BeforeEach
    fun setUp() {
        handler = SignalingWebSocketHandler(
            workerSignalingClient = workerSignalingClient,
            objectMapper = objectMapper,
            workerCallExecutor = DirectExecutorService(),
        )

        session = mockk(relaxed = true)
        every { session.id } returns "test-session-1"
        every { session.isOpen } returns true
        every { session.attributes } returns mutableMapOf(
            SignalingHandshakeInterceptor.ATTR_WORKER_BASE_URL to workerBaseUrl
        )

        sentMessage = slot()
        every { session.sendMessage(capture(sentMessage)) } returns Unit

        handler.afterConnectionEstablished(session)
    }

    private fun capturedSentJson() = objectMapper.readTree(sentMessage.captured.payload)

    @Test
    fun `offer that the worker accepts results in an answer message`() {
        every { workerSignalingClient.isReady(workerBaseUrl) } returns true
        every { workerSignalingClient.exchangeSdp(workerBaseUrl, "v=0...offer") } returns
                SdpAnswer(sdp = "v=0...answer")

        handler.handleTextMessage(session, TextMessage("""{"type": "offer", "sdp": "v=0...offer"}"""))

        val response = capturedSentJson()
        assertThat(response["type"].asText()).isEqualTo("answer")
        assertThat(response["sdp"].asText()).isEqualTo("v=0...answer")
    }

    @Test
    fun `offer while the worker is not ready results in WORKER_NOT_READY, exchangeSdp never called`() {
        every { workerSignalingClient.isReady(workerBaseUrl) } returns false

        handler.handleTextMessage(session, TextMessage("""{"type": "offer", "sdp": "v=0...offer"}"""))

        val response = capturedSentJson()
        assertThat(response["type"].asText()).isEqualTo("error")
        assertThat(response["code"].asText()).isEqualTo(ErrorCode.WORKER_NOT_READY.name)
        verify(exactly = 0) { workerSignalingClient.exchangeSdp(any(), any()) }
    }

    @Test
    fun `a worker timeout is surfaced as WORKER_TIMEOUT`() {
        every { workerSignalingClient.isReady(any()) } returns true
        every { workerSignalingClient.exchangeSdp(any(), any()) } throws
                WorkerTimeoutException(workerBaseUrl)

        handler.handleTextMessage(session, TextMessage("""{"type": "offer", "sdp": "v=0...offer"}"""))

        val response = capturedSentJson()
        assertThat(response["type"].asText()).isEqualTo("error")
        assertThat(response["code"].asText()).isEqualTo(ErrorCode.WORKER_TIMEOUT.name)
    }

    @Test
    fun `malformed JSON results in INVALID_MESSAGE without ever calling the worker`() {
        handler.handleTextMessage(session, TextMessage("not json at all"))

        val response = capturedSentJson()
        assertThat(response["type"].asText()).isEqualTo("error")
        assertThat(response["code"].asText()).isEqualTo(ErrorCode.INVALID_MESSAGE.name)
        verify(exactly = 0) { workerSignalingClient.isReady(any()) }
        verify(exactly = 0) { workerSignalingClient.exchangeSdp(any(), any()) }
    }

    @Test
    fun `an unexpected exception from the client is surfaced as INTERNAL_ERROR, not a crash`() {
        every { workerSignalingClient.isReady(any()) } returns true
        every { workerSignalingClient.exchangeSdp(any(), any()) } throws IllegalStateException("boom")

        handler.handleTextMessage(session, TextMessage("""{"type": "offer", "sdp": "v=0...offer"}"""))

        val response = capturedSentJson()
        assertThat(response["type"].asText()).isEqualTo("error")
        assertThat(response["code"].asText()).isEqualTo(ErrorCode.INTERNAL_ERROR.name)
    }

    @Test
    fun `closing the session forgets it, so a stray message after close is not sent anywhere`() {
        handler.afterConnectionClosed(session, org.springframework.web.socket.CloseStatus.NORMAL)
        every { session.isOpen } returns false
        every { workerSignalingClient.isReady(any()) } returns true
        every { workerSignalingClient.exchangeSdp(any(), any()) } returns SdpAnswer(sdp = "v=0...answer")

        handler.handleTextMessage(session, TextMessage("""{"type": "offer", "sdp": "v=0...offer"}"""))

        assertThat(sentMessage.isCaptured).isFalse()
    }

    @Test
    fun `missing workerBaseUrl in session attributes closes session with SERVER_ERROR`() {
        val sessionWithoutWorker = mockk<WebSocketSession>(relaxed = true)
        every { sessionWithoutWorker.id } returns "session-without-worker"
        every { sessionWithoutWorker.isOpen } returns true
        every { sessionWithoutWorker.attributes } returns mutableMapOf()

        handler.handleTextMessage(sessionWithoutWorker, TextMessage("""{"type": "offer", "sdp": "v=0...offer"}"""))

        verify { sessionWithoutWorker.close(org.springframework.web.socket.CloseStatus.SERVER_ERROR) }
        verify(exactly = 0) { workerSignalingClient.isReady(any()) }
        verify(exactly = 0) { workerSignalingClient.exchangeSdp(any(), any()) }
    }
}