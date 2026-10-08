[CmdletBinding()]
param(
    [ValidatePattern('^[A-Za-z0-9-]{1,16}$')][string]$RunId = 'smallfixture180',
    [ValidatePattern('^ticketon-controlled172(-[a-z0-9]{1,12})?$')]
    [string]$ComposeProject = 'ticketon-controlled172'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$compose = Join-Path $root 'compose.yml'
$preflight = Join-Path $PSScriptRoot 'Run-ControlledSeatHoldChurn.ps1'
Import-Module (Join-Path $PSScriptRoot 'ControlledSmallFixture.psm1') -Force

# The existing preflight checks loopback endpoints, dedicated Compose label, empty application
# tables, Backend/DB identity, bounded inputs, and unused result path before the first POST.
$preflightOutput = & $preflight -CheckOnly -BatchId $RunId -ComposeProject $ComposeProject `
    -Rate 1 -DurationSeconds 1 -Repeats 1 -HoldDwellMilliseconds 0
$plan = $preflightOutput | ConvertFrom-Json
if ($plan.Status -ne 'READY' -or $plan.BatchId -cne $RunId -or
    $plan.ComposeProject -cne $ComposeProject) {
    throw 'Dedicated environment preflight did not return the requested READY identity.'
}

$baseUrl = 'http://127.0.0.1:18080'
$configuration = Invoke-RestMethod -Method Get -Uri "$baseUrl/loadtest/fixture-config" -TimeoutSec 10
Assert-ControlledSmallFixtureConfig -Config $configuration | Out-Null

# This is the only write request. Failure after this point leaves the dedicated DB intact for inspection.
$fixture = Invoke-RestMethod -Method Post -Uri "$baseUrl/loadtest/runs?runId=$RunId" -TimeoutSec 30
$snapshot = Invoke-RestMethod -Method Get -Uri "$baseUrl/loadtest/snapshot?runId=$RunId" -TimeoutSec 10
$holds = Invoke-RestMethod -Method Get -Uri "$baseUrl/loadtest/seat-holds/snapshot?runId=$RunId" -TimeoutSec 10
$sql = 'SELECT COUNT(*) FROM concert UNION ALL SELECT COUNT(*) FROM concert_time UNION ALL ' +
    'SELECT COUNT(*) FROM seat UNION ALL SELECT COUNT(*) FROM site_user UNION ALL ' +
    'SELECT COUNT(*) FROM reservation UNION ALL SELECT COUNT(*) FROM reservation_booking UNION ALL ' +
    'SELECT COUNT(*) FROM reservation_checkout UNION ALL SELECT COUNT(*) FROM reservation_payment;'
$databaseCounts = @(& docker compose -p $ComposeProject -f $compose exec -T mariadb `
    mariadb -uroot -ponticket-root -N -B onticket_local -e $sql 2>&1)
if ($LASTEXITCODE -ne 0) { throw 'Could not independently verify the dedicated fixture database.' }
Assert-ControlledSmallFixtureState -RunId $RunId -Fixture $fixture -Snapshot $snapshot `
    -Holds $holds -DatabaseCounts $databaseCounts | Out-Null

[ordered]@{
    Status = 'FIXTURE_READY'
    RunId = $RunId
    ComposeProject = $ComposeProject
    ConcertId = $fixture.concertId
    ConcertTimeId = $fixture.concertTimeId
    Rows = 2
    SeatsPerRow = 10
    SeatCount = 20
    RemainingSeats = 20
    Reservations = 0
    Bookings = 0
    Payments = 0
    ActiveHolds = 0
    InvariantSatisfied = $true
} | ConvertTo-Json -Depth 3 -Compress
