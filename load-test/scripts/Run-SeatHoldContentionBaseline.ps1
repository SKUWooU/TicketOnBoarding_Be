[CmdletBinding()]
param(
    [string]$BatchId = '',
    [ValidateRange(1, 3600)][int]$DurationSeconds = 10,
    [ValidateRange(1, 10)][int]$Repeats = 3,
    [ValidateRange(250, 10000)][int]$SampleIntervalMilliseconds = 1000,
    [ValidateRange(1, 500)][int]$PreAllocatedVus = 250,
    [ValidateRange(1, 500)][int]$MaxVus = 250,
    [string]$BaseUrl = 'http://127.0.0.1:18080',
    [string]$ManagementBaseUrl = 'http://127.0.0.1:18081',
    [string]$DatabaseUser = 'onticket',
    [string]$DatabasePassword = 'onticket'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$issue65ScriptDirectory = $PSScriptRoot
$issue65RepositoryRoot = (Resolve-Path (Join-Path $issue65ScriptDirectory '..\..')).Path
Import-Module (Join-Path $issue65ScriptDirectory 'SeatHoldContention.psm1') -Force
$issue65MeasureScript = Join-Path $issue65ScriptDirectory 'Measure-SeatHoldContention.ps1'

if ($PreAllocatedVus -gt $MaxVus) { throw 'PreAllocatedVus must not exceed MaxVus.' }
if ([string]::IsNullOrWhiteSpace($BatchId)) {
    $BatchId = 'h65-' + (Get-Date).ToUniversalTime().ToString('MMddHHmmss')
}
if ($BatchId -notmatch '^[A-Za-z0-9-]{1,16}$') {
    throw 'BatchId must contain 1-16 letters, numbers, or hyphens.'
}

$issue65FixtureRunId = "$BatchId-fixture"
$issue65OutputDirectory = Join-Path $issue65RepositoryRoot "load-test\results\$BatchId"
$issue65ManifestPath = Join-Path $issue65OutputDirectory 'seat-hold-baseline-manifest.json'
$issue65AggregatePath = Join-Path $issue65OutputDirectory 'seat-hold-baseline-aggregate.json'
if (Test-Path -LiteralPath $issue65OutputDirectory) {
    throw "Refusing to reuse an existing seat-hold baseline batch: $issue65OutputDirectory"
}
New-Item -ItemType Directory -Path $issue65OutputDirectory -Force | Out-Null

$issue65Plan = @(New-SeatHoldBaselinePlan -DurationSeconds $DurationSeconds -Repeats $Repeats -TotalSeats 2000)
$issue65Records = New-Object 'Collections.Generic.List[object]'
$issue65Skipped = New-Object 'Collections.Generic.List[object]'
$issue65StoppedScenarios = @{}
$issue65ScenarioCodes = @{ 'distributed' = 'dis'; 'hot-section' = 'sec'; 'hot-seat' = 'seat' }

function Write-Issue65BatchFiles {
    [ordered]@{
        SchemaVersion = 1
        BatchId = $BatchId
        FixtureRunId = $issue65FixtureRunId
        DurationSeconds = $DurationSeconds
        Repeats = $Repeats
        PreAllocatedVus = $PreAllocatedVus
        MaxVus = $MaxVus
        WarmupExcluded = $true
        FixtureReusedAndReset = $true
        Records = $issue65Records.ToArray()
        Skipped = $issue65Skipped.ToArray()
        StoppedScenarios = $issue65StoppedScenarios
    } | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $issue65ManifestPath -Encoding UTF8

    $issue65Measured = @($issue65Records.ToArray() | Where-Object { -not $_.Warmup })
    if ($issue65Measured.Count -gt 0) {
        New-SeatHoldBaselineAggregate -Records $issue65Measured |
            ConvertTo-Json -Depth 12 |
            Set-Content -LiteralPath $issue65AggregatePath -Encoding UTF8
    }
}

try {
    foreach ($issue65Stage in $issue65Plan) {
        if (-not $issue65Stage.Warmup -and $issue65StoppedScenarios.ContainsKey($issue65Stage.Scenario)) {
            $issue65Skipped.Add([pscustomobject]@{
                Scenario = $issue65Stage.Scenario
                Rate = $issue65Stage.Rate
                Repeat = $issue65Stage.Repeat
                Reason = $issue65StoppedScenarios[$issue65Stage.Scenario]
            })
            continue
        }

        $issue65RepeatLabel = if ($issue65Stage.Warmup) { 'warm' } else { "r$($issue65Stage.Repeat)" }
        $issue65RunId = "$BatchId-$($issue65ScenarioCodes[$issue65Stage.Scenario])-$($issue65Stage.Rate)-$issue65RepeatLabel"
        Write-Output "SEAT_HOLD_BASELINE_RUN_START sequence=$($issue65Stage.Sequence) runId=$issue65RunId scenario=$($issue65Stage.Scenario) rate=$($issue65Stage.Rate) warmup=$($issue65Stage.Warmup)"

        & $issue65MeasureScript `
            -Scenario $issue65Stage.Scenario `
            -Rate $issue65Stage.Rate `
            -DurationSeconds $DurationSeconds `
            -SampleIntervalMilliseconds $SampleIntervalMilliseconds `
            -PreAllocatedVus $PreAllocatedVus `
            -MaxVus $MaxVus `
            -RunId $issue65RunId `
            -FixtureRunId $issue65FixtureRunId `
            -BaseUrl $BaseUrl `
            -ManagementBaseUrl $ManagementBaseUrl `
            -OutputDirectory $issue65OutputDirectory `
            -DatabaseUser $DatabaseUser `
            -DatabasePassword $DatabasePassword `
            -DisablePerformanceThresholds

        $issue65SummaryPath = Join-Path $issue65OutputDirectory "$issue65RunId-summary.json"
        $issue65Summary = Get-Content -Raw -Encoding UTF8 -LiteralPath $issue65SummaryPath | ConvertFrom-Json
        if (-not [bool]$issue65Summary.ValidMeasurement -or -not [bool]$issue65Summary.K6.StateInvariantSatisfied) {
            throw "Seat-hold baseline run is not valid: $issue65RunId"
        }
        $issue65Records.Add([pscustomobject]@{
            RunId = $issue65RunId
            Scenario = $issue65Stage.Scenario
            Rate = $issue65Stage.Rate
            Repeat = $issue65Stage.Repeat
            Warmup = $issue65Stage.Warmup
            SummaryFile = [IO.Path]::GetFileName($issue65SummaryPath)
            Summary = $issue65Summary
        })
        Write-Issue65BatchFiles
        Write-Output "SEAT_HOLD_BASELINE_RUN_COMPLETE runId=$issue65RunId p95=$($issue65Summary.K6.Result.HoldDurationMs.P95) dropped=$($issue65Summary.K6.Result.DroppedIterations) held=$($issue65Summary.K6.FinalSnapshot.activeHeldSeats)"

        if (-not $issue65Stage.Warmup -and $issue65Stage.Repeat -eq $Repeats) {
            $issue65StageRecords = @(
                $issue65Records.ToArray() |
                    Where-Object { -not $_.Warmup -and $_.Scenario -eq $issue65Stage.Scenario -and $_.Rate -eq $issue65Stage.Rate }
            )
            $issue65ReasonCounts = @{}
            foreach ($issue65Record in $issue65StageRecords) {
                foreach ($issue65Reason in @(Get-SeatHoldBaselineStopReasons -Summary $issue65Record.Summary)) {
                    if (-not $issue65ReasonCounts.ContainsKey($issue65Reason)) { $issue65ReasonCounts[$issue65Reason] = 0 }
                    $issue65ReasonCounts[$issue65Reason] += 1
                }
            }
            $issue65RepeatedReasons = @(
                $issue65ReasonCounts.Keys |
                    Where-Object { $issue65ReasonCounts[$_] -ge 2 } |
                    Sort-Object
            )
            if ($issue65RepeatedReasons.Count -gt 0) {
                $issue65StoppedScenarios[$issue65Stage.Scenario] = $issue65RepeatedReasons -join ','
                Write-Output "SEAT_HOLD_BASELINE_SCENARIO_STOP scenario=$($issue65Stage.Scenario) rate=$($issue65Stage.Rate) reasons=$($issue65StoppedScenarios[$issue65Stage.Scenario])"
            }
        }
    }
    Write-Issue65BatchFiles
    Write-Output "SEAT_HOLD_BASELINE_BATCH_COMPLETE batchId=$BatchId records=$($issue65Records.Count) skipped=$($issue65Skipped.Count)"
    Write-Output "SEAT_HOLD_BASELINE_AGGREGATE_PATH $issue65AggregatePath"
} catch {
    Write-Issue65BatchFiles
    throw
}
