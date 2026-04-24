package com.p2pstorm.sdk.webview

import android.annotation.SuppressLint
import android.net.Uri
import android.webkit.WebView
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebMessagePortCompat
import androidx.webkit.WebViewCompat
import com.p2pstorm.sdk.SegmentRequest
import com.p2pstorm.sdk.logger.Logger
import com.p2pstorm.sdk.utils.SegmentAbortedException
import com.p2pstorm.sdk.utils.SegmentNotFoundException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

internal class WebMessageProtocol(
    private val webView: WebView,
    private val coroutineScope: CoroutineScope,
) {
    @SuppressLint("RequiresFeature")
    private val channels: Array<WebMessagePortCompat> =
        WebViewCompat.createWebMessageChannel(webView)
    private val segmentResponseCallbacks = mutableMapOf<Int, CompletableDeferred<ByteArray>>()
    private val mutex = Mutex()
    private val incomingRequestIdQueue = ConcurrentLinkedQueue<Int>()
    private val requestIdCounter = AtomicInteger(0)

    private var wasInitialMessageSent = false

    init {
        initializeWebMessageCallback()
    }

    @SuppressLint("RequiresFeature")
    private fun initializeWebMessageCallback() {
        channels[0].setWebMessageCallback(
            object : WebMessagePortCompat.WebMessageCallbackCompat() {
                override fun onMessage(
                    port: WebMessagePortCompat,
                    message: WebMessageCompat?,
                ) {
                    try {
                        when (message?.type) {
                            WebMessageCompat.TYPE_ARRAY_BUFFER -> {
                                handleSegmentIdBytes(message.arrayBuffer)
                            }

                            WebMessageCompat.TYPE_STRING -> {
                                handleMessage(message.data!!)
                            }
                        }
                    } catch (e: Exception) {
                        Logger.e(TAG, "Error while handling message: ${e.message}", e)
                    }
                }
            },
        )
    }

    private fun handleSegmentIdBytes(arrayBuffer: ByteArray) {
        val requestId =
            incomingRequestIdQueue.poll()
                ?: throw IllegalStateException("Received segment bytes without a segment ID")

        coroutineScope.launch {
            val deferred =
                getSegmentResponseCallback(requestId) ?: throw IllegalStateException("No deferred found for request ID: $requestId")

            deferred.complete(arrayBuffer)
            removeSegmentResponseCallback(requestId)
        }
    }

    private fun handleMessage(message: String) {
        if (message.contains("Error")) {
            handleErrorMessage(message)
        } else {
            handleSegmentIdMessage(message)
        }
    }

    private fun handleErrorMessage(message: String) {
        coroutineScope.launch {
            try {
                val error = message.substringBefore("|")
                val errorParts = error.split(":")
                if (errorParts.size < 3) {
                    Logger.e(TAG, "Malformed error message: $message")
                    return@launch
                }
                val requestId = errorParts[1].toIntOrNull()
                if (requestId == null) {
                    Logger.e(TAG, "Invalid request ID in error message: $message")
                    return@launch
                }
                val errorType = errorParts[2]
                val segmentId = message.substringAfter("|")

                val deferredSegmentBytes = getSegmentResponseCallback(requestId)
                if (deferredSegmentBytes == null) {
                    Logger.e(TAG, "No deferred found for request ID: $requestId")
                    return@launch
                }

                val exception =
                    when (errorType) {
                        "aborted" -> SegmentAbortedException("$segmentId request was aborted")
                        "not_found" -> SegmentNotFoundException("$segmentId not found in core engine")
                        "failed" -> Exception("Error occurred while fetching segment")
                        else -> Exception("Unknown error occurred while fetching segment")
                    }

                deferredSegmentBytes.completeExceptionally(exception)
                removeSegmentResponseCallback(requestId)
            } catch (e: Exception) {
                Logger.e(TAG, "Error handling error message: ${e.message}", e)
            }
        }
    }

    private fun handleSegmentIdMessage(requestId: String) {
        val requestIdToInt =
            requestId.toIntOrNull() ?: throw IllegalStateException("Invalid request ID")
        if (incomingRequestIdQueue.size >= MAX_PENDING_REQUESTS) {
            Logger.e(TAG, "Request ID queue full ($MAX_PENDING_REQUESTS), dropping requestId: $requestIdToInt")
            return
        }
        incomingRequestIdQueue.add(requestIdToInt)
    }

    @SuppressLint("RequiresFeature")
    suspend fun sendInitialMessage() {
        if (wasInitialMessageSent) return
        withContext(Dispatchers.Main) {
            val initialMessage = WebMessageCompat("", arrayOf(channels[1]))
            WebViewCompat.postWebMessage(
                webView,
                initialMessage,
                Uri.parse("*"),
            )
            wasInitialMessageSent = true
        }
    }

    suspend fun requestSegmentBytes(segmentUrl: String): CompletableDeferred<ByteArray> {
        val deferred = CompletableDeferred<ByteArray>()
        val requestId = generateRequestId()
        val segmentRequest = SegmentRequest(requestId, segmentUrl)
        val jsonRequest = Json.encodeToString(segmentRequest)

        addSegmentResponseCallback(requestId, deferred)
        sendSegmentRequest(jsonRequest)

        // Auto-cleanup after 30s to prevent memory leak
        coroutineScope.launch {
            kotlinx.coroutines.delay(30_000)
            val cb = getSegmentResponseCallback(requestId)
            if (cb != null && !cb.isCompleted) {
                cb.completeExceptionally(Exception("Segment request timeout: $segmentUrl"))
                removeSegmentResponseCallback(requestId)
                Logger.e(TAG, "Segment request $requestId timed out, cleaned up")
            }
        }

        return deferred
    }

    private fun generateRequestId(): Int = requestIdCounter.getAndIncrement()

    private suspend fun sendSegmentRequest(segmentUrl: String) {
        val escaped = segmentUrl
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\n", "\\n")
        withContext(Dispatchers.Main) {
            webView.evaluateJavascript(
                "javascript:window.p2p.processSegmentRequest('$escaped');",
                null,
            )
        }
    }

    private suspend fun addSegmentResponseCallback(
        requestId: Int,
        deferred: CompletableDeferred<ByteArray>,
    ) {
        mutex.withLock {
            segmentResponseCallbacks[requestId] = deferred
        }
    }

    private suspend fun getSegmentResponseCallback(requestId: Int): CompletableDeferred<ByteArray>? =
        mutex.withLock {
            segmentResponseCallbacks[requestId]
        }

    private suspend fun removeSegmentResponseCallback(requestId: Int) {
        mutex.withLock {
            segmentResponseCallbacks.remove(requestId)
        }
    }

    private suspend fun resetSegmentResponseCallbacks() {
        mutex.withLock {
            segmentResponseCallbacks.forEach { (_, deferred) ->
                if (deferred.isCompleted) return@forEach

                deferred.completeExceptionally(
                    Exception("WebMessageProtocol is closing, no segment data will arrive."),
                )
            }
            segmentResponseCallbacks.clear()
        }
    }

    @SuppressLint("RequiresFeature")
    suspend fun clear() {
        incomingRequestIdQueue.clear()
        resetSegmentResponseCallbacks()
    }

    companion object {
        private const val TAG = "WebMessageProtocol"
        private const val MAX_PENDING_REQUESTS = 200
    }
}
