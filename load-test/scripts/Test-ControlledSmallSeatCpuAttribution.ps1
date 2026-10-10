[CmdletBinding()]
param()
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'ControlledSmallSeatCpuAttribution.psm1') -Force
$assertions = 0
function Assert-True([bool]$condition) { $script:assertions++; if (-not $condition) { throw 'Expected true.' } }
function Assert-Throws([scriptblock]$action) {
    $script:assertions++
    try { & $action | Out-Null } catch { return }
    throw 'Expected an error.'
}
$stat = '1 (mariadbd) S 0 1 1 0 -1 4194560 9798 25285 0 0 12 33 48 22 20 0 9'
$before = ConvertFrom-MariaDbProcessStat $stat '100'
$after = ConvertFrom-MariaDbProcessStat ($stat.Replace('12 33 48 22', '42 53 48 22')) '100'
Assert-True ($before.UserTicks -eq 12 -and $before.SystemTicks -eq 33)
Assert-True ($after.UserTicks -eq 42 -and $after.SystemTicks -eq 53)
Assert-Throws { ConvertFrom-MariaDbProcessStat ($stat.Replace('(mariadbd)', '(sh)')) '100' }
Assert-Throws { ConvertFrom-MariaDbProcessStat $stat '0' }
Assert-Throws { ConvertFrom-MariaDbProcessStat '1 (mariadbd) S 0' '100' }
$samples = @(1..5 | ForEach-Object { [pscustomobject]@{ BackendProcessCpuPercent = [double]($_ * 2) } })
$summary = New-ControlledSmallSeatCpuAttribution $samples $before $after 1.0 10.0 10.0
Assert-True ($summary.SampleCount -eq 5 -and $summary.BackendProcessCpuPeakPercent -eq 10)
Assert-True ($summary.BackendProcessCpuAveragePercent -eq 6)
Assert-True ([math]::Abs($summary.MariaDbProcessCpuSeconds - 0.5) -lt 0.0001)
Assert-True ([math]::Abs($summary.MariaDbCoreAveragePercent - 5) -lt 0.0001)
Assert-True ([math]::Abs($summary.K6CoreAveragePercent - 10) -lt 0.0001)
Assert-Throws { New-ControlledSmallSeatCpuAttribution $samples $after $before 1.0 10.0 10.0 }
Assert-Throws { New-ControlledSmallSeatCpuAttribution $samples $before $after -1.0 10.0 10.0 }
$samples[2].BackendProcessCpuPercent = [double]::NaN
Assert-Throws { New-ControlledSmallSeatCpuAttribution $samples $before $after 1.0 10.0 10.0 }
$p1 = [pscustomobject]@{ TimestampUtc='2026-10-10T00:00:00Z'; ElapsedMilliseconds=0; HostCpuPercent=50; ProcessSnapshot=@(
    [pscustomobject]@{Id=10;Name='java';CpuSeconds=1},
    [pscustomobject]@{Id=20;Name='k6';CpuSeconds=1},
    [pscustomobject]@{Id=30;Name='vmmemWSL';CpuSeconds=1},
    [pscustomobject]@{Id=40;Name='editor';CpuSeconds=1}) }
$p2 = [pscustomobject]@{ TimestampUtc='2026-10-10T00:00:01Z'; ElapsedMilliseconds=1000; HostCpuPercent=60; ProcessSnapshot=@(
    [pscustomobject]@{Id=10;Name='java';CpuSeconds=1.2},
    [pscustomobject]@{Id=20;Name='k6';CpuSeconds=1.1},
    [pscustomobject]@{Id=30;Name='vmmemWSL';CpuSeconds=1.3},
    [pscustomobject]@{Id=40;Name='editor';CpuSeconds=2.0}) }
$group = New-ControlledHostProcessCpuGroups $p1 $p2 10 4
Assert-True ($group.MatchedProcessCount -eq 4 -and $group.IntervalMilliseconds -eq 1000)
Assert-True ([math]::Abs($group.BackendJvmHostCapacityPercent - 5) -lt 0.001)
Assert-True ([math]::Abs($group.LoadGeneratorHostCapacityPercent - 2.5) -lt 0.001)
Assert-True ([math]::Abs($group.DockerWslHostCapacityPercent - 7.5) -lt 0.001)
Assert-True ([math]::Abs($group.OtherReadableHostCapacityPercent - 25) -lt 0.001)
Assert-Throws { New-ControlledHostProcessCpuGroups $p1 $p2 0 4 }
$empty = $p2.PSObject.Copy(); $empty.ProcessSnapshot = @()
Assert-Throws { New-ControlledHostProcessCpuGroups $p1 $empty 10 4 }
Write-Output "CONTROLLED_SMALL_SEAT_CPU_ATTRIBUTION_TESTS_PASSED assertions=$assertions"
