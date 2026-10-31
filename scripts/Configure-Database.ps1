# Windows PowerShell 5.1 / PowerShell 7. Passwords never enter argv or logs.
[CmdletBinding()]
param(
    [switch]$UseExistingAccount,
    [switch]$CheckOnly,
    [switch]$ApplyPhase3Migration,
    [switch]$ApplyPhase4Migration,
    [switch]$ApplyPhase5Migration,
    [switch]$ApplyPhase6Migration,
    [switch]$ApplyPhase7Migration,
    [string]$MySqlBin = 'D:\MySQL\Server\bin\mysql.exe'
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$privateDir = Join-Path $projectRoot '.local'
$credentialFile = Join-Path $privateDir 'db.credentials.xml'
$pendingFile = Join-Path $privateDir 'db.credentials.pending.xml'
$migrationFile = Join-Path $projectRoot 'database\migrations\001_phase2.sql'

function Get-SafeMySqlError([string]$Text, [int]$ExitCode) {
    # Never echo raw stderr: syntax errors can quote CREATE USER and its password.
    $match = [regex]::Match($Text, '(?m)ERROR\s+(\d{4})\s+\(([A-Z0-9]{5})\)')
    if (-not $match.Success) { return "MySQL client failed (exit $ExitCode); raw output suppressed for credential safety." }
    $code = $match.Groups[1].Value
    $state = $match.Groups[2].Value
    $hint = switch ($code) {
        '1045' { 'Authentication denied; check account, host match, password and authentication plugin.' }
        '1044' { 'Database access denied.' }
        '1049' { 'Database does not exist.' }
        '1054' { 'Existing table columns do not match the expected schema.' }
        '1064' { 'SQL syntax rejected.' }
        '1142' { 'Required SQL permission denied.' }
        '1146' { 'Required table does not exist.' }
        '1227' { 'Administrator privilege required.' }
        '1273' { 'Unsupported database collation.' }
        '1396' { 'Account operation rejected; existing accounts will not be reset.' }
        '1819' { 'Password policy rejected the generated password; pending credentials are preserved.' }
        default { 'Check this error code locally; raw server text is suppressed.' }
    }
    return "MySQL ERROR $code ($state): $hint"
}

function Invoke-PrivateSql([PSCredential]$Credential, [string]$Sql, [string]$Step) {
    if ($Credential.UserName -notmatch '^[a-zA-Z0-9_]+$') { throw 'Use an alphanumeric MySQL account name.' }
    $start = [Diagnostics.ProcessStartInfo]::new()
    $start.FileName = $MySqlBin
    $start.Arguments = '--no-defaults --protocol=TCP --host=127.0.0.1 --port=3306 --connect-timeout=5 --batch --skip-column-names --default-character-set=utf8mb4 --user=' + $Credential.UserName
    $start.UseShellExecute = $false
    $start.CreateNoWindow = $true
    $start.RedirectStandardInput = $true
    $start.RedirectStandardOutput = $true
    $start.RedirectStandardError = $true
    $start.StandardOutputEncoding = [Text.UTF8Encoding]::new($false)
    $start.StandardErrorEncoding = [Text.UTF8Encoding]::new($false)
    # --no-defaults still reads .mylogin.cnf. Isolate that file without unsupported flags.
    $start.EnvironmentVariables['MYSQL_TEST_LOGIN_FILE'] = Join-Path $privateDir ([guid]::NewGuid().ToString('N') + '.absent')
    $start.EnvironmentVariables['MYSQL_HISTFILE'] = 'NUL'
    $start.EnvironmentVariables['MYSQL_PWD'] = $Credential.GetNetworkCredential().Password
    $process = [Diagnostics.Process]::new()
    $process.StartInfo = $start
    try {
        [void]$process.Start()
        $outputTask = $process.StandardOutput.ReadToEndAsync()
        $errorTask = $process.StandardError.ReadToEndAsync()
        # Explicit UTF-8 also works under Windows PowerShell 5.1 (no BOM).
        $writer = [IO.StreamWriter]::new($process.StandardInput.BaseStream, [Text.UTF8Encoding]::new($false))
        try { $writer.WriteLine($Sql) } finally { $writer.Dispose() }
        if (-not $process.WaitForExit(30000)) {
            $process.Kill()
            $process.WaitForExit()
            throw "$Step timed out. Earlier DDL may have committed; rerun to resume, never delete the database."
        }
        $output = $outputTask.GetAwaiter().GetResult()
        $errors = $errorTask.GetAwaiter().GetResult()
        if ($process.ExitCode -ne 0) {
            throw ($Step + ': ' + (Get-SafeMySqlError $errors $process.ExitCode))
        }
        return $output.Trim()
    } finally {
        $start.EnvironmentVariables.Remove('MYSQL_PWD')
        $process.Dispose()
    }
}

function Confirm-ProjectDatabase([PSCredential]$Credential) {
    if ($Credential.UserName -eq 'root') { throw 'The application must not use root.' }
    $sql = @'
USE campus_market;
SELECT IF(COUNT(*) = 1 AND MAX(schema_version) >= 1, 'METADATA_OK', 'METADATA_INVALID')
FROM app_metadata WHERE id = 1;
SELECT IF(CURRENT_USER() = CONCAT(SUBSTRING_INDEX(USER(), '@', 1), '@localhost'), 'ACCOUNT_OK', 'ACCOUNT_MISMATCH');
SELECT IF(COUNT(DISTINCT PRIVILEGE_TYPE) = 4, 'DML_OK', 'DML_MISSING')
FROM information_schema.SCHEMA_PRIVILEGES
WHERE TABLE_SCHEMA = 'campus_market'
  AND GRANTEE = CONCAT(CHAR(39), SUBSTRING_INDEX(CURRENT_USER(), '@', 1), CHAR(39), '@', CHAR(39), 'localhost', CHAR(39))
  AND PRIVILEGE_TYPE IN ('SELECT', 'INSERT', 'UPDATE', 'DELETE');
SELECT IF(COUNT(*) = 0, 'GLOBAL_OK', 'GLOBAL_PRIVILEGES_PRESENT')
FROM information_schema.USER_PRIVILEGES WHERE PRIVILEGE_TYPE <> 'USAGE';
SELECT DATABASE(), VERSION(), CURRENT_USER(), project_name, schema_version, CURRENT_TIMESTAMP
FROM app_metadata WHERE id = 1;
'@
    $result = Invoke-PrivateSql $Credential $sql 'Business account verification'
    $rows = @($result -split '\r?\n')
    if ($rows.Count -ne 5 -or $rows[0] -ne 'METADATA_OK' -or $rows[1] -ne 'ACCOUNT_OK' -or
        $rows[2] -ne 'DML_OK' -or $rows[3] -ne 'GLOBAL_OK') {
        throw 'Verification failed: expected metadata, localhost business account and scoped DML grants without global privileges. No credentials promoted.'
    }
    Write-Host 'PASS: campus_market database, app_metadata row, business-account login and four DML grants; no global privileges.'
    Write-Host ('Database / version / business account / project / schema / database time: ' + $rows[4])
}

if (-not (Test-Path -LiteralPath $MySqlBin -PathType Leaf)) { throw 'MySQL client executable not found.' }
if (-not (Test-Path -LiteralPath $migrationFile -PathType Leaf)) { throw 'Migration file not found.' }
$clientVersion = & $MySqlBin --no-defaults --version 2>&1
if ($LASTEXITCODE -ne 0 -or ($clientVersion -join ' ') -notmatch 'Ver 8\.0\.') { throw 'A working MySQL 8.0 client is required.' }
Write-Host ($clientVersion -join ' ')
if ($CheckOnly) {
    $sampleSecret = 'DO_NOT_PRINT_THIS_PASSWORD'
    $safe = Get-SafeMySqlError "ERROR 1064 (42000) near '$sampleSecret'" 1
    if ($safe -match $sampleSecret -or $safe -notmatch '1064 \(42000\)') { throw 'Error sanitization self-check failed.' }
    $safe = Get-SafeMySqlError "unexpected client output $sampleSecret" 2
    if ($safe -match $sampleSecret) { throw 'Client error sanitization self-check failed.' }
    Write-Host 'PASS: client version and error sanitization. No connection, password prompt or database changes.'
    return
}
New-Item -ItemType Directory -Force -Path $privateDir | Out-Null
# Prevent concurrent setup processes from creating separate accounts or overwriting credentials.
$lock = [IO.File]::Open((Join-Path $privateDir 'db-setup.lock'), [IO.FileMode]::OpenOrCreate, [IO.FileAccess]::ReadWrite, [IO.FileShare]::None)
try {
    if ($ApplyPhase7Migration) {
        if ($UseExistingAccount -or $ApplyPhase3Migration -or $ApplyPhase4Migration -or $ApplyPhase5Migration -or $ApplyPhase6Migration) { throw 'Choose only the phase 7 migration switch.' }
        $appCredential=Import-Clixml -LiteralPath $credentialFile
        $prior=Invoke-PrivateSql $appCredential 'SELECT schema_version FROM campus_market.app_metadata WHERE id=1;' 'Phase 7 prerequisite check'
        if ([int]$prior -lt 5) { throw 'Apply phase 6 migration first.' }
        $adminName=Read-Host 'Migration administrator username (Enter for root)'
        if ([string]::IsNullOrWhiteSpace($adminName)) { $adminName='root' }
        $adminPassword=Read-Host 'Migration administrator password (hidden)' -AsSecureString
        $adminCredential=[PSCredential]::new($adminName,$adminPassword)
        $sql=Get-Content -LiteralPath (Join-Path $projectRoot 'database\migrations\006_messaging.sql') -Raw -Encoding UTF8
        $null=Invoke-PrivateSql $adminCredential $sql 'Phase 7 additive migration'
        $verified=Invoke-PrivateSql $appCredential 'SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=''campus_market'' AND TABLE_NAME IN (''conversations'',''messages''); SELECT schema_version FROM campus_market.app_metadata WHERE id=1; SELECT id,product_id,buyer_id,seller_id,product_title,last_seq,buyer_read_seq,seller_read_seq,last_message_preview,created_at,updated_at FROM campus_market.conversations LIMIT 0; SELECT id,conversation_id,seq,sender_id,request_id,content,created_at FROM campus_market.messages LIMIT 0;' 'Phase 7 schema verification'
        $rows=@($verified -split '\r?\n')
        if($rows.Count -ne 2 -or $rows[0] -ne '2' -or [int]$rows[1] -lt 6){throw 'Phase 7 schema verification failed.'}
        Write-Host 'PHASE7 MIGRATION PASS: conversations, messages and schema version verified. Existing data and grants retained.'
        return
    }
    if ($ApplyPhase6Migration) {
        if ($UseExistingAccount -or $ApplyPhase3Migration -or $ApplyPhase4Migration -or $ApplyPhase5Migration) { throw 'Choose only the phase 6 migration switch.' }
        $appCredential=Import-Clixml -LiteralPath $credentialFile
        $prior=Invoke-PrivateSql $appCredential 'SELECT schema_version FROM campus_market.app_metadata WHERE id=1;' 'Phase 6 prerequisite check'
        if ([int]$prior -lt 4) { throw 'Apply phase 5 migration first.' }
        $adminName=Read-Host 'Migration administrator username (Enter for root)'
        if ([string]::IsNullOrWhiteSpace($adminName)) { $adminName='root' }
        $adminPassword=Read-Host 'Migration administrator password (hidden)' -AsSecureString
        $adminCredential=[PSCredential]::new($adminName,$adminPassword)
        $sql=Get-Content -LiteralPath (Join-Path $projectRoot 'database\migrations\005_orders.sql') -Raw -Encoding UTF8
        $null=Invoke-PrivateSql $adminCredential $sql 'Phase 6 additive migration'
        $verified=Invoke-PrivateSql $appCredential 'SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=''campus_market'' AND TABLE_NAME IN (''orders'',''simulated_payments'',''order_events''); SELECT schema_version FROM campus_market.app_metadata WHERE id=1; SELECT id,product_id,buyer_id,seller_id,request_id,product_title,product_description,condition_label,trade_location,amount,status,active_product_id FROM campus_market.orders LIMIT 0; SELECT order_id,amount,status,paid_at,refunded_at FROM campus_market.simulated_payments LIMIT 0; SELECT order_id,actor_id,action,from_status,to_status FROM campus_market.order_events LIMIT 0;' 'Phase 6 schema verification'
        $rows=@($verified -split '\r?\n')
        if($rows.Count -ne 2 -or $rows[0] -ne '3' -or [int]$rows[1] -lt 5){throw 'Phase 6 schema verification failed.'}
        Write-Host 'PHASE6 MIGRATION PASS: orders, simulated_payments, order_events and schema version verified. Existing data and grants retained.'
        return
    }
    if ($ApplyPhase5Migration) {
        if ($UseExistingAccount -or $ApplyPhase3Migration -or $ApplyPhase4Migration) { throw 'Choose only the phase 5 migration switch.' }
        $appCredential=Import-Clixml -LiteralPath $credentialFile
        $prior=Invoke-PrivateSql $appCredential 'SELECT schema_version FROM campus_market.app_metadata WHERE id=1;' 'Phase 5 prerequisite check'
        if ([int]$prior -lt 3) { throw 'Apply phase 4 migration first.' }
        $adminName=Read-Host 'Migration administrator username (Enter for root)'
        if ([string]::IsNullOrWhiteSpace($adminName)) { $adminName='root' }
        $adminPassword=Read-Host 'Migration administrator password (hidden)' -AsSecureString
        $adminCredential=[PSCredential]::new($adminName,$adminPassword)
        $sql=Get-Content -LiteralPath (Join-Path $projectRoot 'database\migrations\004_publishing.sql') -Raw -Encoding UTF8
        $null=Invoke-PrivateSql $adminCredential $sql 'Phase 5 additive migration'
        $verified=Invoke-PrivateSql $appCredential 'SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=''campus_market'' AND TABLE_NAME IN (''product_images'',''favorites''); SELECT schema_version FROM campus_market.app_metadata WHERE id=1; SELECT id,uploader_id,product_id,storage_name,media_type,file_size,width,height,position,active FROM campus_market.product_images LIMIT 0; SELECT user_id,product_id,active,updated_at FROM campus_market.favorites LIMIT 0;' 'Phase 5 schema verification'
        $rows=@($verified -split '\r?\n')
        if($rows.Count -ne 2 -or $rows[0] -ne '2' -or [int]$rows[1] -lt 4){throw 'Phase 5 schema verification failed.'}
        Write-Host 'PHASE5 MIGRATION PASS: product_images, favorites and schema version verified. Existing data and grants retained.'
        return
    }
    if ($ApplyPhase4Migration) {
        if ($UseExistingAccount -or $ApplyPhase3Migration) { throw 'Choose only the phase 4 migration switch.' }
        if (-not (Test-Path -LiteralPath $credentialFile)) { throw 'Configure the project database first.' }
        $appCredential=Import-Clixml -LiteralPath $credentialFile
        $prior=Invoke-PrivateSql $appCredential 'SELECT schema_version FROM campus_market.app_metadata WHERE id=1;' 'Phase 4 prerequisite check'
        if ([int]$prior -lt 2) { throw 'Apply phase 3 migration first.' }
        $adminName=Read-Host 'Migration administrator username (Enter for root)'
        if ([string]::IsNullOrWhiteSpace($adminName)) { $adminName='root' }
        $adminPassword=Read-Host 'Migration administrator password (hidden)' -AsSecureString
        $adminCredential=[PSCredential]::new($adminName,$adminPassword)
        $sql=Get-Content -LiteralPath (Join-Path $projectRoot 'database\migrations\003_catalog.sql') -Raw -Encoding UTF8
        $null=Invoke-PrivateSql $adminCredential $sql 'Phase 4 additive migration'
        $verified=Invoke-PrivateSql $appCredential 'SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=''campus_market'' AND TABLE_NAME IN (''categories'',''products''); SELECT schema_version FROM campus_market.app_metadata WHERE id=1; SELECT id,code,name,sort_order,enabled FROM campus_market.categories LIMIT 0; SELECT id,seller_id,category_id,title,description,price,condition_label,trade_location,status,created_at,updated_at FROM campus_market.products LIMIT 0;' 'Phase 4 schema verification'
        $rows=@($verified -split '\r?\n')
        if($rows.Count -ne 2 -or $rows[0] -ne '2' -or [int]$rows[1] -lt 3){throw 'Phase 4 schema verification failed.'}
        Write-Host 'PHASE4 MIGRATION PASS: categories, products, required columns and schema version verified. Existing data retained.'
        return
    }
    if ($ApplyPhase3Migration) {
        if ($UseExistingAccount) { throw 'Do not combine migration with -UseExistingAccount.' }
        if (-not (Test-Path -LiteralPath $credentialFile)) { throw 'Complete phase 2 database configuration first.' }
        Confirm-ProjectDatabase (Import-Clixml -LiteralPath $credentialFile)
        $adminName = Read-Host 'Migration administrator username (Enter for root)'
        if ([string]::IsNullOrWhiteSpace($adminName)) { $adminName = 'root' }
        $adminPassword = Read-Host 'Migration administrator password (hidden)' -AsSecureString
        $adminCredential = [PSCredential]::new($adminName, $adminPassword)
        $null = Invoke-PrivateSql $adminCredential 'SELECT VERSION(), CURRENT_USER();' 'Migration administrator connection'
        $phase3Sql = Get-Content -LiteralPath (Join-Path $projectRoot 'database\migrations\002_users_and_sessions.sql') -Raw -Encoding UTF8
        $null = Invoke-PrivateSql $adminCredential $phase3Sql 'Phase 3 additive migration'
        $appCredential = Import-Clixml -LiteralPath $credentialFile
        $verified = Invoke-PrivateSql $appCredential 'SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = ''campus_market'' AND TABLE_NAME IN (''users'', ''auth_sessions''); SELECT schema_version FROM campus_market.app_metadata WHERE id=1; SELECT id,username,password_hash,nickname,school,campus,bio,status,failed_attempts,locked_until FROM campus_market.users LIMIT 0; SELECT token_hash,user_id,expires_at,revoked_at FROM campus_market.auth_sessions LIMIT 0;' 'Phase 3 schema verification'
        $rows = @($verified -split '\r?\n')
        if ($rows.Count -ne 2 -or $rows[0] -ne '2' -or [int]$rows[1] -lt 2) { throw 'Phase 3 schema verification failed.' }
        Write-Host 'PHASE3 MIGRATION PASS: users, auth_sessions, required columns and schema version verified using the business account. Existing data and grants retained.'
        return
    }
    if (Test-Path -LiteralPath $credentialFile) {
        Confirm-ProjectDatabase (Import-Clixml -LiteralPath $credentialFile)
        Write-Host 'Already configured. Read-only verification completed; no migration, grants or password changes.'
        return
    }
    if ($UseExistingAccount) {
        if (Test-Path -LiteralPath $pendingFile) { throw 'A pending setup exists. Rerun without -UseExistingAccount to resume.' }
        $account = Read-Host 'Existing project MySQL username (not root)'
        $password = Read-Host 'Existing project MySQL password (hidden)' -AsSecureString
        $appCredential = [PSCredential]::new($account, $password)
        Confirm-ProjectDatabase $appCredential
        $appCredential | Export-Clixml -LiteralPath $credentialFile
    } else {
        $adminName = Read-Host 'MySQL administrator username (Enter for root)'
        if ([string]::IsNullOrWhiteSpace($adminName)) { $adminName = 'root' }
        $adminPassword = Read-Host 'MySQL administrator password (hidden)' -AsSecureString
        $adminCredential = [PSCredential]::new($adminName, $adminPassword)
        $null = Invoke-PrivateSql $adminCredential 'SELECT VERSION(), CURRENT_USER();' 'Administrator connection (read-only)'
        Write-Host 'PASS: administrator TCP connection to 127.0.0.1:3306.'
        if (Test-Path -LiteralPath $pendingFile) {
            $appCredential = Import-Clixml -LiteralPath $pendingFile
            if ($appCredential.UserName -notmatch '^cm_[a-f0-9]{12}$') { throw 'Unexpected pending account name; stopped without migration.' }
            Write-Host 'Resuming the same pending account; no password will be reset.'
        } else {
            $random = New-Object byte[] 32
            $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
            try { $rng.GetBytes($random) } finally { $rng.Dispose() }
            # Prefix guarantees upper/lower/digit/symbol classes for common password policies.
            $generatedPassword = 'Cm!7' + [Convert]::ToBase64String($random)
            $account = 'cm_' + [guid]::NewGuid().ToString('N').Substring(0, 12)
            $appCredential = [PSCredential]::new($account, (ConvertTo-SecureString $generatedPassword -AsPlainText -Force))
            $generatedPassword = $null
            # Persist encrypted recovery material BEFORE any database changes.
            $appCredential | Export-Clixml -LiteralPath $pendingFile
        }
        $account = $appCredential.UserName
        $exists = Invoke-PrivateSql $adminCredential "SELECT COUNT(*) FROM mysql.user WHERE User = '$account' AND Host = 'localhost';" 'Account existence (read-only)'
        if ($exists -eq '1') {
            # Do not grant privileges to an unrelated account after a name collision.
            $null = Invoke-PrivateSql $appCredential 'SELECT CURRENT_USER();' 'Pending account ownership (read-only)'
        } elseif ($exists -ne '0') { throw 'Unexpected account lookup result; stopped without migration.' }
        $migration = Get-Content -LiteralPath $migrationFile -Raw -Encoding UTF8
        $null = Invoke-PrivateSql $adminCredential $migration 'Schema initialization (non-destructive)'
        Write-Host 'PASS: database and metadata initialization.'
        if ($exists -eq '0') {
            $secret = $appCredential.GetNetworkCredential().Password
            # Generated passwords contain no SQL quotes or backslashes; no user input enters this SQL.
            if ($secret -notmatch '^Cm!7[A-Za-z0-9+/=]+$') { throw 'Unexpected generated password format; no account was created.' }
            try { $null = Invoke-PrivateSql $adminCredential "CREATE USER '$account'@'localhost' IDENTIFIED BY '$secret';" 'Business account creation' }
            finally { $secret = $null }
        }
        $null = Invoke-PrivateSql $adminCredential "GRANT SELECT, INSERT, UPDATE, DELETE ON campus_market.* TO '$account'@'localhost';" 'Project-scoped DML grants'
        Confirm-ProjectDatabase $appCredential
        Move-Item -LiteralPath $pendingFile -Destination $credentialFile
    }
    Write-Host 'SUCCESS: verified credentials saved with Windows DPAPI in .local/db.credentials.xml (ignored by Git).'
    Write-Host 'Only this Windows user on this computer can decrypt them. Administrator credentials were not saved.'
} finally {
    $adminCredential = $null
    $adminPassword = $null
    $appCredential = $null
    $lock.Dispose()
}
