package cl.innova.nfc.emitter

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.nfc.NfcAdapter
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.delay

internal data class Diagnostics(
    val available: Boolean = false,
    val enabled: Boolean = false,
    val hce: Boolean = false,
    val secureSupported: Boolean? = null,
    val secureEnabled: Boolean? = null,
)

class MainActivity : ComponentActivity() {
    private lateinit var controller: AttendeeController
    private var diagnostics by mutableStateOf(Diagnostics())

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.AttendeeTheme)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.light(0, 0), navigationBarStyle = SystemBarStyle.light(0, 0))
        controller = ViewModelProvider(this)[AttendeeViewModel::class.java].controller
        setContent {
            var showBrand by remember { mutableStateOf(savedInstanceState == null) }
            if (showBrand) {
                Box(
                    Modifier.fillMaxSize().background(Color.White).safeDrawingPadding().padding(horizontal = 16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    AttendeeBrand(Modifier.widthIn(max = 288.dp).fillMaxWidth().aspectRatio(3f))
                }
                LaunchedEffect(Unit) {
                    delay(1_000L)
                    showBrand = false
                }
            } else {
                val state by controller.state.collectAsState()
                val slot by controller.sharing.state.collectAsState()
                val identity = controller.visibleIdentity(state, slot)
                AttendeeScreen(controller.presentation(state, slot, identity), identity, controller,
                    diagnostics, ::refresh, ::openNfcSettings)
            }
        }
    }

    private fun openNfcSettings() {
        try {
            startActivity(Intent(Settings.ACTION_NFC_SETTINGS))
        } catch (_: ActivityNotFoundException) {
            startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS))
        }
    }

    override fun onResume() {
        super.onResume()
        controller.resume()
        refresh()
    }

    override fun onPause() {
        controller.pause()
        super.onPause()
    }

    private fun refresh() {
        val adapter = NfcAdapter.getDefaultAdapter(this)
        diagnostics = Diagnostics(
            available = adapter != null,
            enabled = adapter?.isEnabled == true,
            hce = packageManager.hasSystemFeature(PackageManager.FEATURE_NFC_HOST_CARD_EMULATION),
            secureSupported = if (Build.VERSION.SDK_INT >= 29) adapter?.isSecureNfcSupported else null,
            secureEnabled = if (Build.VERSION.SDK_INT >= 29) adapter?.isSecureNfcEnabled else null,
        )
    }
}
