package com.cloudgaming.signalingservice.client

import com.cloudgaming.signalingservice.config.WorkerProperties
import com.cloudgaming.signalingservice.exception.WorkerProtocolException
import com.cloudgaming.signalingservice.exception.WorkerRejectedOfferException
import com.cloudgaming.signalingservice.exception.WorkerTimeoutException
import com.cloudgaming.signalingservice.exception.WorkerUnreachableException
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.http.MediaType
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import org.springframework.web.client.RestClientResponseException
import org.springframework.web.client.UnknownContentTypeException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.http.HttpClient
import java.net.http.HttpConnectTimeoutException
import java.net.http.HttpTimeoutException
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong

private data class OfferRequestBody(val sdp: String, val type: String = "offer")
private data class ReadyResponseBody(val status: String? = null, val pipeline: String? = null)

private object WorkerApi {
    const val READY_PATH = "/ready"
    const val SDP_PATH = "/sdp"
    const val PIPELINE_PLAYING = "PLAYING"
    const val ERROR_DETAIL_FIELD = "detail"
}

@Component
class RestClientWorkerSignalingClient(
    private val workerProperties: WorkerProperties,
    private val objectMapper: ObjectMapper,
) : WorkerSignalingClient {

    private val log = LoggerFactory.getLogger(RestClientWorkerSignalingClient::class.java)

    private val readyClient: RestClient = buildClient(
        responseTimeout = workerProperties.readyTimeout,
        connectionTimeout = workerProperties.readyTimeout,
    )

    private val sdpClient: RestClient = buildClient(
        responseTimeout = workerProperties.sdpTimeout.plus(workerProperties.sdpReadTimeoutSlack),
        connectionTimeout = workerProperties.connectTimeout,
    )

    private val sdpExecutor: ExecutorService = Executors.newFixedThreadPool(
        Runtime.getRuntime().availableProcessors(),
        object : ThreadFactory {
            private val counter = AtomicLong(0)
            override fun newThread(r: Runnable): Thread {
                val t = Thread(r, "sdp-worker-${counter.incrementAndGet()}")
                t.isDaemon = true
                return t
            }
        },
    )

    private fun buildClient(responseTimeout: Duration, connectionTimeout: Duration): RestClient {
        val httpClient = HttpClient.newBuilder()
            .connectTimeout(connectionTimeout)
            .build()

        val requestFactory = JdkClientHttpRequestFactory(httpClient).apply {
            setReadTimeout(responseTimeout)
        }

        return RestClient.builder()
            .requestFactory(requestFactory)
            .build()
    }

    override fun isReady(workerBaseUrl: String): Boolean {
        return try {
            val body = readyClient.get()
                .uri("$workerBaseUrl${WorkerApi.READY_PATH}")
                .retrieve()
                .body(String::class.java)
                ?: return false

            val parsed = objectMapper.readValue(body, ReadyResponseBody::class.java)
            parsed.pipeline == WorkerApi.PIPELINE_PLAYING
        } catch (e: Exception) {
            log.debug("isReady({}) -> false: {}", workerBaseUrl, e.toString())
            false
        }
    }

    override fun exchangeSdp(workerBaseUrl: String, offerSdp: String): SdpAnswer {
        val requestBody = objectMapper.writeValueAsString(OfferRequestBody(sdp = offerSdp, type = "offer"))

        val future = CompletableFuture.supplyAsync({
            val response = sdpClient.post()
                .uri("$workerBaseUrl${WorkerApi.SDP_PATH}")
                .contentType(MediaType.APPLICATION_JSON)
                .body(requestBody)
                .retrieve()
                .onStatus({ status: HttpStatusCode -> status == HttpStatus.BAD_REQUEST }) { _, response ->
                    val text = response.body.readAllBytes().toString(Charsets.UTF_8)
                    throw WorkerRejectedOfferException(workerBaseUrl, extractDetail(text))
                }
                .onStatus({ status: HttpStatusCode -> status.is5xxServerError }) { request, response ->
                    throw WorkerProtocolException(
                        workerBaseUrl,
                        "Worker returned ${response.statusCode.value()} on ${request.uri}",
                    )
                }
                .body(String::class.java)

            response ?: throw WorkerProtocolException(workerBaseUrl, "empty response body")
        }, sdpExecutor)

        val responseBody: String
        try {
            responseBody = future.get(workerProperties.sdpTimeout.toMillis(), TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            future.cancel(true)
            throw WorkerTimeoutException(workerBaseUrl, e)
        } catch (e: ExecutionException) {
            when (val cause = e.cause) {
                is WorkerRejectedOfferException -> throw cause
                is WorkerProtocolException -> throw cause
                is RestClientException -> throw classifyException(workerBaseUrl, cause)
                is IOException -> throw classifyException(workerBaseUrl, cause)
                else -> throw WorkerProtocolException(workerBaseUrl, "unexpected error calling worker", cause ?: e)
            }
        }

        return try {
            objectMapper.readValue(responseBody, SdpAnswer::class.java)
        } catch (e: Exception) {
            throw WorkerProtocolException(workerBaseUrl, "failed to parse response as SdpAnswer", e)
        }
    }

    private fun classifyException(workerBaseUrl: String, throwable: Throwable): RuntimeException {
        var current: Throwable? = throwable
        while (current != null) {
            when (current) {
                is HttpConnectTimeoutException -> {
                    return WorkerUnreachableException(workerBaseUrl, throwable)
                }
                is HttpTimeoutException, is SocketTimeoutException -> {
                    return WorkerTimeoutException(workerBaseUrl, throwable)
                }
                is ConnectException, is UnknownHostException -> {
                    return WorkerUnreachableException(workerBaseUrl, throwable)
                }
                is RestClientResponseException -> {
                    return WorkerProtocolException(
                        workerBaseUrl,
                        "Worker returned HTTP ${current.statusCode} on ${current.message}",
                        throwable,
                    )
                }
                is UnknownContentTypeException -> {
                    return WorkerProtocolException(
                        workerBaseUrl,
                        "Worker returned unexpected content type: ${current.contentType}",
                        throwable,
                    )
                }
                is IOException -> {
                    val message = current.message?.lowercase() ?: ""
                    if (message.contains("connection reset") ||
                        message.contains("connection closed") ||
                        message.contains("broken pipe") ||
                        message.contains("eof")
                    ) {
                        return WorkerUnreachableException(workerBaseUrl, throwable)
                    }
                    return WorkerUnreachableException(workerBaseUrl, throwable)
                }
            }
            current = current.cause
        }
        return WorkerUnreachableException(workerBaseUrl, throwable)
    }

    private fun extractDetail(rawBody: String): String =
        try {
            objectMapper.readTree(rawBody).path(WorkerApi.ERROR_DETAIL_FIELD).asText(rawBody)
        } catch (e: Exception) {
            rawBody
        }

    @PreDestroy
    fun shutdown() {
        sdpExecutor.shutdown()
    }
}