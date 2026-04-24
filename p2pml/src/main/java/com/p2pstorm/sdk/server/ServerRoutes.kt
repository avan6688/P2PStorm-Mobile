package com.p2pstorm.sdk.server

import com.p2pstorm.sdk.Constants.CORE_FILE_PATH
import com.p2pstorm.sdk.Constants.CUSTOM_FILE_PATH
import io.ktor.server.application.Application
import io.ktor.server.http.content.staticResources
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing

internal class ServerRoutes(
    private val manifestHandler: ManifestHandler,
    private val segmentHandler: SegmentHandler,
    private val customP2pmlImplementationPath: String? = null,
) {
    fun setup(application: Application) {
        application.routing {
            get("/") {
                when {
                    call.parameters["manifest"] != null ->
                        manifestHandler.handleManifestRequest(call)
                    call.parameters["segment"] != null -> segmentHandler.handleSegmentRequest(call)

                    else ->
                        call.respondText(
                            "Missing required parameter",
                            status = io.ktor.http.HttpStatusCode.BadRequest,
                        )
                }
            }

            staticResources("/static", CORE_FILE_PATH) {
                default("index.html")
            }

            if (customP2pmlImplementationPath != null) {
                staticResources(CUSTOM_FILE_PATH, customP2pmlImplementationPath) {
                    default("index.html")
                }
            }
        }
    }
}
