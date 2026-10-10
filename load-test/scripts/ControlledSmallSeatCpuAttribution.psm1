Set-StrictMode -Version Latest

function ConvertFrom-MariaDbProcessStat {
    param([Parameter(Mandatory = $true)][string]$Stat,
        [Parameter(Mandatory = $true)][string]$ClockTicks)
    if ($Stat -notmatch '^1\s+\(mariadbd\)\s+\S+\s+(.+)$') {
        throw 'MariaDB PID 1 process stat is missing or has an unexpected identity.'
    }
    $fields = @($Matches[1] -split '\s+')
    if ($fields.Count -lt 12 -or $fields[10] -notmatch '^\d+$' -or
        $fields[11] -notmatch '^\d+$' -or $ClockTicks -notmatch '^\d+$' -or
        [long]$ClockTicks -le 0) {
        throw 'MariaDB process CPU counters or clock ticks are invalid.'
    }
    [pscustomobject]@{
        UserTicks = [long]$fields[10]
        SystemTicks = [long]$fields[11]
        ClockTicksPerSecond = [long]$ClockTicks
    }
}

function New-ControlledSmallSeatCpuAttribution {
    param(
        [Parameter(Mandatory = $true)][object[]]$Samples,
        [Parameter(Mandatory = $true)][object]$DatabaseBefore,
        [Parameter(Mandatory = $true)][object]$DatabaseAfter,
        [Parameter(Mandatory = $true)][double]$K6CpuSeconds,
        [Parameter(Mandatory = $true)][double]$K6ElapsedSeconds,
        [Parameter(Mandatory = $true)][double]$DatabaseElapsedSeconds
    )
    if ($Samples.Count -lt 5 -or $K6ElapsedSeconds -le 0 -or $DatabaseElapsedSeconds -le 0 -or
        [double]::IsNaN($K6ElapsedSeconds) -or [double]::IsNaN($DatabaseElapsedSeconds) -or
        [double]::IsInfinity($K6ElapsedSeconds) -or [double]::IsInfinity($DatabaseElapsedSeconds) -or
        $K6CpuSeconds -lt 0 -or
        [double]::IsNaN($K6CpuSeconds) -or [double]::IsInfinity($K6CpuSeconds) -or
        $DatabaseBefore.ClockTicksPerSecond -ne $DatabaseAfter.ClockTicksPerSecond) {
        throw 'CPU attribution input is missing or invalid.'
    }
    $beforeTicks = [long]$DatabaseBefore.UserTicks + [long]$DatabaseBefore.SystemTicks
    $afterTicks = [long]$DatabaseAfter.UserTicks + [long]$DatabaseAfter.SystemTicks
    if ($afterTicks -lt $beforeTicks) { throw 'MariaDB process CPU counter decreased.' }
    foreach ($sample in $Samples) {
        if ($null -eq $sample.BackendProcessCpuPercent -or
            [double]::IsNaN([double]$sample.BackendProcessCpuPercent) -or
            [double]::IsInfinity([double]$sample.BackendProcessCpuPercent) -or
            [double]$sample.BackendProcessCpuPercent -lt 0 -or
            [double]$sample.BackendProcessCpuPercent -gt 100) {
            throw 'Backend JVM CPU sample is missing or invalid.'
        }
    }
    $dbCpuSeconds = ($afterTicks - $beforeTicks) / [double]$DatabaseBefore.ClockTicksPerSecond
    [pscustomobject]@{
        SampleCount = $Samples.Count
        BackendProcessCpuPeakPercent = [double](($Samples | Measure-Object BackendProcessCpuPercent -Maximum).Maximum)
        BackendProcessCpuAveragePercent = [double](($Samples | Measure-Object BackendProcessCpuPercent -Average).Average)
        MariaDbProcessCpuSeconds = $dbCpuSeconds
        MariaDbCoreAveragePercent = 100 * $dbCpuSeconds / $DatabaseElapsedSeconds
        K6ProcessCpuSeconds = $K6CpuSeconds
        K6CoreAveragePercent = 100 * $K6CpuSeconds / $K6ElapsedSeconds
        K6ElapsedSeconds = $K6ElapsedSeconds
        MariaDbElapsedSeconds = $DatabaseElapsedSeconds
    }
}

