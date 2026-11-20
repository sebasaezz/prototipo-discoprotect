package cl.discoprotect.ui

import kotlinx.serialization.Serializable

@Serializable
data class EventCompanion(val id: String, val name: String)

@Serializable
data class EventCompanions(val companions: List<EventCompanion>)
