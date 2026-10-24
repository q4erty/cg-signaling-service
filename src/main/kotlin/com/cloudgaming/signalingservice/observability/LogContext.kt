package com.cloudgaming.signalingservice.observability

import org.slf4j.MDC
import java.util.UUID

object LogContext {

    const val CONNECTION_ID = "connectionId"
    const val ROOM_ID = "roomId"
    const val USER_ID = "userId"
    const val WORKER = "worker"

    fun generateConnectionId(): String = UUID.randomUUID().toString()

    fun set(connectionId: String? = null, roomId: String? = null, userId: String? = null, worker: String? = null) {
        connectionId?.let { MDC.put(CONNECTION_ID, it) }
        roomId?.let { MDC.put(ROOM_ID, it) }
        userId?.let { MDC.put(USER_ID, it) }
        worker?.let { MDC.put(WORKER, it) }
    }

    fun clear() {
        MDC.clear()
    }

    fun capture(): Map<String, String> = MDC.getCopyOfContextMap() ?: emptyMap()

    fun restore(context: Map<String, String>) {
        MDC.clear()
        context.forEach { (key, value) -> MDC.put(key, value) }
    }
}
