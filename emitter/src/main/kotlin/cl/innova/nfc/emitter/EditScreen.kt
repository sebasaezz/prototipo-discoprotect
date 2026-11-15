package cl.innova.nfc.emitter

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import cl.discoprotect.ui.*

@Composable
internal fun EditScreen(state: AttendeeState, draft: EditDraft, actions: AttendeeController, onCancel: () -> Unit) {
    val idle = state.task == null
    val enabled = idle && state.review == null
    val canSave = state.canSave && draft.dirty
    if (state.review != null && state.fresh && state.snapshot != null) {
        ReviewPanel(state.review, state.snapshot, draft, actions, idle)
    } else if (!state.fresh && idle) {
        if (state.person == null) {
            Button(onClick = actions::login, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("Volver a iniciar sesión como Antonia")
            }
        } else {
            Button(onClick = actions::refresh, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("Cargar versión vigente")
            }
        }
    }
    when (draft) {
        is EditDraft.Card -> {
            Heading("Editar ficha", "Versión del borrador ${draft.version}")
            CardEditor(draft.fields, enabled, actions::updateCard)
            Button(onClick = actions::save, enabled = canSave && draft.fields.valid(),
                modifier = Modifier.heightIn(min = 48.dp)) { Text("Guardar ficha") }
        }
        is EditDraft.ContactEdit -> {
            Heading(if (draft.id == null) "Agregar contacto" else "Editar contacto", "Versión del borrador ${draft.version}")
            ContactEditor(draft.fields, enabled, actions::updateContact)
            Button(onClick = actions::save, enabled = canSave && draft.fields.valid(),
                modifier = Modifier.heightIn(min = 48.dp)) { Text("Guardar contacto") }
        }
        is EditDraft.Remove -> {
            Heading("Eliminar contacto", "Confirma que quieres quitar a ${draft.contact.name} de tus contactos de emergencia.")
            Field("Relación", draft.contact.relationship)
            Field("Teléfono", draft.contact.phone)
            Button(onClick = actions::save, enabled = canSave, modifier = Modifier.heightIn(min = 48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) {
                Text("Confirmar eliminación")
            }
        }
    }

    TextButton(onClick = onCancel, enabled = idle, modifier = Modifier.heightIn(min = 48.dp)) {
        Text(if (state.review != null) "Cerrar borrador" else "Cancelar")
    }
}

@Composable
private fun ReviewPanel(review: Review, data: EmergencySnapshot, draft: EditDraft, actions: AttendeeController, idle: Boolean) {
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
        Column(Modifier.padding(Space.md), verticalArrangement = Arrangement.spacedBy(Space.md)) {
            Text("Versión vigente · ${data.card.version}", style = MaterialTheme.typography.titleMedium)
            if (draft is EditDraft.Card) {
                Field("Grupo sanguíneo declarado", data.card.bloodType ?: "Sin registrar")
                Values("Alergias", data.card.allergies)
                Values("Condiciones relevantes", data.card.conditions)
                Values("Medicamentos", data.card.medications)
            } else {
                Text("Contactos en el servidor", style = MaterialTheme.typography.labelLarge)
                if (data.contacts.isEmpty()) Text("Sin contactos registrados.")
                data.contacts.forEach { Field(it.name, "${it.relationship}\n${it.phone}") }
            }
            if (review in setOf(Review.Conflict, Review.Uncertain)) {
                Text("Compara los datos vigentes con el formulario. Al continuar se conservan los campos que no editaste.")
                Button(onClick = actions::acceptLatestVersion, enabled = idle, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("Continuar editando con esta versión")
                }
            }
        }
    }
}

@Composable
private fun CardEditor(fields: CardFields, enabled: Boolean, change: (CardFields) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
        Text("Grupo sanguíneo declarado", style = MaterialTheme.typography.labelLarge)
        Box {
            OutlinedButton(onClick = { expanded = true }, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(fields.bloodType ?: "Sin registrar")
            }
            DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
                bloodGroups.forEach { group ->
                    DropdownMenuItem(
                        text = { Text(group ?: "Sin registrar") },
                        onClick = { expanded = false; change(fields.copy(bloodType = group)) },
                    )
                }
            }
        }
    }
    ListEditor("Alergias", "Alergia", fields.allergies, enabled) { change(fields.copy(allergies = it)) }
    ListEditor("Condiciones relevantes", "Condición", fields.conditions, enabled) { change(fields.copy(conditions = it)) }
    ListEditor("Medicamentos", "Medicamento", fields.medications, enabled) { change(fields.copy(medications = it)) }
}

@Composable
private fun ListEditor(title: String, label: String, values: List<String>, enabled: Boolean, change: (List<String>) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        values.forEachIndexed { index, value ->
            val error = textError(value)
            Column(Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value, { entry -> change(values.toMutableList().apply { set(index, entry) }) },
                    label = { Text("$label ${index + 1}") },
                    enabled = enabled, isError = error != null, maxLines = 4,
                    supportingText = error?.let { { Text(it) } }, modifier = Modifier.fillMaxWidth(),
                )
                TextButton(onClick = { change(values.toMutableList().apply { removeAt(index) }) },
                    enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("Quitar ${label.lowercase()} ${index + 1}")
                }
            }
        }
        if (values.size == MAX_ITEMS) Text("Máximo de 30 elementos.")
        else OutlinedButton(onClick = { change(values + "") }, enabled = enabled,
            modifier = Modifier.heightIn(min = 48.dp)) { Text("Agregar ${label.lowercase()}") }
    }
}

@Composable
private fun ContactEditor(fields: ContactFields, enabled: Boolean, change: (ContactFields) -> Unit) {
    ContactInput("Nombre", fields.name, enabled, textError(fields.name)) { change(fields.copy(name = it)) }
    ContactInput("Relación", fields.relationship, enabled, textError(fields.relationship)) { change(fields.copy(relationship = it)) }
    ContactInput("Teléfono", fields.phone, enabled, phoneError(fields.phone), KeyboardType.Phone) { change(fields.copy(phone = it)) }
}

@Composable
private fun ContactInput(
    label: String, value: String, enabled: Boolean, error: String?, keyboard: KeyboardType = KeyboardType.Text,
    change: (String) -> Unit,
) {
    OutlinedTextField(
        value, change, modifier = Modifier.fillMaxWidth(), label = { Text(label) },
        enabled = enabled, maxLines = 3, isError = value.isNotEmpty() && error != null,
        supportingText = if (value.isNotEmpty() && error != null) { { Text(error) } } else null,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
    )
}
