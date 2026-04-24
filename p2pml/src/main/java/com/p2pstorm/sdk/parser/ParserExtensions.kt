package com.p2pstorm.sdk.parser

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import com.p2pstorm.sdk.ByteRange
import com.p2pstorm.sdk.utils.Utils

val HlsMediaPlaylist.Segment.byteRange: ByteRange?
    @OptIn(UnstableApi::class)
    get() =
        byteRangeLength
            .takeIf { it != -1L }
            ?.let { ByteRange(byteRangeOffset, byteRangeOffset + byteRangeLength - 1) }

@OptIn(UnstableApi::class)
fun HlsMediaPlaylist.Segment.getAbsoluteUrl(baseUrl: String): String =
    Utils.getAbsoluteUrl(
        baseUrl,
        this.url,
    )

@OptIn(UnstableApi::class)
fun HlsMediaPlaylist.Segment.getRuntimeUrl(baseUrl: String): String {
    val absoluteUrl = this.getAbsoluteUrl(baseUrl)

    return if (this.byteRange != null) {
        "$absoluteUrl|${this.byteRange!!.start}-${this.byteRange!!.end}"
    } else {
        absoluteUrl
    }
}
