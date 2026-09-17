[CmdletBinding()]
param(
    [ValidateSet('distributed', 'hot-seat')][string]$Scenario = 'distributed',
    [ValidateRange(1, 1000)][int]$Rate = 5,
    [ValidateRange(1, 60)][int]$DurationSeconds = 10,
    [ValidateRange(250, 10000)][int]$SampleIntervalMilliseconds = 1000,
    [ValidateRange(1, 500)][int]$PreAllocatedVus = 20,
    [ValidateRange(1, 500)][int]$MaxVus = 100,
    [Parameter(Mandatory = $true)][string]$RunId,
    [string]$BaseUrl = 'http://127.0.0.1:18080',
    [string]$ManagementBaseUrl = 'http://127.0.0.1:18081',
    [string]$OutputDirectory = '',
    [string]$DatabaseUser = 'onticket',
    [string]$DatabasePassword = 'onticket',
    [switch]$DisablePerformanceThresholds
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$scriptDirectory = $PSScriptRoot
$repositoryRoot = (Resolve-Path (Join-Path $scriptDirectory '..\..')).Path
$k6Script = Join-Path $repositoryRoot 'load-test\k6\checkout-contention.js'
$composeFile = Join-Path $repositoryRoot 'compose.yml'
Import-Module (Join-Path $scriptDirectory 'ContentionMetrics.psm1') -Force
Import-Module (Join-Path $scriptDirectory 'CheckoutContention.psm1') -Force

if ($PreAllocatedVus -gt $MaxVus) { throw 'PreAllocatedVus must not exceed MaxVus.' }
$RunId = Assert-ContentionRunId -RunId $RunId
if ([string]::IsNullOrWhiteSpace($OutputDirectory)) { $OutputDirectory = Join-Path $repositoryRoot 'load-test\results' }
$resolvedOutput = [IO.Path]::GetFullPath($OutputDirectory)
$allowedOutput = [IO.Path]::GetFullPath((Join-Path $repositoryRoot 'load-test\results'))
if (-not $resolvedOutput.StartsWith($allowedOutput, [StringComparison]::OrdinalIgnoreCase)) { throw "OutputDirectory must stay under $allowedOutput" }
New-Item -ItemType Directory -Path $resolvedOutput -Force | Out-Null

$paths = [ordered]@{
    Samples = Join-Path $resolvedOutput "$RunId-metrics.csv"
    Summary = Join-Path $resolvedOutput "$RunId-summary.json"
    Stdout = Join-Path $resolvedOutput "$RunId-k6.stdout.log"
    Stderr = Join-Path $resolvedOutput "$RunId-k6.stderr.log"
    Failure = Join-Path $resolvedOutput "$RunId-failure.json"
}
foreach ($path in $paths.Values) { if (Test-Path -LiteralPath $path) { throw "Refusing to overwrite an existing measurement result: $path" } }

Get-Command k6 -ErrorAction Stop | Out-Null
Get-Command docker -ErrorAction Stop | Out-Null
if ((Invoke-RestMethod -Uri "$ManagementBaseUrl/actuator/health" -Method Get).status -ne 'UP') { throw 'Backend health is not UP.' }

$statusQuery = "SHOW GLOBAL STATUS WHERE Variable_name IN ('Innodb_deadlocks','Innodb_row_lock_current_waits','Innodb_row_lock_time','Innodb_row_lock_waits','Threads_connected','Threads_running');"
function Get-MariaDbStatus {
    $output = & docker compose -f $composeFile exec -T mariadb mariadb "-u$DatabaseUser" "-p$DatabasePassword" -N -e $statusQuery 2>&1
    if ($LASTEXITCODE -ne 0) { throw "MariaDB status query failed with exit code $LASTEXITCODE." }
    ConvertFrom-MariaDbStatus -Lines $output
}
function Get-MetricSample {
    param([Parameter(Mandatory = $true)][Diagnostics.Stopwatch]$Stopwatch)
    $prometheus = Invoke-WebRequest -UseBasicParsing -Uri "$ManagementBaseUrl/actuator/prometheus" -Method Get
    $hikari = ConvertFrom-PrometheusHikari -Text $prometheus.Content
    $runtime = ConvertFrom-PrometheusRuntimeMetrics -Text $prometheus.Content
    $jvm = ConvertFrom-PrometheusJvmContentionMetrics -Text $prometheus.Content
    $db = Get-MariaDbStatus
    [pscustomobject]@{
        TimestampUtc = (Get-Date).ToUniversalTime().ToString('o'); ElapsedMilliseconds = $Stopwatch.ElapsedMilliseconds
        HikariActive = $hikari.Active; HikariPending = $hikari.Pending; HikariIdle = $hikari.Idle; HikariMax = $hikari.Max
        ProcessCpuUsage = $runtime.ProcessCpuUsage; SystemCpuUsage = $runtime.SystemCpuUsage; HeapUsedBytes = $runtime.HeapUsedBytes
        JvmThreadsLive = $jvm.JvmThreadsLive; JvmGcPauseSeconds = $jvm.JvmGcPauseSeconds; JvmGcPauseCount = $jvm.JvmGcPauseCount
        MariaDbContainerCpuPercent = $null; MariaDbContainerMemoryBytes = $null
        DbRowLockCurrentWaits = $db.RowLockCurrentWaits; DbRowLockWaits = $db.RowLockWaits; DbRowLockTimeMs = $db.RowLockTimeMs; DbDeadlocks = $db.Deadlocks
        DbThreadsConnected = $db.ThreadsConnected; DbThreadsRunning = $db.ThreadsRunning
    }
}

$fixture = Invoke-RestMethod -Uri "$BaseUrl/loadtest/runs?runId=$RunId" -Method Post
if ([int]$fixture.totalSeats -ne 2000) { throw 'Checkout fixture must contain 2,000 seats.' }
$startedAt = (Get-Date).ToUniversalTime()
$stopwatch = [Diagnostics.Stopwatch]::StartNew()
$samples = New-Object 'Collections.Generic.List[object]'
$process = $null; $stdoutTask = $null; $stderrTask = $null
try {
    $samples.Add((Get-MetricSample -Stopwatch $stopwatch))
    $command = Get-Command k6 -ErrorAction Stop
    $arguments = @('run', '-e', "TEST_SCENARIO=$Scenario", '-e', "RATE=$Rate", '-e', "DURATION=${DurationSeconds}s", '-e', "RUN_ID=$RunId", '-e', 'FIXTURE_PREPARED=true', '-e', "BASE_URL=$BaseUrl", '-e', "MANAGEMENT_BASE_URL=$ManagementBaseUrl", '-e', "PRE_ALLOCATED_VUS=$PreAllocatedVus", '-e', "MAX_VUS=$MaxVus", '-e', "TOKEN_COUNT=$MaxVus", '-e', "ENFORCE_THRESHOLDS=$((-not $DisablePerformanceThresholds.IsPresent).ToString().ToLowerInvariant())", $k6Script)
    $info = New-Object Diagnostics.ProcessStartInfo
    $info.FileName = $command.Source; $info.Arguments = (($arguments | ForEach-Object { '"' + ([string]$_).Replace('"', '\"') + '"' }) -join ' ')
    $info.UseShellExecute = $false; $info.RedirectStandardOutput = $true; $info.RedirectStandardError = $true; $info.CreateNoWindow = $true
    $process = New-Object Diagnostics.Process; $process.StartInfo = $info
    if (-not $process.Start()) { throw 'Failed to start k6.' }
    $stdoutTask = $process.StandardOutput.ReadToEndAsync(); $stderrTask = $process.StandardError.ReadToEndAsync()
    $nextSample = $stopwatch.ElapsedMilliseconds + $SampleIntervalMilliseconds
    while (-not $process.HasExited) {
        $wait = $nextSample - $stopwatch.ElapsedMilliseconds
        if ($wait -gt 0) { Start-Sleep -Milliseconds $wait }
        $process.Refresh(); if ($process.HasExited) { break }
        $samples.Add((Get-MetricSample -Stopwatch $stopwatch))
        do { $nextSample += $SampleIntervalMilliseconds } while ($nextSample -le $stopwatch.ElapsedMilliseconds)
    }
    $process.WaitForExit(); $stdout = $stdoutTask.GetAwaiter().GetResult(); $stderr = $stderrTask.GetAwaiter().GetResult()
    Set-Content -LiteralPath $paths.Stdout -Value $stdout -Encoding UTF8; Set-Content -LiteralPath $paths.Stderr -Value $stderr -Encoding UTF8
    $samples.Add((Get-MetricSample -Stopwatch $stopwatch)); $stopwatch.Stop()
    if ($process.ExitCode -ne 0 -and $process.ExitCode -ne 99) { throw "k6 failed with exit code $($process.ExitCode)." }
    $text = ($stdout + "`n" + $stderr).Replace('\"', '"')
    $result = ConvertFrom-CheckoutK6Result -Text $text
    $thresholds = -not $DisablePerformanceThresholds.IsPresent
    Assert-CheckoutRunIdentity -Result $result -Scenario $Scenario -Rate $Rate -DurationSeconds $DurationSeconds -ThresholdsEnforced $thresholds | Out-Null
    $snapshot = ConvertFrom-CheckoutFinalSnapshot -Text $text; $delta = ConvertFrom-CheckoutTransitionDelta -Text $text
    Assert-CheckoutDomainState -Result $result -Snapshot $snapshot -TransitionDelta $delta | Out-Null
    $metrics = New-ContentionMetricsSummary -Samples $samples.ToArray(); $samples | Export-Csv -LiteralPath $paths.Samples -NoTypeInformation -Encoding UTF8
    $thresholdsPassed = $process.ExitCode -eq 0
    [ordered]@{ SchemaVersion = 1; ValidMeasurement = $true; Run = [ordered]@{ Id = $RunId; Scenario = $Scenario; RatePerSecond = $Rate; DurationSeconds = $DurationSeconds; StartedAtUtc = $startedAt.ToString('o') }; Fixture = [ordered]@{ TotalSeats = 2000; PreparedBeforeSampling = $true }; K6 = [ordered]@{ ExitCode = $process.ExitCode; ThresholdsPassed = $thresholdsPassed; Result = $result; FinalSnapshot = $snapshot; TransitionDelta = $delta }; Metrics = $metrics; SamplesFile = [IO.Path]::GetFileName($paths.Samples); ObserverEffects = $metrics.ObserverEffects } | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $paths.Summary -Encoding UTF8
    Write-Output "VALID_CHECKOUT_MEASUREMENT runId=$RunId scenario=$Scenario thresholdsPassed=$thresholdsPassed samples=$($metrics.SampleCount)"
    Write-Output "SUMMARY_PATH $($paths.Summary)"
} catch {
    $stopwatch.Stop(); if ($null -ne $process -and -not $process.HasExited) { $process.Kill(); $process.WaitForExit() }
    [ordered]@{ SchemaVersion = 1; ValidMeasurement = $false; RunId = $RunId; Scenario = $Scenario; Error = $_.Exception.Message } | ConvertTo-Json | Set-Content -LiteralPath $paths.Failure -Encoding UTF8
    throw
}
