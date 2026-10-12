[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][ValidatePattern('^[a-z0-9-]{1,16}$')][string]$RunId,
    [Parameter(Mandatory = $true)][ValidatePattern('^ticketon-controlled172-[a-z0-9]{1,12}$')][string]$ComposeProject,
    [switch]$CheckOnly
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'SqlCounterProbe.psm1') -Force
Import-Module (Join-Path $PSScriptRoot 'SmallSeatSqlDiagnostics.psm1') -Force
$plan = @(Get-SqlCounterProbePlan)
if ($CheckOnly) { $plan | ConvertTo-Json; return }
$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$output = Join-Path $root "load-test/results/$RunId"
if (Test-Path -LiteralPath $output) { throw 'Probe output already exists; no overwrite.' }
$mysql = (Get-Command mysql -ErrorAction Stop).Source
$container = "$ComposeProject-mariadb-1"
$inspection = (& docker inspect $container | ConvertFrom-Json)[0]
if ($LASTEXITCODE -ne 0 -or -not $inspection.State.Running -or
    $inspection.Config.Labels.'com.docker.compose.project' -cne $ComposeProject -or
    @($inspection.NetworkSettings.Ports.'3306/tcp').Count -lt 1 -or
    @($inspection.NetworkSettings.Ports.'3306/tcp' | Where-Object HostPort -ne '3309').Count -gt 0) {
    throw 'The dedicated probe DB identity/port is invalid.'
}
if ((Get-CimInstance Win32_OperatingSystem).FreePhysicalMemory -lt 2097152) { throw 'Insufficient free host memory.' }
$previousPassword = $env:MYSQL_PWD
$env:MYSQL_PWD = 'onticket-root'
function Read-ProbeSql([string]$Query, [string]$Database = 'onticket_local') {
    $lines = @(& $mysql --protocol=tcp --host=127.0.0.1 --port=3309 --user=root --batch --skip-column-names $Database --execute $Query 2>&1)
    if ($LASTEXITCODE -ne 0) { throw 'Probe DB command failed; raw SQL output withheld.' }
    $lines
}
function Read-ProbeCounters {
    $query = "SELECT REPLACE(TO_BASE64(DIGEST_TEXT),CHAR(10),''),COUNT_STAR,SUM_TIMER_WAIT,SUM_ROWS_EXAMINED,SUM_ROWS_AFFECTED,SUM_ERRORS " +
        "FROM performance_schema.events_statements_summary_by_digest WHERE SCHEMA_NAME='onticket_sql_probe' AND DIGEST_TEXT IS NOT NULL;"
    ConvertFrom-SmallSeatSqlCounters -Lines @(Read-ProbeSql $query)
}
$clients = New-Object 'Collections.Generic.List[object]'
$records = New-Object 'Collections.Generic.List[object]'
$complete = $false
$failure = $null
$createdOutput = $false
try {
    if ((@(Read-ProbeSql 'SELECT @@hostname;') -join '') -cne $inspection.Config.Hostname) { throw 'CLI DB does not match the inspected container.' }
    $checks = @(Read-ProbeSql "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA='onticket_local'; SELECT COUNT(*) FROM information_schema.SCHEMATA WHERE SCHEMA_NAME='onticket_sql_probe'; SELECT @@performance_schema; SELECT ENABLED FROM performance_schema.setup_consumers WHERE NAME='statements_digest';")
    if (($checks -join ',') -cne '0,0,1,YES') { throw 'Probe requires a fresh dedicated DB and digest instrumentation.' }
    New-Item -ItemType Directory -Path $output -ErrorAction Stop | Out-Null
    $createdOutput = $true
    Read-ProbeSql 'CREATE DATABASE onticket_sql_probe; CREATE TABLE onticket_sql_probe.seat (id INT PRIMARY KEY, counter INT NOT NULL) ENGINE=InnoDB; INSERT INTO onticket_sql_probe.seat VALUES (1,0),(2,0),(3,0),(4,0);' | Out-Null
    foreach ($stage in $plan) {
        $before = Read-ProbeCounters
        $clients.Clear()
        $deadline = [Diagnostics.Stopwatch]::StartNew()
        foreach ($worker in 1..4) {
            $start = New-Object Diagnostics.ProcessStartInfo
            $start.FileName = $mysql
            $start.Arguments = '--protocol=tcp --host=127.0.0.1 --port=3309 --user=root --batch --skip-column-names onticket_sql_probe'
            $start.UseShellExecute = $false
            $start.CreateNoWindow = $true
            $start.RedirectStandardInput = $true
            $start.RedirectStandardOutput = $true
            $start.RedirectStandardError = $true
            $process = New-Object Diagnostics.Process
            $process.StartInfo = $start
            if (-not $process.Start()) { throw 'Probe client failed to start.' }
            $client = [pscustomobject]@{ Process = $process; Out = $process.StandardOutput.ReadToEndAsync(); Err = $process.StandardError.ReadToEndAsync() }
            $clients.Add($client)
            $process.StandardInput.WriteLine((Get-SqlCounterProbeWorkload -Worker $worker))
            $process.StandardInput.Close()
            if ($stage.Mode -eq 'serial') {
                if (-not $process.WaitForExit([math]::Max(1, 30000 - [int]$deadline.ElapsedMilliseconds))) { throw 'Probe stage exceeded 30 seconds.' }
            }
        }
        $completed = 0
        foreach ($client in $clients) {
            if (-not $client.Process.WaitForExit([math]::Max(1, 30000 - [int]$deadline.ElapsedMilliseconds))) { throw 'Probe stage exceeded 30 seconds.' }
            $stderr = $client.Err.GetAwaiter().GetResult()
            if (-not [string]::IsNullOrWhiteSpace($stderr)) { throw 'Probe client emitted an error; raw output withheld.' }
            $completed += Assert-SqlCounterProbeCompletion -Output $client.Out.GetAwaiter().GetResult() -ExitCode $client.Process.ExitCode
        }
        if ($deadline.ElapsedMilliseconds -gt 30000) { throw 'Probe stage exceeded 30 seconds.' }
        $after = Read-ProbeCounters
        $delta = @(New-SmallSeatSqlDelta -Before $before -After $after)
        $values = @(Read-ProbeSql 'SELECT counter FROM seat ORDER BY id;' 'onticket_sql_probe' | ForEach-Object { [long]$_ })
        $expectedValue = 25 * $stage.Sequence
        if ($completed -ne 100 -or $values.Count -ne 4 -or @($values | Where-Object { $_ -ne $expectedValue }).Count -gt 0) {
            throw 'Probe commit markers and independent durable counters disagree.'
        }
        $matched = $delta[0].Executions -eq 100 -and $delta[1].Executions -eq 100 -and @($delta | Where-Object SqlErrors -ne 0).Count -eq 0
        $records.Add([pscustomobject]@{
            Sequence = $stage.Sequence; Mode = $stage.Mode; Workers = 4; CyclesPerWorker = 25
            CompletedTransactions = $completed; DurableCounters = $values
            ExpectedLockSelects = 100; ExpectedSeatUpdates = 100
            SqlOperationDeltas = $delta
            Coverage = if ($matched) { 'COUNTERS_MATCH_WORKLOAD' } else { 'SQL_COUNTER_COVERAGE_MISMATCH' }
        })
        Write-Output "SQL_PROBE_STAGE sequence=$($stage.Sequence) mode=$($stage.Mode) commits=$completed lock_selects=$($delta[0].Executions) updates=$($delta[1].Executions)"
        foreach ($client in $clients) { $client.Process.Dispose() }
        $clients.Clear()
    }
    $complete = $records.Count -eq 6
} catch {
    $failure = 'PROBE_EXECUTION_OR_INDEPENDENT_COMMIT_CHECK_FAILED'
    throw
} finally {
    foreach ($client in $clients) {
        if (-not $client.Process.HasExited) { $client.Process.Kill(); $client.Process.WaitForExit() }
        $client.Process.Dispose()
    }
    $env:MYSQL_PWD = $previousPassword
    if ($createdOutput) {
        [ordered]@{
            SchemaVersion = 1; RunId = $RunId; ComposeProject = $ComposeProject; Complete = $complete; Failure = $failure
            Scope = 'isolated local text-protocol SQL counter probe; not JDBC, reservation workload or capacity proof'
            SqlTextIncluded = $false; Records = $records.ToArray()
        } | ConvertTo-Json -Depth 8 | Set-Content -Encoding UTF8 -LiteralPath (Join-Path $output 'sql-counter-probe.json')
    }
}
