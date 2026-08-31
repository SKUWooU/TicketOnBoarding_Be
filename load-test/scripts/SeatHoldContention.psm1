Set-StrictMode -Version Latest

function ConvertFrom-SeatHoldK6Result {
    [CmdletBinding()]
    param([Parameter(Mandatory = $true)][string]$Text)

    $issue65Prefix = 'SEAT_HOLD_RESULT '
    $issue65Lines = @(
        ($Text -split "`r?`n") |
            ForEach-Object { $_.Trim() } |
            Where-Object { $_.StartsWith($issue65Prefix, [StringComparison]::Ordinal) }
    )
    if ($issue65Lines.Count -ne 1) {
        throw "Expected exactly one structured seat-hold result, found $($issue65Lines.Count)."
    }
    try {
        $issue65Result = $issue65Lines[0].Substring($issue65Prefix.Length) | ConvertFrom-Json
    } catch {
        throw "Structured seat-hold result is not valid JSON: $($_.Exception.Message)"
    }
    foreach ($issue65Property in @(
        'schemaVersion', 'scenario', 'targetRatePerSecond', 'duration',
        'thresholdsEnforced', 'iterations', 'droppedIterations', 'holdSuccess',
        'expectedContention', 'unexpectedNonSuccessful', 'unexpectedFailureRate',
        'holdDurationMs', 'maxObservedVus', 'maxAllocatedVus',
        'preAllocatedVus', 'configuredMaxVus'
    )) {
        if ($issue65Property -notin $issue65Result.PSObject.Properties.Name) {
            throw "Structured seat-hold result is missing: $issue65Property"
        }
    }
    foreach ($issue65DurationProperty in @('average', 'median', 'p95', 'maximum')) {
        if ($issue65DurationProperty -notin $issue65Result.holdDurationMs.PSObject.Properties.Name) {
            throw "Structured seat-hold duration is missing: $issue65DurationProperty"
        }
    }
    if ([int]$issue65Result.schemaVersion -ne 1) {
        throw "Unsupported structured seat-hold result schema: $($issue65Result.schemaVersion)"
    }
    $issue65Result
}

function ConvertFrom-SeatHoldFinalSnapshot {
    [CmdletBinding()]
    param([Parameter(Mandatory = $true)][string]$Text)

    $issue65Matches = [regex]::Matches($Text, 'SEAT_HOLD_FINAL_SNAPSHOT\s+(\{[^{}]*\})')
    if ($issue65Matches.Count -ne 1) {
        throw "Expected exactly one final seat-hold snapshot, found $($issue65Matches.Count)."
    }
    try {
        $issue65Snapshot = $issue65Matches[0].Groups[1].Value | ConvertFrom-Json
    } catch {
        throw "Final seat-hold snapshot is not valid JSON: $($_.Exception.Message)"
    }
    foreach ($issue65Property in @(
        'expectedTotalSeats', 'actualSeatCount', 'remainingSeats', 'reservedSeats',
        'activeHeldSeats', 'holdRows', 'partialHoldStates', 'reservations',
        'bookings', 'payments', 'invariantSatisfied'
    )) {
        if ($issue65Property -notin $issue65Snapshot.PSObject.Properties.Name) {
            throw "Final seat-hold snapshot is missing: $issue65Property"
        }
    }
    $issue65Snapshot
}

function Assert-SeatHoldRunIdentity {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)][object]$Result,
        [Parameter(Mandatory = $true)][string]$Scenario,
        [Parameter(Mandatory = $true)][int]$Rate,
        [Parameter(Mandatory = $true)][int]$DurationSeconds,
        [Parameter(Mandatory = $true)][bool]$ThresholdsEnforced
    )

    if ([string]$Result.scenario -ne $Scenario) {
        throw "Seat-hold scenario mismatch: expected=$Scenario actual=$($Result.scenario)"
    }
    if ([int]$Result.targetRatePerSecond -ne $Rate) {
        throw "Seat-hold rate mismatch: expected=$Rate actual=$($Result.targetRatePerSecond)"
    }
    if ([string]$Result.duration -ne "$($DurationSeconds)s") {
        throw "Seat-hold duration mismatch: expected=$($DurationSeconds)s actual=$($Result.duration)"
    }
    if ([bool]$Result.thresholdsEnforced -ne $ThresholdsEnforced) {
        throw "Seat-hold threshold mode mismatch: expected=$ThresholdsEnforced actual=$($Result.thresholdsEnforced)"
    }
    $true
}

