param([ValidateSet('verify','expire')][string]$Mode='verify')
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'LocalEnvironment.ps1')
$java=Get-ProjectJava
$cp=(Join-Path $projectRoot 'server\target\test-classes')+';'+(Join-Path $projectRoot 'server\target\classes')+';'+(Join-Path $projectRoot 'server\target\campus-market\WEB-INF\lib\*')
$fixture=Join-Path $projectRoot '.local\evidence\phase3-ui-result.json'
Invoke-WithDatabaseEnvironment {
    & $java '-Dfile.encoding=UTF-8' -cp $cp com.campusmarket.UiFixtureProbe $Mode $fixture
    if($LASTEXITCODE -ne 0){throw 'UI fixture JDBC verification failed.'}
}
