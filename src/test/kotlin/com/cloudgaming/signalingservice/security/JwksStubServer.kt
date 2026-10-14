package com.cloudgaming.signalingservice.security

import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress

/**
 * Простой JWKS-сервер на встроенном JDK HttpServer.
 *
 * Выбор: не используем WireMock (лишняя зависимость), не используем MockWebServer (уже
 * используется для воркеров и там другая семантика). JDK HttpServer: 0 зависимостей,
 * полностью контролируемый lifecycle, простой JSON-ответ.
 *
 * Отдаёт JWKS по пути /.well-known/jwks.json с указанными публичными ключами.
 */
class JwksStubServer(private val rsaKeys: List<RSAKey>) {

    private var server: HttpServer? = null
    var port: Int = 0
        private set

    val jwksUri: String
        get() = "http://localhost:$port/.well-known/jwks.json"

    fun start() {
        val httpServer = HttpServer.create(InetSocketAddress(0), 0)
        httpServer.createContext("/.well-known/jwks.json") { exchange ->
            handleJwks(exchange)
        }
        httpServer.start()
        server = httpServer
        port = httpServer.address.port
    }

    fun stop() {
        server?.stop(0)
        server = null
    }

    private fun handleJwks(exchange: HttpExchange) {
        val jwks = JWKSet(rsaKeys)
        val body = jwks.toJSONObject().toJSONString().toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(200, body.size.toLong())
        exchange.responseBody.use { it.write(body) }
    }
}

private fun Map<String, Any>.toJSONString(): String {
    val sb = StringBuilder()
    sb.append("{")
    var first = true
    for ((key, value) in this) {
        if (!first) sb.append(",")
        first = false
        sb.append("\"$key\":")
        when (value) {
            is String -> sb.append("\"$value\"")
            is List<*> -> {
                sb.append("[")
                sb.append(value.joinToString(",") { item ->
                    when (item) {
                        is Map<*, *> -> (item as Map<String, Any>).toJSONString()
                        is String -> "\"$item\""
                        else -> item.toString()
                    }
                })
                sb.append("]")
            }
            else -> sb.append(value.toString())
        }
    }
    sb.append("}")
    return sb.toString()
}