$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'LocalEnvironment.ps1')
$java = Get-ProjectJava
$classPath = (Join-Path $projectRoot 'server\target\test-classes') + ';' +
    (Join-Path $projectRoot 'server\target\classes') + ';' +
    (Join-Path $projectRoot 'server\target\campus-market\WEB-INF\lib\*')
$evidence = Join-Path $projectRoot '.local\evidence\phase3-api.json'
Invoke-WithDatabaseEnvironment {
    & $java '-Dfile.encoding=UTF-8' -cp $classPath com.campusmarket.AuthIntegrationProbe $evidence
    if ($LASTEXITCODE -ne 0) { throw 'Auth integration failed. Raw credentials and responses are intentionally not logged.' }
}
