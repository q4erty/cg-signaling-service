package com.cloudgaming.signalingservice.websocket

enum class HandshakeResolution(
    val httpStatus: Int,
    val logicalCode: Int,
    val description: String,
) {
    SUCCESS(200, 0, "Handshake successful"),
    INVALID_ROOM_ID(400, 4400, "Invalid or missing room_id"),
    ROOM_NOT_FOUND(404, 4404, "Room not found"),
    ROOM_NOT_ACTIVE(409, 4409, "Room is not active"),
    ROOM_MALFORMED(502, 4402, "Room data is corrupted"),
    REDIS_UNAVAILABLE(503, 4403, "Redis is unavailable"),
    ;
}