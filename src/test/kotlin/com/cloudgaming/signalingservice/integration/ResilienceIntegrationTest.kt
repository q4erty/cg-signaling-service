package com.cloudgaming.signalingservice.integration

import com.cloudgaming.signalingservice.dto.ClientMessage
import com.cloudgaming.signalingservice.dto.ErrorCode
import com.cloudgaming.signalingservice.dto.ServerMessage
import com.cloudgaming.signalingservice.model.Room
import com.cloudgaming.signalingservice.model.RoomId
import com.cloudgaming.signalingservice.model.RoomStatus
import com.cloudgaming.signalingservice.repository.RoomRegistry
import com.cloudgaming.signalingservice.model.RoomResolutionResult
import com.cloudgaming.signalingservice.security.AuthenticationResult
import com.cloudgaming.signalingservice.security.HandshakeAuthenticator
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.mockk.every
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketSession
import org.springframework.web.socket.client.standard.StandardWebSocketClient
import org.springframework.web.socket.handler.TextWebSocketHandler
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

//@SpringBootTest(
//    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
//    properties = [
//        "worker.ready-timeout=500ms",
//        "worker.sdp-timeout=2s",
//        "worker.connect-timeout=500ms",
//        "worker.call-executor-pool-size=2",
//        "websocket.idle-timeout=10s",
//        "auth.issuer-uri=https://test-issuer.example.com",
//        "auth.jwk-set-uri=http://localhost:18080/.well-known/jwks.json",
//    ],
//)
@Disabled("Requires JWT test infrastructure - see Ticket 5")
class ResilienceIntegrationTest {

    @MockitoBean
    private lateinit var roomRegistry: RoomRegistry

    @MockitoBean
    private lateinit var handshakeAuthenticator: HandshakeAuthenticator

    @LocalServerPort
    private var port: Int = 0

    private lateinit var worker: MockWebServer
    private val objectMapper = jacksonObjectMapper()
    private val userId = "test-user"

    @BeforeEach
    fun setUp() {
        every { handshakeAuthenticator.authenticate(any()) } returns AuthenticationResult.Authenticated(userId)

        worker = MockWebServer()
        worker.start()

        val room = Room(
            roomId = RoomId.of("test-room").getOrThrow(),
            workerIp = "localhost",
            webrtcPort = worker.port,
            gameId = "xonotic",
            status = RoomStatus.ACTIVE,
            createdAt = Instant.now(),
            ownerId = userId,
        )
        every { roomRegistry.resolve(any()) } returns RoomResolutionResult.Found(room)
    }

    @AfterEach
    fun tearDown() {
        worker.shutdown()
    }

