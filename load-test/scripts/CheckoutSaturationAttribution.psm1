Set-StrictMode -Version Latest
Import-Module (Join-Path $PSScriptRoot 'CheckoutContention.psm1') -Force

function Get-CheckoutSaturationMedian {
    param([Parameter(Mandatory = $true)][double[]]$Values)
    if ($Values.Count -eq 0) { throw 'At least one value is required.' }
    $sorted = @($Values | Sort-Object)
    $middle = [int][Math]::Floor($sorted.Count / 2)
    if (($sorted.Count % 2) -eq 1) { return [double]$sorted[$middle] }
    ([double]$sorted[$middle - 1] + [double]$sorted[$middle]) / 2
}

function New-CheckoutSaturationRange {
    param([Parameter(Mandatory = $true)][double[]]$Values)
    [pscustomobject]@{
        Median = Get-CheckoutSaturationMedian -Values $Values
        Minimum = [double](($Values | Measure-Object -Minimum).Minimum)
        Maximum = [double](($Values | Measure-Object -Maximum).Maximum)
    }
}

function New-CheckoutSaturationAttributionPlan {
    [CmdletBinding()]
    param(
        [int[]]$Rates = @(50, 75, 100),
        [ValidateRange(3, 10)][int]$Repeats = 3
    )

    $records = New-Object 'Collections.Generic.List[object]'
    $sequence = 0
    foreach ($rate in $Rates) {
        if ($rate -lt 1 -or $rate -gt 1000) { throw 'Rates must be between 1 and 1000.' }
        $sequence += 1
        $records.Add([pscustomobject]@{ Sequence=$sequence; Rate=$rate; Repeat=0; Warmup=$true })
        foreach ($repeat in 1..$Repeats) {
            $sequence += 1
            $records.Add([pscustomobject]@{ Sequence=$sequence; Rate=$rate; Repeat=$repeat; Warmup=$false })
        }
    }
    $records.ToArray()
}

function Assert-CheckoutSaturationSummary {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)][object]$Summary,
        [Parameter(Mandatory = $true)][string]$RunId,
        [Parameter(Mandatory = $true)][int]$Rate,
        [ValidateRange(1, 60)][int]$DurationSeconds = 10
    )

    if (-not [bool]$Summary.ValidMeasurement) { throw "Invalid measurement: $RunId" }
    if ($Summary.Run.Id -ne $RunId -or $Summary.Run.Scenario -ne 'distributed' -or
        [int]$Summary.Run.RatePerSecond -ne $Rate -or [int]$Summary.Run.DurationSeconds -ne $DurationSeconds) {
        throw "Run identity does not match the attribution plan: $RunId"
    }
    if ([int]$Summary.Fixture.TotalSeats -ne 2000 -or -not [bool]$Summary.Fixture.PreparedBeforeSampling) {
        throw "Fixture contract does not match the attribution plan: $RunId"
    }
    if ([int]$Summary.K6.Result.schemaVersion -ne 2) { throw "Attribution requires Checkout schema v2: $RunId" }
    Assert-CheckoutDomainState -Result $Summary.K6.Result -Snapshot $Summary.K6.FinalSnapshot -TransitionDelta $Summary.K6.TransitionDelta | Out-Null
    foreach ($property in @('maxObservedVus', 'maxAllocatedVus', 'preAllocatedVus', 'configuredMaxVus')) {
        if ($property -notin $Summary.K6.Result.PSObject.Properties.Name) { throw "k6 VU field is missing: $property" }
    }
    $observed = [int]$Summary.K6.Result.maxObservedVus
    $allocated = [int]$Summary.K6.Result.maxAllocatedVus
    $preAllocated = [int]$Summary.K6.Result.preAllocatedVus
    $configured = [int]$Summary.K6.Result.configuredMaxVus
    if ($observed -lt 0 -or $allocated -lt 0 -or $preAllocated -lt 1 -or $configured -lt $preAllocated -or $allocated -gt $configured -or $observed -gt $allocated) {
        throw "Invalid k6 VU bounds: $RunId"
    }
    $true
}

