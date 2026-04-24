package com.p2pstorm.demo

import android.os.Bundle
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.OptIn
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.media3.common.util.UnstableApi
import com.p2pstorm.demo.ui.ExoPlayerScreen
import com.p2pstorm.demo.viewmodel.ExoPlayerViewModel

@UnstableApi
class MainActivity : ComponentActivity() {
    private val viewModel: ExoPlayerViewModel by lazy {
        ExoPlayerViewModel(application)
    }

    @OptIn(UnstableApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Enable debugging of WebViews
        WebView.setWebContentsDebuggingEnabled(true)

        viewModel.setupP2PML()

        setContent {
            val isLoading by viewModel.loadingState.collectAsState()
            ExoPlayerScreen(player = viewModel.player, videoTitle = "Test Stream", isLoading)
        }
    }

    override fun onStop() {
        super.onStop()
        viewModel.updateP2PConfig(isP2PDisabled = true)
    }

    override fun onRestart() {
        super.onRestart()
        viewModel.updateP2PConfig(isP2PDisabled = false)
    }

    override fun onDestroy() {
        super.onDestroy()
        viewModel.releasePlayer()
    }
}
