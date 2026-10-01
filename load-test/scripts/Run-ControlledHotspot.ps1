[CmdletBinding()]
param(
    [ValidatePattern('^[A-Za-z0-9-]{1,16}$')][string]$BatchId = 'controlled166',
    [ValidateRange(1, 10)][int]$Repeats = 2,
    [ValidateRange(1, 3600)][int]$DurationSeconds = 10,
    [ValidateRange(1, 10000)][int]$Rate = 50,
    [string]$BaseUrl = 'http://127.0.0.1:18080',
    [string]$ManagementBaseUrl = 'http://127.0.0.1:18081'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$compose = Join-Path $root 'compose.yml'
$measure = Join-Path $PSScriptRoot 'Measure-Contention.ps1'
Import-Module (Join-Path $PSScriptRoot 'ControlledHotspot.psm1') -Force
Import-Module (Join-Path $PSScriptRoot 'SeatIndexExperiment.psm1') -Force
$project = 'ticketon-controlled166'
$containerId = @(& docker compose -p $project -f $compose ps -q mariadb 2>&1)
if ($LASTEXITCODE -ne 0 -or $containerId.Count -ne 1 -or [string]::IsNullOrWhiteSpace($containerId[0])) {
    throw 'The dedicated ticketon-controlled166 MariaDB container must already be running.'
}
$containerLabels = [string](& docker inspect -f '{{json .Config.Labels}}' $containerId[0] 2>&1)
if ($LASTEXITCODE -ne 0) { throw 'Cannot verify the MariaDB Compose project label.' }
$containerProject = [string](($containerLabels | ConvertFrom-Json).'com.docker.compose.project')
Assert-ControlledHotspotProject -ExpectedProject $project -ActualProject $env:COMPOSE_PROJECT_NAME -ContainerProject $containerProject | Out-Null

$output = Join-Path $root "load-test\results\$BatchId"
if (Test-Path -LiteralPath $output) { throw "Refusing to overwrite an existing batch: $output" }
New-Item -ItemType Directory -Path $output | Out-Null
$plan = @(New-ControlledHotspotPlan -Repeats $Repeats -DurationSeconds $DurationSeconds -Rate $Rate)
$records = New-Object 'Collections.Generic.List[object]'
$manifest = Join-Path $output 'controlled-hotspot-manifest.json'

function Invoke-ControlledQuery([string]$query) {
    $lines = @(& docker compose -p $project -f $compose exec -T mariadb mariadb -uroot -ponticket-root -N -B onticket_local -e $query 2>&1)
    if ($LASTEXITCODE -ne 0) { throw 'Dedicated MariaDB diagnostic query failed.' }
    $lines
}
$executor = { param([string]$query) Invoke-ControlledQuery $query }

try {
    foreach ($stage in $plan) {
        $counts = @(Invoke-ControlledQuery @'
SELECT (SELECT COUNT(*) FROM seat),
       (SELECT COUNT(*) FROM seat s JOIN concert_time ct ON ct.id=s.concert_time_id WHERE ct.concert_id LIKE 'LOAD-TEST-%'),
       (SELECT COUNT(*) FROM concert WHERE concert_id NOT LIKE 'LOAD-TEST-%'),
       (SELECT COUNT(*) FROM reservation),
       (SELECT COUNT(*) FROM reservation WHERE concert_id LIKE 'LOAD-TEST-%'),
       (SELECT COUNT(*) FROM reservation_booking),
       (SELECT COUNT(DISTINCT b.id) FROM reservation_booking b JOIN reservation r ON r.booking_id=b.id WHERE b.idempotency_key LIKE 'lt-%' AND r.concert_id LIKE 'LOAD-TEST-%'),
       (SELECT COUNT(*) FROM reservation_payment),
       (SELECT COUNT(DISTINCT p.id) FROM reservation_payment p JOIN reservation_booking b ON b.id=p.booking_id JOIN reservation r ON r.booking_id=b.id WHERE p.provider_payment_id LIKE 'LT:load-user-%' AND r.concert_id LIKE 'LOAD-TEST-%'),
       (SELECT COUNT(*) FROM reservation_checkout),
       (SELECT COUNT(*) FROM review);
'@)
        if ($counts.Count -ne 1) { throw 'Expected one pre-cleanup count row.' }
        $parts = $counts[0].Split([char]9)
        if ($parts.Count -ne 11) { throw 'Pre-cleanup count query returned malformed data.' }
        Assert-ControlledHotspotPreCleanup `
            -TotalSeatRows ([long]$parts[0]) -FixtureSeatRows ([long]$parts[1]) `
            -OtherConcertRows ([long]$parts[2]) `
            -TotalReservations ([long]$parts[3]) -FixtureReservations ([long]$parts[4]) `
            -TotalBookings ([long]$parts[5]) -FixtureBookings ([long]$parts[6]) `
            -TotalPayments ([long]$parts[7]) -FixturePayments ([long]$parts[8]) `
            -CheckoutRows ([long]$parts[9]) -ReviewRows ([long]$parts[10]) | Out-Null
        $cleanup = Clear-Issue57LoadTestFixtures -QueryExecutor $executor
        if ($cleanup.SeatRowsAfterCleanup -ne 0) { throw 'Fixture cleanup did not leave an empty seat table.' }

        $runId = "$BatchId-r$($stage.Repeat)-h$($stage.HotSeatCount)"
        & $measure -Scenario weighted-hotspot -RunId $runId `
            -Rate $Rate -DurationSeconds $DurationSeconds `
            -HotSeatCount $stage.HotSeatCount -HotRequestPercent 70 -SelectionSeed 17 `
            -PreAllocatedVus 200 -MaxVus 200 `
            -BaseUrl $BaseUrl -ManagementBaseUrl $ManagementBaseUrl `
            -OutputDirectory $output -CollectStatementDigests -RequireFreshFixture `
            -DisablePerformanceThresholds
        $summaryPath = Join-Path $output "$runId-summary.json"
        $summary = Get-Content -Raw -Encoding UTF8 -LiteralPath $summaryPath | ConvertFrom-Json
        Assert-ControlledHotspotResult -Summary $summary | Out-Null
        $records.Add([pscustomobject]@{
            Sequence = $stage.Sequence
            Repeat = $stage.Repeat
            HotSeatCount = $stage.HotSeatCount
            RunId = $runId
            SummaryFile = [IO.Path]::GetFileName($summaryPath)
            Success = $summary.K6.Result.ReservationSuccess
            SeatConflicts = $summary.K6.Result.ExpectedContention
            SuccessP95Ms = $summary.K6.Result.ReservationSuccessDurationMs.P95
            ConflictP95Ms = $summary.K6.Result.ReservationSeatContentionDurationMs.P95
            SeatLockSelectAverageMs = $summary.DatabaseStatementDigests.SeatLockSelect.AverageMilliseconds
            ConcertTimeDecrementAverageMs = $summary.DatabaseStatementDigests.ConcertTimeDecrement.AverageMilliseconds
            DbRowLockWaits = $summary.Metrics.Deltas.DbRowLockWaits
        })
        [ordered]@{
            SchemaVersion = 1
            BatchId = $BatchId
            ComposeProject = $project
            FixtureIsolatedBeforeEachRun = $true
            SqlDigestTimingIsNotRowLockWaitAttribution = $true
            Complete = $false
            Records = $records.ToArray()
        } | ConvertTo-Json -Depth 8 | Set-Content -Encoding UTF8 -LiteralPath $manifest
        Write-Output "CONTROLLED_HOTSPOT_RUN_COMPLETE $runId"
    }
} finally {
    [ordered]@{
        SchemaVersion = 1
        BatchId = $BatchId
        ComposeProject = $project
        FixtureIsolatedBeforeEachRun = $true
        SqlDigestTimingIsNotRowLockWaitAttribution = $true
        Complete = $records.Count -eq $plan.Count
        Records = $records.ToArray()
    } | ConvertTo-Json -Depth 8 | Set-Content -Encoding UTF8 -LiteralPath $manifest
}
Write-Output "CONTROLLED_HOTSPOT_BATCH_COMPLETE $manifest"
