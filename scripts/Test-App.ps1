[CmdletBinding()]
param([string]$Serial = 'emulator-5554', [string]$Adb = 'D:\Android\SDK\platform-tools\adb.exe', [switch]$ExpectUnavailable)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$apk = Join-Path $projectRoot 'android-client\app\build\outputs\apk\debug\app-debug.apk'
$evidence = Join-Path $projectRoot '.local\evidence'
New-Item -ItemType Directory -Force -Path $evidence | Out-Null
[xml]$strings = Get-Content (Join-Path $projectRoot 'android-client\app\src\main\res\values\strings.xml') -Raw -Encoding UTF8
$successText = $strings.SelectSingleNode("/resources/string[@name='connected']").InnerText
function Invoke-Adb([string[]]$Arguments) {
    # Windows PowerShell treats successful adb pull progress on stderr as ErrorRecords.
    $previousPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $output = & $Adb -s $Serial @Arguments 2>&1
        $exitCode = $LASTEXITCODE
    } finally { $ErrorActionPreference = $previousPreference }
    if ($exitCode -ne 0) { throw ('ADB failed: ' + ($output -join ' ')) }
    return ($output -join "`n")
}
function Get-AppUi {
    $null = Invoke-Adb @('shell', 'uiautomator', 'dump', '/sdcard/campus-market-window.xml')
    $path = Join-Path $evidence 'ui.xml'
    $null = Invoke-Adb @('pull', '/sdcard/campus-market-window.xml', $path)
    return [xml](Get-Content -LiteralPath $path -Raw -Encoding UTF8)
}
function Find-Node([xml]$Ui, [string]$Id) {
    return $Ui.SelectSingleNode("//node[@resource-id='com.campusmarket:id/$Id']")
}
function Click-Node([xml]$Ui, [string]$Id) {
    $node = Find-Node $Ui $Id
    if (-not $node -or $node.bounds -notmatch '^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$') { throw ('UI control missing: ' + $Id) }
    $x = [int](([int]$Matches[1] + [int]$Matches[3]) / 2)
    $y = [int](([int]$Matches[2] + [int]$Matches[4]) / 2)
    $null = Invoke-Adb @('shell', 'input', 'tap', "$x", "$y")
}
function Wait-DatabaseResult {
    for ($attempt = 0; $attempt -lt 8; $attempt++) {
        $ui = Get-AppUi
        $status = Find-Node $ui 'connection_status'
        $details = Find-Node $ui 'database_details'
        if ($status -and $details -and $status.text -eq $successText -and
            $details.text -match 'campus_market' -and $details.text -match '8\.0\.' -and
            $details.text -match '\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}') { return $ui }
        Start-Sleep -Milliseconds 400
    }
    throw 'App did not display a successful, real database response. Inspect .local/evidence/ui.xml.'
}
if (-not (Test-Path -LiteralPath $apk)) { throw 'Build the debug APK first.' }
if ((Invoke-Adb @('shell', 'getprop', 'sys.boot_completed')).Trim() -ne '1') { throw 'Device has not finished booting.' }
$install = Invoke-Adb @('install', '-r', $apk)
if ($install -notmatch 'Success') { throw 'APK installation was not successful.' }
$null = Invoke-Adb @('shell', 'am', 'force-stop', 'com.campusmarket')
$launch = Invoke-Adb @('shell', 'am', 'start', '-W', '-n', 'com.campusmarket/.MainActivity')
if ($launch -notmatch 'Status: ok') { throw 'Activity did not launch successfully.' }
$ui = Get-AppUi
if(Find-Node $ui 'open_diagnostics'){Click-Node $ui 'open_diagnostics';$ui=Get-AppUi}
foreach ($id in @('nav_home', 'nav_publish', 'nav_messages', 'nav_profile')) {
    if (-not (Find-Node $ui $id)) { throw ('Navigation entry missing: ' + $id) }
}
Click-Node $ui 'check_connection'
if ($ExpectUnavailable) {
    $errorText = $strings.SelectSingleNode("/resources/string[@name='connection_failed']").InnerText
    for ($attempt = 0; $attempt -lt 8; $attempt++) {
        $ui = Get-AppUi
        $status = Find-Node $ui 'connection_status'
        $button = Find-Node $ui 'check_connection'
        if ($status -and $status.text -eq $errorText -and $button.enabled -eq 'true') {
            $null = Invoke-Adb @('shell', 'screencap', '-p', '/sdcard/campus-market-unavailable.png')
            $null = Invoke-Adb @('pull', '/sdcard/campus-market-unavailable.png', (Join-Path $evidence 'app-unavailable.png'))
            @{ checkedAt = (Get-Date -Format o); backendUnavailableHandled = $true; retryEnabled = $true } |
                ConvertTo-Json | Set-Content (Join-Path $evidence 'app-unavailable-test.json') -Encoding UTF8
            Write-Host 'APP FAILURE-PATH PASS: unavailable backend shows a recoverable error and enables retry.'
            return
        }
        Start-Sleep -Milliseconds 400
    }
    throw 'App did not handle the unavailable backend correctly.'
}
$ui = Wait-DatabaseResult
$firstDetails = (Find-Node $ui 'database_details').text
# Exercise all reserved navigation entries, then return to the retained home state.
foreach ($id in @('nav_publish', 'nav_messages')) {
    Click-Node $ui $id
    $ui = Get-AppUi
    if (-not (Find-Node $ui 'placeholder')) { throw ('Placeholder missing: ' + $id) }
}
Click-Node $ui 'nav_profile'
$ui = Get-AppUi
if (-not (Find-Node $ui 'profile_panel')) { throw 'Profile tab missing.' }
Click-Node $ui 'nav_home'
$ui = Get-AppUi
if(Find-Node $ui 'open_diagnostics'){Click-Node $ui 'open_diagnostics';$ui=Get-AppUi}
Click-Node $ui 'check_connection'
$ui = Wait-DatabaseResult
$secondDetails = (Find-Node $ui 'database_details').text
if ($secondDetails -eq $firstDetails) { throw 'Database timestamp did not change on refresh.' }
$null = Invoke-Adb @('shell', 'screencap', '-p', '/sdcard/campus-market-phase2.png')
$null = Invoke-Adb @('pull', '/sdcard/campus-market-phase2.png', (Join-Path $evidence 'app-success.png'))
@{
    checkedAt = (Get-Date -Format o)
    device = $Serial
    installed = $true
    launched = $true
    navigationEntries = 4
    httpDatabaseResultDisplayed = $true
    databaseTimestampRefreshed = $true
    displayedResult = $secondDetails
} | ConvertTo-Json | Set-Content (Join-Path $evidence 'app-test.json') -Encoding UTF8
Write-Host 'APP PASS: installed, launched, four navigation entries, real database response and refreshed database timestamp.'
Write-Host ('Evidence: ' + $evidence)
