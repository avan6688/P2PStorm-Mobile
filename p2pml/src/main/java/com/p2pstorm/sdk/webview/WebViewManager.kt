package com.p2pstorm.sdk.webview

import android.annotation.SuppressLint
import android.content.Context
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.webkit.WebViewClientCompat
import com.p2pstorm.sdk.DynamicP2PCoreConfig
import com.p2pstorm.sdk.logger.Logger
import com.p2pstorm.sdk.providers.PlaybackProvider
import com.p2pstorm.sdk.utils.EventEmitter
import com.p2pstorm.sdk.utils.P2PStateManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private fun escapeForJs(json: String): String {
    return json
        .replace("\\", "\\\\")
        .replace("'", "\\'")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
}

@SuppressLint("JavascriptInterface")
@OptIn(UnstableApi::class)
internal class WebViewManager(
    context: Context,
    private val coroutineScope: CoroutineScope,
    private val engineStateManager: P2PStateManager,
    private val playbackProvider: PlaybackProvider,
    private val eventEmitter: EventEmitter,
    customJavaScriptInterfaces: List<Pair<String, Any>>,
    onPageLoadFinished: () -> Unit,
) {
    @SuppressLint("SetJavaScriptEnabled")
    private val webView =
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = WebViewClientCompat()
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                    consoleMessage?.let {
                        val level = it.messageLevel()
                        val msg = "${it.message()} [${it.sourceId()}:${it.lineNumber()}]"
                        when (level) {
                            ConsoleMessage.MessageLevel.ERROR -> Logger.e(TAG, "JS: $msg")
                            ConsoleMessage.MessageLevel.WARNING -> Logger.w(TAG, "JS: $msg")
                            else -> Logger.d(TAG, "JS: $msg")
                        }
                    }
                    return true
                }
            }
            visibility = View.GONE
            addJavascriptInterface(
                JavaScriptInterface(onPageLoadFinished, eventEmitter),
                "Android",
            )
        }
    private val webMessageProtocol = WebMessageProtocol(webView, coroutineScope)

    private var playbackInfoJob: Job? = null

    init {
        customJavaScriptInterfaces.forEach { (name, obj) ->
            val isValid = validateJavaScriptInterface(obj)
            if (!isValid) {
                Logger.e(TAG, "Object $obj does not have any methods annotated with @JavascriptInterface")
                return@forEach
            }
            webView.addJavascriptInterface(obj, name)
        }
    }

    private fun validateJavaScriptInterface(obj: Any): Boolean {
        val methods = obj::class.java.methods
        return methods.any { it.isAnnotationPresent(JavascriptInterface::class.java) }
    }

    private fun startPlaybackInfoUpdate() {
        if (playbackInfoJob?.isActive == true) return

        playbackInfoJob =
            coroutineScope.launch {
                while (isActive) {
                    try {
                        if (engineStateManager.isEngineDisabled()) {
                            break
                        }

                        val currentPlaybackInfo =
                            playbackProvider.getPlaybackPositionAndSpeed()
                        val playbackInfoJson = Json.encodeToString(currentPlaybackInfo)

                        sendPlaybackInfo(playbackInfoJson)

                        delay(PLAYBACK_INFO_INTERVAL_MS)
                    } catch (e: Exception) {
                        Logger.d(TAG, "Playback info update failed: ${e.message}")
                    }
                }
            }
    }

    fun loadWebView(url: String) {
        coroutineScope.launch(Dispatchers.Main) {
            webView.loadUrl(url)
        }
    }

    fun applyDynamicConfig(dynamicCoreConfigJson: String) {
        coroutineScope.launch(Dispatchers.Main) {
            val isP2PDisabled = determineP2PDisabledStatus(dynamicCoreConfigJson) ?: return@launch

            engineStateManager.changeP2PEngineStatus(isP2PDisabled)

            webView.evaluateJavascript(
                "javascript:window.p2p.applyDynamicP2PCoreConfig('${escapeForJs(dynamicCoreConfigJson)}');",
                null,
            )
        }
    }

    private fun determineP2PDisabledStatus(coreDynamicConfigJson: String): Boolean? =
        try {
            val config = Json.decodeFromString<DynamicP2PCoreConfig>(coreDynamicConfigJson)

            config.isP2PDisabled
                ?: if (config.mainStream?.isP2PDisabled == config.secondaryStream?.isP2PDisabled) {
                    config.mainStream?.isP2PDisabled
                } else {
                    null
                }
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to determine P2P engine status: ${e.message}")
            null
        }

    suspend fun requestSegmentBytes(segmentUrl: String): CompletableDeferred<ByteArray>? {
        if (engineStateManager.isEngineDisabled()) return null

        startPlaybackInfoUpdate()
        return webMessageProtocol.requestSegmentBytes(segmentUrl)
    }

    suspend fun sendInitialMessage() {
        if (engineStateManager.isEngineDisabled()) return

        webMessageProtocol.sendInitialMessage()
    }

    private suspend fun sendPlaybackInfo(playbackInfoJson: String) {
        if (engineStateManager.isEngineDisabled()) return

        withContext(Dispatchers.Main) {
            webView.evaluateJavascript(
                "javascript:window.p2p.updatePlaybackInfo('${escapeForJs(playbackInfoJson)}');",
                null,
            )
        }
    }

    suspend fun sendAllStreams(streamsJson: String) {
        if (engineStateManager.isEngineDisabled()) return

        withContext(Dispatchers.Main) {
            webView.evaluateJavascript(
                "javascript:window.p2p.parseAllStreams('${escapeForJs(streamsJson)}');",
                null,
            )
        }
    }

    suspend fun initCoreEngine(coreConfigJson: String) {
        withContext(Dispatchers.Main) {
            webView.evaluateJavascript(
                "javascript:window.p2p.initP2P('${escapeForJs(coreConfigJson)}');",
                null,
            )
        }
    }

    suspend fun sendStream(streamJson: String) {
        if (engineStateManager.isEngineDisabled()) return

        withContext(Dispatchers.Main) {
            webView.evaluateJavascript(
                "javascript:window.p2p.parseStream('${escapeForJs(streamJson)}');",
                null,
            )
        }
    }

    suspend fun setManifestUrl(manifestUrl: String) {
        if (engineStateManager.isEngineDisabled()) return

        withContext(Dispatchers.Main) {
            webView.evaluateJavascript(
                "javascript:window.p2p.setManifestUrl('${escapeForJs(manifestUrl)}');",
                null,
            )
        }
    }

    private fun destroyWebView() {
        webView.apply {
            clearHistory()
            clearCache(true) // true = clear disk cache too
            removeJavascriptInterface("Android")
            destroy()
        }
    }

    suspend fun destroy() {
        playbackInfoJob?.cancel()
        playbackInfoJob = null

        webMessageProtocol.clear()
        destroyWebView()
    }

    companion object {
        private const val TAG = "WebViewManager"
        internal const val PLAYBACK_INFO_INTERVAL_MS = 400L
    }
}
