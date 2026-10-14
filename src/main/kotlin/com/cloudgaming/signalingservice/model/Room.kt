package com.cloudgaming.signalingservice.model

import java.time.Instant

data class Room(
    val roomId: RoomId,
    val workerIp: String,
    val webrtcPort: Int,
    val gameId: String,
    val status: RoomStatus,
    val createdAt: Instant,
    val ownerId: String,
) {
    companion object {
        private val HOSTNAME_LABEL_REGEX = Regex("[a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?")
        private val IPV4_REGEX = Regex("^(?:[0-9]{1,3}\\.){3}[0-9]{1,3}$")
        private val IPV6_BODY_REGEX = Regex("^[0-9a-fA-F:]+$")

        fun validateWorkerIp(ip: String): Boolean {
            if (ip.isBlank()) return false

            // IPv6 в квадратных скобках (для URL)
            if (ip.startsWith("[") && ip.endsWith("]")) {
                val body = ip.substring(1, ip.length - 1)
                // Должно содержать ":" и состоять только из hex-цифр и ":"
                return body.contains(":") && IPV6_BODY_REGEX.matches(body)
            }

            // Строгий IPv4: ровно 4 октета, без leading zeros, каждый в 0..255
            if (IPV4_REGEX.matches(ip)) {
                val octets = ip.split(".")
                return octets.all { octet ->
                    if (octet.length > 1 && octet[0] == '0') return@all false
                    val value = octet.toIntOrNull() ?: return@all false
                    value in 0..255
                }
            }

            // DNS hostname
            if (!ip.any(Char::isLetter)) return false

            val labels = ip.split(".")
            if (labels.isEmpty() || labels.size > 127) return false
            return labels.all { HOSTNAME_LABEL_REGEX.matches(it) }
        }

        fun validatePort(port: Int): Boolean = port in 1..65535
    }

    fun workerBaseUrl(): String {
        val host = when {
            workerIp.startsWith("[") && workerIp.endsWith("]") -> workerIp
            workerIp.contains(":") -> "[$workerIp]"
            else -> workerIp
        }
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
