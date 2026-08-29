[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

Import-Module (Join-Path $PSScriptRoot 'ContentionBottleneckDiagnosis.psm1') -Force
$issue55Assertions = 0

function Assert-Issue55Equal {
    param($Actual, $Expected, [string]$Message)
    $script:issue55Assertions += 1
    if ($Actual -ne $Expected) {
        throw "$Message Expected=$Expected Actual=$Actual"
    }
}

function Assert-Issue55Throws {
    param([scriptblock]$Action, [string]$Message)
    $script:issue55Assertions += 1
    try {
        & $Action
    } catch {
        return
    }
    throw $Message
}

$issue55Plan = @(New-ContentionBottleneckDiagnosisPlan)
Assert-Issue55Equal $issue55Plan.Count 7 'The diagnosis plan must contain one warmup and six measured runs.'
Assert-Issue55Equal @($issue55Plan | Where-Object Warmup).Count 1 'The diagnosis plan must contain one warmup.'
Assert-Issue55Equal @($issue55Plan | Where-Object { -not $_.Warmup -and $_.Rate -eq 50 }).Count 3 'The 50 RPS stage must repeat three times.'
Assert-Issue55Equal @($issue55Plan | Where-Object { -not $_.Warmup -and $_.Rate -eq 100 }).Count 3 'The 100 RPS stage must repeat three times.'
Assert-Issue55Throws { New-ContentionBottleneckDiagnosisPlan -DurationSeconds 20 -TotalSeats 2000 } 'A plan that can exhaust distributed inventory must fail.'

function New-Issue55Summary {
    param(
        [double]$P95,
        [double]$Pending,
        [double]$DbLockTime,
        [double]$SeatAverage,
        [double]$CounterAverage
    )
    [pscustomobject]@{
        K6 = [pscustomobject]@{
            Result = [pscustomobject]@{
                Iterations = 1000
                DroppedIterations = 0
                CompletedIterationsPerScheduledSecond = 100
                ScheduledIterationAttainmentRate = 1
                ReservationDurationMs = [pscustomobject]@{ P95 = $P95 }
            }
        }
        Metrics = [pscustomobject]@{
            Peaks = [pscustomobject]@{ HikariActive = 10; HikariPending = $Pending }
            Deltas = [pscustomobject]@{ DbRowLockWaits = 900; DbRowLockTimeMs = $DbLockTime; DbDeadlocks = 0 }
        }
        DatabaseStatementDigests = [pscustomobject]@{
            SeatLockSelect = [pscustomobject]@{ Count = 1000; TotalMilliseconds = ($SeatAverage * 1000); AverageMilliseconds = $SeatAverage; MaximumMilliseconds = ($SeatAverage * 2) }
            ConcertTimeDecrement = [pscustomobject]@{ Count = 1000; TotalMilliseconds = ($CounterAverage * 1000); AverageMilliseconds = $CounterAverage; MaximumMilliseconds = ($CounterAverage * 2) }
        }
    }
}

$issue55Records = @(
    [pscustomobject]@{ Rate = 100; Summary = (New-Issue55Summary -P95 2400 -Pending 180 -DbLockTime 90000 -SeatAverage 2 -CounterAverage 90) },
    [pscustomobject]@{ Rate = 100; Summary = (New-Issue55Summary -P95 2500 -Pending 190 -DbLockTime 93000 -SeatAverage 3 -CounterAverage 96) },
    [pscustomobject]@{ Rate = 100; Summary = (New-Issue55Summary -P95 2600 -Pending 200 -DbLockTime 96000 -SeatAverage 4 -CounterAverage 100) }
)
$issue55Aggregate = @(New-ContentionBottleneckDiagnosisAggregate -Records $issue55Records)
Assert-Issue55Equal $issue55Aggregate.Count 1 'One rate group must produce one aggregate.'
Assert-Issue55Equal $issue55Aggregate[0].RepeatCount 3 'All diagnosis repeats must be counted.'
Assert-Issue55Equal $issue55Aggregate[0].ReservationP95Ms.Median 2500 'Reservation p95 median must be aggregated.'
Assert-Issue55Equal $issue55Aggregate[0].HikariPendingPeak.Median 190 'Hikari pending median must be aggregated.'
Assert-Issue55Equal $issue55Aggregate[0].DbRowLockTimeMsDelta.Median 93000 'DB lock time median must be aggregated.'
Assert-Issue55Equal $issue55Aggregate[0].SeatLockSelect.AverageMilliseconds.Median 3 'Seat lock statement median must be aggregated.'
Assert-Issue55Equal $issue55Aggregate[0].ConcertTimeDecrement.AverageMilliseconds.Median 96 'Counter update statement median must be aggregated.'
Assert-Issue55Equal $issue55Aggregate[0].ConcertToSeatAverageRatio.Median 32 'Per-run counter/seat ratios must be aggregated.'
Assert-Issue55Equal $issue55Aggregate[0].SeatToConcertAverageRatio.Median 0.03125 'Per-run seat/counter ratios must be aggregated.'
$issue55MissingDigests = $issue55Records[0].PSObject.Copy()
$issue55MissingDigests.Summary = $issue55Records[0].Summary.PSObject.Copy()
$issue55MissingDigests.Summary.DatabaseStatementDigests = $null
Assert-Issue55Throws { New-ContentionBottleneckDiagnosisAggregate -Records @($issue55MissingDigests) } 'Missing statement digests must fail aggregation.'

Write-Output "ContentionBottleneckDiagnosis checks passed: $issue55Assertions assertions."
