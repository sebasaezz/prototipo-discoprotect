# Demostración H1 en PowerShell

Usa dos terminales en `backend/`. Prepara `.env` con el PIN de guardia según el README y ejecuta `npm run db:seed`. Usa una base demo restaurada si necesitas recuperar los datos iniciales.

En la primera terminal, ejecuta `npm start`. En la segunda terminal, ejecuta:

```powershell
$api = 'http://127.0.0.1:3000/api/v1'
Invoke-RestMethod "$api/health"

function New-DemoSession($account, $pin) {
    $body = @{ account = $account }
    if ($pin) { $body.pin = $pin }
    Invoke-RestMethod "$api/demo/sessions" -Method Post -ContentType 'application/json' -Body ($body | ConvertTo-Json)
}

$attendee = New-DemoSession antonia
$attendeeHeaders = @{ Authorization = "Bearer $($attendee.token)" }
$before = Invoke-RestMethod "$api/me/emergency-card" -Headers $attendeeHeaders
$edit = @{
    version = $before.version
    bloodType = $before.bloodType
    allergies = @('Látex')
    conditions = $before.conditions
    medications = $before.medications
}
Invoke-RestMethod "$api/me/emergency-card" -Method Put -Headers $attendeeHeaders -ContentType 'application/json' -Body ($edit | ConvertTo-Json)
```

Comprueba que la ficha devuelve la alergia y una versión mayor. Detén el servidor con Ctrl+C en la primera terminal. Ejecuta de nuevo `npm start` con la misma ruta SQLite. Continúa en la segunda terminal:

```powershell
Invoke-RestMethod "$api/me/emergency-card" -Headers $attendeeHeaders
$staffPin = Read-Host 'PIN de guardia configurado en backend/.env' -MaskInput
$staff = New-DemoSession guardia $staffPin
Remove-Variable staffPin
$staffHeaders = @{ Authorization = "Bearer $($staff.token)" }
$nfc = @{ token = '8F31A20C779145D4B522F10492AA610E'; eventId = 'demo-festival' } | ConvertTo-Json
$result = Invoke-RestMethod "$api/nfc/resolve" -Method Post -Headers $staffHeaders -ContentType 'application/json' -Body $nfc
$result.person
$result.card
$result.contacts
Invoke-WebRequest "$api/nfc/resolve" -Method Post -Headers $attendeeHeaders -ContentType 'application/json' -Body $nfc -SkipHttpErrorCheck | Select-Object StatusCode, Content
```

La sesión de guardia obtiene la ficha y sus contactos; la de asistente obtiene 403. La sesión previa al reinicio sigue siendo válida si está dentro de sus ocho horas. Usa PowerShell 7 para `-MaskInput` y `-SkipHttpErrorCheck`.

La demostración de Android se realiza por separado con la [checklist física](nfc-contract.md). Hasta H2, el lector NFC recupera el valor de la ficha local y la API demuestra el dato persistido.
