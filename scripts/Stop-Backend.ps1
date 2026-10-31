$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$stateFile = Join-Path $projectRoot '.local\backend-process.json'
if (-not (Test-Path -LiteralPath $stateFile)) { Write-Host 'No project backend recorded.'; return }
$state = Get-Content -LiteralPath $stateFile -Raw | ConvertFrom-Json
$backendProcess = Get-Process -Id $state.processId -ErrorAction SilentlyContinue
if (-not $backendProcess) { Write-Host 'Project backend is already stopped.'; return }
if ($backendProcess.StartTime.ToUniversalTime().Ticks.ToString() -ne $state.startTicks) {
    throw 'PID has been reused. Refusing to stop an unrelated process.'
}
if ($backendProcess.ProcessName -ne 'java') { throw 'Recorded process is not Java; refusing to stop it.' }
$backendProcess.Kill()
$backendProcess.WaitForExit()
Write-Host 'Project backend stopped.'
