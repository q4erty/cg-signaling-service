package com.cloudgaming.signalingservice.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RoomIdTest {

    @Test
    fun `valid room_id`() {
        val result = RoomId.of("room-123_test")
        assertTrue(result.isSuccess)
        assertEquals("room-123_test", result.getOrThrow().value)
    }

    @Test
    fun `empty room_id`() {
        val result = RoomId.of("")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("required") == true)
    }

    @Test
    fun `null room_id`() {
        val result = RoomId.of(null)
        assertTrue(result.isFailure)
    }

    @Test
    fun `room_id with invalid characters`() {
        val result = RoomId.of("room@123")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("pattern") == true)
    }

    @Test
    fun `room_id too long`() {
        val longId = "a".repeat(65)
        val result = RoomId.of(longId)
        assertTrue(result.isFailure)
    }

    @Test
    fun `room_id max length`() {
        val maxLengthId = "a".repeat(64)
        val result = RoomId.of(maxLengthId)
        assertTrue(result.isSuccess)
    }
}
