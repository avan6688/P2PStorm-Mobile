package com.p2pstorm.sdk

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.p2pstorm.sdk.Constants.CORE_FILE_URL
import com.p2pstorm.sdk.Constants.CUSTOM_FILE_URL
import com.p2pstorm.sdk.Constants.QueryParams.MANIFEST
import com.p2pstorm.sdk.interop.EventListener
import com.p2pstorm.sdk.interop.OnP2PReadyCallback
import com.p2pstorm.sdk.interop.OnP2PReadyErrorCallback
import com.p2pstorm.sdk.logger.Logger
import com.p2pstorm.sdk.parser.HlsManifestParser
import com.p2pstorm.sdk.providers.ExoPlayerPlaybackProvider
import com.p2pstorm.sdk.providers.ExternalPlaybackProvider
import com.p2pstorm.sdk.providers.PlaybackProvider
import com.p2pstorm.sdk.server.ServerModule
import com.p2pstorm.sdk.utils.EventEmitter
import com.p2pstorm.sdk.utils.NetworkMonitor
import com.p2pstorm.sdk.utils.P2PStateManager
import com.p2pstorm.sdk.utils.P2PStats
import com.p2pstorm.sdk.utils.P2PStatsCollector
import com.p2pstorm.sdk.utils.Utils
import com.p2pstorm.sdk.webview.WebViewManager
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * `P2PMediaLoader` facilitates peer-to-peer media streaming within an Android application.
 *
 * @param onP2PReadyCallback Callback invoked when the P2P engine is ready for use
 * @param onP2PReadyErrorCallback Callback invoked when an error occurs
 * @param coreConfigJson Sets core P2P configurations. See [P2PML Core Config](https://docs.p2pstorm.cn/v2.1.0/types/p2p-media-loader-core.CoreConfig.html)
 * JSON string with core configurations. Default: empty string (uses default config)
 *
 * @param serverPort Port number for the local server. Default: 8080
 * @param customJavaScriptInterfaces List of custom JavaScript interfaces to inject into the WebView.
 * The feature has to be used with custom engine implementation. Default: empty list
 *
 * @param customEngineImplementationPath Resource path for custom implementation.
 * Default: null (uses built-in implementation)
 */
@UnstableApi
class P2PMediaLoader(
    private val onP2PReadyCallback: OnP2PReadyCallback,
    private val onP2PReadyErrorCallback: OnP2PReadyErrorCallback,
    private val coreConfigJson: String = "",
    private val serverPort: Int = Constants.DEFAULT_SERVER_PORT,
    private val customJavaScriptInterfaces: List<Pair<String, Any>> = emptyList(),
    private val customEngineImplementationPath: String? = null,
    private val appKey: String = "",
    private val statsReportUrl: String = "",
    enableDebugLogs: Boolean = false,
) {
    init {
        Logger.setDebugMode(enableDebugLogs)
    }

    // Second constructor for Java compatibility
    constructor(
        onP2PReadyCallback: OnP2PReadyCallback,
        onP2PReadyErrorCallback: OnP2PReadyErrorCallback,
        serverPort: Int,
        coreConfigJson: String,
        enableDebugLogs: Boolean,
    ) : this(
        onP2PReadyCallback,
        onP2PReadyErrorCallback,
        coreConfigJson,
        serverPort,
        enableDebugLogs = enableDebugLogs,
    )

    private val eventEmitter = EventEmitter()
    private val engineStateManager = P2PStateManager()
    @Volatile
    private var appState = AppState.INITIALIZED

    private var job: Job? = null
    private var scope: CoroutineScope? = null
    private var serverModule: ServerModule? = null
    private var manifestParser: HlsManifestParser? = null
    private var webViewManager: WebViewManager? = null
    private var playbackProvider: PlaybackProvider? = null
    private var networkMonitor: NetworkMonitor? = null
    private var statsCollector: P2PStatsCollector? = null

    /**
     * Adds an event listener to the P2P engine.
     *
     * @param event Event type to listen for
     * @param listener Callback function to invoke when the event occurs
     */
    fun <T> addEventListener(
        event: CoreEventMap<T>,
        listener: EventListener<T>,
    ) {
        eventEmitter.addEventListener(event, listener)
    }

    /**
     * Removes an event listener from the P2P engine.
     *
     * @param event Event type to remove the listener from
     * @param listener Callback function to remove
     */
    fun <T> removeEventListener(
        event: CoreEventMap<T>,
        listener: EventListener<T>,
    ) {
        eventEmitter.removeEventListener(event, listener)
    }

    /**
     * Initializes and starts P2P media streaming components.
     *
     * @param context Android context required for WebView initialization
     * @param exoPlayer ExoPlayer instance for media playback
     * @throws IllegalStateException if called in an invalid state
     */
    fun start(
        context: Context,
        exoPlayer: ExoPlayer,
    ) {
        Logger.d(TAG, "Starting P2P Media Loader with ExoPlayer")
        prepareStart(context, ExoPlayerPlaybackProvider(exoPlayer))
    }

    /**
     * Initializes and starts P2P media streaming components.
     *
     * @param context Android context required for WebView initialization
     * @param getPlaybackInfo Function to retrieve playback information
     * @throws IllegalStateException if called in an invalid state
     */
    fun start(
        context: Context,
        getPlaybackInfo: () -> PlaybackInfo,
    ) {
        Logger.d(TAG, "Starting P2P Media Loader with playback info callback")
        prepareStart(context, ExternalPlaybackProvider(getPlaybackInfo))
    }

    private fun prepareStart(
        context: Context,
        provider: PlaybackProvider,
    ) {
        if (appState == AppState.STARTED) {
            val errorMessage = "Cannot start P2PMediaLoader in state: $appState"
            Logger.e(TAG, errorMessage)
            throw IllegalStateException(errorMessage)
        }

        job = Job()
        scope = CoroutineScope(job!! + Dispatchers.Main)
        playbackProvider = provider

        initializeComponents(context.applicationContext, provider)

        appState = AppState.STARTED
    }

    private fun initializeComponents(
        context: Context,
        playbackProvider: PlaybackProvider,
    ) {
        manifestParser = HlsManifestParser(playbackProvider, serverPort)
        webViewManager =
            WebViewManager(
                context,
                scope!!,
                engineStateManager,
                playbackProvider,
                eventEmitter,
                customJavaScriptInterfaces,
                onPageLoadFinished = { onWebViewLoaded() },
            )

        // Stats collector (init before server so it can be passed to SegmentHandler)
        if (appKey.isNotEmpty()) {
            val url = statsReportUrl.ifEmpty { "https://api.p2pstorm.cn/v1/stats" }
            statsCollector = P2PStatsCollector(appKey, url, scope!!)
            statsCollector?.start()

            // Connect JS events to stats collector
            statsCollector?.let { collector ->
                eventEmitter.addEventListener(CoreEventMap.OnPeerConnect, EventListener<PeerDetails> {
                    collector.peersCount.incrementAndGet()
                })
                eventEmitter.addEventListener(CoreEventMap.OnPeerClose, EventListener<PeerDetails> {
                    collector.peersCount.updateAndGet { maxOf(0, it - 1) }
                })
                eventEmitter.addEventListener(CoreEventMap.OnChunkUploaded, EventListener<ChunkUploadedDetails> { details ->
                    collector.recordUpload(details.bytesLength.toLong())
                })
            }
        }

        serverModule =
            ServerModule(
                webViewManager!!,
                manifestParser!!,
                engineStateManager,
                customEngineImplementationPath,
                onServerStarted = { onServerStarted() },
                onServerError = { onP2PReadyErrorCallback.onError(it) },
                onManifestChanged = { onManifestChanged() },
                statsCollector = statsCollector,
            ).apply { start(serverPort) }

        // Network monitoring
        networkMonitor = NetworkMonitor(context) { isConnected, isWifi ->
            scope?.launch {
                if (!isConnected) {
                    Logger.w(TAG, "Network lost, disabling P2P temporarily")
                    engineStateManager.changeP2PEngineStatus(true)
                } else {
                    Logger.d(TAG, "Network available (wifi=$isWifi), re-enabling P2P")
                    engineStateManager.changeP2PEngineStatus(false)
                }
            }
        }
        networkMonitor?.start()
    }

    /**
     * Applies dynamic core configurations to the `P2PMediaLoader` engine.
     *
     * @param dynamicCoreConfigJson A JSON string containing dynamic core configurations for the P2P engine.
     * Refer to the [DynamicCoreConfig Documentation](https://docs.p2pstorm.cn/v2.1.0/types/p2p-media-loader-core.DynamicCoreConfig.html).
     * @throws IllegalStateException if P2PMediaLoader is not started
     */
    fun applyDynamicConfig(dynamicCoreConfigJson: String) {
        ensureStarted()

        webViewManager!!.applyDynamicConfig(dynamicCoreConfigJson)
    }

    /**
     * Converts an external HLS manifest URL to a local URL handled by the P2P engine.
     *
     * @param manifestUrl External HLS manifest URL (.m3u8)
     * @return Local URL for P2P-enabled playback
     * @throws IllegalStateException if P2PMediaLoader is not started
     */
    fun getManifestUrl(manifestUrl: String): String {
        ensureStarted()

        val encodedManifestURL = manifestUrl.encodeURLParameter()
        val port = serverModule?.actualPort ?: serverPort
        return Utils.getUrl(port, "$MANIFEST$encodedManifestURL")
    }

    private fun ensureStarted() {
        if (appState != AppState.STARTED) {
            val errorMessage = "Operation not allowed in state: $appState"
            Logger.e(TAG, errorMessage)
            throw IllegalStateException(errorMessage)
        }
    }

    /**
     * Stops P2P streaming and releases all resources.
     * Call [start] to reinitialize after stopping.
     *
     * @throws IllegalStateException if P2PMediaLoader is not started
     */
    fun stop() {
        ensureStarted()

        Logger.d(TAG, "Stopping P2PMediaLoader...")

        appState = AppState.STOPPED

        // Use CountDownLatch to ensure async cleanup completes before cancelling scope
        val latch = java.util.concurrent.CountDownLatch(2)

        // Destroy WebView on Main thread
        scope?.launch(Dispatchers.Main) {
            try {
                webViewManager?.destroy()
            } catch (e: Exception) {
                Logger.e(TAG, "Error destroying WebView: ${e.message}")
            }
            webViewManager = null
            latch.countDown()
        } ?: latch.countDown()

        // Stop server and cleanup on IO thread
        scope?.launch(Dispatchers.IO) {
            try {
                serverModule?.stop()
            } catch (e: Exception) {
                Logger.e(TAG, "Error stopping server: ${e.message}")
            }
            serverModule = null
            latch.countDown()
        } ?: latch.countDown()

        // Wait up to 3 seconds for async cleanup to finish
        latch.await(3, java.util.concurrent.TimeUnit.SECONDS)

        networkMonitor?.stop()
        networkMonitor = null

        statsCollector?.stop()
        statsCollector = null

        manifestParser?.reset()
        manifestParser = null

        playbackProvider = null

        engineStateManager.reset()
        eventEmitter.removeAllListeners()

        // Cancel scope after cleanup is complete
        job?.cancel()
        job = null
        scope = null

        Logger.d(TAG, "P2PMediaLoader stopped and resources freed.")
    }

    private suspend fun onManifestChanged() {
        Logger.d(TAG, "Manifest changed, resetting data")
        playbackProvider!!.resetData()
        manifestParser!!.reset()
    }

    private fun onWebViewLoaded() {
        scope!!.launch {
            try {
                Logger.d(TAG, "WebView loaded, initializing P2P engine")
                webViewManager!!.initCoreEngine(coreConfigJson)
                Logger.d(TAG, "P2P engine initialized, notifying onP2PReadyCallback")
                onP2PReadyCallback.onReady()
            } catch (e: Exception) {
                onP2PReadyErrorCallback.onError(e.message ?: "Unknown error")
            }
        }
    }

    private fun onServerStarted() {
        val port = serverModule?.actualPort ?: serverPort
        // Update manifest parser with actual port in case of fallback
        manifestParser?.serverPort = port
        Logger.d(TAG, "Server started on port $port")
        val urlPath =
            if (customEngineImplementationPath != null) {
                Utils.getUrl(port, CUSTOM_FILE_URL)
            } else {
                Utils.getUrl(port, CORE_FILE_URL)
            }

        try {
            Logger.d(TAG, "Loading WebView with URL: $urlPath")
            webViewManager!!.loadWebView(urlPath)
        } catch (e: Exception) {
            onP2PReadyErrorCallback.onError(e.message ?: "Unknown error")
        }
    }

    /**
     * Get current P2P statistics.
     * @return P2PStats with p2p_bytes, cdn_bytes, upload_bytes, peers_count, duration
     */
    fun getStats(): P2PStats? = statsCollector?.getStats()

    /**
     * Get current P2P ratio (0-100%).
     */
    fun getP2PRatio(): Double = statsCollector?.getRatio() ?: 0.0

    /**
     * Check if currently connected to network.
     */
    fun isNetworkConnected(): Boolean = networkMonitor?.isConnected() ?: true

    /**
     * Check if connected via WiFi.
     */
    fun isWifi(): Boolean = networkMonitor?.isWifi() ?: false

    companion object {
        private const val TAG = "P2PMediaLoader"
    }
}
