package com.muzik.app.player

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import com.muzik.app.model.PlaybackState
import kotlin.math.abs

@SuppressLint("SetJavaScriptEnabled", "ViewConstructor")
class YouTubePlayerView(
    context: Context,
    private val onEnded: (Long) -> Unit,
    private val onMessage: (String) -> Unit,
    private val onProgress: (Long, Long) -> Unit,
) : FrameLayout(context) {
    private val handler = Handler(Looper.getMainLooper())
    private val webView = WebView(context)
    private var ready = false
    private var desired: PlaybackState? = null
    private var clockOffsetMs: Long = 0
    private var appliedRevision: Long = Long.MIN_VALUE
    private var appliedVideoId: String? = null
    private var playerState: Int = -1
    private var scheduledAction: Runnable? = null
    private var preferredQuality = "default"
    private val driftCheck = object : Runnable {
        override fun run() {
            val playback = desired
            if (ready && playback?.status == "playing" &&
                System.currentTimeMillis() + clockOffsetMs >= playback.anchorServerTimeMs
            ) {
                if (playerState == 2) js("window.MuzikPlayer.play()")
                if (playerState == 1 || playerState == 2) {
                    js("window.MuzikPlayer.reportPosition()")
                }
            }
            handler.postDelayed(this, 4_000)
        }
    }

    init {
        addView(webView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        webView.setBackgroundColor(android.graphics.Color.BLACK)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.mediaPlaybackRequiresUserGesture = false
        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = WebViewClient()
        webView.addJavascriptInterface(PlayerBridge(), "AndroidPlayer")

        val html = context.assets.open("youtube_player.html").bufferedReader().use { it.readText() }
        // YouTube requires a non-empty Referer. loadDataWithBaseURL sets it for a bundled WebView player.
        webView.loadDataWithBaseURL(
            "https://com.muzik.app",
            html,
            "text/html",
            "UTF-8",
            null,
        )
        handler.post(driftCheck)
    }

    fun synchronize(playback: PlaybackState, serverClockOffsetMs: Long) {
        desired = playback
        clockOffsetMs = serverClockOffsetMs
        if (ready) applyRevisionIfNeeded()
    }

    fun setPreferredQuality(quality: String) {
        if (quality !in supportedQualities || quality == preferredQuality) return
        preferredQuality = quality
        if (ready) applyPreferredQuality()
    }

    fun destroy() {
        scheduledAction?.let(handler::removeCallbacks)
        handler.removeCallbacks(driftCheck)
        webView.removeJavascriptInterface("AndroidPlayer")
        webView.destroy()
    }

    private fun applyRevisionIfNeeded() {
        val playback = desired ?: return
        if (playback.revision == appliedRevision) return

        scheduledAction?.let(handler::removeCallbacks)
        val video = playback.video
        if (video == null || playback.status == "idle") {
            js("window.MuzikPlayer.stop()")
            appliedVideoId = null
            appliedRevision = playback.revision
            return
        }

        val estimatedServerNow = System.currentTimeMillis() + clockOffsetMs
        val delayMs = (playback.anchorServerTimeMs - estimatedServerNow).coerceAtLeast(0)
        val videoChanged = appliedVideoId != video.videoId

        if (videoChanged && delayMs > 0) {
            js("window.MuzikPlayer.cue('${video.videoId}', ${playback.positionMs / 1000.0})")
        }

        val action = Runnable {
            val current = desired?.takeIf { it.revision == playback.revision } ?: return@Runnable
            val expectedSeconds = current.expectedPositionMs(
                System.currentTimeMillis() + clockOffsetMs,
            ) / 1000.0
            when {
                videoChanged && current.status == "playing" ->
                    js("window.MuzikPlayer.load('${video.videoId}', $expectedSeconds)")
                videoChanged ->
                    js("window.MuzikPlayer.cue('${video.videoId}', $expectedSeconds)")
                current.status == "playing" -> {
                    js("window.MuzikPlayer.seek($expectedSeconds)")
                    js("window.MuzikPlayer.play()")
                }
                else -> {
                    js("window.MuzikPlayer.seek($expectedSeconds)")
                    js("window.MuzikPlayer.pause()")
                }
            }
        }
        scheduledAction = action
        handler.postDelayed(action, delayMs)
        appliedVideoId = video.videoId
        appliedRevision = playback.revision
    }

    private fun reconcilePosition(actualSeconds: Double) {
        val playback = desired ?: return
        if (playback.status != "playing") return
        val expectedMs = playback.expectedPositionMs(System.currentTimeMillis() + clockOffsetMs)
        val driftMs = actualSeconds * 1000 - expectedMs
        if (abs(driftMs) >= 900) {
            js("window.MuzikPlayer.seek(${expectedMs / 1000.0})")
        }
    }

    private fun js(script: String) {
        webView.evaluateJavascript(script, null)
    }

    private fun applyPreferredQuality() {
        js("window.MuzikPlayer.setQuality('$preferredQuality')")
    }

    inner class PlayerBridge {
        @JavascriptInterface
        fun onReady() = handler.post {
            ready = true
            applyRevisionIfNeeded()
            applyPreferredQuality()
        }

        @JavascriptInterface
        fun onStateChanged(
            state: Int,
            positionSeconds: Double,
            durationSeconds: Double,
        ) = handler.post {
            playerState = state
            onProgress(
                (positionSeconds * 1_000).toLong(),
                (durationSeconds * 1_000).toLong(),
            )
            if (state == 0) onEnded(appliedRevision)
            if (state == 1) {
                applyPreferredQuality()
                reconcilePosition(positionSeconds)
            }
        }

        @JavascriptInterface
        fun onPosition(positionSeconds: Double, durationSeconds: Double) = handler.post {
            onProgress(
                (positionSeconds * 1_000).toLong(),
                (durationSeconds * 1_000).toLong(),
            )
            reconcilePosition(positionSeconds)
        }

        @JavascriptInterface
        fun onError(code: Int) = handler.post {
            val message = when (code) {
                100 -> "This video is no longer available"
                101, 150 -> "This video does not allow embedded playback"
                153 -> "YouTube could not identify this app"
                else -> "YouTube player error ($code)"
            }
            onMessage(message)
        }

        @JavascriptInterface
        fun onAutoplayBlocked() = handler.post {
            onMessage("Tap the YouTube play button to allow playback on this device")
        }
    }

    private companion object {
        val supportedQualities = setOf(
            "default",
            "tiny",
            "small",
            "medium",
            "large",
            "hd720",
            "hd1080",
            "highres",
        )
    }
}
