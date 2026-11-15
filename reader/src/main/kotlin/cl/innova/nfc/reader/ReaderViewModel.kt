package cl.innova.nfc.reader

import android.app.Application
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope

internal class ReaderViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = application.getSharedPreferences("staff_connection", Application.MODE_PRIVATE)
    private val client = HttpStaffApi.client()

    val controller = ReaderController(
        scope = viewModelScope,
        allowHttp = BuildConfig.DEBUG,
        storedAddress = prefs.getString("server_address", null),
        saveAddress = { prefs.edit { putString("server_address", it) } },
        apiFor = { HttpStaffApi(it, client) },
    )
}
