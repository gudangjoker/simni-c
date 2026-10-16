package com.chatrok.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chatrok.app.core.designsystem.theme.ChatrokTheme
import com.chatrok.app.core.push.PushConstants
import com.chatrok.app.feature.settings.ThemeViewModel
import com.chatrok.app.navigation.ChatrokNavGraph
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val themeViewModel: ThemeViewModel by viewModels()
    private var pushConversationId by mutableStateOf<String?>(null)

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        // Izin diproses secara graceful
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleNotificationIntent(intent)
        checkNotificationPermission()
        enableEdgeToEdge()

        // Optimasi Refresh Rate Tinggi (90Hz / 120Hz / 144Hz otomatis)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val displayModes = display?.supportedModes ?: emptyArray()
                val maxMode = displayModes.maxByOrNull { it.refreshRate }
                maxMode?.let {
                    val lp = window.attributes
                    lp.preferredDisplayModeId = it.modeId
                    window.attributes = lp
                }
            } catch (_: Exception) {
                // Abaikan jika OEM tidak mengizinkan override
            }
        }
        setContent {
            val currentThemeMode by themeViewModel.themeMode.collectAsStateWithLifecycle()

            ChatrokTheme(themeMode = currentThemeMode) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    ChatrokNavGraph(initialConversationId = pushConversationId)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleNotificationIntent(intent)
    }

    private fun handleNotificationIntent(intent: Intent?) {
        val convId = intent?.getStringExtra(PushConstants.EXTRA_CONVERSATION_ID)
        if (!convId.isNullOrBlank()) {
            pushConversationId = convId
        }
    }

    private fun checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}

