package com.cloudgaming.signalingservice.integration

import com.cloudgaming.signalingservice.dto.ClientMessage
import com.cloudgaming.signalingservice.dto.ServerMessage
import com.cloudgaming.signalingservice.security.JwtTestHelper
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketSession
import org.springframework.web.socket.client.standard.StandardWebSocketClient
import org.springframework.web.socket.handler.TextWebSocketHandler
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * Integration tests against a REAL gst-test worker container.
 *
 * Requirements:
 * - Docker daemon available
 * - `gst-test` image built locally (see project README for build instructions)
 * - Port 9090 free on localhost
 * - Linux or Docker Desktop with host networking enabled
 *
 * Run with: ./gradlew integrationTest
 *
 * These tests are tagged with "integration" and excluded from regular `./gradlew test`.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WorkerIntegrationTest {

    companion object {
        private const val WORKER_PORT = 9090
        private val WORKER_IMAGE = System.getenv("WORKER_TEST_IMAGE") ?: "gst-test"
        private val REQUIRE_INTEGRATION = System.getenv("REQUIRE_INTEGRATION") == "true"
        private const val USER_A = "integration-user"
        private const val ROOM_ID = "integration-room"

        private val jwtHelper = JwtTestHelper(issuer = "https://test-issuer.example.com")
        private var workerContainer: GenericContainer<*>? = null
        private var portWasFree = false

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("auth.issuer-uri") { jwtHelper.issuer }
            registry.add("auth.jwk-set-uri") { "http://localhost:18080/.well-known/jwks.json" }
            registry.add("internal.secret") { "test-secret" }
        }

        private fun checkPreconditions() {
            portWasFree = try {
                ServerSocket(WORKER_PORT).use { true }
            } catch (e: Exception) {
                false
            }

            if (!portWasFree) {
                val msg = "Port $WORKER_PORT is already in use. Integration tests require a free port."
                if (REQUIRE_INTEGRATION) throw IllegalStateException(msg)
                assumeTrue(false, msg)
            }

            val dockerAvailable = try {
                val process = ProcessBuilder("docker", "info")
                    .redirectErrorStream(true)
                    .start()
                process.waitFor(10, TimeUnit.SECONDS)
                true
            } catch (e: Exception) {
                false
            }

            if (!dockerAvailable) {
                val msg = "Docker daemon is not available. Integration tests require Docker."
                if (REQUIRE_INTEGRATION) throw IllegalStateException(msg)
                assumeTrue(false, msg)
            }

            val imageExists = try {
                val process = ProcessBuilder("docker", "image", "inspect", WORKER_IMAGE)
                    .redirectErrorStream(true)
                    .start()
                val completed = process.waitFor(10, TimeUnit.SECONDS)
                completed && process.exitValue() == 0
            } catch (e: Exception) {
                false
            }

            if (!imageExists) {
                val msg = "Image '$WORKER_IMAGE' not found locally. Build it first: docker build -t $WORKER_IMAGE ."
                if (REQUIRE_INTEGRATION) throw IllegalStateException(msg)
                assumeTrue(false, msg)
            }
        }

        private fun startWorker() {
            workerContainer = GenericContainer(WORKER_IMAGE)
                .withNetworkMode("host")
                .withEnv("HTTP_PORT", WORKER_PORT.toString())
                .waitingFor(Wait.forLogMessage(".*Pipeline is PLAYING.*", 1))
                .withStartupTimeout(Duration.ofSeconds(60))

            workerContainer!!.start()

            await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(500)).until {
                try {
                    val client = HttpClient.newHttpClient()
                    val request = HttpRequest.newBuilder()
                        .uri(URI.create("http://127.0.0.1:$WORKER_PORT/ready"))
                        .GET()
                        .build()
                    val response = client.send(request, HttpResponse.BodyHandlers.ofString())
                    response.statusCode() == 200 && response.body().contains("PLAYING")
                } catch (e: Exception) {
                    false
                }
            }
        }

        private fun stopWorker() {
            workerContainer?.stop()
            workerContainer = null
        }
    }

    @LocalServerPort
    private var port: Int = 0

    private val objectMapper = jacksonObjectMapper()

    @BeforeAll
    fun setUp() {
        checkPreconditions()
        startWorker()
    }

    @AfterAll
    fun tearDown() {
        stopWorker()
    }

    @Test
    fun `real offer to real worker returns valid SDP answer`() {
        registerRoom(ROOM_ID, USER_A)

        val token = jwtHelper.validToken(subject = USER_A)
        val sdpOffer = loadTestSdpOffer()

        val handler = TestHandler(objectMapper)
        val client = StandardWebSocketClient()
        val wsUrl = "ws://localhost:$port/api/v1/signaling?room_id=$ROOM_ID&token=$token"
        val session = client.execute(handler, wsUrl).get(5, TimeUnit.SECONDS)

        // Send first offer
        val answer1 = sendOfferAndWaitForAnswer(handler, session, sdpOffer)
        assert(answer1.sdp.startsWith("v=0")) { "SDP answer should start with v=0" }
        assert(answer1.sdp.contains("m=video")) { "SDP answer should contain m=video" }

        // Send second offer in same session (ICE restart)
        val answer2 = sendOfferAndWaitForAnswer(handler, session, sdpOffer)
        assert(answer2.sdp.startsWith("v=0")) { "Second SDP answer should start with v=0" }

        session.close()
    }

    private fun sendOfferAndWaitForAnswer(
        handler: TestHandler,
        session: WebSocketSession,
        sdpOffer: String,
    ): ServerMessage.Answer {
        val offer = ClientMessage.Offer(sdp = sdpOffer)
        session.sendMessage(TextMessage(objectMapper.writeValueAsString(offer)))

        await().atMost(Duration.ofSeconds(30))
            .until({ handler.lastMessage != null && handler.lastMessage is ServerMessage.Answer })

        return handler.lastMessage as ServerMessage.Answer
    }

    private fun registerRoom(roomId: String, ownerId: String) {
        val client = HttpClient.newHttpClient()
        val body = """{"workerIp":"127.0.0.1","webrtcPort":9090,"gameId":"xonotic","ownerId":"$ownerId"}"""
        val request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:$port/internal/rooms/$roomId/register"))
            .header("Content-Type", "application/json")
            .header("X-Internal-Secret", "test-secret")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        client.send(request, HttpResponse.BodyHandlers.discarding())
    }

    private fun loadTestSdpOffer(): String {
        return this::class.java.getResource("/test-sdp-offer.txt")?.readText()
            ?: throw IllegalStateException("test-sdp-offer.txt not found in test resources")
    }

    class TestHandler(
        private val objectMapper: ObjectMapper,
    ) : TextWebSocketHandler() {
        @Volatile
        var lastMessage: ServerMessage? = null

        override fun handleTextMessage(
            session: WebSocketSession,
            message: TextMessage,
        ) {
            lastMessage = objectMapper.readValue(message.payload, ServerMessage::class.java)
        }
    }
}