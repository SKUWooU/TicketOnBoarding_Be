Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

Import-Module (Join-Path $PSScriptRoot 'SeatHoldContention.psm1') -Force
$issue65Assertions = 0

function Assert-Issue65Equal {
    param([object]$Actual, [object]$Expected, [string]$Message)
    $script:issue65Assertions += 1
    if ($Actual -ne $Expected) { throw "$Message expected=$Expected actual=$Actual" }
}

function Assert-Issue65True {
    param([bool]$Condition, [string]$Message)
    $script:issue65Assertions += 1
    if (-not $Condition) { throw $Message }
}

function Assert-Issue65Throws {
    param([scriptblock]$Action, [string]$Message)
    $script:issue65Assertions += 1
    try { & $Action; throw "Expected failure: $Message" } catch {
        if ($_.Exception.Message -eq "Expected failure: $Message") { throw }
    }
}

function New-Issue65ResultText {
    param(
        [string]$Scenario = 'distributed',
        [long]$Iterations = 100,
        [long]$Dropped = 0,
        [long]$Success = 100,
        [long]$Contention = 0,
        [long]$Unexpected = 0,
        [double]$UnexpectedRate = 0,
        [double]$P95 = 20,
        [int]$Rate = 10,
        [string]$Duration = '10s',
        [bool]$Thresholds = $false
    )
    $issue65Result = [ordered]@{
        schemaVersion = 1
        scenario = $Scenario
        targetRatePerSecond = $Rate
        duration = $Duration
        thresholdsEnforced = $Thresholds
        iterations = $Iterations
        droppedIterations = $Dropped
        holdSuccess = $Success
        expectedContention = $Contention
        unexpectedNonSuccessful = $Unexpected
        unexpectedFailureRate = $UnexpectedRate
        holdDurationMs = [ordered]@{ average = 10; median = 9; p95 = $P95; maximum = 30 }
        maxObservedVus = 4
        maxAllocatedVus = 250
        preAllocatedVus = 250
        configuredMaxVus = 250
    }
    'SEAT_HOLD_RESULT ' + ($issue65Result | ConvertTo-Json -Compress -Depth 4)
}

function New-Issue65SnapshotText {
    param([long]$Held = 100, [bool]$Invariant = $true)
    $issue65Snapshot = [ordered]@{
        expectedTotalSeats = 2000
        actualSeatCount = 2000
        remainingSeats = 2000
        reservedSeats = 0
        activeHeldSeats = $Held
        holdRows = $Held
        partialHoldStates = 0
        reservations = 0
        bookings = 0
        payments = 0
        invariantSatisfied = $Invariant
    }
    'SEAT_HOLD_FINAL_SNAPSHOT ' + ($issue65Snapshot | ConvertTo-Json -Compress)
}

function New-Issue65MetricSummary {
    param([double]$P95, [double]$Attainment = 1.0, [double]$Unexpected = 0.0)
    [pscustomobject]@{
        ValidMeasurement = $true
        K6 = [pscustomobject]@{
            StateInvariantSatisfied = $true
            Result = [pscustomobject]@{
                Iterations = 100
                DroppedIterations = 0
                CompletedIterationsPerScheduledSecond = 10
                ScheduledIterationAttainmentRate = $Attainment
                HoldSuccess = 100
                ExpectedContention = 0
                UnexpectedFailureRate = $Unexpected
                HoldDurationMs = [pscustomobject]@{ P95 = $P95 }
            }
            FinalSnapshot = [pscustomobject]@{ activeHeldSeats = 100 }
        }
        Metrics = [pscustomobject]@{
            Peaks = [pscustomobject]@{ HikariActive = 2; HikariPending = 0 }
            Deltas = [pscustomobject]@{ DbRowLockWaits = 3; DbRowLockTimeMs = 4; DbDeadlocks = 0 }
        }
    }
}

