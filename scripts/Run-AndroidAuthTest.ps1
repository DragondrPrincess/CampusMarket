[CmdletBinding()]
param([string]$Serial='emulator-5554')
$ErrorActionPreference='Stop'
$projectRoot=Split-Path $PSScriptRoot -Parent
$adb='D:\Android\SDK\platform-tools\adb.exe'
function Run-Adb([string[]]$Arguments) {
    $previous=$ErrorActionPreference
    try {$ErrorActionPreference='Continue';$output=& $adb -s $Serial @Arguments 2>&1;$code=$LASTEXITCODE}
    finally {$ErrorActionPreference=$previous}
    if($code -ne 0){throw ('ADB failed: '+($output -join ' '))}
    return ($output -join "`n")
}
$app=Join-Path $projectRoot 'android-client\app\build\outputs\apk\debug\app-debug.apk'
$test=Join-Path $projectRoot 'android-client\app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk'
if(-not (Test-Path $app) -or -not (Test-Path $test)){throw 'Run gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest first.'}
$null=Run-Adb @('install','-r',$app)
$null=Run-Adb @('install','-r','-t',$test)
# Gradle UTP cleans up packages after connected tests. Direct instrumentation retains
# the app's encrypted session so process-death and server-restart tests can follow.
$result=Run-Adb @('shell','am','instrument','-w','-r','-e','class','com.campusmarket.AuthFlowTest','com.campusmarket.test/androidx.test.runner.AndroidJUnitRunner')
$result|Set-Content (Join-Path $projectRoot '.local\logs\phase3-instrumentation.log') -Encoding UTF8
if($result -notmatch 'OK \(1 test\)' -or $result -match 'FAILURES!!!'){throw 'Android auth instrumentation failed; inspect the test log.'}
$evidence=Join-Path $projectRoot '.local\evidence'
New-Item -ItemType Directory -Force -Path $evidence|Out-Null
$null=Run-Adb @('pull','/sdcard/Android/data/com.campusmarket/files/phase3-ui-result.json',(Join-Path $evidence 'phase3-ui-result.json'))
$null=Run-Adb @('pull','/sdcard/Android/data/com.campusmarket/files/phase3-profile.png',(Join-Path $evidence 'phase3-profile.png'))
$summary=Get-Content (Join-Path $evidence 'phase3-ui-result.json') -Raw -Encoding UTF8|ConvertFrom-Json
if(-not $summary.passed){throw 'Android auth fixture summary not successful.'}
Write-Host 'ANDROID AUTH UI PASS: real register/login/edit/logout flow, encrypted token storage; fixture state retained for process restart verification.'
