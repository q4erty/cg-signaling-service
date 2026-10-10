package com.cloudgaming.signalingservice.model

import java.time.Instant

data class Room(
    val roomId: RoomId,
    val workerIp: String,
    val webrtcPort: Int,
    val gameId: String,
    val status: RoomStatus,
    val createdAt: Instant,
) {
    companion object {
        private val VALID_IP_PATTERN = Regex(
            "^(?:" +
                    "(?:[0-9]{1,3}\\.){3}[0-9]{1,3}|" +  // IPv4
                    "\\[[0-9a-fA-F:]+\\]|" +              // IPv6 in brackets
                    "[a-zA-Z0-9]([a-zA-Z0-9-]*[a-zA-Z0-9])?(\\.[a-zA-Z0-9]([a-zA-Z0-9-]*[a-zA-Z0-9])?)*" +  // DNS name
                    ")$"
        )

        fun validateWorkerIp(ip: String): Boolean {
            if (!VALID_IP_PATTERN.matches(ip)) {
                return false
            }
            // Дополнительная проверка IPv4 октетов
            if (ip.matches(Regex("^(?:[0-9]{1,3}\\.){3}[0-9]{1,3}$"))) {
                val octets = ip.split(".").map { it.toIntOrNull() ?: return false }
                return octets.all { it in 0..255 }
            }
            return true
        }

        fun validatePort(port: Int): Boolean = port in 1..65535
    }

    fun workerBaseUrl(): String {
        val host = if (workerIp.contains(":")) "[$workerIp]" else workerIp
        return "http://$host:$webrtcPort"
    }
}

enum class RoomStatus {
    ACTIVE,
    STOPPED,
    ERROR,
    ;

    companion object {
        fun fromString(value: String): RoomStatus? = entries.find { it.name == value }
    }
}