$issue65Raw = ConvertFrom-SeatHoldK6Result -Text (New-Issue65ResultText)
Assert-Issue65Equal $issue65Raw.scenario 'distributed' 'scenario parser'
Assert-Issue65Equal $issue65Raw.holdSuccess 100 'success parser'
Assert-Issue65Equal $issue65Raw.holdDurationMs.p95 20 'duration parser'
Assert-Issue65Throws { ConvertFrom-SeatHoldK6Result -Text 'missing' } 'missing result must fail'
Assert-Issue65Throws { ConvertFrom-SeatHoldK6Result -Text ((New-Issue65ResultText) + "`n" + (New-Issue65ResultText)) } 'duplicate result must fail'

$issue65Snapshot = ConvertFrom-SeatHoldFinalSnapshot -Text (New-Issue65SnapshotText)
Assert-Issue65Equal $issue65Snapshot.activeHeldSeats 100 'snapshot held parser'
Assert-Issue65True $issue65Snapshot.invariantSatisfied 'snapshot invariant parser'
Assert-Issue65Throws { ConvertFrom-SeatHoldFinalSnapshot -Text 'missing' } 'missing snapshot must fail'

Assert-Issue65True (Assert-SeatHoldRunIdentity -Result $issue65Raw -Scenario distributed -Rate 10 -DurationSeconds 10 -ThresholdsEnforced $false) 'identity accepts exact request'
Assert-Issue65Throws { Assert-SeatHoldRunIdentity -Result $issue65Raw -Scenario hot-seat -Rate 10 -DurationSeconds 10 -ThresholdsEnforced $false } 'scenario mismatch'
Assert-Issue65Throws { Assert-SeatHoldRunIdentity -Result $issue65Raw -Scenario distributed -Rate 20 -DurationSeconds 10 -ThresholdsEnforced $false } 'rate mismatch'

$issue65Summary = New-SeatHoldRunSummary -Result $issue65Raw -DurationSeconds 10
Assert-Issue65Equal $issue65Summary.Iterations 100 'summary iterations'
Assert-Issue65Equal $issue65Summary.CompletedIterationsPerScheduledSecond 10 'summary throughput'
Assert-Issue65Equal $issue65Summary.ScheduledIterationAttainmentRate 1 'summary attainment'
Assert-Issue65Equal $issue65Summary.HoldDurationMs.P95 20 'summary p95'
Assert-Issue65Throws {
    New-SeatHoldRunSummary -Result (ConvertFrom-SeatHoldK6Result -Text (New-Issue65ResultText -Iterations 100 -Success 99)) -DurationSeconds 10
} 'counter mismatch'

Assert-Issue65True (Assert-SeatHoldFinalState -Summary $issue65Summary -Snapshot $issue65Snapshot) 'distributed final state'
$issue65HotSectionRaw = ConvertFrom-SeatHoldK6Result -Text (New-Issue65ResultText -Scenario hot-section -Success 40 -Contention 60)
$issue65HotSectionSummary = New-SeatHoldRunSummary -Result $issue65HotSectionRaw -DurationSeconds 10
$issue65HotSectionSnapshot = ConvertFrom-SeatHoldFinalSnapshot -Text (New-Issue65SnapshotText -Held 40)
Assert-Issue65True (Assert-SeatHoldFinalState -Summary $issue65HotSectionSummary -Snapshot $issue65HotSectionSnapshot) 'hot-section final state allows owner retries'
$issue65FalseHotRaw = ConvertFrom-SeatHoldK6Result -Text (New-Issue65ResultText -Scenario hot-section -Success 100 -Contention 0)
$issue65FalseHotSummary = New-SeatHoldRunSummary -Result $issue65FalseHotRaw -DurationSeconds 10
Assert-Issue65Throws {
    Assert-SeatHoldFinalState -Summary $issue65FalseHotSummary -Snapshot $issue65HotSectionSnapshot
} 'hot-section without 409 contention must fail'
$issue65HotSeatRaw = ConvertFrom-SeatHoldK6Result -Text (New-Issue65ResultText -Scenario hot-seat -Success 2 -Contention 98)
$issue65HotSeatSummary = New-SeatHoldRunSummary -Result $issue65HotSeatRaw -DurationSeconds 10
$issue65HotSeatSnapshot = ConvertFrom-SeatHoldFinalSnapshot -Text (New-Issue65SnapshotText -Held 1)
Assert-Issue65True (Assert-SeatHoldFinalState -Summary $issue65HotSeatSummary -Snapshot $issue65HotSeatSnapshot) 'hot-seat final state allows owner retry'
Assert-Issue65Throws { Assert-SeatHoldFinalState -Summary $issue65Summary -Snapshot (ConvertFrom-SeatHoldFinalSnapshot -Text (New-Issue65SnapshotText -Held 99)) } 'distributed persistence mismatch'
Assert-Issue65Throws { Assert-SeatHoldFinalState -Summary $issue65Summary -Snapshot (ConvertFrom-SeatHoldFinalSnapshot -Text (New-Issue65SnapshotText -Held 100 -Invariant $false)) } 'false invariant'