function Get-CheckoutSaturationSignals {
    [CmdletBinding()]
    param([Parameter(Mandatory = $true)][object]$Summary)

    $result = $Summary.K6.Result
    $metrics = $Summary.Metrics
    $vuCapReached = ([long]$result.droppedIterations -gt 0 -and [int]$result.maxObservedVus -ge [int]$result.configuredMaxVus -and [int]$result.maxAllocatedVus -ge [int]$result.configuredMaxVus)
    [pscustomobject]@{
        VuCapReached = $vuCapReached
        PoolQueueObserved = ([double]$metrics.Peaks.HikariPending -gt 0)
        DbLockObserved = ([long]$metrics.Deltas.DbRowLockWaits -gt 0 -or [long]$metrics.Deltas.DbRowLockTimeMs -gt 0 -or [long]$metrics.Peaks.DbRowLockCurrentWaits -gt 0)
        DbDeadlockObserved = ([long]$metrics.Deltas.DbDeadlocks -gt 0)
        Label = if ($vuCapReached) { 'vu-cap-reached-attribution-limited' }
                elseif ([double]$metrics.Peaks.HikariPending -gt 0 -and ([long]$metrics.Deltas.DbRowLockWaits -gt 0 -or [long]$metrics.Deltas.DbRowLockTimeMs -gt 0)) { 'pool-and-db-lock-signals' }
                elseif ([double]$metrics.Peaks.HikariPending -gt 0) { 'pool-queue-signal' }
                elseif ([long]$metrics.Deltas.DbRowLockWaits -gt 0 -or [long]$metrics.Deltas.DbRowLockTimeMs -gt 0) { 'db-lock-signal' }
                else { 'no-attribution-signal' }
    }
}

function New-CheckoutSaturationAttributionAggregate {
    [CmdletBinding()]
    param([Parameter(Mandatory = $true)][object[]]$Records)

    $aggregates = New-Object 'Collections.Generic.List[object]'
    foreach ($group in ($Records | Where-Object { -not $_.Warmup } | Group-Object Rate | Sort-Object { [int]$_.Name })) {
        $items = @($group.Group)
        if ($items.Count -lt 3) { throw "Rate $($group.Name) needs at least three measured records." }
        foreach ($item in $items) { Assert-CheckoutSaturationSummary -Summary $item.Summary -RunId $item.RunId -Rate ([int]$group.Name) | Out-Null }
        $summaries = @($items | ForEach-Object { $_.Summary })
        $signals = @($summaries | ForEach-Object { Get-CheckoutSaturationSignals -Summary $_ })
        $aggregates.Add([pscustomobject]@{
            RatePerSecond = [int]$group.Name
            MeasuredRepeatCount = $items.Count
            WarmupExcluded = $true
            SignalRuns = [pscustomobject]@{
                VuCapReached = @($signals | Where-Object VuCapReached).Count
                PoolQueueObserved = @($signals | Where-Object PoolQueueObserved).Count
                DbLockObserved = @($signals | Where-Object DbLockObserved).Count
                DbDeadlockObserved = @($signals | Where-Object DbDeadlockObserved).Count
                Labels = @($signals | Group-Object Label | ForEach-Object { [pscustomobject]@{ Label=$_.Name; Count=$_.Count } })
            }
            Metrics = [pscustomobject]@{
                ScheduledIterationAttainmentRate = New-CheckoutSaturationRange -Values @($summaries | ForEach-Object { $scheduled=[double]$_.K6.Result.iterations + [double]$_.K6.Result.droppedIterations; if ($scheduled -eq 0) { 0 } else { [double]$_.K6.Result.iterations / $scheduled } })
                DroppedIterations = New-CheckoutSaturationRange -Values @($summaries | ForEach-Object { [double]$_.K6.Result.droppedIterations })
                CheckoutP95Milliseconds = New-CheckoutSaturationRange -Values @($summaries | ForEach-Object { [double]$_.K6.Result.checkoutDurationMs.p95 })
                MaxObservedVus = New-CheckoutSaturationRange -Values @($summaries | ForEach-Object { [double]$_.K6.Result.maxObservedVus })
                MaxAllocatedVus = New-CheckoutSaturationRange -Values @($summaries | ForEach-Object { [double]$_.K6.Result.maxAllocatedVus })
                HikariPendingPeak = New-CheckoutSaturationRange -Values @($summaries | ForEach-Object { [double]$_.Metrics.Peaks.HikariPending })
                DbRowLockWaits = New-CheckoutSaturationRange -Values @($summaries | ForEach-Object { [double]$_.Metrics.Deltas.DbRowLockWaits })
                DbRowLockTimeMilliseconds = New-CheckoutSaturationRange -Values @($summaries | ForEach-Object { [double]$_.Metrics.Deltas.DbRowLockTimeMs })
                DbDeadlocks = New-CheckoutSaturationRange -Values @($summaries | ForEach-Object { [double]$_.Metrics.Deltas.DbDeadlocks })
            }
        })
    }
    @($aggregates.ToArray() | Sort-Object RatePerSecond)
}

Export-ModuleMember -Function @('New-CheckoutSaturationAttributionPlan', 'Assert-CheckoutSaturationSummary', 'Get-CheckoutSaturationSignals', 'New-CheckoutSaturationAttributionAggregate')
