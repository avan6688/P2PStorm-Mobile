package com.p2pstorm.sdk.server

import androidx.media3.common.util.UnstableApi
import com.p2pstorm.sdk.logger.Logger
import com.p2pstorm.sdk.parser.HlsManifestParser
import com.p2pstorm.sdk.utils.P2PStateManager
import com.p2pstorm.sdk.utils.P2PStatsCollector
import com.p2pstorm.sdk.webview.WebViewManager
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStarted
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.cors.routing.CORS
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

@UnstableApi
internal class ServerModule(
    private val webViewManager: WebViewManager,
    private val manifestParser: HlsManifestParser,
    private val p2pEngineStateManager: P2PStateManager,
    private val customEngineImplementationPath: String? = null,
    private val onServerStarted: () -> Unit,
    private val onServerError: (String) -> Unit,
    private val onManifestChanged: suspend () -> Unit,
    private val statsCollector: P2PStatsCollector? = null,
) {
    private var httpClient: OkHttpClient? = null
    private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null

    var actualPort: Int = 8080
        private set

    fun start(port: Int = 8080) {
        if (server != null) return

        // Try requested port, then fallback to next available
        for (tryPort in port..(port + 10)) {
            try {
                server =
                    embeddedServer(CIO, tryPort) {
                        configureCORS(this)
                        configureRouting(this)
                        subscribeToServerStarted(this)
                    }.start(wait = false)
                actualPort = tryPort
                if (tryPort != port) {
                    Logger.w(TAG, "Port $port in use, started on $tryPort instead")
                }
                return
            } catch (e: Exception) {
                if (tryPort == port + 10) {
                    val message = "Failed to start server on ports $port-${port + 10}: ${e.message}"
                    Logger.e(TAG, message, e)
                    onServerError(message)
                }
            }
        }
    }

    private fun stopServer() {
        server?.stop()
        server = null
    }

    suspend fun stop() {
        destroyHttpClient()
        stopServer()
    }

    private fun subscribeToServerStarted(application: Application) {
        application.monitor.subscribe(ApplicationStarted) {
            onServerStarted()
        }
    }

    private fun configureCORS(application: Application) {
        application.install(CORS) {
            anyHost()
        }
    }

    private fun configureRouting(application: Application) {
        httpClient = OkHttpClient.Builder()
            .connectionPool(ConnectionPool(5, 30, TimeUnit.SECONDS))
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .build()
        val manifestHandler = ManifestHandler(httpClient!!, manifestParser, webViewManager, onManifestChanged)
        val segmentHandler =
            SegmentHandler(
                httpClient!!,
                webViewManager,
                manifestParser,
                p2pEngineStateManager,
                statsCollector,
            )
        val routingModule =
            ServerRoutes(
                manifestHandler,
                segmentHandler,
                customEngineImplementationPath,
            )

        routingModule.setup(application)
    }

    private suspend fun destroyHttpClient() {
        withContext(Dispatchers.IO) {
            httpClient?.dispatcher?.executorService?.shutdown()
            httpClient?.connectionPool?.evictAll()
            httpClient = null
        }
    }

    companion object {
        private const val TAG = "ServerModule"
    }
}
