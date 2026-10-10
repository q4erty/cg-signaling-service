package com.cloudgaming.signalingservice.dto

import com.fasterxml.jackson.annotation.JsonProperty

data class RegisterRoomRequest(
    @JsonProperty("workerIp")
    val workerIp: String,

    @JsonProperty("webrtcPort")
    val webrtcPort: Int,

    @JsonProperty("gameId")
    val gameId: String,
)