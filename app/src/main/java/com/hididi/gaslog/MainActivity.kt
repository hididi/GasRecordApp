package com.hididi.gaslog

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import com.hididi.gaslog.ui.GasLogRoot
import com.hididi.gaslog.ui.theme.GasLogTheme
import com.hididi.gaslog.ui.vm.GasLogViewModel

class MainActivity : ComponentActivity() {

    private val vm: GasLogViewModel by viewModels()
    private var incomingUri by mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        incomingUri = extractImage(intent)
        setContent {
            GasLogTheme {
                GasLogRoot(
                    vm = vm,
                    incomingUri = incomingUri,
                    onIncomingConsumed = { incomingUri = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        extractImage(intent)?.let { incomingUri = it }
    }

    @Suppress("DEPRECATION")
    private fun extractImage(intent: Intent?): Uri? {
        if (intent == null) return null
        return when (intent.action) {
            Intent.ACTION_SEND -> {
                val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                if (uri != null && intent.type?.startsWith("image") == true) uri else null
            }

            Intent.ACTION_SEND_MULTIPLE -> {
                val list = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                list?.firstOrNull { true }
            }

            else -> null
        }
    }
}