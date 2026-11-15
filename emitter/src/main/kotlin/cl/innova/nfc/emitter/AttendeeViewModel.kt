package cl.innova.nfc.emitter

import android.app.Application
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope

internal class AttendeeViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = application.getSharedPreferences("attendee_connection", Application.MODE_PRIVATE)
    private val client = HttpAttendeeApi.client()
    val controller = AttendeeController(
        scope = viewModelScope,
        allowHttp = BuildConfig.DEBUG,
        storedAddress = prefs.getString("server_address", null),
        storeUrl = { prefs.edit { putString("server_address", it) } },
        apiFor = { HttpAttendeeApi(it, client) },
        sharing = ProcessHceIdentity.store,
    )

    override fun onCleared() {
        controller.close()
        super.onCleared()
    }
}
