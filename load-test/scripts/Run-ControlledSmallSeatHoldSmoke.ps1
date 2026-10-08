[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[A-Za-z0-9-]{1,16}$')][string]$RunId,
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^ticketon-controlled172(-[a-z0-9]{1,12})?$')]
    [string]$ComposeProject
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$compose = Join-Path $root 'compose.yml'
$baseUrl = 'http://127.0.0.1:18080'
$scenario = 'weighted-hotspot-churn'
$rate = 5
$durationSeconds = 10
$output = Join-Path $root "load-test\results\$RunId"
Import-Module (Join-Path $PSScriptRoot 'SeatHoldContention.psm1') -Force
$k6 = Get-Command k6 -ErrorAction Stop

# The fixture runner first checks the dedicated Compose label, empty application tables,
# Backend/DB identity, loopback endpoints and unused result path before any write.
$fixtureOutput = & (Join-Path $PSScriptRoot 'Run-ControlledSmallFixture.ps1') `
    -RunId $RunId -ComposeProject $ComposeProject
$fixture = $fixtureOutput | ConvertFrom-Json
if ($fixture.Status -ne 'FIXTURE_READY' -or $fixture.RunId -cne $RunId -or
    $fixture.ComposeProject -cne $ComposeProject -or [int]$fixture.SeatCount -ne 20) {
    throw 'The dedicated 20-seat fixture was not verified.'
}

function Get-DeadlockCount {
    $value = [string](& docker compose -p $ComposeProject -f $compose exec -T mariadb `
        mariadb -uroot -ponticket-root -N -B onticket_local `
        -e "SHOW GLOBAL STATUS LIKE 'Innodb_deadlocks';" 2>&1)
    if ($LASTEXITCODE -ne 0 -or $value.Trim() -notmatch '^Innodb_deadlocks\s+(\d+)$') {
        throw 'Could not read MariaDB deadlock counter.'
    }
    [long]$Matches[1]
}

$deadlocksBefore = Get-DeadlockCount
New-Item -ItemType Directory -Path $output -ErrorAction Stop | Out-Null
$arguments = @('run', '-e', "BASE_URL=$baseUrl", '-e', "FIXTURE_RUN_ID=$RunId",
    '-e', 'EXPECTED_TOTAL_SEATS=20', '-e', "TEST_SCENARIO=$scenario",
    '-e', "RATE=$rate", '-e', "DURATION=$($durationSeconds)s",
    '-e', 'HOT_SEAT_COUNT=5', '-e', 'HOT_REQUEST_PERCENT=70', '-e', 'SELECTION_SEED=17',
    '-e', 'HOLD_DWELL_MS=100', '-e', 'TOKEN_COUNT=20',
    '-e', 'PRE_ALLOCATED_VUS=10', '-e', 'MAX_VUS=10',
    (Join-Path $root 'load-test\k6\seat-hold-contention.js'))
$start = New-Object Diagnostics.ProcessStartInfo
$start.FileName = $k6.Source
$start.Arguments = ($arguments | ForEach-Object { '"' + ([string]$_).Replace('"', '\"') + '"' }) -join ' '
$start.UseShellExecute = $false
$start.RedirectStandardOutput = $true
$start.RedirectStandardError = $true
$start.CreateNoWindow = $true
$process = New-Object Diagnostics.Process
$process.StartInfo = $start
if (-not $process.Start()) { throw 'Could not start k6.' }
$stdout = $process.StandardOutput.ReadToEndAsync()
$stderr = $process.StandardError.ReadToEndAsync()
$process.WaitForExit()
$k6ExitCode = $process.ExitCode
$combined = ($stdout.GetAwaiter().GetResult() + "`n" + $stderr.GetAwaiter().GetResult()).Replace('\"', '"')
if ($k6ExitCode -ne 0) { throw "k6 exited with code $k6ExitCode; dedicated DB and result path were retained." }
$result = ConvertFrom-SeatHoldK6Result -Text $combined
$snapshot = ConvertFrom-SeatHoldFinalSnapshot -Text $combined
Assert-SeatHoldRunIdentity -Result $result -Scenario $scenario -Rate $rate `
    -DurationSeconds $durationSeconds -ThresholdsEnforced $true | Out-Null
$summary = New-SeatHoldRunSummary -Result $result -DurationSeconds $durationSeconds
Assert-SeatHoldFinalState -Summary $summary -Snapshot $snapshot | Out-Null
$freshSnapshot = Invoke-RestMethod -Method Get `
    -Uri "$baseUrl/loadtest/seat-holds/snapshot?runId=$RunId" -TimeoutSec 10
$deadlocksAfter = Get-DeadlockCount
if ([long]$summary.Iterations -lt 45 -or [long]$summary.DroppedIterations -ne 0 -or
    [long]$summary.HoldSuccess -le 0 -or
    [long]$summary.HoldSuccess -ne [long]$summary.ReleaseSuccess -or
    [long]$summary.UnexpectedNonSuccessful -ne 0 -or
    [long]$summary.UnexpectedRelease -ne 0 -or
    [long]$snapshot.actualSeatCount -ne 20 -or [long]$snapshot.holdRows -ne 0 -or
    [long]$freshSnapshot.holdRows -ne 0 -or [long]$freshSnapshot.activeHeldSeats -ne 0 -or
    -not [bool]$freshSnapshot.invariantSatisfied -or $deadlocksAfter -ne $deadlocksBefore) {
    throw 'Small fixture smoke failed its completion, inventory or deadlock gate; DB retained.'
}

$record = [ordered]@{
    SchemaVersion = 1
    Status = 'PASS'
    Scope = 'local virtual 20-seat fixture smoke; not production performance'
    RunId = $RunId
    ComposeProject = $ComposeProject
    Rate = $rate
    DurationSeconds = $durationSeconds
    HotSeatCount = 5
    Iterations = $summary.Iterations
    HoldSuccess = $summary.HoldSuccess
    ReleaseSuccess = $summary.ReleaseSuccess
    ExpectedContention = $summary.ExpectedContention
    DroppedIterations = $summary.DroppedIterations
    UnexpectedFailures = $summary.UnexpectedNonSuccessful + $summary.UnexpectedRelease
    FinalActiveHolds = $freshSnapshot.activeHeldSeats
    FinalHoldRows = $freshSnapshot.holdRows
    InvariantSatisfied = $freshSnapshot.invariantSatisfied
    DbDeadlocksDelta = $deadlocksAfter - $deadlocksBefore
}
$record | ConvertTo-Json -Depth 4 | Set-Content -Encoding UTF8 -LiteralPath (Join-Path $output 'smoke-summary.json')
$record | ConvertTo-Json -Depth 4 -Compress
