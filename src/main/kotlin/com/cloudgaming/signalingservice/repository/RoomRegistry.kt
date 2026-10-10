package com.cloudgaming.signalingservice.repository

import com.cloudgaming.signalingservice.model.RoomId
import com.cloudgaming.signalingservice.model.RoomResolutionResult

interface RoomRegistry {
    fun resolve(roomId: RoomId): RoomResolutionResult
}