    @Test
    fun `repeated offer - two offers in same session get two answers`() {
        val sdpRequestCount = AtomicInteger(0)
        worker.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return when (request.path) {
                    "/ready" -> MockResponse().setBody("""{"status":"ready","pipeline":"PLAYING"}""")
                    "/sdp" -> {
                        val count = sdpRequestCount.incrementAndGet()
                        MockResponse().setBody("""{"sdp":"answer-$count","type":"answer"}""")
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        val (session1, handler1) = connect()
        val answer1 = sendOfferAndWaitForAnswer(handler1, session1, "offer-1")
        val answer2 = sendOfferAndWaitForAnswer(handler1, session1, "offer-2")

        assertEquals("answer-1", answer1)
        assertEquals("answer-2", answer2)
        assertEquals(2, sdpRequestCount.get())

        assertEquals(session1.id, session1.id)
    }

    @Test
    fun `recovery - worker unreachable then back online, same session works`() {
        worker.shutdown()

        val (session, handler) = connect()
        val error1 = sendOfferAndWaitForError(handler, session, "offer-1")
        assertEquals(ErrorCode.WORKER_UNREACHABLE, error1.code)

        assertTrue(session.isOpen)

        worker = MockWebServer()
        worker.start()

        val room = Room(
            roomId = RoomId.of("test-room").getOrThrow(),
            workerIp = "localhost",
            webrtcPort = worker.port,
            gameId = "xonotic",
            status = RoomStatus.ACTIVE,
            createdAt = Instant.now(),
            ownerId = userId,
        )
        every { roomRegistry.resolve(any()) } returns RoomResolutionResult.Found(room)

        worker.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return when (request.path) {
                    "/ready" -> MockResponse().setBody("""{"status":"ready","pipeline":"PLAYING"}""")
                    "/sdp" -> MockResponse().setBody("""{"sdp":"answer-2","type":"answer"}""")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        val answer2 = sendOfferAndWaitForAnswer(handler, session, "offer-2")
        assertEquals("answer-2", answer2)
    }

    @Test
    fun `worker not ready - GET ready returns 503`() {
        worker.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return when (request.path) {
                    "/ready" -> MockResponse().setResponseCode(503)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        val (session, handler) = connect()
        val error = sendOfferAndWaitForError(handler, session, "offer-1")

        assertEquals(ErrorCode.WORKER_NOT_READY, error.code)
        assertTrue(session.isOpen)
    }

    @Test
    fun `worker error - POST sdp returns 500`() {
        worker.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return when (request.path) {
                    "/ready" -> MockResponse().setBody("""{"status":"ready","pipeline":"PLAYING"}""")
                    "/sdp" -> MockResponse().setResponseCode(500).setBody("Internal server error")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        val (session, handler) = connect()
        val error = sendOfferAndWaitForError(handler, session, "offer-1")

        assertEquals(ErrorCode.WORKER_ERROR, error.code)
        assertTrue(session.isOpen)
    }

    @Test
    fun `worker rejected offer - POST sdp returns 400`() {
        worker.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return when (request.path) {
                    "/ready" -> MockResponse().setBody("""{"status":"ready","pipeline":"PLAYING"}""")
                    "/sdp" -> MockResponse()
                        .setResponseCode(400)
                        .setBody("""{"detail":"Invalid SDP format"}""")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        val (session, handler) = connect()
        val error = sendOfferAndWaitForError(handler, session, "offer-1")

        assertEquals(ErrorCode.WORKER_REJECTED_OFFER, error.code)
        assertTrue(error.message.contains("Invalid SDP format"))
        assertTrue(session.isOpen)
    }

    @Test
    fun `connection reset during sdp - WORKER_UNREACHABLE quickly`() {
        val startTime = System.currentTimeMillis()

        worker.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return when (request.path) {
                    "/ready" -> MockResponse().setBody("""{"status":"ready","pipeline":"PLAYING"}""")
                    "/sdp" -> MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_DURING_REQUEST_BODY)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        val (session, handler) = connect()
        val error = sendOfferAndWaitForError(handler, session, "offer-1")

        val elapsed = System.currentTimeMillis() - startTime

        assertEquals(ErrorCode.WORKER_UNREACHABLE, error.code)
        assertTrue(session.isOpen)

        assertTrue(elapsed < 1000, "Connection reset should be detected quickly, took ${elapsed}ms")
    }

    @Test
    fun `read timeout - WORKER_TIMEOUT after sdpTimeout`() {
        worker.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return when (request.path) {
                    "/ready" -> MockResponse().setBody("""{"status":"ready","pipeline":"PLAYING"}""")
                    "/sdp" -> MockResponse()
                        .setBody("""{"sdp":"answer","type":"answer"}""")
                        .setBodyDelay(5, TimeUnit.SECONDS)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        val (session, handler) = connect()
        val error = sendOfferAndWaitForError(handler, session, "offer-1")

        assertEquals(ErrorCode.WORKER_TIMEOUT, error.code)
        assertTrue(session.isOpen)
    }

    @Test
    fun `offer in progress - second offer gets OFFER_IN_PROGRESS`() {
        val releaseLatch = CountDownLatch(1)
        val sdpRequestCount = AtomicInteger(0)

        worker.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return when (request.path) {
                    "/ready" -> MockResponse().setBody("""{"status":"ready","pipeline":"PLAYING"}""")
                    "/sdp" -> {
                        sdpRequestCount.incrementAndGet()
                        // Блокируем до release
                        releaseLatch.await(10, TimeUnit.SECONDS)
                        MockResponse().setBody("""{"sdp":"answer-1","type":"answer"}""")
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        val (session, handler) = connect()

        val offer1 = ClientMessage.Offer(sdp = "offer-1")
        handler.sendMessage(session, TextMessage(objectMapper.writeValueAsString(offer1)))

        await().atMost(2, TimeUnit.SECONDS).until { sdpRequestCount.get() == 1 }

        val error2 = sendOfferAndWaitForError(handler, session, "offer-2")
        assertEquals(ErrorCode.OFFER_IN_PROGRESS, error2.code)

        assertEquals(1, sdpRequestCount.get())

        releaseLatch.countDown()

        val answer1 = handler.waitForAnswer(10, TimeUnit.SECONDS)
        assertNotNull(answer1)
        assertEquals("answer-1", answer1)

        val answer3 = sendOfferAndWaitForAnswer(handler, session, "offer-3")
        assertEquals("answer-2", answer3) // Воркер увеличил счётчик
        assertEquals(2, sdpRequestCount.get())
    }

    @Test
    fun `server busy - executor pool exhausted`() {
        val releaseLatch = CountDownLatch(1)

        worker.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return when (request.path) {
                    "/ready" -> MockResponse().setBody("""{"status":"ready","pipeline":"PLAYING"}""")
                    "/sdp" -> {
                        releaseLatch.await(10, TimeUnit.SECONDS)
                        MockResponse().setBody("""{"sdp":"answer","type":"answer"}""")
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        val (session1, handler1) = connect()
        val (session2, handler2) = connect()
        val (session3, handler3) = connect()

        handler1.sendMessage(session1, TextMessage(objectMapper.writeValueAsString(ClientMessage.Offer(sdp = "offer-1"))))
        handler2.sendMessage(session2, TextMessage(objectMapper.writeValueAsString(ClientMessage.Offer(sdp = "offer-2"))))

        Thread.sleep(500)

        val error3 = sendOfferAndWaitForError(handler3, session3, "offer-3")
        assertEquals(ErrorCode.SERVER_BUSY, error3.code)
        assertTrue(session3.isOpen)

        releaseLatch.countDown()

        val answer1 = handler1.waitForAnswer(10, TimeUnit.SECONDS)
        val answer2 = handler2.waitForAnswer(10, TimeUnit.SECONDS)
        assertNotNull(answer1)
        assertNotNull(answer2)
    }

    @Test
    fun `stress test - rapid offer submission`() {
        val sdpRequestCount = AtomicInteger(0)
        worker.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return when (request.path) {
                    "/ready" -> MockResponse().setBody("""{"status":"ready","pipeline":"PLAYING"}""")
                    "/sdp" -> {
                        sdpRequestCount.incrementAndGet()
                        MockResponse().setBody("""{"sdp":"answer","type":"answer"}""")
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        val (session, handler) = connect()
        val offerCount = 10

        repeat(offerCount) { i ->
            val offer = ClientMessage.Offer(sdp = "offer-$i")
            handler.sendMessage(session, TextMessage(objectMapper.writeValueAsString(offer)))
        }

        val answers = handler.waitForMultipleAnswers(offerCount, 30, TimeUnit.SECONDS)

        assertEquals(offerCount, answers.size)
        assertTrue(answers.all { it.type == "answer" })
    }

    @Test
    fun `malformed JSON - INVALID_MESSAGE, session stays open`() {
        val (session, handler) = connect()

        handler.sendMessage(session, TextMessage("not valid json"))

        val error = handler.waitForError(5, TimeUnit.SECONDS)
        assertNotNull(error)
        assertEquals(ErrorCode.INVALID_MESSAGE, error?.code)
        assertTrue(session.isOpen)
    }

    @Test
    fun `unknown message type - INVALID_MESSAGE, session stays open`() {
        val (session, handler) = connect()

        val unknownMessage = """{"type":"unknown","data":"something"}"""
        handler.sendMessage(session, TextMessage(unknownMessage))

        val error = handler.waitForError(5, TimeUnit.SECONDS)
        assertNotNull(error)
        assertEquals(ErrorCode.INVALID_MESSAGE, error?.code)
        assertTrue(session.isOpen)
    }

    private fun connect(): Pair<WebSocketSession, TestWebSocketHandler> {
        val client = StandardWebSocketClient()
        val handler = TestWebSocketHandler(objectMapper)
        val wsUrl = "ws://localhost:$port/api/v1/signaling?room_id=test-room&token=dummy"
        val session = client.execute(handler, wsUrl).get(5, TimeUnit.SECONDS)
        return session to handler
    }

    private fun sendOfferAndWaitForAnswer(
        handler: TestWebSocketHandler,
        session: WebSocketSession,
        offerSdp: String,
    ): String {
        val offer = ClientMessage.Offer(sdp = offerSdp)
        handler.sendMessage(session, TextMessage(objectMapper.writeValueAsString(offer)))
        val answer = handler.waitForAnswer(10, TimeUnit.SECONDS)
        assertNotNull(answer, "Did not receive answer in time")
        return answer!!
    }

    private fun sendOfferAndWaitForError(
        handler: TestWebSocketHandler,
        session: WebSocketSession,
        offerSdp: String,
    ): ServerMessage.Error {
        val offer = ClientMessage.Offer(sdp = offerSdp)
        handler.sendMessage(session, TextMessage(objectMapper.writeValueAsString(offer)))
        val error = handler.waitForError(10, TimeUnit.SECONDS)
        assertNotNull(error, "Did not receive error in time")
        return error!!
    }

    class TestWebSocketHandler(
        private val objectMapper: ObjectMapper,
    ) : TextWebSocketHandler() {
        private val messages = mutableListOf<ServerMessage>()
        private val latch = CountDownLatch(1)
        private val messageLatch = LinkedBlockingDeque<ServerMessage>()

        override fun handleTextMessage(
            session: WebSocketSession,
            message: TextMessage,
        ) {
            try {
                val serverMessage = objectMapper.readValue(message.payload, ServerMessage::class.java)
                messages.add(serverMessage)
                messageLatch.offer(serverMessage)
                latch.countDown()
            } catch (_: Exception) {
                // Игнорируем ошибки парсинга
            }
        }

        fun sendMessage(session: WebSocketSession, message: TextMessage) {
            session.sendMessage(message)
        }

        fun waitForAnswer(timeout: Long, unit: TimeUnit): String? {
            val message = messageLatch.poll(timeout, unit)
            return (message as? ServerMessage.Answer)?.sdp
        }

        fun waitForError(timeout: Long, unit: TimeUnit): ServerMessage.Error? {
            val message = messageLatch.poll(timeout, unit)
            return message as? ServerMessage.Error
        }

        fun waitForMultipleAnswers(count: Int, timeout: Long, unit: TimeUnit): List<ServerMessage> {
            val results = mutableListOf<ServerMessage>()
            val deadline = System.currentTimeMillis() + unit.toMillis(timeout)

            while (results.size < count && System.currentTimeMillis() < deadline) {
                val remaining = deadline - System.currentTimeMillis()
                val message = messageLatch.poll(remaining, TimeUnit.MILLISECONDS) ?: break
                results.add(message)
            }

            return results
        }
    }
}