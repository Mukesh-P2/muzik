package com.muzik.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.muzik.app.ui.MuzikApp
import com.muzik.app.ui.MuzikTheme

class MainActivity : ComponentActivity() {
    private val viewModel: MuzikViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyInvite(intent)
        enableEdgeToEdge()
        setContent {
            MuzikTheme {
                MuzikApp(
                    state = viewModel.uiState.collectAsStateWithLifecycle().value,
                    viewModel = viewModel,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyInvite(intent)
    }

    private fun applyInvite(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        intent.data?.getQueryParameter("room")?.let(viewModel::setRoomCode)
    }
}
