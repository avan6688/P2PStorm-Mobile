package com.p2pstorm.sdk.utils

import com.p2pstorm.sdk.logger.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

@Serializable
data class P2PStats(
    val app_key: String = "",
    val session_id: String = "",
    val p2p_bytes: Long = 0,
    val cdn_bytes: Long = 0,
    val upload_bytes: Long = 0,
    val peers_count: Int = 0,
    val duration: Int = 0,
    val platform: String = "android",
    val version: String = "1.0.0",
)

class P2PStatsCollector(
    private val appKey: String,
    private val reportUrl: String,
    private val coroutineScope: CoroutineScope,
    private val reportIntervalMs: Long = 60_000L,
) {
    private val sessionId = UUID.randomUUID().toString().take(16)
    private val startTime = System.currentTimeMillis()

    val p2pBytes = AtomicLong(0)
    val cdnBytes = AtomicLong(0)
    val uploadBytes = AtomicLong(0)
    val peersCount = AtomicInteger(0)

    private var reportJob: Job? = null
    private val httpClient = OkHttpClient()

    fun start() {
        if (reportJob != null) return
        reportJob = coroutineScope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(reportIntervalMs)
                report()
            }
        }
    }

    fun recordP2PDownload(bytes: Long) {
        p2pBytes.addAndGet(bytes)
    }

    fun recordCDNDownload(bytes: Long) {
        cdnBytes.addAndGet(bytes)
    }

    fun recordUpload(bytes: Long) {
        uploadBytes.addAndGet(bytes)
    }

    fun getStats(): P2PStats {
        val duration = ((System.currentTimeMillis() - startTime) / 1000).toInt()
        return P2PStats(
            app_key = appKey,
            session_id = sessionId,
            p2p_bytes = p2pBytes.get(),
            cdn_bytes = cdnBytes.get(),
            upload_bytes = uploadBytes.get(),
            peers_count = peersCount.get(),
            duration = duration,
            platform = "android",
        )
    }

    fun getRatio(): Double {
        val p2p = p2pBytes.get()
        val cdn = cdnBytes.get()
        val total = p2p + cdn
        return if (total > 0) p2p.toDouble() / total * 100 else 0.0
    }

    private fun report() {
        if (appKey.isEmpty() || reportUrl.isEmpty()) return

        try {
            val stats = getStats()
            val json = Json.encodeToString(stats)
            val body = json.toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url(reportUrl)
                .post(body)
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    Logger.d(TAG, "Stats reported: p2p=${stats.p2p_bytes} cdn=${stats.cdn_bytes} ratio=${String.format("%.1f", getRatio())}%")
                }
            }
        } catch (e: Exception) {
            Logger.d(TAG, "Stats report failed: ${e.message}")
        }
    }

    fun stop() {
        reportJob?.cancel()
        reportJob = null
        // Final report on a dedicated thread (not dependent on parent scope which may be cancelled)
        Thread {
            try {
                report()
            } catch (_: Exception) {}
            try {
                httpClient.dispatcher.executorService.shutdown()
                httpClient.connectionPool.evictAll()
            } catch (_: Exception) {}
        }.start()
    }

    companion object {
        private const val TAG = "P2PStatsCollector"
    }
}
