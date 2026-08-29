Set-StrictMode -Version Latest

Import-Module (Join-Path $PSScriptRoot 'ContentionBottleneckDiagnosis.psm1') -Force

function New-SeatIndexContentionComparisonPlan {
    [CmdletBinding()]
    param(
        [ValidateRange(1, 3600)]
        [int]$DurationSeconds = 10,

        [ValidateRange(1, 10)]
        [int]$Repeats = 3,

        [ValidateRange(1, 10000)]
        [int]$TotalSeats = 2000
    )

    if (((100 * $DurationSeconds) + 1) -gt $TotalSeats) {
        throw "Distributed comparison can exceed fixture inventory: duration=$DurationSeconds seats=$TotalSeats"
    }
    $issue57Plan = New-Object 'Collections.Generic.List[object]'
    $issue57Sequence = 1
    foreach ($issue57WarmVariant in @('current', 'composite')) {
        $issue57Plan.Add([pscustomobject]@{
            Sequence = $issue57Sequence
            Rate = 20
            Repeat = 0
            Warmup = $true
            Variant = $issue57WarmVariant
        })
        $issue57Sequence += 1
    }
    foreach ($issue57Rate in @(50, 100)) {
        for ($issue57Repeat = 1; $issue57Repeat -le $Repeats; $issue57Repeat += 1) {
            $issue57VariantOrder = if (($issue57Repeat % 2) -eq 0) {
                @('composite', 'current')
            } else {
                @('current', 'composite')
            }
            foreach ($issue57Variant in $issue57VariantOrder) {
                $issue57Plan.Add([pscustomobject]@{
                    Sequence = $issue57Sequence
                    Rate = $issue57Rate
                    Repeat = $issue57Repeat
                    Warmup = $false
                    Variant = $issue57Variant
                })
                $issue57Sequence += 1
            }
        }
    }
    $issue57Plan.ToArray()
}

function New-Issue57MetricComparison {
    param(
        [double]$Current,
        [double]$Composite,
        [ValidateSet('lower', 'higher')]
        [string]$Better
    )

    $issue57Delta = $Composite - $Current
    $issue57Ratio = if ($Current -eq 0) { $null } else { $Composite / $Current }
    $issue57ImprovementPercent = if ($Current -eq 0) {
        $null
    } elseif ($Better -eq 'lower') {
        (($Current - $Composite) / $Current) * 100.0
    } else {
        (($Composite - $Current) / $Current) * 100.0
    }
    [pscustomobject]@{
        CurrentMedian = $Current
        CompositeMedian = $Composite
        CompositeMinusCurrent = $issue57Delta
        CompositeToCurrentRatio = $issue57Ratio
        ImprovementPercent = $issue57ImprovementPercent
        Better = $Better
    }
}

function Test-SeatIndexContentionBatchComplete {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)][object[]]$Records,
        [Parameter(Mandatory = $true)][object[]]$Plan
    )

    if ($Records.Count -ne $Plan.Count -or @($Records.RunId | Sort-Object -Unique).Count -ne $Records.Count) {
        return $false
    }
    for ($issue57Index = 0; $issue57Index -lt $Plan.Count; $issue57Index += 1) {
        $issue57Record = $Records[$issue57Index]
        $issue57Stage = $Plan[$issue57Index]
        if ([int]$issue57Record.Sequence -ne [int]$issue57Stage.Sequence -or
            [int]$issue57Record.Rate -ne [int]$issue57Stage.Rate -or
            [int]$issue57Record.Repeat -ne [int]$issue57Stage.Repeat -or
            [bool]$issue57Record.Warmup -ne [bool]$issue57Stage.Warmup -or
            [string]$issue57Record.Variant -ne [string]$issue57Stage.Variant) {
            return $false
        }
    }
    $true
}

