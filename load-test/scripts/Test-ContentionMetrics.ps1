[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$issue51ModulePath = Join-Path $PSScriptRoot 'ContentionMetrics.psm1'
Import-Module $issue51ModulePath -Force
$issue51Assertions = 0

function Assert-Issue51Equal {
    param(
        [Parameter(Mandatory = $true)]$Actual,
        [Parameter(Mandatory = $true)]$Expected,
        [Parameter(Mandatory = $true)][string]$Message
    )
    $script:issue51Assertions += 1
    if ($Actual -ne $Expected) {
        throw "$Message Expected=$Expected Actual=$Actual"
    }
}

function Assert-Issue51Throws {
    param(
        [Parameter(Mandatory = $true)][scriptblock]$Action,
        [Parameter(Mandatory = $true)][string]$Message
    )
    $script:issue51Assertions += 1
    $issue51Thrown = $false
    try {
        & $Action
    } catch {
        $issue51Thrown = $true
    }
    if (-not $issue51Thrown) {
        throw $Message
    }
}

$issue51PrometheusFixture = @'
# TYPE hikaricp_connections_active gauge
hikaricp_connections_active{application="onticket-loadtest",pool="HikariPool-1",} 2.0
hikaricp_connections_active{application="onticket-loadtest",pool="HikariPool-2",} 1.0
hikaricp_connections_pending{application="onticket-loadtest",pool="HikariPool-1",} 1.0
hikaricp_connections_pending{application="onticket-loadtest",pool="HikariPool-2",} 0.0
hikaricp_connections_idle{application="onticket-loadtest",pool="HikariPool-1",} 7.0
hikaricp_connections_idle{application="onticket-loadtest",pool="HikariPool-2",} 4.0
hikaricp_connections_max{application="onticket-loadtest",pool="HikariPool-1",} 10.0
hikaricp_connections_max{application="onticket-loadtest",pool="HikariPool-2",} 5.0
'@
$issue51Hikari = ConvertFrom-PrometheusHikari -Text $issue51PrometheusFixture
Assert-Issue51Equal $issue51Hikari.Active 3.0 'Hikari active values must be summed across pools.'
Assert-Issue51Equal $issue51Hikari.Pending 1.0 'Hikari pending values must be summed across pools.'
Assert-Issue51Equal $issue51Hikari.Idle 11.0 'Hikari idle values must be summed across pools.'
Assert-Issue51Equal $issue51Hikari.Max 15.0 'Hikari max values must be summed across pools.'
Assert-Issue51Throws { ConvertFrom-PrometheusHikari -Text 'hikaricp_connections_active 1.0' } 'Missing Hikari metrics must fail parsing.'

$issue51BeforeDb = ConvertFrom-MariaDbStatus -Lines @'
Innodb_deadlocks	2
Innodb_row_lock_current_waits	0
Innodb_row_lock_time	100
Innodb_row_lock_waits	4
Threads_connected	11
Threads_running	1
'@
$issue51AfterDb = ConvertFrom-MariaDbStatus -Lines @'
Innodb_deadlocks 3
Innodb_row_lock_current_waits 2
Innodb_row_lock_time 370
Innodb_row_lock_waits 9
Threads_connected 12
Threads_running 5
'@
Assert-Issue51Equal $issue51BeforeDb.RowLockWaits 4 'MariaDB row lock waits must be parsed.'
Assert-Issue51Equal $issue51AfterDb.RowLockTimeMs 370 'MariaDB row lock time must be parsed.'
Assert-Issue51Throws { ConvertFrom-MariaDbStatus -Lines 'Threads_running 1' } 'Missing MariaDB status must fail parsing.'

$issue51Samples = @(
    [pscustomobject]@{
        ElapsedMilliseconds = 0
        HikariActive = 0; HikariPending = 0; HikariIdle = 10; HikariMax = 10
        DbRowLockCurrentWaits = 0; DbRowLockWaits = 4; DbRowLockTimeMs = 100
        DbDeadlocks = 2; DbThreadsConnected = 11; DbThreadsRunning = 1
    },
    [pscustomobject]@{
        ElapsedMilliseconds = 1000
        HikariActive = 8; HikariPending = 3; HikariIdle = 2; HikariMax = 10
        DbRowLockCurrentWaits = 2; DbRowLockWaits = 7; DbRowLockTimeMs = 220
        DbDeadlocks = 2; DbThreadsConnected = 12; DbThreadsRunning = 5
    },
    [pscustomobject]@{
        ElapsedMilliseconds = 2200
        HikariActive = 1; HikariPending = 0; HikariIdle = 9; HikariMax = 10
        DbRowLockCurrentWaits = 0; DbRowLockWaits = 9; DbRowLockTimeMs = 370
        DbDeadlocks = 3; DbThreadsConnected = 11; DbThreadsRunning = 1
    }
)
$issue51Summary = New-ContentionMetricsSummary -Samples $issue51Samples
Assert-Issue51Equal $issue51Summary.SampleCount 3 'All samples must be counted.'
Assert-Issue51Equal $issue51Summary.Sampling.MinimumIntervalMs 1000 'Minimum sample interval must be calculated.'
Assert-Issue51Equal $issue51Summary.Sampling.AverageIntervalMs 1100 'Average sample interval must be calculated.'
Assert-Issue51Equal $issue51Summary.Sampling.MaximumIntervalMs 1200 'Maximum sample interval must be calculated.'
Assert-Issue51Equal $issue51Summary.Peaks.HikariActive 8.0 'Hikari active peak must be calculated.'
Assert-Issue51Equal $issue51Summary.Peaks.HikariPending 3.0 'Hikari pending peak must be calculated.'
Assert-Issue51Equal $issue51Summary.Peaks.DbRowLockCurrentWaits 2 'DB current wait peak must be calculated.'
Assert-Issue51Equal $issue51Summary.Deltas.DbRowLockWaits 5 'DB row lock wait delta must be calculated.'
Assert-Issue51Equal $issue51Summary.Deltas.DbRowLockTimeMs 270 'DB row lock time delta must be calculated.'
Assert-Issue51Equal $issue51Summary.Deltas.DbDeadlocks 1 'DB deadlock delta must be calculated.'
Assert-Issue51Equal $issue51Summary.ObserverEffects.ConnectionsCounterExcluded $true 'Connections counter exclusion must be explicit.'

Assert-Issue51Equal (Assert-ContentionRunId -RunId 'issue51-smoke-01') 'issue51-smoke-01' 'Valid run IDs must be preserved.'
Assert-Issue51Throws { Assert-ContentionRunId -RunId 'invalid run id' } 'Invalid run IDs must fail.'

$issue51CounterResetSamples = @($issue51Samples[2], $issue51Samples[0])
Assert-Issue51Throws { New-ContentionMetricsSummary -Samples $issue51CounterResetSamples } 'Counter resets must invalidate the measurement.'

$issue51MiddleResetSamples = @(
    $issue51Samples[0],
    [pscustomobject]@{
        ElapsedMilliseconds = 1000
        HikariActive = 1; HikariPending = 0; HikariIdle = 9; HikariMax = 10
        DbRowLockCurrentWaits = 0; DbRowLockWaits = 0; DbRowLockTimeMs = 0
        DbDeadlocks = 0; DbThreadsConnected = 11; DbThreadsRunning = 1
    },
    [pscustomobject]@{
        ElapsedMilliseconds = 2000
        HikariActive = 1; HikariPending = 0; HikariIdle = 9; HikariMax = 10
        DbRowLockCurrentWaits = 0; DbRowLockWaits = 10; DbRowLockTimeMs = 400
        DbDeadlocks = 3; DbThreadsConnected = 11; DbThreadsRunning = 1
    }
)
Assert-Issue51Throws { New-ContentionMetricsSummary -Samples $issue51MiddleResetSamples } 'A counter reset between the first and last samples must invalidate the measurement.'

$issue53K6Fixture = @'
LOADTEST_RESULT {"schemaVersion":1,"scenario":"distributed","targetRatePerSecond":100,"duration":"10s","thresholdsEnforced":false,"iterations":991,"droppedIterations":9,"reservationSuccess":990,"expectedContention":0,"unexpectedNonSuccessful":1,"unexpectedFailureRate":0.001009,"reservationDurationMs":{"average":31.2,"median":30.1,"p95":45.6,"maximum":88.9},"maxObservedVus":12,"maxAllocatedVus":20,"preAllocatedVus":20,"configuredMaxVus":100}
'@
$issue53K6Result = ConvertFrom-K6ContentionResult -Text $issue53K6Fixture
$issue53K6Summary = New-K6ContentionRunSummary -Result $issue53K6Result -DurationSeconds 10
Assert-Issue51Equal $issue53K6Summary.Iterations 991 'Completed k6 iterations must be parsed.'
Assert-Issue51Equal $issue53K6Summary.DroppedIterations 9 'Dropped k6 iterations must be parsed.'
Assert-Issue51Equal $issue53K6Summary.CompletedIterationsPerScheduledSecond 99.1 'Completed iterations per scheduled second must exclude setup time.'
Assert-Issue51Equal $issue53K6Summary.ScheduledIterationAttainmentRate 0.991 'Scheduled iteration attainment must include dropped iterations.'
Assert-Issue51Equal $issue53K6Summary.ReservationDurationMs.P95 45.6 'Reservation p95 must be parsed from the custom trend.'
Assert-Issue51Equal $issue53K6Summary.ThresholdsEnforced $false 'Baseline runs must record disabled performance thresholds.'
Assert-Issue51Equal $issue53K6Summary.MaxAllocatedVus 20 'The allocated VU gauge must remain distinct from the configured cap.'
Assert-Issue51Equal $issue53K6Summary.ConfiguredMaxVus 100 'The configured VU cap must come from the scenario option.'
Assert-Issue51Equal (Assert-K6ContentionRunIdentity -Result $issue53K6Result -Scenario 'distributed' -Rate 100 -DurationSeconds 10 -ThresholdsEnforced $false) $true 'Matching structured k6 run identity must pass.'
Assert-Issue51Throws { Assert-K6ContentionRunIdentity -Result $issue53K6Result -Scenario 'hot-seat' -Rate 100 -DurationSeconds 10 -ThresholdsEnforced $false } 'A mismatched scenario must fail.'
Assert-Issue51Throws { Assert-K6ContentionRunIdentity -Result $issue53K6Result -Scenario 'distributed' -Rate 99 -DurationSeconds 10 -ThresholdsEnforced $false } 'A mismatched target rate must fail.'
Assert-Issue51Throws { Assert-K6ContentionRunIdentity -Result $issue53K6Result -Scenario 'distributed' -Rate 100 -DurationSeconds 9 -ThresholdsEnforced $false } 'A mismatched duration must fail.'
Assert-Issue51Throws { Assert-K6ContentionRunIdentity -Result $issue53K6Result -Scenario 'distributed' -Rate 100 -DurationSeconds 10 -ThresholdsEnforced $true } 'A mismatched threshold mode must fail.'
Assert-Issue51Throws { ConvertFrom-K6ContentionResult -Text 'missing marker' } 'Missing structured k6 results must fail.'
Assert-Issue51Throws { ConvertFrom-K6ContentionResult -Text "$issue53K6Fixture`n$issue53K6Fixture" } 'Duplicate structured k6 results must fail.'

$issue53InvalidCounters = $issue53K6Result.PSObject.Copy()
$issue53InvalidCounters.reservationSuccess = 989
Assert-Issue51Throws { New-K6ContentionRunSummary -Result $issue53InvalidCounters -DurationSeconds 10 } 'Reservation result counters must match completed iterations.'

$issue53SnapshotFixture = 'LOADTEST_FINAL_SNAPSHOT {"expectedTotalSeats":2000,"actualSeatCount":2000,"remainingSeats":1010,"reservedSeats":990,"reservations":990,"bookings":990,"payments":990,"invariantSatisfied":true}'
$issue53Snapshot = ConvertFrom-K6FinalSnapshot -Text $issue53SnapshotFixture
Assert-Issue51Equal $issue53Snapshot.remainingSeats 1010 'Final snapshot inventory must be parsed.'
Assert-Issue51Equal $issue53Snapshot.invariantSatisfied $true 'Final snapshot invariant must be parsed.'
Assert-Issue51Throws { ConvertFrom-K6FinalSnapshot -Text 'missing snapshot' } 'Missing final snapshots must fail.'

Write-Output "ContentionMetrics checks passed: $issue51Assertions assertions."
