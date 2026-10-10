package com.cloudgaming.signalingservice.model

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
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
        private val HOSTNAME_LABEL_REGEX = Regex("[a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?")

        fun validateWorkerIp(ip: String): Boolean {
            if (ip.isBlank()) return false

            // IPv6 в квадратных скобках (для URL)
            if (ip.startsWith("[") && ip.endsWith("]")) {
                val body = ip.substring(1, ip.length - 1)
                return isValidIpv6(body)
            }

            // Пробуем распарсить как IP-адрес
            try {
                val addr = InetAddress.getByName(ip)
                if (addr is Inet4Address || addr is Inet6Address) {
                    return true
                }
            } catch (_: Exception) {
                // Не IP — проверим как hostname
            }

            // DNS hostname: должен содержать хотя бы одну букву или дефис
            if (!ip.any { it.isLetter() || it == '-' }) {
                return false
            }

            val labels = ip.split(".")
            if (labels.isEmpty() || labels.size > 127) return false
            return labels.all { HOSTNAME_LABEL_REGEX.matches(it) }
        }

        private fun isValidIpv6(ip: String): Boolean {
            if (!ip.contains(":")) return false
            try {
                InetAddress.getByName(ip)
                return true
            } catch (_: Exception) {
                return false
            }
        }

        fun validatePort(port: Int): Boolean = port in 1..65535
    }

    fun workerBaseUrl(): String {
        val host = if (workerIp.contains(":") && !workerIp.startsWith("[")) "[$workerIp]" else workerIp
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
