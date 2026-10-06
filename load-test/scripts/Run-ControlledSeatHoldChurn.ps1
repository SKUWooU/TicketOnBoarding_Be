[CmdletBinding()]
param(
    [ValidatePattern('^[A-Za-z0-9-]{1,16}$')][string]$BatchId = 'holdchurn172',
    [ValidateRange(1, 2)][int]$Repeats = 2,
    [ValidateRange(1, 10)][int]$DurationSeconds = 10,
    [ValidateRange(1, 100)][int]$Rate = 50,
    [ValidateRange(0, 1000)][int]$HoldDwellMilliseconds = 100,
    [ValidatePattern('^ticketon-controlled172(-[a-z0-9]{1,12})?$')][string]$ComposeProject = 'ticketon-controlled172',
    [string]$BaseUrl = 'http://127.0.0.1:18080',
    [string]$ManagementBaseUrl = 'http://127.0.0.1:18081'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$compose = Join-Path $root 'compose.yml'
$measure = Join-Path $PSScriptRoot 'Measure-SeatHoldContention.ps1'
Import-Module (Join-Path $PSScriptRoot 'ControlledHotspot.psm1') -Force
if ($BaseUrl -ne 'http://127.0.0.1:18080' -or $ManagementBaseUrl -ne 'http://127.0.0.1:18081') {
    throw 'Controlled load must target only the fixed local loopback endpoints.'
}
if ($env:COMPOSE_PROJECT_NAME -ne $ComposeProject -or $env:ONTICKET_DB_PORT -ne '3309') {
    throw 'COMPOSE_PROJECT_NAME and ONTICKET_DB_PORT must identify the dedicated controlled172 DB on port 3309.'
}
$containerId = @(& docker compose -p $ComposeProject -f $compose ps -q mariadb 2>&1)
if ($LASTEXITCODE -ne 0 -or $containerId.Count -ne 1 -or [string]::IsNullOrWhiteSpace($containerId[0])) {
    throw 'The dedicated controlled172 MariaDB container must already be running.'
}
$labels = [string](& docker inspect -f '{{json .Config.Labels}}' $containerId[0] 2>&1)
if ($LASTEXITCODE -ne 0 -or [string](($labels | ConvertFrom-Json).'com.docker.compose.project') -ne $ComposeProject) {
    throw 'The MariaDB container does not belong to the dedicated project.'
}
$emptyTables = @('concert', 'concert_detail', 'concert_time', 'place', 'refresh_token',
    'reservation', 'reservation_booking', 'reservation_checkout', 'reservation_checkout_request_key',
    'reservation_checkout_seat_assignment', 'reservation_payment', 'review', 'seat', 'site_user',
    'sty_urls', 'sty_urls_sty_url')
$emptyCheckSql = ($emptyTables | ForEach-Object { "SELECT COUNT(*) FROM $_" }) -join ' UNION ALL '
$rowCounts = @(& docker compose -p $ComposeProject -f $compose exec -T mariadb mariadb -uroot -ponticket-root -N -B onticket_local -e "$emptyCheckSql;" 2>&1)
if ($LASTEXITCODE -ne 0 -or $rowCounts.Count -ne $emptyTables.Count -or
    @($rowCounts | Where-Object { $_.Trim() -ne '0' }).Count -ne 0) {
    throw 'The dedicated database must have no application data before this batch; no cleanup is performed.'
}

$output = Join-Path $root "load-test\results\$BatchId"
if (Test-Path -LiteralPath $output) { throw "Refusing to overwrite an existing batch: $output" }
New-Item -ItemType Directory -Path $output | Out-Null
$plan = @(New-ControlledHotspotPlan -Repeats $Repeats -DurationSeconds $DurationSeconds -Rate $Rate)
$records = New-Object 'Collections.Generic.List[object]'
$manifest = Join-Path $output 'controlled-seat-hold-churn-manifest.json'
$fixtureRunId = "$BatchId-fixture"

try {
    foreach ($stage in $plan) {
        $runId = "$BatchId-r$($stage.Repeat)-h$($stage.HotSeatCount)"
        & $measure -Scenario weighted-hotspot-churn -RunId $runId -FixtureRunId $fixtureRunId `
            -Rate $Rate -DurationSeconds $DurationSeconds `
            -HotSeatCount $stage.HotSeatCount -HotRequestPercent 70 -SelectionSeed 17 `
            -HoldDwellMilliseconds $HoldDwellMilliseconds -PreAllocatedVus 200 -MaxVus 200 `
            -BaseUrl $BaseUrl -ManagementBaseUrl $ManagementBaseUrl `
            -OutputDirectory $output -DisablePerformanceThresholds
        $summaryPath = Join-Path $output "$runId-summary.json"
        $summary = Get-Content -Raw -Encoding UTF8 -LiteralPath $summaryPath | ConvertFrom-Json
        $result = $summary.K6.Result
        $final = $summary.K6.FinalSnapshot
        if (-not [bool]$summary.ValidMeasurement -or
            -not [bool]$summary.K6.StateInvariantSatisfied -or
            [long]$result.DroppedIterations -ne 0 -or
            [long]$result.UnexpectedNonSuccessful -ne 0 -or
            [long]$result.UnexpectedRelease -ne 0 -or
            [long]$summary.Metrics.Deltas.DbDeadlocks -ne 0 -or
            [long]$result.HoldSuccess -le 0 -or
            [long]$result.HoldSuccess -ne [long]$result.ReleaseSuccess -or
            [long]$final.activeHeldSeats -ne 0 -or [long]$final.holdRows -ne 0 -or
            [long]$result.Iterations -lt ([double]$Rate * $DurationSeconds * 0.99) -or
            [double]$result.ScheduledIterationAttainmentRate -lt 0.99) {
            throw "Controlled weighted churn run failed its comparison gate: $runId"
        }
        $records.Add([pscustomobject]@{
            Sequence = $stage.Sequence
            Repeat = $stage.Repeat
            HotSeatCount = $stage.HotSeatCount
            RunId = $runId
            SummaryFile = [IO.Path]::GetFileName($summaryPath)
            Iterations = $result.Iterations
            HoldSuccess = $result.HoldSuccess
            ReleaseSuccess = $result.ReleaseSuccess
            ExpectedContention = $result.ExpectedContention
            SuccessP95Ms = $result.HoldSuccessDurationMs.P95
            SeatConflictP95Ms = $result.HoldSeatConflictDurationMs.P95
            HikariPendingPeak = $summary.Metrics.Peaks.HikariPending
            DbRowLockWaits = $summary.Metrics.Deltas.DbRowLockWaits
        })
        Write-Output "CONTROLLED_SEAT_HOLD_CHURN_RUN_COMPLETE $runId"
    }
} finally {
    [ordered]@{
        SchemaVersion = 1
        BatchId = $BatchId
        Scenario = 'weighted-hotspot-churn'
        ComposeProject = $ComposeProject
        FixtureRunId = $fixtureRunId
        FixtureResetBeforeEachRun = $true
        HoldDwellMilliseconds = $HoldDwellMilliseconds
        Complete = $records.Count -eq $plan.Count
        Records = $records.ToArray()
    } | ConvertTo-Json -Depth 8 | Set-Content -Encoding UTF8 -LiteralPath $manifest
}
