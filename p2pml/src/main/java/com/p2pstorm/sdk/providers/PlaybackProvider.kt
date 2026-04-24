package com.p2pstorm.sdk.providers

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import com.p2pstorm.sdk.PlaybackInfo

interface PlaybackProvider {
    @OptIn(UnstableApi::class)
    suspend fun getAbsolutePlaybackPosition(parsedMediaPlaylist: HlsMediaPlaylist): Double

    suspend fun getPlaybackPositionAndSpeed(): PlaybackInfo

    suspend fun resetData()
}
