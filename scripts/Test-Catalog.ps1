param([switch]$SeedDemo)
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'LocalEnvironment.ps1')
$java=Get-ProjectJava
$cp=(Join-Path $projectRoot 'server\target\test-classes')+';'+(Join-Path $projectRoot 'server\target\classes')+';'+(Join-Path $projectRoot 'server\target\campus-market\WEB-INF\lib\*')
Invoke-WithDatabaseEnvironment {
    if($SeedDemo){& $java '-Dfile.encoding=UTF-8' -cp $cp com.campusmarket.CatalogSeed;if($LASTEXITCODE -ne 0){throw 'Catalog seed failed'}}
    & $java '-Dfile.encoding=UTF-8' -cp $cp com.campusmarket.CatalogIntegrationProbe (Join-Path $projectRoot '.local\evidence\phase4-catalog.json')
    if($LASTEXITCODE -ne 0){throw 'Catalog HTTP/JDBC verification failed'}
}
