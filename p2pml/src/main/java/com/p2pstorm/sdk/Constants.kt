package com.p2pstorm.sdk

internal object Constants {
    const val MPEGURL_CONTENT_TYPE = "application/vnd.apple.mpegurl"
    const val DEFAULT_SERVER_PORT = 8080
    const val LOCALHOST = "127.0.0.1"
    const val HTTP_PREFIX = "http://"
    const val HTTPS_PREFIX = "https://"
    const val CORE_FILE_PATH = "p2pml/static/"
    const val CORE_FILE_URL = "static/index.html"
    const val CUSTOM_FILE_PATH = "custom-static/"
    const val CUSTOM_FILE_URL = "custom-static/index.html"

    object QueryParams {
        const val SEGMENT = "?segment="
        const val MANIFEST = "?manifest="
    }

    object StreamTypes {
        const val MAIN = "main"
        const val SECONDARY = "secondary"
    }
}
