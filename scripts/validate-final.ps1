param(
    [switch]$Install,
    [switch]$OpenAI,
    [int]$HealthPort
)

$ErrorActionPreference = 'Stop'
$repoPath = Split-Path -Parent $PSScriptRoot
function Run-Checked([string]$Program, [string[]]$Arguments) {
    & $Program @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$Program falló con código $LASTEXITCODE." }
}
foreach ($module in @('backend', 'web')) {
    Push-Location (Join-Path $repoPath $module)
    try {
        if ($Install) { Run-Checked 'npm' @('ci') }
        Run-Checked 'npm' @('test')
        if ($module -eq 'web') { Run-Checked 'npm' @('run', 'build') }
    } finally { Pop-Location }
}
Push-Location $repoPath
try {
    Run-Checked '.\gradlew.bat' @('--no-daemon', ':protocol:test', ':emitter:testDebugUnitTest',
        ':reader:testDebugUnitTest', ':emitter:assembleDebug', ':reader:assembleDebug')
} finally { Pop-Location }
if ($HealthPort) {
    $health = Invoke-RestMethod -Uri "http://127.0.0.1:$HealthPort/api/v1/health" -TimeoutSec 10
    if ($health.status -ne 'ok' -or $health.database -ne 'ok') { throw 'Fastify no confirmó la salud de SQLite.' }
    Write-Output 'Fastify y SQLite confirmaron su estado.'
}
if ($OpenAI) {
    Push-Location (Join-Path $repoPath 'backend')
    try { Run-Checked 'npm' @('run', 'acceptance:openai') }
    finally { Pop-Location }
}