function Get-ControlledHostProcessSnapshot {
    $items = New-Object 'Collections.Generic.List[object]'
    foreach ($process in (Get-Process -ErrorAction Stop)) {
        try {
            $cpu = $process.TotalProcessorTime.TotalSeconds
            if ($cpu -ge 0 -and -not [double]::IsNaN($cpu)) {
                $items.Add([pscustomobject]@{
                    Id = [int]$process.Id
                    Name = [string]$process.ProcessName
                    CpuSeconds = [double]$cpu
                })
            }
        } catch {
            # Protected/system processes may not expose CPU time. The count remains visible.
        }
    }
    if ($items.Count -eq 0) { throw 'No readable Windows process CPU counters.' }
    $items.ToArray()
}

function New-ControlledHostProcessCpuGroups {
    param(
        [Parameter(Mandatory = $true)][object]$Previous,
        [Parameter(Mandatory = $true)][object]$Current,
        [Parameter(Mandatory = $true)][int]$BackendPid,
        [Parameter(Mandatory = $true)][int]$LogicalProcessors
    )
    $seconds = ([long]$Current.ElapsedMilliseconds - [long]$Previous.ElapsedMilliseconds) / 1000
    if ($seconds -le 0 -or $seconds -gt 3 -or $LogicalProcessors -le 0 -or $BackendPid -le 0) {
        throw 'Windows process CPU sample interval or process identity is invalid.'
    }
    $previousById = @{}
    foreach ($item in $Previous.ProcessSnapshot) { $previousById[[int]$item.Id] = $item }
    $groups = [ordered]@{ BackendJvm = 0.0; LoadGenerator = 0.0; DockerWslHost = 0.0; OtherReadable = 0.0 }
    $matched = 0
    foreach ($item in $Current.ProcessSnapshot) {
        $id = [int]$item.Id
        if (-not $previousById.ContainsKey($id)) { continue }
        $before = $previousById[$id]
        if ([string]$before.Name -cne [string]$item.Name) { continue }
        $delta = [double]$item.CpuSeconds - [double]$before.CpuSeconds
        if ($delta -lt 0) { continue }
        $matched++
        $name = [string]$item.Name
        $group = if ($id -eq $BackendPid) { 'BackendJvm' }
            elseif ($name -eq 'k6') { 'LoadGenerator' }
            elseif ($name -match '^(?:vmmem|vmmemWSL|com\.docker\.backend|Docker Desktop|wsl|wslhost|vmcompute)$') { 'DockerWslHost' }
            else { 'OtherReadable' }
        $groups[$group] += $delta
    }
    if ($matched -eq 0) { throw 'No Windows process CPU counters were matched across the interval.' }
    [pscustomobject]@{
        TimestampUtc = $Current.TimestampUtc
        IntervalMilliseconds = [long]$Current.ElapsedMilliseconds - [long]$Previous.ElapsedMilliseconds
        MatchedProcessCount = $matched
        ExcludedOrShortLivedProcessCount = @($Current.ProcessSnapshot).Count - $matched
        HostCpuPercent = [double]$Current.HostCpuPercent
        BackendJvmHostCapacityPercent = 100 * $groups.BackendJvm / $seconds / $LogicalProcessors
        LoadGeneratorHostCapacityPercent = 100 * $groups.LoadGenerator / $seconds / $LogicalProcessors
        DockerWslHostCapacityPercent = 100 * $groups.DockerWslHost / $seconds / $LogicalProcessors
        OtherReadableHostCapacityPercent = 100 * $groups.OtherReadable / $seconds / $LogicalProcessors
    }
}

Export-ModuleMember -Function 'ConvertFrom-MariaDbProcessStat', 'New-ControlledSmallSeatCpuAttribution',
    'Get-ControlledHostProcessSnapshot', 'New-ControlledHostProcessCpuGroups'
