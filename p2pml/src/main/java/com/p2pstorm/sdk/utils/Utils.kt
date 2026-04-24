package com.p2pstorm.sdk.utils

import com.p2pstorm.sdk.Constants
import com.p2pstorm.sdk.Constants.HTTP_PREFIX
import com.p2pstorm.sdk.Constants.LOCALHOST
import io.ktor.http.decodeURLQueryComponent
import io.ktor.http.encodeURLParameter
import io.ktor.server.application.ApplicationCall
import io.ktor.util.decodeBase64String
import io.ktor.util.encodeBase64
import okhttp3.Request

internal object Utils {
    fun getAbsoluteUrl(
        baseManifestUrl: String,
        mediaUri: String,
    ): String {
        if (mediaUri.startsWith(HTTP_PREFIX) || mediaUri.startsWith(Constants.HTTPS_PREFIX)) {
            return mediaUri
        }

        var baseUrl = baseManifestUrl.substringBeforeLast("/")
        if (!baseUrl.endsWith("/")) {
            baseUrl += "/"
        }

        return "$baseUrl$mediaUri"
    }

    fun encodeUrlToBase64(url: String): String = url.encodeBase64().encodeURLParameter()

    fun decodeBase64Url(encodedString: String): String = encodedString.decodeURLQueryComponent().decodeBase64String()

    fun getUrl(
        port: Int,
        path: String,
    ): String = "$HTTP_PREFIX$LOCALHOST:$port/$path"

    fun copyHeaders(
        call: ApplicationCall,
        requestBuilder: Request.Builder,
    ) {
        val excludedHeaders =
            setOf(
                "Host",
                "Connection",
                "Transfer-Encoding",
                "Expect",
                "Upgrade",
                "Proxy-Connection",
                "Keep-Alive",
                "Accept-Encoding",
            )

        for (headerName in call.request.headers.names()) {
            if (headerName !in excludedHeaders) {
                val headerValues = call.request.headers.getAll(headerName)
                if (headerValues != null) {
                    for (headerValue in headerValues) {
                        requestBuilder.addHeader(headerName, headerValue)
                    }
                }
            }
        }
    }
}
