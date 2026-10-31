$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'LocalEnvironment.ps1')
$java = Get-ProjectJava
$classPath = (Join-Path $projectRoot 'server\target\test-classes') + ';' +
    (Join-Path $projectRoot 'server\target\classes') + ';' +
    (Join-Path $projectRoot 'server\target\campus-market\WEB-INF\lib\*')
Invoke-WithDatabaseEnvironment {
    & $java '-Dfile.encoding=UTF-8' -cp $classPath com.campusmarket.JdbcProbe
    if ($LASTEXITCODE -ne 0) { throw 'JDBC probe failed. Only the safe error code above is needed for diagnosis.' }
}
