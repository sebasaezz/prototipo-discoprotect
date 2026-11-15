package cl.innova.nfc.reader

import cl.innova.nfc.protocol.NfcProtocol
import java.time.Instant

internal data class EmergencyContact(val name: String, val relationship: String, val phone: String)

internal data class HealthProfile(
    val name: String,
    val age: Int?,
    val bloodType: String?,
    val allergies: List<String>,
    val conditions: List<String>,
    val medications: List<String>,
    val contacts: List<EmergencyContact>,
    val updatedAt: Instant? = null,
    val version: Int? = null,
)

/** Fichas ficticias para el modo demo local seleccionado por el guardia. */
internal class HealthRepository {
    private val profiles = mapOf(
        NfcProtocol.DEMO_PERSON_ID to HealthProfile(
            "Antonia Reyes", 22, "O+", listOf("Penicilina"), listOf("Diabetes tipo 1"), listOf("Insulina"),
            listOf(EmergencyContact("María Reyes", "Madre", "+56 9 0000 0001")),
        ),
        "00000000000000000000000000000002" to HealthProfile(
            "Martín Ejemplo", 57, "A−", listOf("Maní"), listOf("Diabetes tipo 2"), listOf("Metformina"),
            listOf(EmergencyContact("Lucía Ejemplo", "Contacto", "+56 9 0000 0002")),
        ),
        "00000000000000000000000000000003" to HealthProfile(
            "Sofía Ficticia", 26, "AB+", emptyList(), listOf("Epilepsia"), listOf("Levetiracetam"),
            listOf(EmergencyContact("Diego Ficticio", "Contacto", "+56 9 0000 0003")),
        ),
    )

    fun find(personId: String): HealthProfile? = profiles[personId]
}
