[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

Import-Module (Join-Path $PSScriptRoot 'SeatIndexContentionComparison.psm1') -Force
$issue57Assertions = 0

function Assert-Issue57Equal {
    param($Actual, $Expected, [string]$Message)
    $script:issue57Assertions += 1
    if ($Actual -ne $Expected) {
        throw "$Message Expected=$Expected Actual=$Actual"
    }
}

function Assert-Issue57Near {
    param([double]$Actual, [double]$Expected, [double]$Tolerance, [string]$Message)
    $script:issue57Assertions += 1
    if ([math]::Abs($Actual - $Expected) -gt $Tolerance) {
        throw "$Message Expected=$Expected Actual=$Actual"
    }
}

function Assert-Issue57Throws {
    param([scriptblock]$Action, [string]$Message)
    $script:issue57Assertions += 1
    try {
        & $Action
    } catch {
        return
    }
    throw $Message
}

$issue57Plan = @(New-SeatIndexContentionComparisonPlan)
Assert-Issue57Equal $issue57Plan.Count 14 'The A/B plan must contain two warmups and twelve measured runs.'
Assert-Issue57Equal @($issue57Plan | Where-Object Warmup).Count 2 'Each index variant must have one warmup.'
Assert-Issue57Equal ($issue57Plan[0].Variant + ',' + $issue57Plan[1].Variant) 'current,composite' 'Warmup order must cover both variants.'
Assert-Issue57Equal (@($issue57Plan | Where-Object { -not $_.Warmup -and $_.Rate -eq 50 -and $_.Variant -eq 'current' }).Count) 3 '50 RPS current must repeat three times.'
Assert-Issue57Equal (@($issue57Plan | Where-Object { -not $_.Warmup -and $_.Rate -eq 50 -and $_.Variant -eq 'composite' }).Count) 3 '50 RPS composite must repeat three times.'
Assert-Issue57Equal ($issue57Plan[4].Variant + ',' + $issue57Plan[5].Variant) 'composite,current' 'The second pair must reverse variant order.'
Assert-Issue57Throws { New-SeatIndexContentionComparisonPlan -DurationSeconds 20 } 'A plan that can exhaust inventory must fail.'
$issue57CompleteBatch = @($issue57Plan | ForEach-Object {
    [pscustomobject]@{ Sequence=$_.Sequence; RunId="batch-$($_.Sequence)"; Rate=$_.Rate; Repeat=$_.Repeat; Warmup=$_.Warmup; Variant=$_.Variant }
})
Assert-Issue57Equal (Test-SeatIndexContentionBatchComplete -Records $issue57CompleteBatch -Plan $issue57Plan) $true 'A complete ordered batch must be recognized.'
Assert-Issue57Equal (Test-SeatIndexContentionBatchComplete -Records @($issue57CompleteBatch | Select-Object -First 13) -Plan $issue57Plan) $false 'A partial batch must never be complete.'
$issue57WrongOrderBatch = @($issue57CompleteBatch | ForEach-Object { $_.PSObject.Copy() })
$issue57WrongOrderBatch[2].Variant = 'composite'
Assert-Issue57Equal (Test-SeatIndexContentionBatchComplete -Records $issue57WrongOrderBatch -Plan $issue57Plan) $false 'A batch that does not match the planned variant order must be invalid.'

function New-Issue57ComparisonSummary {
    param(
        [string]$Variant,
        [double]$Completed,
        [double]$Attainment,
        [double]$Dropped,
        [double]$P95,
        [double]$Pending,
        [double]$LockTime,
        [double]$SeatAverage
    )

    [pscustomobject]@{
        FixturePreparation = [pscustomobject]@{ TotalSeats = 2000 }
        SeatIndexExperiment = [pscustomobject]@{ Variant = $Variant; PhysicalSeatRows = 2000; StatisticsAnalyzed = $true }
        K6 = [pscustomobject]@{
            Result = [pscustomobject]@{
                Iterations = ($Completed * 10)
                DroppedIterations = $Dropped
                CompletedIterationsPerScheduledSecond = $Completed
                ScheduledIterationAttainmentRate = $Attainment
                ReservationDurationMs = [pscustomobject]@{ P95 = $P95 }
                UnexpectedNonSuccessful = 0
            }
        }
        Metrics = [pscustomobject]@{
            Peaks = [pscustomobject]@{ HikariActive = 10; HikariPending = $Pending }
            Deltas = [pscustomobject]@{ DbRowLockWaits = 500; DbRowLockTimeMs = $LockTime; DbDeadlocks = 0 }
        }
        DatabaseStatementDigests = [pscustomobject]@{
            SeatLockSelect = [pscustomobject]@{ Count = 1000; TotalMilliseconds = ($SeatAverage * 1000); AverageMilliseconds = $SeatAverage; MaximumMilliseconds = ($SeatAverage * 2) }
            ConcertTimeDecrement = [pscustomobject]@{ Count = 1000; TotalMilliseconds = 300; AverageMilliseconds = 0.3; MaximumMilliseconds = 1 }
            Coverage = [pscustomobject]@{ MinimumObservedRate = 1.0 }
            InstrumentationHealth = [pscustomobject]@{ RequiredMinimumCoverageRate = 0.95; PerformanceSchemaDigestLost = 0; NullDigestEvents = 0 }
        }
    }
}

$issue57Records = New-Object 'Collections.Generic.List[object]'
foreach ($issue57Repeat in 1..3) {
    $issue57Records.Add([pscustomobject]@{
        Rate = 100
        Repeat = $issue57Repeat
        Warmup = $false
        Variant = 'current'
        FixtureIsolation = [pscustomobject]@{ LoadTestFixturesRemoved = $true; SeatRowsAfterCleanup = 0 }
        Summary = New-Issue57ComparisonSummary -Variant current -Completed (70 + $issue57Repeat) -Attainment 0.72 -Dropped 280 -P95 (3900 + ($issue57Repeat * 100)) -Pending 189 -LockTime 100000 -SeatAverage 150
    })
    $issue57Records.Add([pscustomobject]@{
        Rate = 100
        Repeat = $issue57Repeat
        Warmup = $false
        Variant = 'composite'
        FixtureIsolation = [pscustomobject]@{ LoadTestFixturesRemoved = $true; SeatRowsAfterCleanup = 0 }
        Summary = New-Issue57ComparisonSummary -Variant composite -Completed (98 + $issue57Repeat) -Attainment 1 -Dropped 0 -P95 (180 + ($issue57Repeat * 10)) -Pending 0 -LockTime 2000 -SeatAverage 2
    })
}

$issue57Aggregate = New-SeatIndexContentionComparisonAggregate -Records $issue57Records.ToArray()
Assert-Issue57Equal $issue57Aggregate.VariantAggregates.Count 2 'One rate must produce two variant aggregates.'
Assert-Issue57Equal $issue57Aggregate.Comparisons.Count 1 'One rate must produce one comparison.'
$issue57Comparison = $issue57Aggregate.Comparisons[0]
Assert-Issue57Equal $issue57Comparison.RepeatCountPerVariant 3 'The comparison must retain three repeats per variant.'
Assert-Issue57Equal $issue57Comparison.ReservationP95Ms.CurrentMedian 4100 'Current p95 median must be preserved.'
Assert-Issue57Equal $issue57Comparison.ReservationP95Ms.CompositeMedian 200 'Composite p95 median must be preserved.'
Assert-Issue57Near $issue57Comparison.ReservationP95Ms.ImprovementPercent 95.121951 0.0001 'Lower p95 improvement must use the current median as baseline.'
Assert-Issue57Near $issue57Comparison.CompletedIterationsPerScheduledSecond.ImprovementPercent 38.888889 0.0001 'Higher completion improvement must use the current median as baseline.'
Assert-Issue57Near $issue57Comparison.SeatLockAverageMilliseconds.CompositeToCurrentRatio (2.0 / 150.0) 0.000001 'Seat statement ratio must compare composite to current.'
Assert-Issue57Near $issue57Comparison.DbRowLockTimeMsDelta.ImprovementPercent 98 0.0001 'DB lock time improvement must be aggregated.'
Assert-Issue57Equal $issue57Comparison.ConcertTimeDecrementAverageMilliseconds.CurrentMedian 0.3 'Current counter statement average must be preserved.'
Assert-Issue57Equal $issue57Comparison.ConcertTimeDecrementAverageMilliseconds.CompositeMedian 0.3 'Composite counter statement average must be preserved.'

$issue59RunnerSource = Get-Content -Raw -LiteralPath (Join-Path $PSScriptRoot 'Run-SeatIndexContentionComparison.ps1')
Assert-Issue57Equal ($issue59RunnerSource.Contains('PermanentCompositeSchemaRestored')) $true 'The A/B manifest must report permanent composite schema restoration.'
Assert-Issue57Equal ($issue59RunnerSource -match '(?s)finally\s*\{\s*try\s*\{\s*Set-Issue57SeatIndexVariant\s*`\s*-Variant composite') $true 'The A/B runner finally block must restore the permanent composite schema.'

$issue57Mismatched = $issue57Records[0].PSObject.Copy()
$issue57Mismatched.Summary = $issue57Records[0].Summary.PSObject.Copy()
$issue57Mismatched.Summary.SeatIndexExperiment = [pscustomobject]@{ Variant = 'composite' }
Assert-Issue57Throws {
    New-SeatIndexContentionComparisonAggregate -Records @($issue57Mismatched)
} 'Mismatched index evidence must fail aggregation.'

$issue57LowCoverage = $issue57Records[0].PSObject.Copy()
$issue57LowCoverage.Summary = $issue57Records[0].Summary.PSObject.Copy()
$issue57LowCoverage.Summary.DatabaseStatementDigests = $issue57Records[0].Summary.DatabaseStatementDigests.PSObject.Copy()
$issue57LowCoverage.Summary.DatabaseStatementDigests.Coverage = [pscustomobject]@{ MinimumObservedRate = 0.90 }
Assert-Issue57Throws {
    New-SeatIndexContentionComparisonAggregate -Records @($issue57LowCoverage)
} 'Digest coverage below the declared minimum must fail aggregation.'

$issue57Unexpected = $issue57Records[0].PSObject.Copy()
$issue57Unexpected.Summary = $issue57Records[0].Summary.PSObject.Copy()
$issue57Unexpected.Summary.K6 = $issue57Records[0].Summary.K6.PSObject.Copy()
$issue57Unexpected.Summary.K6.Result = $issue57Records[0].Summary.K6.Result.PSObject.Copy()
$issue57Unexpected.Summary.K6.Result.UnexpectedNonSuccessful = 1
Assert-Issue57Throws {
    New-SeatIndexContentionComparisonAggregate -Records @($issue57Unexpected)
} 'An aggregate must reject an unexpected response even if other evidence is valid.'

$issue57NonExclusive = $issue57Records[0].PSObject.Copy()
$issue57NonExclusive.FixtureIsolation = [pscustomobject]@{ LoadTestFixturesRemoved = $true; SeatRowsAfterCleanup = 2000 }
Assert-Issue57Throws {
    New-SeatIndexContentionComparisonAggregate -Records @($issue57NonExclusive)
} 'An aggregate must reject a run without an exclusive fixed-size fixture.'

$issue57StaleStatistics = $issue57Records[0].PSObject.Copy()
$issue57StaleStatistics.Summary = $issue57Records[0].Summary.PSObject.Copy()
$issue57StaleStatistics.Summary.SeatIndexExperiment = $issue57Records[0].Summary.SeatIndexExperiment.PSObject.Copy()
$issue57StaleStatistics.Summary.SeatIndexExperiment.StatisticsAnalyzed = $false
Assert-Issue57Throws {
    New-SeatIndexContentionComparisonAggregate -Records @($issue57StaleStatistics)
} 'An aggregate must reject a run without refreshed optimizer statistics.'

Write-Output "SeatIndexContentionComparison checks passed: $issue57Assertions assertions."
