$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$base = 'http://127.0.0.1:8080/campus-market/api/v1'
$service = Invoke-WebRequest ($base + '/health') -UseBasicParsing
if ($service.StatusCode -ne 200 -or ($service.Content | ConvertFrom-Json).data.status -ne 'UP') { throw 'Service health check failed.' }
$database = Invoke-WebRequest ($base + '/health/db') -UseBasicParsing
$body = $database.Content | ConvertFrom-Json
if ($database.StatusCode -ne 200 -or $body.code -ne 0 -or $body.data.databaseName -ne 'campus_market' -or
    $body.data.schemaVersion -lt 1 -or -not $body.data.databaseTime) { throw 'Database HTTP health check failed.' }
if ($database.Headers['Cache-Control'] -notmatch 'no-store') { throw 'Live database health must not be cached.' }
$unsupportedStatus = 0
try { $null = Invoke-WebRequest ($base + '/health/db') -Method Post -UseBasicParsing }
catch { if ($_.Exception.Response) { $unsupportedStatus = [int]$_.Exception.Response.StatusCode } else { throw } }
if ($unsupportedStatus -ne 405) { throw 'Unsupported HTTP method should return 405.' }
$evidence = Join-Path $projectRoot '.local\evidence'
New-Item -ItemType Directory -Force -Path $evidence | Out-Null
$body | ConvertTo-Json -Depth 5 | Set-Content (Join-Path $evidence 'http-health.json') -Encoding UTF8
Write-Host 'HTTP PASS: service 200, real database 200, no-store, unsupported POST 405.'