function New-SeatHoldRunSummary {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)][object]$Result,
        [Parameter(Mandatory = $true)][ValidateRange(1, 3600)][int]$DurationSeconds
    )

    $issue65Iterations = [long]$Result.iterations
    $issue65Dropped = [long]$Result.droppedIterations
    $issue65Success = [long]$Result.holdSuccess
    $issue65Contention = [long]$Result.expectedContention
    $issue65Unexpected = [long]$Result.unexpectedNonSuccessful
    foreach ($issue65Value in @($issue65Iterations, $issue65Dropped, $issue65Success, $issue65Contention, $issue65Unexpected)) {
        if ($issue65Value -lt 0) {
            throw 'Structured seat-hold counters must not be negative.'
        }
    }
    if ($issue65Iterations -le 0) {
        throw 'Structured seat-hold result must contain at least one completed iteration.'
    }
    if ($issue65Iterations -ne ($issue65Success + $issue65Contention + $issue65Unexpected)) {
        throw 'Structured seat-hold counters do not match completed iterations.'
    }
    $issue65Scheduled = $issue65Iterations + $issue65Dropped
    [pscustomobject]@{
        Scenario = [string]$Result.scenario
        TargetRatePerSecond = [int]$Result.targetRatePerSecond
        DurationSeconds = $DurationSeconds
        ThresholdsEnforced = [bool]$Result.thresholdsEnforced
        Iterations = $issue65Iterations
        DroppedIterations = $issue65Dropped
        ScheduledIterationAttainmentRate = if ($issue65Scheduled -eq 0) { 0.0 } else { [double]$issue65Iterations / $issue65Scheduled }
        CompletedIterationsPerScheduledSecond = [double]$issue65Iterations / $DurationSeconds
        HoldSuccess = $issue65Success
        ExpectedContention = $issue65Contention
        UnexpectedNonSuccessful = $issue65Unexpected
        UnexpectedFailureRate = [double]$Result.unexpectedFailureRate
        HoldDurationMs = [pscustomobject]@{
            Average = [double]$Result.holdDurationMs.average
            Median = [double]$Result.holdDurationMs.median
            P95 = [double]$Result.holdDurationMs.p95
            Maximum = [double]$Result.holdDurationMs.maximum
        }
        MaxObservedVus = [int]$Result.maxObservedVus
        MaxAllocatedVus = [int]$Result.maxAllocatedVus
        PreAllocatedVus = [int]$Result.preAllocatedVus
        ConfiguredMaxVus = [int]$Result.configuredMaxVus
    }
}

function Assert-SeatHoldFinalState {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)][object]$Summary,
        [Parameter(Mandatory = $true)][object]$Snapshot
    )

    if (-not [bool]$Snapshot.invariantSatisfied) {
        throw 'Final seat-hold snapshot invariant is false.'
    }
    foreach ($issue65ZeroProperty in @('reservedSeats', 'partialHoldStates', 'reservations', 'bookings', 'payments')) {
        if ([long]$Snapshot.$issue65ZeroProperty -ne 0) {
            throw "Final seat-hold snapshot must keep $issue65ZeroProperty at zero."
        }
    }
    if ([long]$Snapshot.expectedTotalSeats -ne [long]$Snapshot.actualSeatCount -or
        [long]$Snapshot.expectedTotalSeats -ne [long]$Snapshot.remainingSeats) {
        throw 'Seat-hold workload must not change physical or remaining inventory.'
    }
    if ([long]$Snapshot.activeHeldSeats -ne [long]$Snapshot.holdRows) {
        throw 'Every persisted hold row must remain active during the measurement window.'
    }

    $issue65ExpectedHeld = switch ([string]$Summary.Scenario) {
        'distributed' { [long]$Summary.HoldSuccess }
        'hot-section' { [math]::Min(40, [long]$Summary.Iterations) }
        'hot-seat' { [math]::Min(1, [long]$Summary.Iterations) }
        default { throw "Unsupported seat-hold scenario: $($Summary.Scenario)" }
    }
    if ([long]$Snapshot.activeHeldSeats -ne $issue65ExpectedHeld) {
        throw "Persisted active holds do not match scenario expectation: expected=$issue65ExpectedHeld actual=$($Snapshot.activeHeldSeats)"
    }
    if ([long]$Snapshot.activeHeldSeats -gt [long]$Summary.HoldSuccess) {
        throw 'Persisted active holds cannot exceed successful hold responses.'
    }
    if ([string]$Summary.Scenario -eq 'distributed' -and [long]$Summary.ExpectedContention -ne 0) {
        throw 'Distributed seat-hold workload must not report expected contention.'
    }
    if ([string]$Summary.Scenario -in @('hot-section', 'hot-seat') -and
        [long]$Summary.ExpectedContention -le 0) {
        throw 'Hot seat-hold workload must observe at least one expected 409 contention response.'
    }
    $true
}

