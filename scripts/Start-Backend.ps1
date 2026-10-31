[CmdletBinding()]
param([string]$TomcatHome = 'D:\Apache\Tomcat10\apache-tomcat-10.1.60')
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'LocalEnvironment.ps1')
$java = Get-ProjectJava
$war = Join-Path $projectRoot 'server\target\campus-market.war'
$base = Join-Path $projectRoot '.local\tomcat'
$stateFile = Join-Path $projectRoot '.local\backend-process.json'
if (-not (Test-Path -LiteralPath $war)) { throw 'Build server with server\mvnw.cmd package first.' }
if (-not (Test-Path -LiteralPath (Join-Path $TomcatHome 'bin\bootstrap.jar'))) { throw 'Tomcat installation not found.' }
if (Test-Path -LiteralPath $stateFile) {
    $state = Get-Content -LiteralPath $stateFile -Raw | ConvertFrom-Json
    $running = Get-Process -Id $state.processId -ErrorAction SilentlyContinue
    if ($running -and $running.StartTime.ToUniversalTime().Ticks.ToString() -eq $state.startTicks) {
        throw 'Project backend is running. Use scripts\Stop-Backend.ps1 before redeploying.'
    }
}
$tcp = [Net.Sockets.TcpClient]::new()
try {
    try { $tcp.Connect('127.0.0.1', 8080); $portInUse = $true } catch { $portInUse = $false }
} finally { $tcp.Dispose() }
if ($portInUse) { throw 'Port 8080 is occupied. Existing processes were not stopped.' }
foreach ($name in @('conf', 'logs', 'temp', 'work', 'webapps')) {
    New-Item -ItemType Directory -Force -Path (Join-Path $base $name) | Out-Null
}
$serverXml = @'
<?xml version="1.0" encoding="UTF-8"?>
<Server port="-1">
  <Service name="Catalina">
    <Connector address="127.0.0.1" port="8080" protocol="HTTP/1.1" connectionTimeout="10000" URIEncoding="UTF-8" />
    <Engine name="Catalina" defaultHost="localhost">
      <Host name="localhost" appBase="webapps" unpackWARs="true" autoDeploy="false" deployOnStartup="true">
        <Valve className="org.apache.catalina.valves.AccessLogValve" directory="logs"
          prefix="access" suffix=".log" pattern="%h %t &quot;%r&quot; %s %b &quot;%{User-Agent}i&quot;" />
      </Host>
    </Engine>
  </Service>
</Server>
'@
[IO.File]::WriteAllText((Join-Path $base 'conf\server.xml'), $serverXml, [Text.UTF8Encoding]::new($false))
Copy-Item -LiteralPath (Join-Path $TomcatHome 'conf\web.xml') -Destination (Join-Path $base 'conf\web.xml')
Copy-Item -LiteralPath $war -Destination (Join-Path $base 'webapps\campus-market.war') -Force
$javaArguments = @(
    ('"-Dcatalina.home=' + $TomcatHome + '"'), ('"-Dcatalina.base=' + $base + '"'),
    ('"-Djava.io.tmpdir=' + $base + '\temp"'), '-Dfile.encoding=UTF-8',
    '-cp', ('"' + $TomcatHome + '\bin\bootstrap.jar;' + $TomcatHome + '\bin\tomcat-juli.jar"'),
    'org.apache.catalina.startup.Bootstrap', 'start'
)
$backendProcess = Invoke-WithDatabaseEnvironment {
    Start-Process -FilePath $java -ArgumentList $javaArguments -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput (Join-Path $base 'logs\stdout.log') `
        -RedirectStandardError (Join-Path $base 'logs\stderr.log')
}
@{ processId = $backendProcess.Id; startTicks = $backendProcess.StartTime.ToUniversalTime().Ticks.ToString() } |
    ConvertTo-Json | Set-Content -LiteralPath $stateFile -Encoding ASCII
try {
    $ready = $false
    for ($attempt = 0; $attempt -lt 25; $attempt++) {
        if ($backendProcess.HasExited) { throw 'Tomcat exited; inspect .local\tomcat\logs.' }
        try {
            $health = Invoke-RestMethod 'http://127.0.0.1:8080/campus-market/api/v1/health/db' -TimeoutSec 2
            if ($health.code -eq 0 -and $health.data.databaseName -eq 'campus_market') { $ready = $true; break }
        } catch { }
        Start-Sleep -Milliseconds 400
    }
    if (-not $ready) { throw 'Backend did not pass JDBC readiness; inspect .local\tomcat\logs.' }
    Write-Host ('Tomcat + JDBC ready. PID=' + $backendProcess.Id)
    $health | ConvertTo-Json -Depth 4
} catch {
    if (-not $backendProcess.HasExited) { $backendProcess.Kill(); $backendProcess.WaitForExit() }
    throw
}
