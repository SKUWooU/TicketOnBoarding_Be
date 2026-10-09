Set-StrictMode -Version Latest

function Get-ControlledSmallSeatHoldRepeatPlan {
    $order = @(@(5, 10, 20), @(20, 10, 5), @(5, 10, 20))
    $sequence = 0
    foreach ($round in 1..3) {
        foreach ($rate in $order[$round - 1]) {
            $sequence++
            [pscustomobject]@{
                Sequence = $sequence
                Round = $round
                Rate = $rate
                DurationSeconds = 10
                HotSeatCount = 1
                HoldDwellMilliseconds = 500
            }
        }
    }
}

function Get-ControlledSmallSeatHoldRepeat30Plan {
    $order = @(@(20, 30), @(30, 20), @(20, 30))
    $sequence = 0
    foreach ($round in 1..3) {
        foreach ($rate in $order[$round - 1]) {
            $sequence++
            [pscustomobject]@{
                Sequence = $sequence
                Round = $round
                Rate = $rate
                DurationSeconds = 10
                HotSeatCount = 1
                HoldDwellMilliseconds = 500
            }
        }
    }
}

function New-ControlledSmallSeatHostSummary {
    param([Parameter(Mandatory = $true)][object[]]$Samples)
    if ($Samples.Count -lt 5) { throw 'At least five host samples are required.' }
    foreach ($sample in $Samples) {
        if ($null -eq $sample.HostCpuPercent -or $null -eq $sample.HostFreeMemoryKb -or
            [double]::IsNaN([double]$sample.HostCpuPercent) -or
            [double]::IsInfinity([double]$sample.HostCpuPercent) -or
            [double]$sample.HostCpuPercent -lt 0 -or [double]$sample.HostCpuPercent -gt 100 -or
            [long]$sample.HostFreeMemoryKb -lt 0) {
            throw 'Host CPU or free-memory sample is missing or invalid.'
        }
    }
    [pscustomobject]@{
        SampleCount = $Samples.Count
        HostCpuPeakPercent = [double](($Samples | Measure-Object -Property HostCpuPercent -Maximum).Maximum)
        HostFreeMemoryMinimumKb = [long](($Samples | Measure-Object -Property HostFreeMemoryKb -Minimum).Minimum)
    }
}

function New-ControlledSmallSeatHoldWaitSummary {
    param([Parameter(Mandatory = $true)][object[]]$Samples)
    if ($Samples.Count -lt 5) { throw 'At least five wait samples are required.' }
    $intervals = New-Object 'Collections.Generic.List[long]'
    foreach ($index in 1..($Samples.Count - 1)) {
        $previous = $Samples[$index - 1]
        $current = $Samples[$index]
        $interval = [long]$current.ElapsedMilliseconds - [long]$previous.ElapsedMilliseconds
        if ($interval -le 0 -or $interval -gt 3000) {
            throw "Wait metric sampling gap is invalid: $interval ms."
        }
        foreach ($name in @('DbRowLockWaits', 'DbRowLockTimeMs', 'DbDeadlocks',
                'HikariAcquireCount', 'HikariAcquireSeconds', 'HikariTimeoutCount')) {
            if ([double]$current.$name -lt [double]$previous.$name) {
                throw "Wait metric counter decreased: $name"
            }
        }
        $intervals.Add($interval)
    }
    $first = $Samples[0]
    $last = $Samples[$Samples.Count - 1]
    $acquireCount = [long]$last.HikariAcquireCount - [long]$first.HikariAcquireCount
    if ($acquireCount -le 0) {
        throw 'Hikari acquire counter did not advance during the measured Hold run.'
    }
    $acquireWaitMs = ([double]$last.HikariAcquireSeconds - [double]$first.HikariAcquireSeconds) * 1000
    [pscustomobject]@{
        SampleCount = $Samples.Count
        MaxSampleGapMs = [long](($intervals | Measure-Object -Maximum).Maximum)
        HikariPendingPeak = [double](($Samples | Measure-Object -Property HikariPending -Maximum).Maximum)
        HikariActivePeak = [double](($Samples | Measure-Object -Property HikariActive -Maximum).Maximum)
        HikariMax = [double]$first.HikariMax
        HikariAcquireCount = $acquireCount
        HikariAcquireWaitAverageMs = $acquireWaitMs / $acquireCount
        HikariTimeoutDelta = [long]$last.HikariTimeoutCount - [long]$first.HikariTimeoutCount
        DbRowLockCurrentWaitsPeak = [long](($Samples | Measure-Object -Property DbRowLockCurrentWaits -Maximum).Maximum)
        DbRowLockWaitsDelta = [long]$last.DbRowLockWaits - [long]$first.DbRowLockWaits
        DbRowLockTimeMsDelta = [long]$last.DbRowLockTimeMs - [long]$first.DbRowLockTimeMs
        DbDeadlocksDelta = [long]$last.DbDeadlocks - [long]$first.DbDeadlocks
    }
}

Export-ModuleMember -Function 'Get-ControlledSmallSeatHoldRepeatPlan',
    'Get-ControlledSmallSeatHoldRepeat30Plan', 'New-ControlledSmallSeatHoldWaitSummary',
    'New-ControlledSmallSeatHostSummary'
