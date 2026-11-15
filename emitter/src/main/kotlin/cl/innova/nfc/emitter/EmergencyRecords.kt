package cl.innova.nfc.emitter

import kotlinx.serialization.Serializable

internal const val MAX_WRITE_VERSION = 9_007_199_254_740_990L
internal const val MAX_ITEMS = 30
internal const val MAX_TEXT = 160
internal val bloodGroups = listOf(null, "A+", "A−", "B+", "B−", "AB+", "AB−", "O+", "O−")

@Serializable internal data class Person(val id: String, val account: String, val name: String, val age: Int?)
@Serializable internal data class EmergencyCard(
    val version: Long, val updatedAt: String, val bloodType: String?,
    val allergies: List<String>, val conditions: List<String>, val medications: List<String>,
) {
    val fields get() = CardFields(bloodType, allergies, conditions, medications)
}
@Serializable internal data class Contact(val id: String, val name: String, val relationship: String, val phone: String) {
    val fields get() = ContactFields(name, relationship, phone)
}

internal data class CardFields(
    val bloodType: String?, val allergies: List<String>, val conditions: List<String>, val medications: List<String>,
) {
    fun normalized() = copy(
        allergies = allergies.map(String::trim), conditions = conditions.map(String::trim),
        medications = medications.map(String::trim),
    )

    fun valid() = bloodType in bloodGroups && listOf(allergies, conditions, medications).all { items ->
        items.size <= MAX_ITEMS && items.all { textError(it) == null }
    }

    fun applyEdits(original: CardFields, current: CardFields) = CardFields(
        if (bloodType == original.bloodType) current.bloodType else bloodType,
        if (allergies == original.allergies) current.allergies else allergies,
        if (conditions == original.conditions) current.conditions else conditions,
        if (medications == original.medications) current.medications else medications,
    )
}

internal data class ContactFields(val name: String = "", val relationship: String = "", val phone: String = "") {
    fun normalized() = ContactFields(name.trim(), relationship.trim(), phone.trim())
    fun valid() = textError(name) == null && textError(relationship) == null && phoneError(phone) == null
    fun applyEdits(original: ContactFields, current: ContactFields) = ContactFields(
        if (name == original.name) current.name else name,
        if (relationship == original.relationship) current.relationship else relationship,
        if (phone == original.phone) current.phone else phone,
    )
}

internal fun textError(input: String): String? = when (input.trim().length) {
    0 -> "Escribe un valor o elimina este campo."
    in 1..MAX_TEXT -> null
    else -> "Usa hasta 160 caracteres."
}

internal fun phoneError(input: String): String? {
    val phone = input.trim()
    if (phone.length !in 3..32) return "Usa entre 3 y 32 caracteres."
    if (phone.none { it in '0'..'9' } || !phone.matches(Regex("[+0-9 ()-]+"))) {
        return "Usa dígitos, espacios, +, paréntesis o guiones e incluye al menos un dígito."
    }
    return null
}

internal data class EmergencySnapshot(val card: EmergencyCard, val contacts: List<Contact>)

internal class EmergencyRepository(private val api: AttendeeApi) {
    // The two GETs share a version but have separate responses. Read the version
    // again to verify that the contact list belongs to the displayed card.
    suspend fun load(session: AttendeeSession): EmergencySnapshot {
        repeat(3) {
            val before = api.card(session)
            val contacts = api.contacts(session)
            val after = api.card(session)
            if (before.version == after.version) return EmergencySnapshot(after, contacts)
        }
        throw AttendeeFailure(FailureKind.Changed)
    }

    suspend fun save(session: AttendeeSession, draft: EditDraft): Long = when (draft) {
        is EditDraft.Card -> api.replaceCard(session, draft.version, draft.fields.normalized()).version
        is EditDraft.ContactEdit -> api.saveContact(session, draft.version, draft.id, draft.fields.normalized())
        is EditDraft.Remove -> api.deleteContact(session, draft.version, draft.contact.id)
    }
}
