Set-StrictMode -Version Latest

function New-ContentionBottleneckDiagnosisPlan {
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
        throw "Distributed diagnosis can exceed fixture inventory: duration=$DurationSeconds seats=$TotalSeats"
    }
    $issue55Plan = New-Object 'Collections.Generic.List[object]'
    $issue55Sequence = 1
    $issue55Plan.Add([pscustomobject]@{
        Sequence = $issue55Sequence
        Rate = 20
        Repeat = 0
        Warmup = $true
    })
    $issue55Sequence += 1
    foreach ($issue55Rate in @(50, 100)) {
        for ($issue55Repeat = 1; $issue55Repeat -le $Repeats; $issue55Repeat += 1) {
            $issue55Plan.Add([pscustomobject]@{
                Sequence = $issue55Sequence
                Rate = $issue55Rate
                Repeat = $issue55Repeat
                Warmup = $false
            })
            $issue55Sequence += 1
        }
    }
    $issue55Plan.ToArray()
}

function Get-Issue55Median {
    param([double[]]$Values)

    if ($Values.Count -eq 0) {
        throw 'At least one value is required for a median.'
    }
    $issue55Sorted = @($Values | Sort-Object)
    $issue55Middle = [math]::Floor($issue55Sorted.Count / 2)
    if (($issue55Sorted.Count % 2) -eq 1) {
        return [double]$issue55Sorted[$issue55Middle]
    }
    ([double]$issue55Sorted[$issue55Middle - 1] + [double]$issue55Sorted[$issue55Middle]) / 2.0
}

function New-Issue55Range {
    param([double[]]$Values)

    [pscustomobject]@{
        Median = Get-Issue55Median -Values $Values
        Minimum = [double](($Values | Measure-Object -Minimum).Minimum)
        Maximum = [double](($Values | Measure-Object -Maximum).Maximum)
    }
}

function New-ContentionBottleneckDiagnosisAggregate {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)]
        [object[]]$Records
    )

    $issue55Aggregates = New-Object 'Collections.Generic.List[object]'
    foreach ($issue55Group in ($Records | Group-Object -Property Rate)) {
        $issue55Items = @($issue55Group.Group)
        $issue55Summaries = @($issue55Items | ForEach-Object { $_.Summary })
        foreach ($issue55Summary in $issue55Summaries) {
            if ($null -eq $issue55Summary.DatabaseStatementDigests) {
                throw 'A diagnosis record is missing database statement digests.'
            }
            if ([double]$issue55Summary.DatabaseStatementDigests.SeatLockSelect.AverageMilliseconds -le 0) {
                throw 'Seat lock statement average must be positive for ratio calculation.'
            }
        }
        $issue55Aggregates.Add([pscustomobject]@{
            Scenario = 'distributed'
            Rate = [int]$issue55Items[0].Rate
            RepeatCount = $issue55Items.Count
            Iterations = New-Issue55Range -Values @($issue55Summaries | ForEach-Object { [double]$_.K6.Result.Iterations })
            DroppedIterations = New-Issue55Range -Values @($issue55Summaries | ForEach-Object { [double]$_.K6.Result.DroppedIterations })
            CompletedIterationsPerScheduledSecond = New-Issue55Range -Values @($issue55Summaries | ForEach-Object { [double]$_.K6.Result.CompletedIterationsPerScheduledSecond })
            ScheduledIterationAttainmentRate = New-Issue55Range -Values @($issue55Summaries | ForEach-Object { [double]$_.K6.Result.ScheduledIterationAttainmentRate })
            ReservationP95Ms = New-Issue55Range -Values @($issue55Summaries | ForEach-Object { [double]$_.K6.Result.ReservationDurationMs.P95 })
            HikariActivePeak = New-Issue55Range -Values @($issue55Summaries | ForEach-Object { [double]$_.Metrics.Peaks.HikariActive })
            HikariPendingPeak = New-Issue55Range -Values @($issue55Summaries | ForEach-Object { [double]$_.Metrics.Peaks.HikariPending })
            DbRowLockWaitsDelta = New-Issue55Range -Values @($issue55Summaries | ForEach-Object { [double]$_.Metrics.Deltas.DbRowLockWaits })
            DbRowLockTimeMsDelta = New-Issue55Range -Values @($issue55Summaries | ForEach-Object { [double]$_.Metrics.Deltas.DbRowLockTimeMs })
            DbDeadlocksDelta = New-Issue55Range -Values @($issue55Summaries | ForEach-Object { [double]$_.Metrics.Deltas.DbDeadlocks })
            SeatLockSelect = [pscustomobject]@{
                Count = New-Issue55Range -Values @($issue55Summaries | ForEach-Object { [double]$_.DatabaseStatementDigests.SeatLockSelect.Count })
                TotalMilliseconds = New-Issue55Range -Values @($issue55Summaries | ForEach-Object { [double]$_.DatabaseStatementDigests.SeatLockSelect.TotalMilliseconds })
                AverageMilliseconds = New-Issue55Range -Values @($issue55Summaries | ForEach-Object { [double]$_.DatabaseStatementDigests.SeatLockSelect.AverageMilliseconds })
                MaximumMilliseconds = New-Issue55Range -Values @($issue55Summaries | ForEach-Object { [double]$_.DatabaseStatementDigests.SeatLockSelect.MaximumMilliseconds })
            }
            ConcertTimeDecrement = [pscustomobject]@{
                Count = New-Issue55Range -Values @($issue55Summaries | ForEach-Object { [double]$_.DatabaseStatementDigests.ConcertTimeDecrement.Count })
                TotalMilliseconds = New-Issue55Range -Values @($issue55Summaries | ForEach-Object { [double]$_.DatabaseStatementDigests.ConcertTimeDecrement.TotalMilliseconds })
                AverageMilliseconds = New-Issue55Range -Values @($issue55Summaries | ForEach-Object { [double]$_.DatabaseStatementDigests.ConcertTimeDecrement.AverageMilliseconds })
                MaximumMilliseconds = New-Issue55Range -Values @($issue55Summaries | ForEach-Object { [double]$_.DatabaseStatementDigests.ConcertTimeDecrement.MaximumMilliseconds })
            }
            ConcertToSeatAverageRatio = New-Issue55Range -Values @($issue55Summaries | ForEach-Object {
                [double]$_.DatabaseStatementDigests.ConcertTimeDecrement.AverageMilliseconds /
                    [double]$_.DatabaseStatementDigests.SeatLockSelect.AverageMilliseconds
            })
            SeatToConcertAverageRatio = New-Issue55Range -Values @($issue55Summaries | ForEach-Object {
                [double]$_.DatabaseStatementDigests.SeatLockSelect.AverageMilliseconds /
                    [double]$_.DatabaseStatementDigests.ConcertTimeDecrement.AverageMilliseconds
            })
        })
    }
    @($issue55Aggregates.ToArray() | Sort-Object -Property Rate)
}

Export-ModuleMember -Function @(
    'New-ContentionBottleneckDiagnosisPlan',
    'New-ContentionBottleneckDiagnosisAggregate'
)
