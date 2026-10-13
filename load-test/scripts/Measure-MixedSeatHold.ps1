[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][ValidatePattern('^[A-Za-z0-9-]{1,24}$')][string]$RunId,
    [ValidateSet('small-only','separate','overlap')][string]$Mode = 'small-only',
    [ValidateRange(1,200)][int]$LargeSeats = 20,
    [ValidateRange(1,20)][int]$SmallRate = 10,
    [ValidateRange(1,5)][int]$LargeRate = 2,
    [ValidateRange(5,30)][int]$DurationSeconds = 15
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
if ($env:COMPOSE_PROJECT_NAME -notmatch '^ticketon-mixed224-[a-z0-9-]+$' -or $env:ONTICKET_DB_PORT -ne '3309') {
    throw 'Use a dedicated ticketon-mixed224-* compose project on loopback port 3309.'
}
if ([Environment]::OSVersion.Platform -ne [PlatformID]::Win32NT) { throw 'This bounded runner requires the Windows host memory preflight.' }
$availableMemoryBytes = [long](Get-CimInstance Win32_OperatingSystem).FreePhysicalMemory * 1024
if ($availableMemoryBytes -lt 2GB) { throw 'Available host memory <2GiB; do not start a new measurement.' }
$root = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
Import-Module (Join-Path $PSScriptRoot 'ContentionMetrics.psm1') -Force
$output = Join-Path $root "load-test/results/$RunId"
if (Test-Path -LiteralPath $output) { throw 'RunId already exists; refusing to overwrite evidence.' }
$base = 'http://127.0.0.1:18080'
$management = 'http://127.0.0.1:18081'
if ((Invoke-RestMethod "$management/actuator/health" -TimeoutSec 10).status -ne 'UP') { throw 'Backend is not UP.' }
$fixture = Invoke-RestMethod -Method Post -Uri "$base/loadtest/runs?runId=$RunId" -TimeoutSec 30
if ($fixture.totalSeats -ne 2000) { throw 'Expected 2000-seat loadtest fixture.' }
$ownership = & docker compose -f (Join-Path $root 'compose.yml') exec -T mariadb mariadb -uonticket -ponticket -N onticket_local -e "SELECT COUNT(*) FROM concert_time t JOIN seat s ON s.concert_time_id=t.id WHERE t.concert_id='LOAD-TEST-$RunId' AND t.id=$($fixture.concertTimeId);" 2>&1
if ($LASTEXITCODE -ne 0 -or ([string]($ownership -join '').Trim()) -ne '2000') { throw 'Backend fixture is not in the dedicated measurement DB.' }
$initial = Invoke-RestMethod "$base/loadtest/seat-holds/snapshot?runId=$RunId" -TimeoutSec 10
if (!$initial.invariantSatisfied -or $initial.holdRows -ne 0) { throw 'Expected unused fixture; no automatic reset.' }
New-Item -ItemType Directory -Path $output | Out-Null
$compose = Join-Path $root 'compose.yml'
$watch = [Diagnostics.Stopwatch]::StartNew()
function Sample {
    $prom = (Invoke-WebRequest -UseBasicParsing "$management/actuator/prometheus" -TimeoutSec 10).Content
    $pool = ConvertFrom-PrometheusHikari -Text $prom
    $runtime = ConvertFrom-PrometheusRuntimeMetrics -Text $prom
    $jvm = ConvertFrom-PrometheusJvmContentionMetrics -Text $prom
    $dbLines = & docker compose -f $compose exec -T mariadb mariadb -uonticket -ponticket -N -e "SHOW GLOBAL STATUS WHERE Variable_name IN ('Innodb_deadlocks','Innodb_row_lock_current_waits','Innodb_row_lock_time','Innodb_row_lock_waits','Threads_connected','Threads_running');" 2>&1
    if ($LASTEXITCODE -ne 0) { throw 'DB status collection failed.' }
    $db = ConvertFrom-MariaDbStatus -Lines $dbLines
    [pscustomobject]@{
        TimestampUtc = (Get-Date).ToUniversalTime().ToString('o')
        ElapsedMilliseconds = $watch.ElapsedMilliseconds
        HikariActive = $pool.Active; HikariPending = $pool.Pending; HikariIdle = $pool.Idle; HikariMax = $pool.Max
        ProcessCpuUsage = $runtime.ProcessCpuUsage; SystemCpuUsage = $runtime.SystemCpuUsage; HeapUsedBytes = $runtime.HeapUsedBytes
        JvmThreadsLive = $jvm.JvmThreadsLive; JvmGcPauseSeconds = $jvm.JvmGcPauseSeconds; JvmGcPauseCount = $jvm.JvmGcPauseCount
        MariaDbContainerCpuPercent = $null; MariaDbContainerMemoryBytes = $null
        DbRowLockCurrentWaits = $db.RowLockCurrentWaits; DbRowLockWaits = $db.RowLockWaits; DbRowLockTimeMs = $db.RowLockTimeMs
        DbDeadlocks = $db.Deadlocks; DbThreadsConnected = $db.ThreadsConnected; DbThreadsRunning = $db.ThreadsRunning
    }
}
$samples = New-Object 'Collections.Generic.List[object]'
$process = $null
try {
    $samples.Add((Sample))
    $k6Arguments = @('run', '-e', "FIXTURE_RUN_ID=$RunId", '-e', "MIXED_MODE=$Mode", '-e', "LARGE_SEATS=$LargeSeats", '-e', "SMALL_RATE=$SmallRate", '-e', "LARGE_RATE=$LargeRate", '-e', "DURATION=$($DurationSeconds)s", (Join-Path $root 'load-test/k6/mixed-seat-hold.js'))
    $start = New-Object Diagnostics.ProcessStartInfo
    $start.FileName = (Get-Command k6 -ErrorAction Stop).Source
    $start.Arguments = ($k6Arguments | ForEach-Object { '"' + $_ + '"' }) -join ' '
    $start.UseShellExecute = $false; $start.CreateNoWindow = $true
    $start.RedirectStandardOutput = $true; $start.RedirectStandardError = $true
    $process = New-Object Diagnostics.Process
    $process.StartInfo = $start
    if (!$process.Start()) { throw 'k6 start failed.' }
    $stdout = $process.StandardOutput.ReadToEndAsync(); $stderr = $process.StandardError.ReadToEndAsync()
    while (!$process.HasExited) {
        Start-Sleep -Milliseconds 1000
        if (!$process.HasExited) { $samples.Add((Sample)) }
    }
    $process.WaitForExit()
    $out = $stdout.GetAwaiter().GetResult(); $err = $stderr.GetAwaiter().GetResult()
    $out | Set-Content -LiteralPath (Join-Path $output 'k6.stdout.log') -Encoding UTF8
    $err | Set-Content -LiteralPath (Join-Path $output 'k6.stderr.log') -Encoding UTF8
    $samples.Add((Sample))
    $samples | Export-Csv -LiteralPath (Join-Path $output 'samples.csv') -NoTypeInformation -Encoding UTF8
    if ($process.ExitCode -ne 0) { throw "k6 failed: $($process.ExitCode)" }
    $resultLine = @($out -split "`r?`n" | Where-Object { $_.StartsWith('MIXED_RESULT ') })
    if ($resultLine.Count -ne 1) { throw 'Missing or ambiguous k6 summary.' }
    $result = $resultLine[0].Substring(13) | ConvertFrom-Json
    if ($result.mode -ne $Mode -or $result.largeSeats -ne $LargeSeats -or $result.smallIterationsPerSecond -ne $SmallRate -or $result.duration -ne "$($DurationSeconds)s" -or $result.droppedIterations -ne 0) { throw 'Run identity/dropped iteration mismatch.' }
    foreach ($kind in @('small','large')) {
        $class = $result.classes.$kind
        if ($class.failures -ne 0 -or $class.attempts -ne ($class.success + $class.conflicts) -or $class.success -ne $class.released) { throw "Invalid $kind outcome accounting." }
    }
    if ($result.classes.small.attempts -eq 0 -or ($Mode -ne 'small-only' -and $result.classes.large.attempts -eq 0)) { throw 'Required request class was not exercised.' }
    $snapshot = Invoke-RestMethod "$base/loadtest/seat-holds/snapshot?runId=$RunId" -TimeoutSec 10
    if (!$snapshot.invariantSatisfied -or $snapshot.holdRows -ne 0) { throw 'Final inventory/Hold invariant failed.' }
    $metrics = New-ContentionMetricsSummary -Samples $samples.ToArray()
    [ordered]@{ SchemaVersion=1; ValidMeasurement=$true; RunId=$RunId; AvailableHostMemoryBytesBeforeRun=$availableMemoryBytes; K6=$result; Snapshot=$snapshot; Metrics=$metrics; RequestedSamplingPauseMilliseconds=1000; DbCountersScope='Whole run including setup/teardown; not query-specific wait attribution' } |
        ConvertTo-Json -Depth 12 | Set-Content -LiteralPath (Join-Path $output 'summary.json') -Encoding UTF8
    Write-Output "MIXED_COMPLETE run=$RunId mode=$Mode largeSeats=$LargeSeats smallP95=$($result.classes.small.holdMs.'p(95)') pending=$($metrics.Peaks.HikariPending)"
} catch {
    if ($null -ne $process -and !$process.HasExited) { $process.Kill(); $process.WaitForExit() }
    [ordered]@{ ValidMeasurement=$false; RunId=$RunId; Error=$_.Exception.Message } | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $output 'failure.json') -Encoding UTF8
    throw
}