function New-SeatHoldBaselinePlan {
    [CmdletBinding()]
    param(
        [ValidateRange(1, 3600)][int]$DurationSeconds = 10,
        [ValidateRange(1, 10)][int]$Repeats = 3,
        [ValidateRange(1, 10000)][int]$TotalSeats = 2000
    )

    $issue65Plan = New-Object 'Collections.Generic.List[object]'
    $issue65Sequence = 1
    $issue65Plan.Add([pscustomobject]@{
        Sequence = $issue65Sequence; Scenario = 'distributed'; Rate = 20; Repeat = 0; Warmup = $true
    })
    $issue65Sequence += 1
    $issue65Matrix = [ordered]@{
        'distributed' = @(50, 100, 150)
        'hot-section' = @(100, 150, 200)
        'hot-seat' = @(100, 150, 200)
    }
    foreach ($issue65Scenario in $issue65Matrix.Keys) {
        foreach ($issue65Rate in $issue65Matrix[$issue65Scenario]) {
            if ($issue65Scenario -eq 'distributed' -and (($issue65Rate * $DurationSeconds) + 1) -gt $TotalSeats) {
                throw "Distributed seat-hold stage can exceed fixture inventory: rate=$issue65Rate duration=$DurationSeconds seats=$TotalSeats"
            }
            for ($issue65Repeat = 1; $issue65Repeat -le $Repeats; $issue65Repeat += 1) {
                $issue65Plan.Add([pscustomobject]@{
                    Sequence = $issue65Sequence
                    Scenario = $issue65Scenario
                    Rate = $issue65Rate
                    Repeat = $issue65Repeat
                    Warmup = $false
                })
                $issue65Sequence += 1
            }
        }
    }
    $issue65Plan.ToArray()
}

function Get-SeatHoldBaselineStopReasons {
    [CmdletBinding()]
    param([Parameter(Mandatory = $true)][object]$Summary)

    $issue65Reasons = New-Object 'Collections.Generic.List[string]'
    if (-not [bool]$Summary.ValidMeasurement) {
        $issue65Reasons.Add('invalid-measurement')
        return $issue65Reasons.ToArray()
    }
    if (-not [bool]$Summary.K6.StateInvariantSatisfied) {
        $issue65Reasons.Add('state-invariant')
    }
    $issue65Result = $Summary.K6.Result
    if ([double]$issue65Result.UnexpectedFailureRate -ge 0.05) {
        $issue65Reasons.Add('unexpected-failure-rate')
    }
    if ((1.0 - [double]$issue65Result.ScheduledIterationAttainmentRate) -ge 0.01) {
        $issue65Reasons.Add('dropped-iteration-rate')
    }
    if ([double]$issue65Result.HoldDurationMs.P95 -ge 2000.0) {
        $issue65Reasons.Add('seat-hold-p95')
    }
    $issue65Reasons.ToArray()
}

