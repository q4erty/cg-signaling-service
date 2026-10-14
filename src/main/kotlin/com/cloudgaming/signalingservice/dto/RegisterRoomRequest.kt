package com.cloudgaming.signalingservice.dto

import com.fasterxml.jackson.annotation.JsonProperty
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

data class RegisterRoomRequest(
    @field:NotBlank(message = "workerIp must not be blank")
    @JsonProperty("workerIp")
    val workerIp: String,

    @JsonProperty("webrtcPort")
    val webrtcPort: Int,

    @field:NotBlank(message = "gameId must not be blank")
    @JsonProperty("gameId")
    val gameId: String,

    @field:NotBlank(message = "ownerId must not be blank")
    @field:Size(max = 255, message = "ownerId must be at most 255 characters")
    @JsonProperty("ownerId")
    val ownerId: String,
)
