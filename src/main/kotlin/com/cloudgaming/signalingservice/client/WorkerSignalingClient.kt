package com.cloudgaming.signalingservice.client

data class SdpAnswer(
    val sdp: String,
    val type: String = "answer",
)

interface WorkerSignalingClient {

    /** false на любую ошибку (сеть/таймаут/не-200) — не кидает исключение. */
    fun isReady(workerBaseUrl: String): Boolean

    /**
     * @throws com.cloudgaming.signalingservice.exception.WorkerTimeoutException
     * @throws com.cloudgaming.signalingservice.exception.WorkerUnreachableException
     * @throws com.cloudgaming.signalingservice.exception.WorkerRejectedOfferException
     * @throws com.cloudgaming.signalingservice.exception.WorkerProtocolException
     */
    fun exchangeSdp(workerBaseUrl: String, offerSdp: String): SdpAnswer
}
