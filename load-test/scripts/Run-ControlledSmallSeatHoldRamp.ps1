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
$output = Join-Path $root "load-test\results\$RunId"
Import-Module (Join-Path $PSScriptRoot 'SeatHoldContention.psm1') -Force
Import-Module (Join-Path $PSScriptRoot 'ControlledSmallSeatHoldRamp.psm1') -Force
$plan = @(Get-ControlledSmallSeatHoldRampPlan)
$k6 = Get-Command k6 -ErrorAction Stop

# One fresh fixture per batch. The reused fixture runner refuses a nonempty DB, wrong
# Compose identity, non-loopback Backend, DB fingerprint mismatch and existing result path.
$fixtureOutput = & (Join-Path $PSScriptRoot 'Run-ControlledSmallFixture.ps1') `
    -RunId $RunId -ComposeProject $ComposeProject
$fixture = $fixtureOutput | ConvertFrom-Json
if ($fixture.Status -ne 'FIXTURE_READY' -or $fixture.RunId -cne $RunId -or
    $fixture.ComposeProject -cne $ComposeProject -or [int]$fixture.SeatCount -ne 20) {
    throw 'The dedicated 20-seat fixture was not verified.'
}
$concertTimeId = [long]$fixture.ConcertTimeId
if ($concertTimeId -le 0) { throw 'Fixture concert time identity is invalid.' }

function Get-DeadlockCount {
    $value = [string](& docker compose -p $ComposeProject -f $compose exec -T mariadb `
        mariadb -uroot -ponticket-root -N -B onticket_local `
        -e "SHOW GLOBAL STATUS LIKE 'Innodb_deadlocks';" 2>&1)
    if ($LASTEXITCODE -ne 0 -or $value.Trim() -notmatch '^Innodb_deadlocks\s+(\d+)$') {
        throw 'Could not read MariaDB deadlock counter.'
    }
    [long]$Matches[1]
}

function Get-DatabaseCounts {
    $sql = "SELECT COUNT(*) FROM seat WHERE concert_time_id=$concertTimeId UNION ALL " +
        "SELECT COUNT(*) FROM seat WHERE concert_time_id=$concertTimeId AND " +
        '(held_by IS NOT NULL OR held_until IS NOT NULL) UNION ALL ' +
        "SELECT COUNT(*) FROM seat WHERE concert_time_id=$concertTimeId AND reserved=1 UNION ALL " +
        "SELECT seat_amount FROM concert_time WHERE id=$concertTimeId UNION ALL " +
        "SELECT COUNT(*) FROM reservation WHERE concert_time_id=$concertTimeId;"
    $counts = @(& docker compose -p $ComposeProject -f $compose exec -T mariadb `
        mariadb -uroot -ponticket-root -N -B onticket_local -e $sql 2>&1)
    if ($LASTEXITCODE -ne 0 -or $counts.Count -ne 5) {
        throw 'Could not independently read the dedicated fixture inventory.'
    }
    $counts
}