function Get-Issue65Median {
    param([double[]]$Values)
    if ($Values.Count -eq 0) { throw 'At least one value is required for a median.' }
    $issue65Sorted = @($Values | Sort-Object)
    $issue65Middle = [math]::Floor($issue65Sorted.Count / 2)
    if (($issue65Sorted.Count % 2) -eq 1) { return [double]$issue65Sorted[$issue65Middle] }
    ([double]$issue65Sorted[$issue65Middle - 1] + [double]$issue65Sorted[$issue65Middle]) / 2.0
}

function New-Issue65Range {
    param([double[]]$Values)
    [pscustomobject]@{
        Median = Get-Issue65Median -Values $Values
        Minimum = [double](($Values | Measure-Object -Minimum).Minimum)
        Maximum = [double](($Values | Measure-Object -Maximum).Maximum)
    }
}

function New-SeatHoldBaselineAggregate {
    [CmdletBinding()]
    param([Parameter(Mandatory = $true)][object[]]$Records)

    $issue65Aggregates = New-Object 'Collections.Generic.List[object]'
    foreach ($issue65Group in ($Records | Group-Object -Property Scenario, Rate)) {
        $issue65Items = @($issue65Group.Group)
        $issue65Summaries = @($issue65Items | ForEach-Object { $_.Summary })
        $issue65First = $issue65Items[0]
        $issue65Aggregates.Add([pscustomobject]@{
            Scenario = [string]$issue65First.Scenario
            Rate = [int]$issue65First.Rate
            RepeatCount = $issue65Items.Count
            Iterations = New-Issue65Range -Values @($issue65Summaries | ForEach-Object { [double]$_.K6.Result.Iterations })
            DroppedIterations = New-Issue65Range -Values @($issue65Summaries | ForEach-Object { [double]$_.K6.Result.DroppedIterations })
            CompletedIterationsPerScheduledSecond = New-Issue65Range -Values @($issue65Summaries | ForEach-Object { [double]$_.K6.Result.CompletedIterationsPerScheduledSecond })
            ScheduledIterationAttainmentRate = New-Issue65Range -Values @($issue65Summaries | ForEach-Object { [double]$_.K6.Result.ScheduledIterationAttainmentRate })
            HoldSuccess = New-Issue65Range -Values @($issue65Summaries | ForEach-Object { [double]$_.K6.Result.HoldSuccess })
            ExpectedContention = New-Issue65Range -Values @($issue65Summaries | ForEach-Object { [double]$_.K6.Result.ExpectedContention })
            ActiveHeldSeats = New-Issue65Range -Values @($issue65Summaries | ForEach-Object { [double]$_.K6.FinalSnapshot.activeHeldSeats })
            UnexpectedFailureRate = New-Issue65Range -Values @($issue65Summaries | ForEach-Object { [double]$_.K6.Result.UnexpectedFailureRate })
            HoldP95Ms = New-Issue65Range -Values @($issue65Summaries | ForEach-Object { [double]$_.K6.Result.HoldDurationMs.P95 })
            HikariActivePeak = New-Issue65Range -Values @($issue65Summaries | ForEach-Object { [double]$_.Metrics.Peaks.HikariActive })
            HikariPendingPeak = New-Issue65Range -Values @($issue65Summaries | ForEach-Object { [double]$_.Metrics.Peaks.HikariPending })
            DbRowLockWaitsDelta = New-Issue65Range -Values @($issue65Summaries | ForEach-Object { [double]$_.Metrics.Deltas.DbRowLockWaits })
            DbRowLockTimeMsDelta = New-Issue65Range -Values @($issue65Summaries | ForEach-Object { [double]$_.Metrics.Deltas.DbRowLockTimeMs })
            DbDeadlocksDelta = New-Issue65Range -Values @($issue65Summaries | ForEach-Object { [double]$_.Metrics.Deltas.DbDeadlocks })
        })
    }
    @($issue65Aggregates.ToArray() | Sort-Object -Property Scenario, Rate)
}

Export-ModuleMember -Function @(
    'ConvertFrom-SeatHoldK6Result',
    'ConvertFrom-SeatHoldFinalSnapshot',
    'Assert-SeatHoldRunIdentity',
    'New-SeatHoldRunSummary',
    'Assert-SeatHoldFinalState',
    'New-SeatHoldBaselinePlan',
    'Get-SeatHoldBaselineStopReasons',
    'New-SeatHoldBaselineAggregate'
)
