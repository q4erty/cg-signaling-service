package com.cloudgaming.signalingservice.controller

import com.cloudgaming.signalingservice.dto.RegisterRoomRequest
import com.cloudgaming.signalingservice.service.RoomRegistrationService
import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import jakarta.validation.ConstraintViolation
import jakarta.validation.Validator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus

class InternalRoomControllerTest {

    private lateinit var roomRegistrationService: RoomRegistrationService
    private lateinit var validator: Validator
    private lateinit var controller: InternalRoomController

    @BeforeEach
    fun setUp() {
        roomRegistrationService = mockk()
        validator = mockk()
        controller = InternalRoomController(roomRegistrationService, ObjectMapper(), validator)

        every { validator.validate(any<RegisterRoomRequest>()) } returns emptySet()
    }

    @Test
    fun `successful registration returns 201`() {
        val roomId = "test-room"
        val body = """{"workerIp":"192.168.1.100","webrtcPort":9090,"gameId":"xonotic","ownerId":"user-42"}"""

        every { roomRegistrationService.register(any(), any(), any(), any(), any()) } returns true

        val response = controller.registerRoom(roomId, body)

        assertEquals(HttpStatus.CREATED, response.statusCode)
        verify { roomRegistrationService.register(any(), eq("192.168.1.100"), eq(9090), eq("xonotic"), eq("user-42")) }
    }

    @Test
    fun `re-registration returns 200`() {
        val roomId = "test-room"
        val body = """{"workerIp":"192.168.1.100","webrtcPort":9090,"gameId":"xonotic","ownerId":"user-42"}"""

        every { roomRegistrationService.register(any(), any(), any(), any(), any()) } returns false

        val response = controller.registerRoom(roomId, body)

        assertEquals(HttpStatus.OK, response.statusCode)
    }

    @Test
    fun `invalid room_id returns 400`() {
        val roomId = "invalid@room"
        val body = """{"workerIp":"192.168.1.100","webrtcPort":9090,"gameId":"xonotic","ownerId":"user-42"}"""

        val response = controller.registerRoom(roomId, body)

        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
        verify(exactly = 0) { roomRegistrationService.register(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `invalid JSON returns 400`() {
        val roomId = "test-room"
        val body = """{"invalid json"""

        val response = controller.registerRoom(roomId, body)

        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
    }

    @Test
    fun `validation failure returns 400`() {
        val roomId = "test-room"
        val body = """{"workerIp":"256.1.1.1","webrtcPort":9090,"gameId":"xonotic","ownerId":"user-42"}"""

        val violation = mockk<ConstraintViolation<RegisterRoomRequest>>()
        every { violation.propertyPath.toString() } returns "workerIp"
        every { violation.message } returns "Invalid IP"
        every { validator.validate(any<RegisterRoomRequest>()) } returns setOf(violation)

        val response = controller.registerRoom(roomId, body)

        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
        verify(exactly = 0) { roomRegistrationService.register(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `invalid worker_ip returns 400`() {
        val roomId = "test-room"
        val body = """{"workerIp":"256.1.1.1","webrtcPort":9090,"gameId":"xonotic","ownerId":"user-42"}"""

        every {
            roomRegistrationService.register(
                any(),
                any(),
                any(),
                any(),
                any()
            )
        } throws IllegalArgumentException("Invalid worker_ip")

        val response = controller.registerRoom(roomId, body)

        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
    }

    @Test
    fun `invalid port returns 400`() {
        val roomId = "test-room"
        val body = """{"workerIp":"192.168.1.100","webrtcPort":70000,"gameId":"xonotic","ownerId":"user-42"}"""

        every {
            roomRegistrationService.register(
                any(),
                any(),
                any(),
                any(),
                any()
            )
        } throws IllegalArgumentException("Invalid webrtc_port")

        val response = controller.registerRoom(roomId, body)

        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
    }
}