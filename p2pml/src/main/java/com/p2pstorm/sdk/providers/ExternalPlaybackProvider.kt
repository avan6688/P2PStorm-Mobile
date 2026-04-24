package com.p2pstorm.sdk.providers

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import com.p2pstorm.sdk.PlaybackInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ExternalPlaybackProvider(
    private val getPlaybackInfo: () -> PlaybackInfo,
) : PlaybackProvider {
    @OptIn(UnstableApi::class)
    override suspend fun getAbsolutePlaybackPosition(parsedMediaPlaylist: HlsMediaPlaylist): Double =
        withContext(Dispatchers.Main) {
            return@withContext getPlaybackInfo().currentPlayPosition
        }

    override suspend fun getPlaybackPositionAndSpeed(): PlaybackInfo =
        withContext(Dispatchers.Main) {
            return@withContext getPlaybackInfo()
        }

    override suspend fun resetData() {
    }
}
