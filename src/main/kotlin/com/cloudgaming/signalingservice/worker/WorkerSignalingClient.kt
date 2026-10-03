package com.cloudgaming.signalingservice.worker

data class SdpAnswer(
    val sdp: String,
    val type: String = "answer",
)

interface WorkerSignalingClient {

    /** false на любую ошибку (сеть/таймаут/не-200) — не кидает исключение. */
    fun isReady(workerBaseUrl: String): Boolean

    /**
     * @throws WorkerTimeoutException
     * @throws WorkerUnreachableException
     * @throws WorkerRejectedOfferException
     * @throws WorkerProtocolException
     */
    fun exchangeSdp(workerBaseUrl: String, offerSdp: String): SdpAnswer
}
