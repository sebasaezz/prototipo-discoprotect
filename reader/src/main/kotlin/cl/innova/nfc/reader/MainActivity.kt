package cl.innova.nfc.reader

import android.content.ActivityNotFoundException
import android.content.Intent
import android.nfc.NfcAdapter
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
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val nfcDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val generation = AtomicInteger()
    private val reader = NfcReader()
    private lateinit var controller: ReaderController
    private var adapter: NfcAdapter? = null
    private var nfc by mutableStateOf(NfcAvailability.Missing)
    private var readerModeEnabled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.StaffTheme)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(0, 0),
            navigationBarStyle = SystemBarStyle.light(0, 0),
        )
        adapter = NfcAdapter.getDefaultAdapter(this)
        controller = ViewModelProvider(this)[ReaderViewModel::class.java].controller
        setContent {
            var showBrand by remember { mutableStateOf(savedInstanceState == null) }
            if (showBrand) {
                Box(
                    Modifier.fillMaxSize().background(Color.White).safeDrawingPadding().padding(horizontal = 16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    StaffBrand(Modifier.widthIn(max = 288.dp).fillMaxWidth().aspectRatio(3f))
                }
                LaunchedEffect(Unit) {
                    delay(1_000L)
                    showBrand = false
                }
            } else {
                val state by controller.state.collectAsState()
                ReaderScreen(state, nfc, controller, ::openNfcSettings)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        controller.resume()
        val current = generation.incrementAndGet()
        val device = adapter
        if (device == null) {
            nfc = NfcAvailability.Missing
            return
        }
        if (!device.isEnabled) {
            nfc = NfcAvailability.Disabled
            return
        }

        try {
            device.enableReaderMode(this, { tag ->
                if (generation.get() == current) {
                    runOnUiThread {
                        if (generation.get() == current && nfc == NfcAvailability.Ready) {
                            controller.scan {
                                withContext(nfcDispatcher) { reader.read(tag) }
                            }
                        }
                    }
                }
            }, NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK, null)
            readerModeEnabled = true
            nfc = NfcAvailability.Ready
        } catch (_: SecurityException) {
            nfc = NfcAvailability.Error
        } catch (_: IllegalStateException) {
            nfc = NfcAvailability.Error
        }
    }

    override fun onPause() {
        generation.incrementAndGet()
        controller.pause()
        if (readerModeEnabled) {
            readerModeEnabled = false
            try {
                adapter?.disableReaderMode(this)
            } catch (_: IllegalStateException) {
                nfc = NfcAvailability.Error
            } catch (_: SecurityException) {
                nfc = NfcAvailability.Error
            }
        }
        super.onPause()
    }

    override fun onDestroy() {
        nfcDispatcher.close()
        super.onDestroy()
    }

    private fun openNfcSettings() {
        try {
            startActivity(Intent(Settings.ACTION_NFC_SETTINGS))
        } catch (_: ActivityNotFoundException) {
            startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS))
        }
    }
}

internal enum class NfcAvailability { Ready, Missing, Disabled, Error }
