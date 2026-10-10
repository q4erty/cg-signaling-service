package com.cloudgaming.signalingservice.exception

sealed class WorkerSignalingException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)

class WorkerTimeoutException(workerBaseUrl: String, cause: Throwable? = null) :
    WorkerSignalingException("Worker at $workerBaseUrl did not respond to /sdp in time", cause)

class WorkerUnreachableException(workerBaseUrl: String, cause: Throwable? = null) :
    WorkerSignalingException("Worker at $workerBaseUrl is unreachable", cause)

class WorkerRejectedOfferException(workerBaseUrl: String, val workerMessage: String) :
    WorkerSignalingException("Worker at $workerBaseUrl rejected the offer: $workerMessage")

class WorkerProtocolException(workerBaseUrl: String, message: String, cause: Throwable? = null) :
    WorkerSignalingException("Unexpected response from worker at $workerBaseUrl: $message", cause)
