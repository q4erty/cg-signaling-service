package com.cloudgaming.signalingservice.model

sealed class RoomResolutionResult {
    data class Found(val room: Room) : RoomResolutionResult()
    data object NotFound : RoomResolutionResult()
    data class Malformed(val reason: String) : RoomResolutionResult()
    data class Unavailable(val cause: Throwable) : RoomResolutionResult()
}