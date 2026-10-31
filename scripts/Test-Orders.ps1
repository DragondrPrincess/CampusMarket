$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'LocalEnvironment.ps1')
$java=Get-ProjectJava
$cp=(Join-Path $projectRoot 'server\target\test-classes')+';'+(Join-Path $projectRoot 'server\target\classes')+';'+(Join-Path $projectRoot 'server\target\campus-market\WEB-INF\lib\*')
Invoke-WithDatabaseEnvironment {
    & $java '-Dfile.encoding=UTF-8' -cp $cp com.campusmarket.OrderIntegrationProbe (Join-Path $projectRoot '.local\evidence\phase6-orders.json')
    if($LASTEXITCODE -ne 0){throw 'Order HTTP/JDBC concurrency verification failed; credentials and raw responses suppressed.'}
}
