[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[A-Za-z0-9-]{1,16}$')][string]$RunId,
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^ticketon-controlled172(-[a-z0-9]{1,12})?$')]
    [string]$ComposeProject,
    [ValidateSet(1, 3)][int]$Repeats = 1,
    [switch]$Probe30
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
if ($Probe30 -and $Repeats -ne 1) { throw 'Probe30 uses one bounded 20->30 RPS pair; Repeats must be 1.' }
$observeWaits = $Repeats -eq 3 -or $Probe30
if ($observeWaits) {
    Import-Module (Join-Path $PSScriptRoot 'ControlledSmallSeatHoldRepeat.psm1') -Force
    Import-Module (Join-Path $PSScriptRoot 'ContentionMetrics.psm1') -Force
    $mysql = Get-Command mysql -ErrorAction Stop
}
$plan = if ($Probe30) { @(Get-ControlledSmallSeatHoldProbe30Plan) } `
    elseif ($Repeats -eq 3) { @(Get-ControlledSmallSeatHoldRepeatPlan) } `
    else { @(Get-ControlledSmallSeatHoldRampPlan) }
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

function Get-WaitSample {
    param([Parameter(Mandatory = $true)][Diagnostics.Stopwatch]$Stopwatch)
    $prometheus = Invoke-WebRequest -UseBasicParsing -Method Get -TimeoutSec 10 `
        -Uri 'http://127.0.0.1:18081/actuator/prometheus'
    $hikari = ConvertFrom-PrometheusHikari -Text $prometheus.Content
    $acquire = ConvertFrom-PrometheusHikariAcquireTiming -Text $prometheus.Content
    $query = "SHOW GLOBAL STATUS WHERE Variable_name IN ('Innodb_deadlocks'," +
        "'Innodb_row_lock_current_waits','Innodb_row_lock_time','Innodb_row_lock_waits'," +
        "'Threads_connected','Threads_running');"
    # The dedicated DB identity is checked before fixture POST. Use its loopback port
    # for repeated samples: Docker exec startup can exceed the sampling interval.
    $env:MYSQL_PWD = 'onticket-root'
    $dbLines = @(& $mysql.Source --protocol=tcp --host=127.0.0.1 --port=3309 `
        --user=root --batch --skip-column-names onticket_local --execute $query 2>&1)
    if ($LASTEXITCODE -ne 0) { throw 'Could not sample the dedicated MariaDB wait status.' }
    $db = ConvertFrom-MariaDbStatus -Lines $dbLines
    [pscustomobject]@{
        ElapsedMilliseconds = $Stopwatch.ElapsedMilliseconds
        HikariPending = $hikari.Pending
        HikariActive = $hikari.Active
        HikariMax = $hikari.Max
        HikariAcquireCount = $acquire.AcquireCount
        HikariAcquireSeconds = $acquire.AcquireSeconds
        HikariTimeoutCount = $acquire.TimeoutCount
        DbRowLockCurrentWaits = $db.RowLockCurrentWaits
        DbRowLockWaits = $db.RowLockWaits
        DbRowLockTimeMs = $db.RowLockTimeMs
        DbDeadlocks = $db.Deadlocks
    }
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
        $waitSamples = New-Object 'Collections.Generic.List[object]'
        $watch = [Diagnostics.Stopwatch]::StartNew()
        if ($Probe30 -and $stage.Rate -eq 30) {
            $freeKb = [long](Get-CimInstance Win32_OperatingSystem).FreePhysicalMemory
            Assert-ControlledSmallSeatProbeMemory -FreePhysicalMemoryKb $freeKb | Out-Null
        }
        if ($observeWaits) { $waitSamples.Add((Get-WaitSample -Stopwatch $watch)) }
        if (-not $process.Start()) { throw 'Could not start k6.' }
        $stdoutTask = $process.StandardOutput.ReadToEndAsync()
        $stderrTask = $process.StandardError.ReadToEndAsync()
        if ($observeWaits) {
            $nextSampleAt = $watch.ElapsedMilliseconds + 1000
            while (-not $process.HasExited) {
                $waitMilliseconds = $nextSampleAt - $watch.ElapsedMilliseconds
                if ($waitMilliseconds -gt 0) { Start-Sleep -Milliseconds $waitMilliseconds }
                $process.Refresh()
                if ($process.HasExited) { break }
                $waitSamples.Add((Get-WaitSample -Stopwatch $watch))
                do { $nextSampleAt += 1000 } while ($nextSampleAt -le $watch.ElapsedMilliseconds)
            }
        }
        $process.WaitForExit()
        if ($observeWaits) { $waitSamples.Add((Get-WaitSample -Stopwatch $watch)) }
        $watch.Stop()
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
        $waitSummary = if ($observeWaits) {
            New-ControlledSmallSeatHoldWaitSummary -Samples $waitSamples.ToArray()
        } else { $null }
        if ($null -ne $waitSummary -and
            ($waitSummary.DbDeadlocksDelta -ne 0 -or $waitSummary.HikariTimeoutDelta -ne 0)) {
            throw 'Wait metrics observed a deadlock or Hikari connection timeout.'
        }
        if ($Probe30 -and $stage.Rate -eq 20) {
            Assert-ControlledSmallSeatProbeBaseline -WaitSummary $waitSummary | Out-Null
        }
        Assert-ControlledSmallSeatHoldStage -Plan $stage -Summary $summary -Snapshot $snapshot `
            -FreshSnapshot $freshSnapshot -DatabaseCounts $databaseCounts `
            -DeadlockDelta $deadlockDelta | Out-Null
        $record = [ordered]@{
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
        }
        if ($observeWaits) {
            $record.Sequence = $stage.Sequence
            $record.Round = $stage.Round
            $record.WaitMetrics = $waitSummary
        }
        $records.Add([pscustomobject]$record)
        Write-Output "SMALL_SEAT_HOLD_RAMP_STAGE_PASS rps=$($stage.Rate) success=$($summary.HoldSuccess) conflict=$($summary.ExpectedContention)"
    }
} finally {
    [ordered]@{
        SchemaVersion = if ($Probe30) { 3 } elseif ($Repeats -eq 3) { 2 } else { 1 }
        Scope = 'local virtual 20-seat fixture; not production performance'
        RunId = $RunId
        ComposeProject = $ComposeProject
        Complete = $records.Count -eq $plan.Count
        Repeats = $Repeats
        Probe30 = [bool]$Probe30
        FixedHotSeatCount = 1
        FixedHoldDwellMilliseconds = 500
        FixedHotRequestPercent = 70
        Records = $records.ToArray()
    } | ConvertTo-Json -Depth 6 | Set-Content -Encoding UTF8 -LiteralPath $manifest
}
