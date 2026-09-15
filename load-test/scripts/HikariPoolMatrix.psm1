Set-StrictMode -Version Latest

function Get-Issue114Median {
    param([Parameter(Mandatory = $true)][double[]]$Values)
    if ($Values.Count -eq 0) { throw 'At least one value is required.' }
    $sorted = @($Values | Sort-Object)
    $middle = [int][math]::Floor($sorted.Count / 2)
    if (($sorted.Count % 2) -eq 1) { return [double]$sorted[$middle] }
    return ([double]$sorted[$middle - 1] + [double]$sorted[$middle]) / 2
}

function New-Issue114Range {
    param([Parameter(Mandatory = $true)][double[]]$Values)
    [pscustomobject]@{
        Median = Get-Issue114Median -Values $Values
        Minimum = [double](($Values | Measure-Object -Minimum).Minimum)
        Maximum = [double](($Values | Measure-Object -Maximum).Maximum)
    }
}

function New-HikariPoolMatrixPlan {
    [CmdletBinding()]
    param(
        [int[]]$PoolSizes = @(10, 16, 24),
        [ValidateRange(1, 10)][int]$Repeats = 3
    )
    $records = New-Object 'Collections.Generic.List[object]'
    $sequence = 0
    foreach ($pool in $PoolSizes) {
        if ($pool -lt 1 -or $pool -gt 100) { throw 'Pool sizes must be between 1 and 100.' }
        $sequence += 1
        $records.Add([pscustomobject]@{ Sequence = $sequence; PoolSize = $pool; Repeat = 0; Warmup = $true })
        foreach ($repeat in 1..$Repeats) {
            $sequence += 1
            $records.Add([pscustomobject]@{ Sequence = $sequence; PoolSize = $pool; Repeat = $repeat; Warmup = $false })
        }
    }
    $records.ToArray()
}

function New-HikariPoolMatrixAggregate {
    [CmdletBinding()]
    param([Parameter(Mandatory = $true)][object[]]$Records)
    $aggregates = New-Object 'Collections.Generic.List[object]'
    foreach ($group in ($Records | Where-Object { -not $_.Warmup } | Group-Object PoolSize)) {
        $items = @($group.Group)
        $summaries = @($items | ForEach-Object { $_.Summary })
        $configured = [int]$group.Name
        foreach ($summary in $summaries) {
            if (-not [bool]$summary.ValidMeasurement -or -not [bool]$summary.K6.StateInvariantSatisfied) { throw "Invalid matrix record for pool $configured." }
            if ([int]$summary.Metrics.Peaks.HikariMax -ne $configured) { throw "Measured Hikari max does not match pool $configured." }
        }
        $aggregates.Add([pscustomobject]@{
            PoolSize = $configured
            RepeatCount = $items.Count
            CompletionRatePercent = New-Issue114Range -Values @($summaries | ForEach-Object { 100 * [double]$_.K6.Result.ScheduledIterationAttainmentRate })
            DroppedIterations = New-Issue114Range -Values @($summaries | ForEach-Object { [double]$_.K6.Result.DroppedIterations })
            HoldP95Ms = New-Issue114Range -Values @($summaries | ForEach-Object { [double]$_.K6.Result.HoldDurationMs.P95 })
            CycleP95Ms = New-Issue114Range -Values @($summaries | ForEach-Object { [double]$_.K6.Result.CycleDurationMs.P95 })
            HikariPendingPeak = New-Issue114Range -Values @($summaries | ForEach-Object { [double]$_.Metrics.Peaks.HikariPending })
            ProcessCpuUsage = New-Issue114Range -Values @($summaries | ForEach-Object { [double]$_.Metrics.Peaks.ProcessCpuUsage })
            SystemCpuUsage = New-Issue114Range -Values @($summaries | ForEach-Object { [double]$_.Metrics.Peaks.SystemCpuUsage })
            HeapUsedBytes = New-Issue114Range -Values @($summaries | ForEach-Object { [double]$_.Metrics.Peaks.HeapUsedBytes })
            DbDeadlocks = New-Issue114Range -Values @($summaries | ForEach-Object { [double]$_.Metrics.Deltas.DbDeadlocks })
        })
    }
    @($aggregates.ToArray() | Sort-Object PoolSize)
}

Export-ModuleMember -Function @('New-HikariPoolMatrixPlan', 'New-HikariPoolMatrixAggregate')
