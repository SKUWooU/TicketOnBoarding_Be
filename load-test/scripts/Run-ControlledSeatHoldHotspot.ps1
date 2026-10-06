[CmdletBinding()]
param(
    [ValidatePattern('^[A-Za-z0-9-]{1,16}$')][string]$BatchId = 'holdhot170',
    [ValidateRange(1, 2)][int]$Repeats = 2,
    [ValidateRange(1, 10)][int]$DurationSeconds = 10,
    [ValidateRange(1, 50)][int]$Rate = 50,
    [ValidatePattern('^ticketon-controlled170(-[a-z0-9]{1,12})?$')][string]$ComposeProject = 'ticketon-controlled170',
    [string]$BaseUrl = 'http://127.0.0.1:18080',
    [string]$ManagementBaseUrl = 'http://127.0.0.1:18081'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$compose = Join-Path $root 'compose.yml'
$measure = Join-Path $PSScriptRoot 'Measure-SeatHoldContention.ps1'
Import-Module (Join-Path $PSScriptRoot 'ControlledHotspot.psm1') -Force
$project = $ComposeProject
if ($BaseUrl -ne 'http://127.0.0.1:18080' -or $ManagementBaseUrl -ne 'http://127.0.0.1:18081') {
    throw 'Controlled load must target only the fixed local loopback endpoints.'
}
if ($env:COMPOSE_PROJECT_NAME -ne $project) {
    throw "COMPOSE_PROJECT_NAME must be $project."
}
$containerId = @(& docker compose -p $project -f $compose ps -q mariadb 2>&1)
if ($LASTEXITCODE -ne 0 -or $containerId.Count -ne 1 -or [string]::IsNullOrWhiteSpace($containerId[0])) {
    throw "The dedicated $project MariaDB container must already be running."
}
$containerLabels = [string](& docker inspect -f '{{json .Config.Labels}}' $containerId[0] 2>&1)
if ($LASTEXITCODE -ne 0 -or [string](($containerLabels | ConvertFrom-Json).'com.docker.compose.project') -ne $project) {
    throw 'The MariaDB container does not belong to the dedicated project.'
}
$seatRows = [string](& docker compose -p $project -f $compose exec -T mariadb mariadb -uroot -ponticket-root -N -B onticket_local -e 'SELECT COUNT(*) FROM seat;' 2>&1)
if ($LASTEXITCODE -ne 0 -or $seatRows.Trim() -ne '0') {
    throw 'The dedicated database must have no seat rows before this batch; no cleanup is performed.'
}

$output = Join-Path $root "load-test\results\$BatchId"
if (Test-Path -LiteralPath $output) { throw "Refusing to overwrite an existing batch: $output" }
New-Item -ItemType Directory -Path $output | Out-Null
$plan = @(New-ControlledHotspotPlan -Repeats $Repeats -DurationSeconds $DurationSeconds -Rate $Rate)
$records = New-Object 'Collections.Generic.List[object]'
$manifest = Join-Path $output 'controlled-seat-hold-manifest.json'
$fixtureRunId = "$BatchId-fixture"

try {
    foreach ($stage in $plan) {
        $runId = "$BatchId-r$($stage.Repeat)-h$($stage.HotSeatCount)"
        & $measure -Scenario weighted-hotspot -RunId $runId -FixtureRunId $fixtureRunId `
            -Rate $Rate -DurationSeconds $DurationSeconds `
            -HotSeatCount $stage.HotSeatCount -HotRequestPercent 70 -SelectionSeed 17 `
            -PreAllocatedVus 200 -MaxVus 200 `
            -BaseUrl $BaseUrl -ManagementBaseUrl $ManagementBaseUrl `
            -OutputDirectory $output -DisablePerformanceThresholds
        $summaryPath = Join-Path $output "$runId-summary.json"
        $summary = Get-Content -Raw -Encoding UTF8 -LiteralPath $summaryPath | ConvertFrom-Json
        if (-not [bool]$summary.ValidMeasurement -or
            -not [bool]$summary.K6.StateInvariantSatisfied -or
            [long]$summary.K6.Result.DroppedIterations -ne 0 -or
            [long]$summary.K6.Result.UnexpectedNonSuccessful -ne 0 -or
            [long]$summary.Metrics.Deltas.DbDeadlocks -ne 0 -or
            [long]$summary.K6.Result.HoldSuccess -le 0 -or
            [long]$summary.K6.Result.ExpectedContention -le 0 -or
            [long]$summary.K6.Result.Iterations -lt ([double]$Rate * $DurationSeconds * 0.99) -or
            [double]$summary.K6.Result.ScheduledIterationAttainmentRate -lt 0.99) {
            throw "Controlled seat-hold run failed its comparison gate: $runId"
        }
        $records.Add([pscustomobject]@{
            Sequence = $stage.Sequence
            Repeat = $stage.Repeat
            HotSeatCount = $stage.HotSeatCount
            RunId = $runId
            SummaryFile = [IO.Path]::GetFileName($summaryPath)
            Iterations = $summary.K6.Result.Iterations
            HoldSuccess = $summary.K6.Result.HoldSuccess
            ExpectedContention = $summary.K6.Result.ExpectedContention
            ActiveHeldSeats = $summary.K6.FinalSnapshot.activeHeldSeats
            HoldP95Ms = $summary.K6.Result.HoldDurationMs.P95
            HikariPendingPeak = $summary.Metrics.Peaks.HikariPending
            DbRowLockWaits = $summary.Metrics.Deltas.DbRowLockWaits
        })
        Write-Output "CONTROLLED_SEAT_HOLD_RUN_COMPLETE $runId"
    }
} finally {
    [ordered]@{
        SchemaVersion = 1
        BatchId = $BatchId
        ComposeProject = $project
        FixtureRunId = $fixtureRunId
        FixtureResetBeforeEachRun = $true
        Complete = $records.Count -eq $plan.Count
        Records = $records.ToArray()
    } | ConvertTo-Json -Depth 8 | Set-Content -Encoding UTF8 -LiteralPath $manifest
}