function New-SeatIndexContentionComparisonAggregate {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)]
        [object[]]$Records
    )

    $issue57Measured = @($Records | Where-Object { -not $_.Warmup })
    if ($issue57Measured.Count -eq 0) {
        throw 'At least one measured A/B record is required.'
    }
    foreach ($issue57Record in $issue57Measured) {
        if ($issue57Record.Variant -notin @('current', 'composite')) {
            throw "Unknown seat index variant: $($issue57Record.Variant)"
        }
        if ($null -eq $issue57Record.Summary.SeatIndexExperiment -or
            $issue57Record.Summary.SeatIndexExperiment.Variant -ne $issue57Record.Variant) {
            throw 'A/B record is missing matching seat index evidence.'
        }
        if ($null -eq $issue57Record.FixtureIsolation -or
            -not [bool]$issue57Record.FixtureIsolation.LoadTestFixturesRemoved -or
            [long]$issue57Record.FixtureIsolation.SeatRowsAfterCleanup -ne 0 -or
            [long]$issue57Record.Summary.SeatIndexExperiment.PhysicalSeatRows -ne [long]$issue57Record.Summary.FixturePreparation.TotalSeats) {
            throw 'A/B record does not prove an exclusive fixed-size seat fixture.'
        }
        if (-not [bool]$issue57Record.Summary.SeatIndexExperiment.StatisticsAnalyzed) {
            throw 'A/B record does not prove that seat optimizer statistics were refreshed.'
        }
        if ([long]$issue57Record.Summary.K6.Result.UnexpectedNonSuccessful -ne 0 -or
            [long]$issue57Record.Summary.Metrics.Deltas.DbDeadlocks -ne 0) {
            throw 'A/B record contains an unexpected response or database deadlock.'
        }
        $issue57Digests = $issue57Record.Summary.DatabaseStatementDigests
        if ($null -eq $issue57Digests.Coverage -or $null -eq $issue57Digests.InstrumentationHealth) {
            throw 'A/B record is missing statement digest coverage evidence.'
        }
        if ([double]$issue57Digests.Coverage.MinimumObservedRate -lt [double]$issue57Digests.InstrumentationHealth.RequiredMinimumCoverageRate -or
            [long]$issue57Digests.InstrumentationHealth.PerformanceSchemaDigestLost -ne 0 -or
            [long]$issue57Digests.InstrumentationHealth.NullDigestEvents -ne 0) {
            throw 'A/B record contains invalid statement digest instrumentation health.'
        }
    }

    $issue57VariantAggregates = New-Object 'Collections.Generic.List[object]'
    foreach ($issue57Variant in @('current', 'composite')) {
        $issue57VariantRecords = @($issue57Measured | Where-Object { $_.Variant -eq $issue57Variant })
        if ($issue57VariantRecords.Count -eq 0) {
            throw "A/B records are missing variant: $issue57Variant"
        }
        foreach ($issue57Aggregate in @(New-ContentionBottleneckDiagnosisAggregate -Records $issue57VariantRecords)) {
            $issue57VariantAggregates.Add([pscustomobject]@{
                Variant = $issue57Variant
                Rate = $issue57Aggregate.Rate
                Metrics = $issue57Aggregate
            })
        }
    }

    $issue57Comparisons = New-Object 'Collections.Generic.List[object]'
    foreach ($issue57Rate in @($issue57Measured.Rate | Sort-Object -Unique)) {
        $issue57Current = @($issue57VariantAggregates | Where-Object { $_.Variant -eq 'current' -and $_.Rate -eq $issue57Rate })
        $issue57Composite = @($issue57VariantAggregates | Where-Object { $_.Variant -eq 'composite' -and $_.Rate -eq $issue57Rate })
        if ($issue57Current.Count -ne 1 -or $issue57Composite.Count -ne 1) {
            throw "Rate $issue57Rate must contain one aggregate for each variant."
        }
        if ($issue57Current[0].Metrics.RepeatCount -ne $issue57Composite[0].Metrics.RepeatCount) {
            throw "Rate $issue57Rate variants must contain the same repeat count."
        }
        $issue57CurrentMetrics = $issue57Current[0].Metrics
        $issue57CompositeMetrics = $issue57Composite[0].Metrics
        $issue57Comparisons.Add([pscustomobject]@{
            Rate = [int]$issue57Rate
            RepeatCountPerVariant = $issue57CurrentMetrics.RepeatCount
            CompletedIterationsPerScheduledSecond = New-Issue57MetricComparison -Better higher -Current $issue57CurrentMetrics.CompletedIterationsPerScheduledSecond.Median -Composite $issue57CompositeMetrics.CompletedIterationsPerScheduledSecond.Median
            ScheduledIterationAttainmentRate = New-Issue57MetricComparison -Better higher -Current $issue57CurrentMetrics.ScheduledIterationAttainmentRate.Median -Composite $issue57CompositeMetrics.ScheduledIterationAttainmentRate.Median
            DroppedIterations = New-Issue57MetricComparison -Better lower -Current $issue57CurrentMetrics.DroppedIterations.Median -Composite $issue57CompositeMetrics.DroppedIterations.Median
            ReservationP95Ms = New-Issue57MetricComparison -Better lower -Current $issue57CurrentMetrics.ReservationP95Ms.Median -Composite $issue57CompositeMetrics.ReservationP95Ms.Median
            HikariPendingPeak = New-Issue57MetricComparison -Better lower -Current $issue57CurrentMetrics.HikariPendingPeak.Median -Composite $issue57CompositeMetrics.HikariPendingPeak.Median
            DbRowLockWaitsDelta = New-Issue57MetricComparison -Better lower -Current $issue57CurrentMetrics.DbRowLockWaitsDelta.Median -Composite $issue57CompositeMetrics.DbRowLockWaitsDelta.Median
            DbRowLockTimeMsDelta = New-Issue57MetricComparison -Better lower -Current $issue57CurrentMetrics.DbRowLockTimeMsDelta.Median -Composite $issue57CompositeMetrics.DbRowLockTimeMsDelta.Median
            SeatLockAverageMilliseconds = New-Issue57MetricComparison -Better lower -Current $issue57CurrentMetrics.SeatLockSelect.AverageMilliseconds.Median -Composite $issue57CompositeMetrics.SeatLockSelect.AverageMilliseconds.Median
            SeatLockTotalMilliseconds = New-Issue57MetricComparison -Better lower -Current $issue57CurrentMetrics.SeatLockSelect.TotalMilliseconds.Median -Composite $issue57CompositeMetrics.SeatLockSelect.TotalMilliseconds.Median
            ConcertTimeDecrementAverageMilliseconds = New-Issue57MetricComparison -Better lower -Current $issue57CurrentMetrics.ConcertTimeDecrement.AverageMilliseconds.Median -Composite $issue57CompositeMetrics.ConcertTimeDecrement.AverageMilliseconds.Median
            ConcertTimeDecrementTotalMilliseconds = New-Issue57MetricComparison -Better lower -Current $issue57CurrentMetrics.ConcertTimeDecrement.TotalMilliseconds.Median -Composite $issue57CompositeMetrics.ConcertTimeDecrement.TotalMilliseconds.Median
        })
    }

    [pscustomobject]@{
        SchemaVersion = 1
        VariantAggregates = $issue57VariantAggregates.ToArray()
        Comparisons = $issue57Comparisons.ToArray()
    }
}

Export-ModuleMember -Function @(
    'New-SeatIndexContentionComparisonPlan',
    'Test-SeatIndexContentionBatchComplete',
    'New-SeatIndexContentionComparisonAggregate'
)