$issue65Plan = @(New-SeatHoldBaselinePlan -DurationSeconds 10 -Repeats 3 -TotalSeats 2000)
Assert-Issue65Equal $issue65Plan.Count 28 'plan count'
Assert-Issue65Equal @($issue65Plan | Where-Object Warmup).Count 1 'warmup count'
Assert-Issue65Equal @($issue65Plan | Where-Object { -not $_.Warmup -and $_.Scenario -eq 'distributed' }).Count 9 'distributed measured count'
Assert-Issue65Equal @($issue65Plan | Where-Object { -not $_.Warmup -and $_.Scenario -eq 'hot-section' }).Count 9 'section measured count'
Assert-Issue65Equal @($issue65Plan | Where-Object { -not $_.Warmup -and $_.Scenario -eq 'hot-seat' }).Count 9 'seat measured count'
Assert-Issue65Equal ($issue65Plan | Select-Object -Last 1).Rate 200 'last rate'
Assert-Issue65Throws { New-SeatHoldBaselinePlan -DurationSeconds 10 -Repeats 1 -TotalSeats 1500 } 'distributed inventory cap'

$issue65Healthy = New-Issue65MetricSummary -P95 100
Assert-Issue65Equal @(Get-SeatHoldBaselineStopReasons -Summary $issue65Healthy).Count 0 'healthy stop reasons'
$issue65Slow = New-Issue65MetricSummary -P95 2500 -Attainment 0.95 -Unexpected 0.1
$issue65SlowReasons = @(Get-SeatHoldBaselineStopReasons -Summary $issue65Slow)
Assert-Issue65True ($issue65SlowReasons -contains 'seat-hold-p95') 'p95 stop reason'
Assert-Issue65True ($issue65SlowReasons -contains 'dropped-iteration-rate') 'dropped stop reason'
Assert-Issue65True ($issue65SlowReasons -contains 'unexpected-failure-rate') 'failure stop reason'

$issue65Records = @(
    [pscustomobject]@{ Scenario = 'distributed'; Rate = 50; Summary = (New-Issue65MetricSummary -P95 30) },
    [pscustomobject]@{ Scenario = 'distributed'; Rate = 50; Summary = (New-Issue65MetricSummary -P95 20) },
    [pscustomobject]@{ Scenario = 'distributed'; Rate = 50; Summary = (New-Issue65MetricSummary -P95 40) }
)
$issue65Aggregate = @(New-SeatHoldBaselineAggregate -Records $issue65Records)
Assert-Issue65Equal $issue65Aggregate.Count 1 'aggregate count'
Assert-Issue65Equal $issue65Aggregate[0].RepeatCount 3 'aggregate repeats'
Assert-Issue65Equal $issue65Aggregate[0].HoldP95Ms.Median 30 'aggregate median'
Assert-Issue65Equal $issue65Aggregate[0].HoldP95Ms.Minimum 20 'aggregate minimum'
Assert-Issue65Equal $issue65Aggregate[0].HoldP95Ms.Maximum 40 'aggregate maximum'
Assert-Issue65Equal $issue65Aggregate[0].DbDeadlocksDelta.Median 0 'aggregate deadlocks'

Write-Output "SEAT_HOLD_CONTENTION_TESTS_PASSED assertions=$issue65Assertions"
