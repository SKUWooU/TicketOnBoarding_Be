Set-StrictMode -Version Latest

function New-ContentionBaselinePlan {
    [CmdletBinding()]
    param(
        [ValidateRange(1, 3600)]
        [int]$DurationSeconds = 10,

        [ValidateRange(1, 10)]
        [int]$Repeats = 3,

        [ValidateRange(1, 10000)]
        [int]$TotalSeats = 2000
    )

    $issue53Plan = New-Object 'Collections.Generic.List[object]'
    $issue53Sequence = 1
    $issue53Plan.Add([pscustomobject]@{
        Sequence = $issue53Sequence
        Scenario = 'distributed'
        Rate = 20
        Repeat = 0
        Warmup = $true
    })
    $issue53Sequence += 1

    $issue53Matrix = [ordered]@{
        'distributed' = @(20, 50, 100, 150)
        'hot-section' = @(50, 100, 150)
        'hot-seat' = @(100, 150)
    }
    foreach ($issue53Scenario in $issue53Matrix.Keys) {
        foreach ($issue53Rate in $issue53Matrix[$issue53Scenario]) {
            if ($issue53Scenario -eq 'distributed' -and (($issue53Rate * $DurationSeconds) + 1) -gt $TotalSeats) {
                throw "Distributed stage can exceed fixture inventory: rate=$issue53Rate duration=$DurationSeconds seats=$TotalSeats"
            }
            for ($issue53Repeat = 1; $issue53Repeat -le $Repeats; $issue53Repeat += 1) {
                $issue53Plan.Add([pscustomobject]@{
                    Sequence = $issue53Sequence
                    Scenario = $issue53Scenario
                    Rate = $issue53Rate
                    Repeat = $issue53Repeat
                    Warmup = $false
                })
                $issue53Sequence += 1
            }
        }
    }

    $issue53Plan.ToArray()
}

function Get-ContentionBaselineStopReasons {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)]
        [object]$Summary
    )

    $issue53Reasons = New-Object 'Collections.Generic.List[string]'
    if (-not [bool]$Summary.ValidMeasurement) {
        $issue53Reasons.Add('invalid-measurement')
        return $issue53Reasons.ToArray()
    }
    if (-not [bool]$Summary.K6.InventoryInvariantSatisfied) {
        $issue53Reasons.Add('inventory-invariant')
    }
    $issue53K6 = $Summary.K6.Result
    if ([double]$issue53K6.UnexpectedFailureRate -ge 0.05) {
        $issue53Reasons.Add('unexpected-failure-rate')
    }
    if ((1.0 - [double]$issue53K6.ScheduledIterationAttainmentRate) -ge 0.01) {
        $issue53Reasons.Add('dropped-iteration-rate')
    }
    if ([double]$issue53K6.ReservationDurationMs.P95 -ge 2000.0) {
        $issue53Reasons.Add('reservation-p95')
    }
    $issue53Reasons.ToArray()
}

function Get-Issue53Median {
    param([double[]]$Values)

    if ($Values.Count -eq 0) {
        throw 'At least one value is required for a median.'
    }
    $issue53Sorted = @($Values | Sort-Object)
    $issue53Middle = [math]::Floor($issue53Sorted.Count / 2)
    if (($issue53Sorted.Count % 2) -eq 1) {
        return [double]$issue53Sorted[$issue53Middle]
    }
    ([double]$issue53Sorted[$issue53Middle - 1] + [double]$issue53Sorted[$issue53Middle]) / 2.0
}

function New-Issue53Range {
    param([double[]]$Values)

    [pscustomobject]@{
        Median = Get-Issue53Median -Values $Values
        Minimum = [double](($Values | Measure-Object -Minimum).Minimum)
        Maximum = [double](($Values | Measure-Object -Maximum).Maximum)
    }
}

function New-ContentionBaselineAggregate {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)]
        [object[]]$Records
    )

    $issue53Aggregates = New-Object 'Collections.Generic.List[object]'
    foreach ($issue53Group in ($Records | Group-Object -Property Scenario, Rate)) {
        $issue53Items = @($issue53Group.Group)
        $issue53Summaries = @($issue53Items | ForEach-Object { $_.Summary })
        $issue53First = $issue53Items[0]
        $issue53Aggregates.Add([pscustomobject]@{
            Scenario = [string]$issue53First.Scenario
            Rate = [int]$issue53First.Rate
            RepeatCount = $issue53Items.Count
            Iterations = New-Issue53Range -Values @($issue53Summaries | ForEach-Object { [double]$_.K6.Result.Iterations })
            DroppedIterations = New-Issue53Range -Values @($issue53Summaries | ForEach-Object { [double]$_.K6.Result.DroppedIterations })
            CompletedIterationsPerScheduledSecond = New-Issue53Range -Values @($issue53Summaries | ForEach-Object { [double]$_.K6.Result.CompletedIterationsPerScheduledSecond })
            ScheduledIterationAttainmentRate = New-Issue53Range -Values @($issue53Summaries | ForEach-Object { [double]$_.K6.Result.ScheduledIterationAttainmentRate })
            ReservationSuccess = New-Issue53Range -Values @($issue53Summaries | ForEach-Object { [double]$_.K6.Result.ReservationSuccess })
            ExpectedContention = New-Issue53Range -Values @($issue53Summaries | ForEach-Object { [double]$_.K6.Result.ExpectedContention })
            UnexpectedNonSuccessful = New-Issue53Range -Values @($issue53Summaries | ForEach-Object { [double]$_.K6.Result.UnexpectedNonSuccessful })
            UnexpectedFailureRate = New-Issue53Range -Values @($issue53Summaries | ForEach-Object { [double]$_.K6.Result.UnexpectedFailureRate })
            ReservationP95Ms = New-Issue53Range -Values @($issue53Summaries | ForEach-Object { [double]$_.K6.Result.ReservationDurationMs.P95 })
            HikariActivePeak = New-Issue53Range -Values @($issue53Summaries | ForEach-Object { [double]$_.Metrics.Peaks.HikariActive })
            HikariPendingPeak = New-Issue53Range -Values @($issue53Summaries | ForEach-Object { [double]$_.Metrics.Peaks.HikariPending })
            HikariMax = New-Issue53Range -Values @($issue53Summaries | ForEach-Object { [double]$_.Metrics.Peaks.HikariMax })
            DbRowLockWaitsDelta = New-Issue53Range -Values @($issue53Summaries | ForEach-Object { [double]$_.Metrics.Deltas.DbRowLockWaits })
            DbRowLockTimeMsDelta = New-Issue53Range -Values @($issue53Summaries | ForEach-Object { [double]$_.Metrics.Deltas.DbRowLockTimeMs })
            DbDeadlocksDelta = New-Issue53Range -Values @($issue53Summaries | ForEach-Object { [double]$_.Metrics.Deltas.DbDeadlocks })
        })
    }
    @($issue53Aggregates.ToArray() | Sort-Object -Property Scenario, Rate)
}

Export-ModuleMember -Function @(
    'New-ContentionBaselinePlan',
    'Get-ContentionBaselineStopReasons',
    'New-ContentionBaselineAggregate'
)
