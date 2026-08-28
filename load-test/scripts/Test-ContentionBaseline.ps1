[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

Import-Module (Join-Path $PSScriptRoot 'ContentionBaseline.psm1') -Force
$issue53Assertions = 0

function Assert-Issue53Equal {
    param($Actual, $Expected, [string]$Message)
    $script:issue53Assertions += 1
    if ($Actual -ne $Expected) {
        throw "$Message Expected=$Expected Actual=$Actual"
    }
}

function Assert-Issue53Throws {
    param([scriptblock]$Action, [string]$Message)
    $script:issue53Assertions += 1
    try {
        & $Action
    } catch {
        return
    }
    throw $Message
}

$issue53Plan = @(New-ContentionBaselinePlan)
Assert-Issue53Equal $issue53Plan.Count 28 'The default plan must contain one warmup and 27 measured runs.'
Assert-Issue53Equal @($issue53Plan | Where-Object Warmup).Count 1 'The default plan must contain one warmup.'
Assert-Issue53Equal @($issue53Plan | Where-Object { $_.Scenario -eq 'distributed' -and -not $_.Warmup }).Count 12 'Distributed must have four rates and three repeats.'
Assert-Issue53Equal @($issue53Plan | Where-Object { $_.Scenario -eq 'hot-section' }).Count 9 'Hot-section must have three rates and three repeats.'
Assert-Issue53Equal @($issue53Plan | Where-Object { $_.Scenario -eq 'hot-seat' }).Count 6 'Hot-seat must have two rates and three repeats.'
Assert-Issue53Throws { New-ContentionBaselinePlan -DurationSeconds 20 -TotalSeats 2000 } 'A distributed plan that can exhaust inventory must be rejected.'

function New-Issue53FixtureSummary {
    param(
        [double]$P95,
        [double]$Attainment,
        [double]$UnexpectedRate,
        [double]$CompletedRate,
        [double]$Pending,
        [double]$LockWaits
    )
    [pscustomobject]@{
        ValidMeasurement = $true
        K6 = [pscustomobject]@{
            InventoryInvariantSatisfied = $true
            Result = [pscustomobject]@{
                ReservationDurationMs = [pscustomobject]@{ P95 = $P95 }
                ScheduledIterationAttainmentRate = $Attainment
                UnexpectedFailureRate = $UnexpectedRate
                CompletedIterationsPerScheduledSecond = $CompletedRate
                Iterations = 1000
                DroppedIterations = 0
                ReservationSuccess = 1000
                ExpectedContention = 0
                UnexpectedNonSuccessful = 0
            }
        }
        Metrics = [pscustomobject]@{
            Peaks = [pscustomobject]@{ HikariActive = 5; HikariPending = $Pending; HikariMax = 10 }
            Deltas = [pscustomobject]@{ DbRowLockWaits = $LockWaits; DbRowLockTimeMs = 50; DbDeadlocks = 0 }
        }
    }
}

$issue53Healthy = New-Issue53FixtureSummary -P95 100 -Attainment 1.0 -UnexpectedRate 0 -CompletedRate 100 -Pending 0 -LockWaits 2
Assert-Issue53Equal @(Get-ContentionBaselineStopReasons -Summary $issue53Healthy).Count 0 'A healthy run must not stop higher stages.'
$issue53Degraded = New-Issue53FixtureSummary -P95 2000 -Attainment 0.98 -UnexpectedRate 0.05 -CompletedRate 98 -Pending 4 -LockWaits 20
$issue53Reasons = @(Get-ContentionBaselineStopReasons -Summary $issue53Degraded)
Assert-Issue53Equal ($issue53Reasons -contains 'reservation-p95') $true 'The p95 stop condition must be detected.'
Assert-Issue53Equal ($issue53Reasons -contains 'dropped-iteration-rate') $true 'The dropped iteration stop condition must be detected.'
Assert-Issue53Equal ($issue53Reasons -contains 'unexpected-failure-rate') $true 'The unexpected failure stop condition must be detected.'

$issue53Records = @(
    [pscustomobject]@{ Scenario = 'distributed'; Rate = 100; Summary = (New-Issue53FixtureSummary -P95 90 -Attainment 1 -UnexpectedRate 0 -CompletedRate 99 -Pending 0 -LockWaits 1) },
    [pscustomobject]@{ Scenario = 'distributed'; Rate = 100; Summary = (New-Issue53FixtureSummary -P95 100 -Attainment 0.99 -UnexpectedRate 0.01 -CompletedRate 100 -Pending 1 -LockWaits 2) },
    [pscustomobject]@{ Scenario = 'distributed'; Rate = 100; Summary = (New-Issue53FixtureSummary -P95 200 -Attainment 0.98 -UnexpectedRate 0.02 -CompletedRate 101 -Pending 2 -LockWaits 3) }
)
$issue53Aggregate = @(New-ContentionBaselineAggregate -Records $issue53Records)
Assert-Issue53Equal $issue53Aggregate.Count 1 'One scenario/rate group must produce one aggregate.'
Assert-Issue53Equal $issue53Aggregate[0].RepeatCount 3 'All repeated runs must be counted.'
Assert-Issue53Equal $issue53Aggregate[0].ReservationP95Ms.Median 100 'The aggregate must report the median p95.'
Assert-Issue53Equal $issue53Aggregate[0].ReservationP95Ms.Minimum 90 'The aggregate must report the minimum p95.'
Assert-Issue53Equal $issue53Aggregate[0].ReservationP95Ms.Maximum 200 'The aggregate must report the maximum p95.'
Assert-Issue53Equal $issue53Aggregate[0].HikariPendingPeak.Median 1 'The aggregate must report the median Hikari pending peak.'
Assert-Issue53Equal $issue53Aggregate[0].DbRowLockWaitsDelta.Median 2 'The aggregate must report the median DB lock wait delta.'
Assert-Issue53Equal $issue53Aggregate[0].ReservationSuccess.Median 1000 'The aggregate must report the median success count.'
Assert-Issue53Equal $issue53Aggregate[0].HikariMax.Median 10 'The aggregate must report the configured Hikari maximum.'

Write-Output "ContentionBaseline checks passed: $issue53Assertions assertions."
