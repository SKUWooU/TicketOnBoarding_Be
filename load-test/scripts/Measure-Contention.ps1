[CmdletBinding()]
param(
    [ValidateSet('distributed', 'hot-section', 'hot-seat', 'idempotent-retry')]
    [string]$Scenario = 'distributed',

    [ValidateRange(1, 10000)]
    [int]$Rate = 5,

    [ValidateRange(1, 3600)]
    [int]$DurationSeconds = 10,

    [ValidateRange(250, 10000)]
    [int]$SampleIntervalMilliseconds = 1000,

    [ValidateRange(1, 500)]
    [int]$PreAllocatedVus = 20,

    [ValidateRange(1, 500)]
    [int]$MaxVus = 100,

    [string]$RunId = '',
    [string]$BaseUrl = 'http://127.0.0.1:18080',
    [string]$ManagementBaseUrl = 'http://127.0.0.1:18081',
    [string]$OutputDirectory = '',
    [string]$DatabaseUser = 'onticket',
    [string]$DatabasePassword = 'onticket',
    [switch]$DisablePerformanceThresholds
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$issue51ScriptDirectory = $PSScriptRoot
$issue51RepositoryRoot = (Resolve-Path (Join-Path $issue51ScriptDirectory '..\..')).Path
$issue51ModulePath = Join-Path $issue51ScriptDirectory 'ContentionMetrics.psm1'
$issue51K6Script = Join-Path $issue51RepositoryRoot 'load-test\k6\reservation-contention.js'
$issue51ComposeFile = Join-Path $issue51RepositoryRoot 'compose.yml'
Import-Module $issue51ModulePath -Force

if ($PreAllocatedVus -gt $MaxVus) {
    throw 'PreAllocatedVus must not exceed MaxVus.'
}

if ([string]::IsNullOrWhiteSpace($RunId)) {
    $RunId = 'obs-' + (Get-Date).ToUniversalTime().ToString('yyyyMMddHHmmss')
}
$RunId = Assert-ContentionRunId -RunId $RunId

if ([string]::IsNullOrWhiteSpace($OutputDirectory)) {
    $OutputDirectory = Join-Path $issue51RepositoryRoot 'load-test\results'
}
$issue51ResolvedOutputDirectory = [IO.Path]::GetFullPath($OutputDirectory)
$issue51ExpectedOutputRoot = [IO.Path]::GetFullPath((Join-Path $issue51RepositoryRoot 'load-test\results'))
$issue51ExpectedOutputPrefix = $issue51ExpectedOutputRoot.TrimEnd([IO.Path]::DirectorySeparatorChar, [IO.Path]::AltDirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
$issue51OutputIsRoot = $issue51ResolvedOutputDirectory.Equals($issue51ExpectedOutputRoot, [StringComparison]::OrdinalIgnoreCase)
$issue51OutputIsChild = $issue51ResolvedOutputDirectory.StartsWith($issue51ExpectedOutputPrefix, [StringComparison]::OrdinalIgnoreCase)
if (-not $issue51OutputIsRoot -and -not $issue51OutputIsChild) {
    throw "OutputDirectory must stay under $issue51ExpectedOutputRoot"
}
New-Item -ItemType Directory -Path $issue51ResolvedOutputDirectory -Force | Out-Null

$issue51SamplesPath = Join-Path $issue51ResolvedOutputDirectory "$RunId-metrics.csv"
$issue51SummaryPath = Join-Path $issue51ResolvedOutputDirectory "$RunId-summary.json"
$issue51StdoutPath = Join-Path $issue51ResolvedOutputDirectory "$RunId-k6.stdout.log"
$issue51StderrPath = Join-Path $issue51ResolvedOutputDirectory "$RunId-k6.stderr.log"
$issue51FailurePath = Join-Path $issue51ResolvedOutputDirectory "$RunId-failure.json"
foreach ($issue51OutputPath in @($issue51SamplesPath, $issue51SummaryPath, $issue51StdoutPath, $issue51StderrPath, $issue51FailurePath)) {
    if (Test-Path -LiteralPath $issue51OutputPath) {
        throw "Refusing to overwrite an existing measurement result: $issue51OutputPath"
    }
}

$issue51K6Command = Get-Command k6 -ErrorAction Stop
Get-Command docker -ErrorAction Stop | Out-Null
$issue51Health = Invoke-RestMethod -Uri "$ManagementBaseUrl/actuator/health" -Method Get
if ($issue51Health.status -ne 'UP') {
    throw "Backend health is not UP: $($issue51Health.status)"
}

$issue51StatusQuery = "SHOW GLOBAL STATUS WHERE Variable_name IN ('Innodb_deadlocks','Innodb_row_lock_current_waits','Innodb_row_lock_time','Innodb_row_lock_waits','Threads_connected','Threads_running');"

function Get-Issue51MariaDbStatus {
    $issue51DbOutput = & docker compose -f $issue51ComposeFile exec -T mariadb mariadb "-u$DatabaseUser" "-p$DatabasePassword" -N -e $issue51StatusQuery 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "MariaDB status query failed with exit code $LASTEXITCODE."
    }
    ConvertFrom-MariaDbStatus -Lines $issue51DbOutput
}

function Get-Issue51MetricSample {
    param(
        [Parameter(Mandatory = $true)]
        [Diagnostics.Stopwatch]$Stopwatch
    )

    $issue51PrometheusResponse = Invoke-WebRequest -UseBasicParsing -Uri "$ManagementBaseUrl/actuator/prometheus" -Method Get
    $issue51Hikari = ConvertFrom-PrometheusHikari -Text $issue51PrometheusResponse.Content
    $issue51Db = Get-Issue51MariaDbStatus

    [pscustomobject]@{
        TimestampUtc             = (Get-Date).ToUniversalTime().ToString('o')
        ElapsedMilliseconds      = $Stopwatch.ElapsedMilliseconds
        HikariActive             = $issue51Hikari.Active
        HikariPending            = $issue51Hikari.Pending
        HikariIdle               = $issue51Hikari.Idle
        HikariMax                = $issue51Hikari.Max
        DbRowLockCurrentWaits    = $issue51Db.RowLockCurrentWaits
        DbRowLockWaits           = $issue51Db.RowLockWaits
        DbRowLockTimeMs          = $issue51Db.RowLockTimeMs
        DbDeadlocks              = $issue51Db.Deadlocks
        DbThreadsConnected       = $issue51Db.ThreadsConnected
        DbThreadsRunning         = $issue51Db.ThreadsRunning
    }
}

$issue53FixturePreparationStartedAt = (Get-Date).ToUniversalTime()
$issue53FixturePreparationStopwatch = [Diagnostics.Stopwatch]::StartNew()
$issue53Fixture = Invoke-RestMethod -Uri "$BaseUrl/loadtest/runs?runId=$RunId" -Method Post
$issue53FixturePreparationStopwatch.Stop()
if ([int]$issue53Fixture.totalSeats -le 0) {
    throw 'Prepared load-test fixture must contain at least one seat.'
}

$issue51StartedAt = (Get-Date).ToUniversalTime()
$issue51Stopwatch = [Diagnostics.Stopwatch]::StartNew()
$issue51Samples = New-Object 'Collections.Generic.List[object]'
$issue51K6Process = $null
$issue51StdoutTask = $null
$issue51StderrTask = $null

try {
    $issue51Samples.Add((Get-Issue51MetricSample -Stopwatch $issue51Stopwatch))

    $issue51K6Executable = $issue51K6Command.Source
    if ([string]::IsNullOrWhiteSpace($issue51K6Executable)) {
        $issue51K6Executable = $issue51K6Command.Path
    }
    $issue51K6Arguments = @(
        'run',
        '-e', "TEST_SCENARIO=$Scenario",
        '-e', "RATE=$Rate",
        '-e', "DURATION=$($DurationSeconds)s",
        '-e', "RUN_ID=$RunId",
        '-e', "BASE_URL=$BaseUrl",
        '-e', "PRE_ALLOCATED_VUS=$PreAllocatedVus",
        '-e', "MAX_VUS=$MaxVus",
        '-e', 'FIXTURE_PREPARED=true',
        '-e', "ENFORCE_THRESHOLDS=$((-not $DisablePerformanceThresholds.IsPresent).ToString().ToLowerInvariant())",
        $issue51K6Script
    )
    $issue51QuotedArguments = $issue51K6Arguments | ForEach-Object {
        '"' + ([string]$_).Replace('"', '\"') + '"'
    }
    $issue51StartInfo = New-Object Diagnostics.ProcessStartInfo
    $issue51StartInfo.FileName = $issue51K6Executable
    $issue51StartInfo.Arguments = $issue51QuotedArguments -join ' '
    $issue51StartInfo.UseShellExecute = $false
    $issue51StartInfo.RedirectStandardOutput = $true
    $issue51StartInfo.RedirectStandardError = $true
    $issue51StartInfo.CreateNoWindow = $true
    $issue51K6Process = New-Object Diagnostics.Process
    $issue51K6Process.StartInfo = $issue51StartInfo
    if (-not $issue51K6Process.Start()) {
        throw 'Failed to start k6.'
    }
    $issue51StdoutTask = $issue51K6Process.StandardOutput.ReadToEndAsync()
    $issue51StderrTask = $issue51K6Process.StandardError.ReadToEndAsync()

    $issue51NextSampleAt = $issue51Stopwatch.ElapsedMilliseconds + $SampleIntervalMilliseconds
    while (-not $issue51K6Process.HasExited) {
        $issue51WaitMilliseconds = $issue51NextSampleAt - $issue51Stopwatch.ElapsedMilliseconds
        if ($issue51WaitMilliseconds -gt 0) {
            Start-Sleep -Milliseconds $issue51WaitMilliseconds
        }
        $issue51K6Process.Refresh()
        if ($issue51K6Process.HasExited) {
            break
        }
        $issue51Samples.Add((Get-Issue51MetricSample -Stopwatch $issue51Stopwatch))
        do {
            $issue51NextSampleAt += $SampleIntervalMilliseconds
        } while ($issue51NextSampleAt -le $issue51Stopwatch.ElapsedMilliseconds)
    }
    $issue51K6Process.WaitForExit()
    $issue51K6ExitCode = $issue51K6Process.ExitCode
    $issue51K6Output = $issue51StdoutTask.GetAwaiter().GetResult()
    $issue51K6ErrorOutput = $issue51StderrTask.GetAwaiter().GetResult()
    Set-Content -LiteralPath $issue51StdoutPath -Value $issue51K6Output -Encoding UTF8
    Set-Content -LiteralPath $issue51StderrPath -Value $issue51K6ErrorOutput -Encoding UTF8
    $issue51Samples.Add((Get-Issue51MetricSample -Stopwatch $issue51Stopwatch))
    $issue51Stopwatch.Stop()

    $issue51CombinedK6Output = $issue51K6Output + "`n" + $issue51K6ErrorOutput
    $issue51NormalizedK6Output = $issue51CombinedK6Output.Replace('\"', '"')
    if ($issue51K6ExitCode -ne 0) {
        throw "k6 failed with exit code $issue51K6ExitCode."
    }
    $issue53K6Result = ConvertFrom-K6ContentionResult -Text $issue51NormalizedK6Output
    $issue53ExpectedThresholdsEnforced = -not $DisablePerformanceThresholds.IsPresent
    Assert-K6ContentionRunIdentity `
        -Result $issue53K6Result `
        -Scenario $Scenario `
        -Rate $Rate `
        -DurationSeconds $DurationSeconds `
        -ThresholdsEnforced $issue53ExpectedThresholdsEnforced | Out-Null
    $issue53K6Summary = New-K6ContentionRunSummary -Result $issue53K6Result -DurationSeconds $DurationSeconds
    $issue53FinalSnapshot = ConvertFrom-K6FinalSnapshot -Text $issue51NormalizedK6Output
    $issue51InvariantSatisfied = [bool]$issue53FinalSnapshot.invariantSatisfied
    if (-not $issue51InvariantSatisfied) {
        throw 'k6 output does not contain a successful final inventory invariant.'
    }

    $issue51MetricSummary = New-ContentionMetricsSummary -Samples $issue51Samples.ToArray()
    $issue51Samples | Export-Csv -LiteralPath $issue51SamplesPath -NoTypeInformation -Encoding UTF8
    $issue51EndedAt = (Get-Date).ToUniversalTime()
    $issue51Summary = [ordered]@{
        SchemaVersion = 1
        ValidMeasurement = $true
        Run = [ordered]@{
            Id = $RunId
            Scenario = $Scenario
            RatePerSecond = $Rate
            DurationSeconds = $DurationSeconds
            SampleIntervalMilliseconds = $SampleIntervalMilliseconds
            StartedAtUtc = $issue51StartedAt.ToString('o')
            EndedAtUtc = $issue51EndedAt.ToString('o')
        }
        FixturePreparation = [ordered]@{
            StartedAtUtc = $issue53FixturePreparationStartedAt.ToString('o')
            DurationMilliseconds = $issue53FixturePreparationStopwatch.ElapsedMilliseconds
            ExcludedFromMetricSamples = $true
            TotalSeats = [int]$issue53Fixture.totalSeats
        }
        K6 = [ordered]@{
            ExitCode = $issue51K6ExitCode
            InventoryInvariantSatisfied = $issue51InvariantSatisfied
            Result = $issue53K6Summary
            FinalSnapshot = $issue53FinalSnapshot
            StdoutFile = [IO.Path]::GetFileName($issue51StdoutPath)
            StderrFile = [IO.Path]::GetFileName($issue51StderrPath)
        }
        Metrics = $issue51MetricSummary
        SamplesFile = [IO.Path]::GetFileName($issue51SamplesPath)
    }
    $issue51Summary | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $issue51SummaryPath -Encoding UTF8

    Write-Output "VALID_MEASUREMENT runId=$RunId scenario=$Scenario samples=$($issue51MetricSummary.SampleCount)"
    Write-Output "SUMMARY_PATH $issue51SummaryPath"
} catch {
    $issue51Stopwatch.Stop()
    if ($null -ne $issue51K6Process -and -not $issue51K6Process.HasExited) {
        $issue51K6Process.Kill()
        $issue51K6Process.WaitForExit()
    }
    if ($null -ne $issue51StdoutTask -and $issue51StdoutTask.IsCompleted) {
        Set-Content -LiteralPath $issue51StdoutPath -Value $issue51StdoutTask.GetAwaiter().GetResult() -Encoding UTF8
    }
    if ($null -ne $issue51StderrTask -and $issue51StderrTask.IsCompleted) {
        Set-Content -LiteralPath $issue51StderrPath -Value $issue51StderrTask.GetAwaiter().GetResult() -Encoding UTF8
    }
    $issue51Failure = [ordered]@{
        SchemaVersion = 1
        ValidMeasurement = $false
        RunId = $RunId
        Scenario = $Scenario
        FailedAtUtc = (Get-Date).ToUniversalTime().ToString('o')
        Error = $_.Exception.Message
    }
    $issue51Failure | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $issue51FailurePath -Encoding UTF8
    throw
}
