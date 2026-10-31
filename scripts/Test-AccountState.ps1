[CmdletBinding()]
param([ValidateSet('Restore','NetworkUnavailable','Expired')][string]$Mode = 'Restore', [string]$Serial = 'emulator-5554')
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$adb = 'D:\Android\SDK\platform-tools\adb.exe'
$evidence = Join-Path $projectRoot '.local\evidence'
$fixture = Get-Content (Join-Path $evidence 'phase3-ui-result.json') -Raw -Encoding UTF8 | ConvertFrom-Json
if ($fixture.username -notmatch '^p3_ui_[a-f0-9]{12}$') { throw 'UI test fixture summary required.' }
[xml]$strings = Get-Content (Join-Path $projectRoot 'android-client\app\src\main\res\values\strings.xml') -Raw -Encoding UTF8
function Run-Adb([string[]]$Arguments) {
    $previous = $ErrorActionPreference
    try { $ErrorActionPreference='Continue'; $output=& $adb -s $Serial @Arguments 2>&1; $code=$LASTEXITCODE }
    finally { $ErrorActionPreference=$previous }
    if ($code -ne 0) { throw ('ADB failure: '+($output -join ' ')) }
}
function Read-Ui {
    Run-Adb @('shell','uiautomator','dump','/sdcard/campus-market-account.xml')
    $path=Join-Path $evidence 'account-ui.xml'
    Run-Adb @('pull','/sdcard/campus-market-account.xml',$path)
    return [xml](Get-Content -LiteralPath $path -Raw -Encoding UTF8)
}
function Node([xml]$Ui,[string]$Id) { return $Ui.SelectSingleNode("//node[@resource-id='com.campusmarket:id/$Id']") }
function Tap([xml]$Ui,[string]$Id) {
    $item=Node $Ui $Id
    if (-not $item -or $item.bounds -notmatch '^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$') { throw ('Missing UI control: '+$Id) }
    $x=[int](([int]$Matches[1]+[int]$Matches[3])/2);$y=[int](([int]$Matches[2]+[int]$Matches[4])/2)
    Run-Adb @('shell','input','tap',"$x","$y")
}
Run-Adb @('shell','am','force-stop','com.campusmarket')
Run-Adb @('shell','am','start','-W','-n','com.campusmarket/.MainActivity')
$ui=Read-Ui
Tap $ui 'nav_profile'
$passed=$false
for($attempt=0;$attempt -lt 10;$attempt++) {
    $ui=Read-Ui
    $status=Node $ui 'account_status';$details=Node $ui 'profile_details'
    if($Mode -eq 'Restore') {
        $passed=$details -and $details.text.Contains($fixture.username) -and $details.text.Contains($fixture.campus)
    } elseif($Mode -eq 'NetworkUnavailable') {
        $expected=$strings.SelectSingleNode("/resources/string[@name='account_network_error']").InnerText
        $retry=Node $ui 'refresh_profile'
        $passed=$status -and $status.text -eq $expected -and $retry -and $retry.enabled -eq 'true'
    } else {
        $expected=$strings.SelectSingleNode("/resources/string[@name='session_expired']").InnerText
        $passed=$status -and $status.text -eq $expected -and (Node $ui 'account_login')
    }
    if($passed){break}
    Start-Sleep -Milliseconds 300
}
if(-not $passed){throw ('Account state check failed: '+$Mode+'; inspect .local/evidence/account-ui.xml')}
$name='phase3-account-'+$Mode.ToLowerInvariant()
Run-Adb @('shell','screencap','-p',('/sdcard/'+$name+'.png'))
Run-Adb @('pull',('/sdcard/'+$name+'.png'),(Join-Path $evidence ($name+'.png')))
@{mode=$Mode;passed=$true;checkedAt=(Get-Date -Format o)}|ConvertTo-Json|Set-Content (Join-Path $evidence ($name+'.json')) -Encoding UTF8
Write-Host ('ACCOUNT STATE PASS: '+$Mode)
