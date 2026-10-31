# Shared local launch helpers. No environment values or credentials are printed.
$projectRoot = Split-Path $PSScriptRoot -Parent
function Get-ProjectJava {
    $jdk = $env:JAVA_HOME
    if (-not $jdk) { $jdk = 'C:\Program Files\Microsoft\jdk-17.0.12.7-hotspot' }
    if (-not (Test-Path -LiteralPath (Join-Path $jdk 'release'))) { throw 'Set JAVA_HOME to JDK 17.' }
    if (-not (Select-String -LiteralPath (Join-Path $jdk 'release') -Pattern '^JAVA_VERSION="17[.]')) {
        throw 'This project requires JDK 17.'
    }
    return (Join-Path $jdk 'bin\java.exe')
}

function Invoke-WithDatabaseEnvironment([scriptblock]$Action) {
    $credential = Import-Clixml -LiteralPath (Join-Path $projectRoot '.local\db.credentials.xml')
    if ($credential -isnot [PSCredential] -or $credential.UserName -eq 'root') {
        throw 'Verified business-account credentials are required.'
    }
    $values = @{
        CAMPUS_UPLOAD_DIR = (Join-Path $projectRoot '.local\uploads')
        CAMPUS_DB_URL = 'jdbc:mysql://127.0.0.1:3306/campus_market?sslMode=PREFERRED&characterEncoding=UTF-8&connectionTimeZone=LOCAL'
        CAMPUS_DB_USER = $credential.UserName
        CAMPUS_DB_PASSWORD = $credential.GetNetworkCredential().Password
    }
    $previous = @{}
    try {
        foreach ($name in $values.Keys) {
            $previous[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
            [Environment]::SetEnvironmentVariable($name, $values[$name], 'Process')
        }
        & $Action
    } finally {
        foreach ($name in $previous.Keys) { [Environment]::SetEnvironmentVariable($name, $previous[$name], 'Process') }
        $values.Clear()
        $previous.Clear()
        $credential = $null
    }
}
