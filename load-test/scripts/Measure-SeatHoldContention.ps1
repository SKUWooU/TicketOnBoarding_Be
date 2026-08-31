[CmdletBinding()]
param(
    [ValidateSet('distributed', 'hot-section', 'hot-seat')]
    [string]$Scenario = 'distributed',
    [ValidateRange(1, 10000)][int]$Rate = 5,
    [ValidateRange(1, 3600)][int]$DurationSeconds = 10,
    [ValidateRange(250, 10000)][int]$SampleIntervalMilliseconds = 1000,
    [ValidateRange(1, 500)][int]$PreAllocatedVus = 20,
    [ValidateRange(1, 500)][int]$MaxVus = 100,
    [Parameter(Mandatory = $true)][string]$RunId,
    [Parameter(Mandatory = $true)][string]$FixtureRunId,
    [string]$BaseUrl = 'http://127.0.0.1:18080',
    [string]$ManagementBaseUrl = 'http://127.0.0.1:18081',
    [string]$OutputDirectory = '',
    [string]$DatabaseUser = 'onticket',
    [string]$DatabasePassword = 'onticket',
    [switch]$DisablePerformanceThresholds
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$issue65ScriptDirectory = $PSScriptRoot
$issue65RepositoryRoot = (Resolve-Path (Join-Path $issue65ScriptDirectory '..\..')).Path
Import-Module (Join-Path $issue65ScriptDirectory 'ContentionMetrics.psm1') -Force
Import-Module (Join-Path $issue65ScriptDirectory 'SeatHoldContention.psm1') -Force
$issue65K6Script = Join-Path $issue65RepositoryRoot 'load-test\k6\seat-hold-contention.js'
$issue65ComposeFile = Join-Path $issue65RepositoryRoot 'compose.yml'

if ($PreAllocatedVus -gt $MaxVus) {
    throw 'PreAllocatedVus must not exceed MaxVus.'
}
$RunId = Assert-ContentionRunId -RunId $RunId
$FixtureRunId = Assert-ContentionRunId -RunId $FixtureRunId

if ([string]::IsNullOrWhiteSpace($OutputDirectory)) {
    $OutputDirectory = Join-Path $issue65RepositoryRoot 'load-test\results'
}
$issue65ResolvedOutputDirectory = [IO.Path]::GetFullPath($OutputDirectory)
$issue65ExpectedOutputRoot = [IO.Path]::GetFullPath((Join-Path $issue65RepositoryRoot 'load-test\results'))
$issue65ExpectedOutputPrefix = $issue65ExpectedOutputRoot.TrimEnd([IO.Path]::DirectorySeparatorChar, [IO.Path]::AltDirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
if (-not $issue65ResolvedOutputDirectory.Equals($issue65ExpectedOutputRoot, [StringComparison]::OrdinalIgnoreCase) -and
    -not $issue65ResolvedOutputDirectory.StartsWith($issue65ExpectedOutputPrefix, [StringComparison]::OrdinalIgnoreCase)) {
    throw "OutputDirectory must stay under $issue65ExpectedOutputRoot"
}
New-Item -ItemType Directory -Path $issue65ResolvedOutputDirectory -Force | Out-Null

$issue65SamplesPath = Join-Path $issue65ResolvedOutputDirectory "$RunId-metrics.csv"
$issue65SummaryPath = Join-Path $issue65ResolvedOutputDirectory "$RunId-summary.json"
$issue65StdoutPath = Join-Path $issue65ResolvedOutputDirectory "$RunId-k6.stdout.log"
$issue65StderrPath = Join-Path $issue65ResolvedOutputDirectory "$RunId-k6.stderr.log"
$issue65FailurePath = Join-Path $issue65ResolvedOutputDirectory "$RunId-failure.json"
foreach ($issue65Path in @($issue65SamplesPath, $issue65SummaryPath, $issue65StdoutPath, $issue65StderrPath, $issue65FailurePath)) {
    if (Test-Path -LiteralPath $issue65Path) {
        throw "Refusing to overwrite an existing measurement result: $issue65Path"
    }
}

$issue65K6Command = Get-Command k6 -ErrorAction Stop
Get-Command docker -ErrorAction Stop | Out-Null
$issue65Health = Invoke-RestMethod -Uri "$ManagementBaseUrl/actuator/health" -Method Get
if ($issue65Health.status -ne 'UP') {
    throw "Backend health is not UP: $($issue65Health.status)"
}

$issue65FixturePreparationStartedAt = (Get-Date).ToUniversalTime()
$issue65FixtureStopwatch = [Diagnostics.Stopwatch]::StartNew()
$issue65Fixture = Invoke-RestMethod -Uri "$BaseUrl/loadtest/runs?runId=$FixtureRunId" -Method Post
$issue65Reset = Invoke-RestMethod -Uri "$BaseUrl/loadtest/seat-holds/reset?runId=$FixtureRunId" -Method Post
$issue65FixtureStopwatch.Stop()
if ([int]$issue65Fixture.totalSeats -ne 2000 -or
    -not [bool]$issue65Reset.invariantSatisfied -or
    [long]$issue65Reset.activeHeldSeats -ne 0 -or
    [long]$issue65Reset.holdRows -ne 0) {
    throw 'Seat-hold fixture reset did not produce an empty 2,000-seat state.'
}

$issue65StatusQuery = "SHOW GLOBAL STATUS WHERE Variable_name IN ('Innodb_deadlocks','Innodb_row_lock_current_waits','Innodb_row_lock_time','Innodb_row_lock_waits','Threads_connected','Threads_running');"
function Get-Issue65MariaDbStatus {
    $issue65DbOutput = & docker compose -f $issue65ComposeFile exec -T mariadb mariadb "-u$DatabaseUser" "-p$DatabasePassword" -N -e $issue65StatusQuery 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "MariaDB status query failed with exit code $LASTEXITCODE."
    }
    ConvertFrom-MariaDbStatus -Lines $issue65DbOutput
}

function Get-Issue65MetricSample {
    param([Parameter(Mandatory = $true)][Diagnostics.Stopwatch]$Stopwatch)
    $issue65Prometheus = Invoke-WebRequest -UseBasicParsing -Uri "$ManagementBaseUrl/actuator/prometheus" -Method Get
    $issue65Hikari = ConvertFrom-PrometheusHikari -Text $issue65Prometheus.Content
    $issue65Db = Get-Issue65MariaDbStatus
    [pscustomobject]@{
        TimestampUtc = (Get-Date).ToUniversalTime().ToString('o')
        ElapsedMilliseconds = $Stopwatch.ElapsedMilliseconds
        HikariActive = $issue65Hikari.Active
        HikariPending = $issue65Hikari.Pending
        HikariIdle = $issue65Hikari.Idle
        HikariMax = $issue65Hikari.Max
        DbRowLockCurrentWaits = $issue65Db.RowLockCurrentWaits
        DbRowLockWaits = $issue65Db.RowLockWaits
        DbRowLockTimeMs = $issue65Db.RowLockTimeMs
        DbDeadlocks = $issue65Db.Deadlocks
        DbThreadsConnected = $issue65Db.ThreadsConnected
        DbThreadsRunning = $issue65Db.ThreadsRunning
    }
}

$issue65StartedAt = (Get-Date).ToUniversalTime()
$issue65Stopwatch = [Diagnostics.Stopwatch]::StartNew()
$issue65Samples = New-Object 'Collections.Generic.List[object]'
$issue65K6Process = $null
$issue65StdoutTask = $null
$issue65StderrTask = $null

try {
    $issue65Samples.Add((Get-Issue65MetricSample -Stopwatch $issue65Stopwatch))
    $issue65K6Executable = $issue65K6Command.Source
    if ([string]::IsNullOrWhiteSpace($issue65K6Executable)) {
        $issue65K6Executable = $issue65K6Command.Path
    }
    $issue65K6Arguments = @(
        'run',
        '-e', "TEST_SCENARIO=$Scenario",
        '-e', "RATE=$Rate",
        '-e', "DURATION=$($DurationSeconds)s",
        '-e', "FIXTURE_RUN_ID=$FixtureRunId",
        '-e', "BASE_URL=$BaseUrl",
        '-e', "PRE_ALLOCATED_VUS=$PreAllocatedVus",
        '-e', "MAX_VUS=$MaxVus",
        '-e', 'TOKEN_COUNT=500',
        '-e', "ENFORCE_THRESHOLDS=$((-not $DisablePerformanceThresholds.IsPresent).ToString().ToLowerInvariant())",
        $issue65K6Script
    )
    $issue65QuotedArguments = $issue65K6Arguments | ForEach-Object { '"' + ([string]$_).Replace('"', '\"') + '"' }
    $issue65StartInfo = New-Object Diagnostics.ProcessStartInfo
    $issue65StartInfo.FileName = $issue65K6Executable
    $issue65StartInfo.Arguments = $issue65QuotedArguments -join ' '
    $issue65StartInfo.UseShellExecute = $false
    $issue65StartInfo.RedirectStandardOutput = $true
    $issue65StartInfo.RedirectStandardError = $true
    $issue65StartInfo.CreateNoWindow = $true
    $issue65K6Process = New-Object Diagnostics.Process
    $issue65K6Process.StartInfo = $issue65StartInfo
    if (-not $issue65K6Process.Start()) { throw 'Failed to start k6.' }
    $issue65StdoutTask = $issue65K6Process.StandardOutput.ReadToEndAsync()
    $issue65StderrTask = $issue65K6Process.StandardError.ReadToEndAsync()

    $issue65NextSampleAt = $issue65Stopwatch.ElapsedMilliseconds + $SampleIntervalMilliseconds
    while (-not $issue65K6Process.HasExited) {
        $issue65WaitMilliseconds = $issue65NextSampleAt - $issue65Stopwatch.ElapsedMilliseconds
        if ($issue65WaitMilliseconds -gt 0) { Start-Sleep -Milliseconds $issue65WaitMilliseconds }
        $issue65K6Process.Refresh()
        if ($issue65K6Process.HasExited) { break }
        $issue65Samples.Add((Get-Issue65MetricSample -Stopwatch $issue65Stopwatch))
        do { $issue65NextSampleAt += $SampleIntervalMilliseconds } while ($issue65NextSampleAt -le $issue65Stopwatch.ElapsedMilliseconds)
    }
    $issue65K6Process.WaitForExit()
    $issue65ExitCode = $issue65K6Process.ExitCode
    $issue65Stdout = $issue65StdoutTask.GetAwaiter().GetResult()
    $issue65Stderr = $issue65StderrTask.GetAwaiter().GetResult()
    Set-Content -LiteralPath $issue65StdoutPath -Value $issue65Stdout -Encoding UTF8
    Set-Content -LiteralPath $issue65StderrPath -Value $issue65Stderr -Encoding UTF8
    $issue65Samples.Add((Get-Issue65MetricSample -Stopwatch $issue65Stopwatch))
    $issue65Stopwatch.Stop()
    if ($issue65ExitCode -ne 0) { throw "k6 failed with exit code $issue65ExitCode." }

    $issue65CombinedOutput = ($issue65Stdout + "`n" + $issue65Stderr).Replace('\"', '"')
    $issue65RawResult = ConvertFrom-SeatHoldK6Result -Text $issue65CombinedOutput
    $issue65ThresholdsEnforced = -not $DisablePerformanceThresholds.IsPresent
    Assert-SeatHoldRunIdentity -Result $issue65RawResult -Scenario $Scenario -Rate $Rate -DurationSeconds $DurationSeconds -ThresholdsEnforced $issue65ThresholdsEnforced | Out-Null
    $issue65K6Summary = New-SeatHoldRunSummary -Result $issue65RawResult -DurationSeconds $DurationSeconds
    $issue65Snapshot = ConvertFrom-SeatHoldFinalSnapshot -Text $issue65CombinedOutput
    Assert-SeatHoldFinalState -Summary $issue65K6Summary -Snapshot $issue65Snapshot | Out-Null
    $issue65MetricSummary = New-ContentionMetricsSummary -Samples $issue65Samples.ToArray()
    $issue65Samples | Export-Csv -LiteralPath $issue65SamplesPath -NoTypeInformation -Encoding UTF8

    $issue65Summary = [ordered]@{
        SchemaVersion = 1
        ValidMeasurement = $true
        Run = [ordered]@{
            Id = $RunId
            FixtureRunId = $FixtureRunId
            Scenario = $Scenario
            RatePerSecond = $Rate
            DurationSeconds = $DurationSeconds
            SampleIntervalMilliseconds = $SampleIntervalMilliseconds
            StartedAtUtc = $issue65StartedAt.ToString('o')
            EndedAtUtc = (Get-Date).ToUniversalTime().ToString('o')
        }
        FixturePreparation = [ordered]@{
            StartedAtUtc = $issue65FixturePreparationStartedAt.ToString('o')
            DurationMilliseconds = $issue65FixtureStopwatch.ElapsedMilliseconds
            ExcludedFromMetricSamples = $true
            ReusedAndReset = $true
            TotalSeats = [int]$issue65Fixture.totalSeats
        }
        K6 = [ordered]@{
            ExitCode = $issue65ExitCode
            StateInvariantSatisfied = [bool]$issue65Snapshot.invariantSatisfied
            Result = $issue65K6Summary
            FinalSnapshot = $issue65Snapshot
            StdoutFile = [IO.Path]::GetFileName($issue65StdoutPath)
            StderrFile = [IO.Path]::GetFileName($issue65StderrPath)
        }
        Metrics = $issue65MetricSummary
        SamplesFile = [IO.Path]::GetFileName($issue65SamplesPath)
    }
    $issue65Summary | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $issue65SummaryPath -Encoding UTF8
    Write-Output "VALID_SEAT_HOLD_MEASUREMENT runId=$RunId scenario=$Scenario samples=$($issue65MetricSummary.SampleCount)"
    Write-Output "SUMMARY_PATH $issue65SummaryPath"
} catch {
    $issue65Stopwatch.Stop()
    if ($null -ne $issue65K6Process -and -not $issue65K6Process.HasExited) {
        $issue65K6Process.Kill()
        $issue65K6Process.WaitForExit()
    }
    if ($null -ne $issue65StdoutTask -and $issue65StdoutTask.IsCompleted) {
        Set-Content -LiteralPath $issue65StdoutPath -Value $issue65StdoutTask.GetAwaiter().GetResult() -Encoding UTF8
    }
    if ($null -ne $issue65StderrTask -and $issue65StderrTask.IsCompleted) {
        Set-Content -LiteralPath $issue65StderrPath -Value $issue65StderrTask.GetAwaiter().GetResult() -Encoding UTF8
    }
    [ordered]@{
        SchemaVersion = 1
        ValidMeasurement = $false
        RunId = $RunId
        FixtureRunId = $FixtureRunId
        Scenario = $Scenario
        FailedAtUtc = (Get-Date).ToUniversalTime().ToString('o')
        Error = $_.Exception.Message
    } | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $issue65FailurePath -Encoding UTF8
    throw
}
