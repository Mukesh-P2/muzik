package com.muzik.app.player

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.OnBackPressedCallback
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.muzik.app.MainActivity
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
    private val activity = context.findActivity()
    private val webView = WebView(context)
    private val pictureInPictureSourceRect = Rect()
    private var customView: View? = null
    private var customViewContainer: FrameLayout? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var fullscreenWindowState: FullscreenWindowState? = null
    private var destroyed = false
    private var ready = false
    private var desired: PlaybackState? = null
    private var clockOffsetMs: Long = 0
    private var appliedRevision: Long = Long.MIN_VALUE
    private var appliedVideoId: String? = null
    private val playbackEndGuard = PlaybackEndGuard()
    private var playerState: Int = -1
    private var scheduledAction: Runnable? = null
    private var preferredQuality = "default"
    private val fullscreenBackCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            hideCustomView()
        }
    }
    private val lifecycleObserver = object : DefaultLifecycleObserver {
        override fun onResume(owner: LifecycleOwner) {
            if (!destroyed) {
                webView.onResume()
                handler.post(::resynchronizeAfterResume)
            }
        }

        override fun onPause(owner: LifecycleOwner) {
            if (!destroyed && activity?.isInPictureInPictureMode != true) webView.onPause()
        }

        override fun onStop(owner: LifecycleOwner) {
            hideCustomView()
        }

        override fun onDestroy(owner: LifecycleOwner) {
            destroy()
        }
    }
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
        // Let the iframe use the WebView's actual CSS viewport. YouTube's responsive
        // overlays otherwise render against a desktop-width layout and their close
        // target can wind up outside the visible player.
        webView.settings.useWideViewPort = true
        webView.settings.loadWithOverviewMode = true
        webView.settings.textZoom = 100
        webView.settings.setSupportZoom(false)
        webView.settings.builtInZoomControls = false
        webView.settings.displayZoomControls = false
        webView.isHorizontalScrollBarEnabled = false
        webView.isVerticalScrollBarEnabled = false
        webView.overScrollMode = View.OVER_SCROLL_NEVER
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        webView.webChromeClient = PlayerChromeClient()
        webView.webViewClient = WebViewClient()
        webView.addJavascriptInterface(PlayerBridge(), "AndroidPlayer")

        (activity as? LifecycleOwner)?.lifecycle?.addObserver(lifecycleObserver)
        (activity as? OnBackPressedDispatcherOwner)?.onBackPressedDispatcher?.addCallback(
            fullscreenBackCallback,
        )

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
        if (destroyed) return
        destroyed = true
        hideCustomView()
        handler.removeCallbacksAndMessages(null)
        (activity as? LifecycleOwner)?.lifecycle?.removeObserver(lifecycleObserver)
        fullscreenBackCallback.remove()
        webView.removeJavascriptInterface("AndroidPlayer")
        webView.webChromeClient = null
        webView.destroy()
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (hasWindowFocus && customView != null) hideSystemBars()
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (!changed || destroyed || activity?.isInPictureInPictureMode == true) return
        if (
            getGlobalVisibleRect(pictureInPictureSourceRect) &&
            !pictureInPictureSourceRect.isEmpty
        ) {
            (activity as? MainActivity)?.updatePictureInPictureSourceRect(
                pictureInPictureSourceRect,
            )
        }
    }

    private fun applyRevisionIfNeeded() {
        val playback = desired ?: return
        if (playback.revision == appliedRevision) return

        // An ended event from the previous iframe item must never advance the newly
        // received room revision before that revision has actually started playing.
        playbackEndGuard.revisionScheduled()

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
            playbackEndGuard.commandDispatched(current.revision)
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

    private fun resynchronizeAfterResume() {
        if (destroyed || !ready || desired == null) return
        // The YouTube iframe may pause its local media when Android backgrounds the
        // Activity without changing the room revision. Reapply that same revision so
        // this device immediately seeks to the shared clock and resumes if the room is
        // still playing; no playback command is sent to the server or other members.
        appliedRevision = Long.MIN_VALUE
        applyRevisionIfNeeded()
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

    private fun showCustomView(
        view: View,
        callback: WebChromeClient.CustomViewCallback,
    ) {
        val hostActivity = activity
        if (destroyed || hostActivity == null || customView != null) {
            callback.onCustomViewHidden()
            return
        }

        val decorView = hostActivity.window.decorView as? ViewGroup
        if (decorView == null) {
            callback.onCustomViewHidden()
            return
        }

        fullscreenWindowState = captureFullscreenWindowState(hostActivity)
        customView = view
        customViewCallback = callback
        fullscreenBackCallback.isEnabled = true

        (view.parent as? ViewGroup)?.removeView(view)
        val container = FrameLayout(hostActivity).apply {
            setBackgroundColor(Color.BLACK)
            isClickable = true
            isFocusable = true
            isFocusableInTouchMode = true
            keepScreenOn = true
            clipChildren = false
            addView(
                view,
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
            )
        }
        customViewContainer = container
        decorView.addView(
            container,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        container.bringToFront()
        container.requestFocus()

        // Locking the current orientation keeps the iframe and its custom view in the
        // same Activity. Forcing landscape here would recreate activities that do not
        // opt into handling orientation configuration changes.
        hostActivity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED
        hideSystemBars()
        container.post {
            if (customView === view) hideSystemBars()
        }
    }

    private fun hideCustomView() {
        val view = customView ?: return
        val callback = customViewCallback
        val container = customViewContainer
        val windowState = fullscreenWindowState

        // Clear state before notifying WebView because the callback may synchronously
        // ask the WebChromeClient to hide the view again.
        customView = null
        customViewCallback = null
        customViewContainer = null
        fullscreenWindowState = null
        fullscreenBackCallback.isEnabled = false

        (view.parent as? ViewGroup)?.removeView(view)
        (container?.parent as? ViewGroup)?.removeView(container)
        restoreFullscreenWindowState(windowState)
        callback?.onCustomViewHidden()
    }

    private fun captureFullscreenWindowState(hostActivity: Activity): FullscreenWindowState {
        val decorView = hostActivity.window.decorView
        val insets = ViewCompat.getRootWindowInsets(decorView)
        val controller = WindowCompat.getInsetsController(hostActivity.window, decorView)
        return FullscreenWindowState(
            requestedOrientation = hostActivity.requestedOrientation,
            statusBarsVisible = insets?.isVisible(WindowInsetsCompat.Type.statusBars()),
            navigationBarsVisible = insets?.isVisible(WindowInsetsCompat.Type.navigationBars()),
            systemBarsBehavior = controller.systemBarsBehavior,
        )
    }

    private fun hideSystemBars() {
        val hostActivity = activity ?: return
        WindowCompat.getInsetsController(
            hostActivity.window,
            hostActivity.window.decorView,
        ).apply {
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun restoreFullscreenWindowState(state: FullscreenWindowState?) {
        val hostActivity = activity ?: return
        if (state == null) return

        val decorView = hostActivity.window.decorView
        WindowCompat.getInsetsController(hostActivity.window, decorView).apply {
            systemBarsBehavior = state.systemBarsBehavior
            state.statusBarsVisible?.let { visible ->
                if (visible) show(WindowInsetsCompat.Type.statusBars())
                else hide(WindowInsetsCompat.Type.statusBars())
            }
            state.navigationBarsVisible?.let { visible ->
                if (visible) show(WindowInsetsCompat.Type.navigationBars())
                else hide(WindowInsetsCompat.Type.navigationBars())
            }
        }
        ViewCompat.requestApplyInsets(decorView)

        if (!hostActivity.isFinishing && !hostActivity.isDestroyed) {
            hostActivity.requestedOrientation = state.requestedOrientation
        }
    }

    private inner class PlayerChromeClient : WebChromeClient() {
        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            showCustomView(view, callback)
        }

        override fun onHideCustomView() {
            hideCustomView()
        }
    }

    inner class PlayerBridge {
        @JavascriptInterface
        fun onReady() = handler.post {
            if (destroyed) return@post
            ready = true
            applyRevisionIfNeeded()
            applyPreferredQuality()
        }

        @JavascriptInterface
        fun onStateChanged(
            state: Int,
            videoId: String,
            positionSeconds: Double,
            durationSeconds: Double,
        ) = handler.post {
            if (destroyed) return@post
            playerState = state
            onProgress(
                (positionSeconds * 1_000).toLong(),
                (durationSeconds * 1_000).toLong(),
            )
            if (state == 1) {
                playbackEndGuard.playerStarted(videoId, desired)
                applyPreferredQuality()
                reconcilePosition(positionSeconds)
            }
            if (state == 0) {
                playbackEndGuard.playerEnded(videoId, desired)?.let(onEnded)
            }
        }

        @JavascriptInterface
        fun onPosition(positionSeconds: Double, durationSeconds: Double) = handler.post {
            if (destroyed) return@post
            onProgress(
                (positionSeconds * 1_000).toLong(),
                (durationSeconds * 1_000).toLong(),
            )
            reconcilePosition(positionSeconds)
        }

        @JavascriptInterface
        fun onError(code: Int) = handler.post {
            if (destroyed) return@post
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
            if (destroyed) return@post
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

    private data class FullscreenWindowState(
        val requestedOrientation: Int,
        val statusBarsVisible: Boolean?,
        val navigationBarsVisible: Boolean?,
        val systemBarsBehavior: Int,
    )
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
