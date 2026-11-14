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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

internal data class Diagnostics(
    val available: Boolean = false,
    val enabled: Boolean = false,
    val hce: Boolean = false,
    val secureSupported: Boolean? = null,
    val secureEnabled: Boolean? = null,
)

class MainActivity : ComponentActivity() {
    private var diagnostics by mutableStateOf(Diagnostics())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.light(0, 0), navigationBarStyle = SystemBarStyle.light(0, 0))
        setContent { EmitterScreen(diagnostics, ::refresh, ::openNfcSettings) }
    }

    private fun openNfcSettings() {
        try {
            startActivity(Intent(Settings.ACTION_NFC_SETTINGS))
        } catch (_: ActivityNotFoundException) {
            startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS))
        }
    }

    override fun onResume() { super.onResume(); refresh() }

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
