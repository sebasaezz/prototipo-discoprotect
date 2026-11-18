param(
    [string]$DatabasePath,
    [int]$Port = 3000,
    [switch]$Lan,
    [switch]$EnableAI
)

$ErrorActionPreference = 'Stop'
$repoPath = Split-Path -Parent $PSScriptRoot
Push-Location (Join-Path $repoPath 'backend')
$variableNames = @('DATABASE_PATH', 'HOST', 'PORT', 'TRIAGE_ENABLED', 'CHAT_AI_ENABLED', 'DEMO_PURGE_ENABLED', 'ALLOW_DEMO_RESET')
$previous = @{}
try {
    foreach ($name in $variableNames) { $previous[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
    if (-not $DatabasePath) { $DatabasePath = 'data/final-' + [Guid]::NewGuid().ToString('N') + '.sqlite' }
    $env:DATABASE_PATH = $DatabasePath
    $env:HOST = if ($Lan) { '0.0.0.0' } else { '127.0.0.1' }
    $env:PORT = "$Port"
    $env:TRIAGE_ENABLED = 'false'
    $env:CHAT_AI_ENABLED = if ($EnableAI) { 'true' } else { 'false' }
    $env:DEMO_PURGE_ENABLED = 'false'
    $env:ALLOW_DEMO_RESET = 'false'
    npm run build
    if ($LASTEXITCODE -ne 0) { throw 'Falló la compilación del backend.' }
    node scripts/seed-final.mjs $DatabasePath
    if ($LASTEXITCODE -ne 0) { throw 'Falló la creación de la base descartable.' }
    npm start
    if ($LASTEXITCODE -ne 0) { throw 'Fastify terminó con error.' }
} finally {
    foreach ($name in $variableNames) { [Environment]::SetEnvironmentVariable($name, $previous[$name], 'Process') }
    Pop-Location
}