New-Item -ItemType Directory -Path $output -ErrorAction Stop | Out-Null
$records = New-Object 'Collections.Generic.List[object]'
$manifest = Join-Path $output 'small-seat-hold-ramp.json'
try {
    foreach ($stage in $plan) {
        $before = Invoke-RestMethod -Method Get -TimeoutSec 10 `
            -Uri "$baseUrl/loadtest/seat-holds/snapshot?runId=$RunId"
        $beforeCounts = @(Get-DatabaseCounts)
        if (-not [bool]$before.invariantSatisfied -or [long]$before.holdRows -ne 0 -or
            (@($beforeCounts | ForEach-Object { [long]$_ }) -join ',') -ne '20,0,0,20,0') {
            throw "Ramp stage $($stage.Rate) RPS did not start with released inventory."
        }
        $deadlocksBefore = Get-DeadlockCount
        $arguments = @('run', '-e', "BASE_URL=$baseUrl", '-e', "FIXTURE_RUN_ID=$RunId",
            '-e', 'EXPECTED_TOTAL_SEATS=20', '-e', "TEST_SCENARIO=$scenario",
            '-e', "RATE=$($stage.Rate)", '-e', "DURATION=$($stage.DurationSeconds)s",
            '-e', 'HOT_SEAT_COUNT=1', '-e', 'HOT_REQUEST_PERCENT=70', '-e', 'SELECTION_SEED=17',
            '-e', 'HOLD_DWELL_MS=500', '-e', 'TOKEN_COUNT=20',
            '-e', 'PRE_ALLOCATED_VUS=20', '-e', 'MAX_VUS=20',
            '-e', 'ENFORCE_THRESHOLDS=false',
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
        $stdoutTask = $process.StandardOutput.ReadToEndAsync()
        $stderrTask = $process.StandardError.ReadToEndAsync()
        $process.WaitForExit()
        $stdout = $stdoutTask.GetAwaiter().GetResult()
        $stderr = $stderrTask.GetAwaiter().GetResult()
        if ($process.ExitCode -ne 0) { throw "k6 failed at $($stage.Rate) RPS with exit code $($process.ExitCode)." }
        $result = ConvertFrom-SeatHoldK6Result -Text $stdout
        $snapshot = ConvertFrom-ControlledK6ConsoleSnapshot -StandardError $stderr
        Assert-SeatHoldRunIdentity -Result $result -Scenario $scenario `
            -Rate $stage.Rate -DurationSeconds $stage.DurationSeconds -ThresholdsEnforced $false | Out-Null
        $summary = New-SeatHoldRunSummary -Result $result -DurationSeconds $stage.DurationSeconds
        Assert-SeatHoldFinalState -Summary $summary -Snapshot $snapshot | Out-Null
        $freshSnapshot = Invoke-RestMethod -Method Get -TimeoutSec 10 `
            -Uri "$baseUrl/loadtest/seat-holds/snapshot?runId=$RunId"
        $databaseCounts = @(Get-DatabaseCounts)
        $deadlockDelta = (Get-DeadlockCount) - $deadlocksBefore
        Assert-ControlledSmallSeatHoldStage -Plan $stage -Summary $summary -Snapshot $snapshot `
            -FreshSnapshot $freshSnapshot -DatabaseCounts $databaseCounts `
            -DeadlockDelta $deadlockDelta | Out-Null
        $records.Add([pscustomobject]@{
            TargetRps = $stage.Rate
            DurationSeconds = $stage.DurationSeconds
            HotSeatCount = $stage.HotSeatCount
            HoldDwellMilliseconds = $stage.HoldDwellMilliseconds
            Iterations = $summary.Iterations
            HoldSuccess = $summary.HoldSuccess
            ReleaseSuccess = $summary.ReleaseSuccess
            ExpectedSeatConflicts = $summary.ExpectedContention
            DroppedIterations = $summary.DroppedIterations
            UnexpectedFailures = $summary.UnexpectedNonSuccessful + $summary.UnexpectedRelease
            HoldSuccessP95Ms = $summary.HoldSuccessDurationMs.P95
            SeatConflictP95Ms = if ($summary.ExpectedContention -gt 0) { $summary.HoldSeatConflictDurationMs.P95 } else { $null }
            FinalHoldRows = $freshSnapshot.holdRows
            DatabaseCounts = @($databaseCounts | ForEach-Object { [long]$_ })
            DbDeadlocksDelta = $deadlockDelta
        })
        Write-Output "SMALL_SEAT_HOLD_RAMP_STAGE_PASS rps=$($stage.Rate) success=$($summary.HoldSuccess) conflict=$($summary.ExpectedContention)"
    }
} finally {
    [ordered]@{
        SchemaVersion = 1
        Scope = 'local virtual 20-seat fixture; not production performance'
        RunId = $RunId
        ComposeProject = $ComposeProject
        Complete = $records.Count -eq $plan.Count
        FixedHotSeatCount = 1
        FixedHoldDwellMilliseconds = 500
        FixedHotRequestPercent = 70
        Records = $records.ToArray()
    } | ConvertTo-Json -Depth 6 | Set-Content -Encoding UTF8 -LiteralPath $manifest
}
