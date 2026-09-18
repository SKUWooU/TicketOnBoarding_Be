Set-StrictMode -Version Latest

function Get-CheckoutDeadlockRepeatMedian {
    param([Parameter(Mandatory = $true)][double[]]$Values)
    if ($Values.Count -eq 0) { throw 'At least one value is required.' }
    $sorted = @($Values | Sort-Object)
    $middle = [int][Math]::Floor($sorted.Count / 2)
    if (($sorted.Count % 2) -eq 1) { return [double]$sorted[$middle] }
    ([double]$sorted[$middle - 1] + [double]$sorted[$middle]) / 2
}

function New-CheckoutDeadlockRepeatRange {
    param([Parameter(Mandatory = $true)][double[]]$Values)
    [pscustomobject]@{
        Median = Get-CheckoutDeadlockRepeatMedian -Values $Values
        Minimum = [double](($Values | Measure-Object -Minimum).Minimum)
        Maximum = [double](($Values | Measure-Object -Maximum).Maximum)
    }
}

function New-CheckoutDeadlockRepeatPlan {
    [CmdletBinding()]
    param([ValidateRange(3, 10)][int]$Repeats = 3)

    $records = New-Object 'Collections.Generic.List[object]'
    $records.Add([pscustomobject]@{ Sequence = 1; Repeat = 0; Warmup = $true })
    foreach ($repeat in 1..$Repeats) {
        $records.Add([pscustomobject]@{ Sequence = $repeat + 1; Repeat = $repeat; Warmup = $false })
    }
    $records.ToArray()
}

function Assert-CheckoutDeadlockRepeatSummary {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)][object]$Summary,
        [Parameter(Mandatory = $true)][string]$RunId,
        [ValidateRange(1, 1000)][int]$Rate = 100,
        [ValidateRange(1, 60)][int]$DurationSeconds = 10
    )

    if (-not [bool]$Summary.ValidMeasurement) { throw "Invalid measurement: $RunId" }
    if ($Summary.Run.Id -ne $RunId -or $Summary.Run.Scenario -ne 'distributed' -or
        [int]$Summary.Run.RatePerSecond -ne $Rate -or [int]$Summary.Run.DurationSeconds -ne $DurationSeconds) {
        throw "Run identity does not match the repeat plan: $RunId"
    }
    if ([int]$Summary.Fixture.TotalSeats -ne 2000 -or -not [bool]$Summary.Fixture.PreparedBeforeSampling) {
        throw "Fixture contract does not match the repeat plan: $RunId"
    }
    foreach ($property in @('Result', 'FinalSnapshot', 'TransitionDelta')) {
        if ($property -notin $Summary.K6.PSObject.Properties.Name) { throw "K6 $property is missing: $RunId" }
    }
    foreach ($property in @('Peaks', 'Deltas')) {
        if ($property -notin $Summary.Metrics.PSObject.Properties.Name) { throw "Metrics $property is missing: $RunId" }
    }
    $true
}

function New-CheckoutDeadlockRepeatAggregate {
    [CmdletBinding()]
    param([Parameter(Mandatory = $true)][object[]]$Records)

    $measured = @($Records | Where-Object { -not $_.Warmup })
    if ($measured.Count -lt 3) { throw 'At least three measured records are required.' }
    foreach ($record in $measured) {
        Assert-CheckoutDeadlockRepeatSummary -Summary $record.Summary -RunId $record.RunId | Out-Null
    }

    $summaries = @($measured | ForEach-Object { $_.Summary })
    $deadlocks = @($summaries | ForEach-Object { [double]$_.Metrics.Deltas.DbDeadlocks })
    $unexpected = @($summaries | ForEach-Object { [double]$_.K6.Result.unexpectedNonSuccessful })
    $invariants = @($summaries | ForEach-Object { [bool]$_.K6.FinalSnapshot.invariantSatisfied })
    [pscustomobject]@{
        SchemaVersion = 1
        Scenario = 'distributed'
        RatePerSecond = 100
        DurationSeconds = 10
        FixtureSeats = 2000
        MeasuredRepeatCount = $measured.Count
        WarmupExcluded = $true
        Gate = [pscustomobject]@{
            AllDeadlocksZero = (@($deadlocks | Where-Object { $_ -ne 0 }).Count -eq 0)
            AllUnexpectedFailuresZero = (@($unexpected | Where-Object { $_ -ne 0 }).Count -eq 0)
            AllInventoryInvariantsSatisfied = (@($invariants | Where-Object { -not $_ }).Count -eq 0)
        }
        Metrics = [pscustomobject]@{
            CheckoutConfirmed = New-CheckoutDeadlockRepeatRange -Values @($summaries | ForEach-Object { [double]$_.K6.Result.checkoutConfirmed })
            UnexpectedNonSuccessful = New-CheckoutDeadlockRepeatRange -Values $unexpected
            UnexpectedFailureRate = New-CheckoutDeadlockRepeatRange -Values @($summaries | ForEach-Object { [double]$_.K6.Result.unexpectedFailureRate })
            CheckoutP95Milliseconds = New-CheckoutDeadlockRepeatRange -Values @($summaries | ForEach-Object { [double]$_.K6.Result.checkoutDurationMs.p95 })
            DroppedIterations = New-CheckoutDeadlockRepeatRange -Values @($summaries | ForEach-Object { [double]$_.K6.Result.droppedIterations })
            HikariPendingPeak = New-CheckoutDeadlockRepeatRange -Values @($summaries | ForEach-Object { [double]$_.Metrics.Peaks.HikariPending })
            DbDeadlocks = New-CheckoutDeadlockRepeatRange -Values $deadlocks
        }
    }
}

Export-ModuleMember -Function @('New-CheckoutDeadlockRepeatPlan', 'Assert-CheckoutDeadlockRepeatSummary', 'New-CheckoutDeadlockRepeatAggregate')
