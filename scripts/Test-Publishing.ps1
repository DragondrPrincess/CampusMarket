$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'LocalEnvironment.ps1')
$java=Get-ProjectJava
$cp=(Join-Path $projectRoot 'server\target\test-classes')+';'+(Join-Path $projectRoot 'server\target\classes')+';'+(Join-Path $projectRoot 'server\target\campus-market\WEB-INF\lib\*')
Invoke-WithDatabaseEnvironment {
    & $java '-Dfile.encoding=UTF-8' -cp $cp com.campusmarket.PublishingIntegrationProbe (Join-Path $projectRoot '.local\evidence\phase5-publishing.json')
    if($LASTEXITCODE -ne 0){throw 'Publishing HTTP/JDBC verification failed; credentials and raw responses suppressed.'}
}
