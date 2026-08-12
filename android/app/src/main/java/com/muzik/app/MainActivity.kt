package com.muzik.app

import android.app.PictureInPictureParams
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.muzik.app.ui.MuzikApp
import com.muzik.app.ui.MuzikTheme

class MainActivity : ComponentActivity() {
    private val viewModel: MuzikViewModel by viewModels {
        MuzikViewModel.Factory(applicationContext)
    }
    private var pictureInPictureMode by mutableStateOf(false)
    private var pictureInPictureSourceRect: Rect? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyInvite(intent)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        setContent {
            val state = viewModel.uiState.collectAsStateWithLifecycle().value
            LaunchedEffect(
                state.playerConsent,
                state.room?.playback?.status,
                state.room?.playback?.video?.videoId,
            ) {
                updatePictureInPictureParams(state)
            }
            MuzikTheme {
                MuzikApp(
                    state = state,
                    viewModel = viewModel,
                    isInPictureInPictureMode = pictureInPictureMode,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyInvite(intent)
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (!isInPictureInPictureMode && supportsPictureInPicture() && canEnterPictureInPicture()) {
            runCatching {
                enterPictureInPictureMode(buildPictureInPictureParams(autoEnter = false))
            }
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        pictureInPictureMode = isInPictureInPictureMode
    }

    private fun updatePictureInPictureParams(state: MuzikUiState) {
        if (!supportsPictureInPicture() || isFinishing || isDestroyed) return
        val eligible = state.playerConsent &&
            state.room?.playback?.video != null &&
            state.room.playback.status == "playing"
        runCatching {
            setPictureInPictureParams(buildPictureInPictureParams(autoEnter = eligible))
        }
    }

    private fun buildPictureInPictureParams(autoEnter: Boolean): PictureInPictureParams {
        val builder = PictureInPictureParams.Builder()
            .setAspectRatio(Rational(16, 9))
        pictureInPictureSourceRect?.let { sourceRect ->
            builder.setSourceRectHint(sourceRect)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder
                .setAutoEnterEnabled(autoEnter)
                .setSeamlessResizeEnabled(true)
        }
        return builder.build()
    }

    internal fun updatePictureInPictureSourceRect(rect: Rect) {
        if (isInPictureInPictureMode || rect.isEmpty || pictureInPictureSourceRect == rect) return
        pictureInPictureSourceRect = Rect(rect)
        updatePictureInPictureParams(viewModel.uiState.value)
    }

    private fun canEnterPictureInPicture(): Boolean {
        val state = viewModel.uiState.value
        return state.playerConsent &&
            state.room?.playback?.video != null &&
            state.room?.playback?.status == "playing"
    }

    private fun supportsPictureInPicture(): Boolean =
        packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

    private fun applyInvite(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        intent.data?.getQueryParameter("room")?.let(viewModel::handleInvite)
    }
}
