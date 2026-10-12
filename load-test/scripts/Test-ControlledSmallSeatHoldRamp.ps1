[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'ControlledSmallSeatHoldRamp.psm1') -Force
$assertions = 0
function Assert-True([bool]$condition) {
    $script:assertions++
    if (-not $condition) { throw 'Expected true.' }
}
function Assert-Throws([scriptblock]$action) {
    $script:assertions++
    try { & $action | Out-Null } catch { return }
    throw 'Expected an error.'
}

$plan = @(Get-ControlledSmallSeatHoldRampPlan)
Assert-True ($plan.Count -eq 3)
Assert-True ((@($plan | ForEach-Object Rate) -join ',') -eq '5,10,20')
Assert-True (@($plan | Where-Object { $_.DurationSeconds -ne 10 -or $_.HotSeatCount -ne 1 -or
    $_.HoldDwellMilliseconds -ne 500 }).Count -eq 0)
$probePlan = @(Get-ControlledSmallSeatHoldProbe30Plan)
Assert-True ($probePlan.Count -eq 2)
Assert-True ((@($probePlan | ForEach-Object Rate) -join ',') -eq '20,30')
Assert-True (@($probePlan | Where-Object { $_.DurationSeconds -ne 10 -or $_.HotSeatCount -ne 1 -or
    $_.HoldDwellMilliseconds -ne 500 }).Count -eq 0)
Import-Module (Join-Path $PSScriptRoot 'ControlledSmallSeatHoldRepeat.psm1') -Force
$repeat30Plan = @(Get-ControlledSmallSeatHoldRepeat30Plan)
Assert-True ($repeat30Plan.Count -eq 6)
Assert-True ((@($repeat30Plan | ForEach-Object Rate) -join ',') -eq '20,30,30,20,20,30')
Assert-True ((@($repeat30Plan | ForEach-Object Sequence) -join ',') -eq '1,2,3,4,5,6')
Assert-True (@($repeat30Plan | Where-Object { $_.DurationSeconds -ne 10 -or $_.HotSeatCount -ne 1 -or
    $_.HoldDwellMilliseconds -ne 500 }).Count -eq 0)
Assert-True (Assert-ControlledSmallSeatProbeMemory 2097152)
Assert-Throws { Assert-ControlledSmallSeatProbeMemory 2097151 }
$healthyWait = [pscustomobject]@{ HikariPendingPeak = 0; HikariActivePeak = 1; HikariMax = 24; HikariTimeoutDelta = 0; DbDeadlocksDelta = 0 }
Assert-True (Assert-ControlledSmallSeatProbeBaseline $healthyWait)
$badWait = $healthyWait.PSObject.Copy(); $badWait.HikariPendingPeak = 1
Assert-Throws { Assert-ControlledSmallSeatProbeBaseline $badWait }
$runner = Join-Path $PSScriptRoot 'Run-ControlledSmallSeatHoldRamp.ps1'
Assert-Throws { & $runner -RunId 'invalidprobe' -ComposeProject 'ticketon-controlled172-r13' -Probe30 -Repeats 3 }
Assert-Throws { & $runner -RunId 'invalidrepeat' -ComposeProject 'ticketon-controlled172-r14' -Repeat30 -Probe30 }
Assert-Throws { & $runner -RunId 'invalidrepeat' -ComposeProject 'ticketon-controlled172-r14' -Repeat30 -Repeats 3 }
Assert-Throws { & $runner -RunId 'invalidcpu' -ComposeProject 'ticketon-controlled172-r14' -AttributeCpu }
Assert-Throws { & $runner -RunId 'invalidcontrast' -ComposeProject 'ticketon-controlled172-r14' -ContrastHotspots -Repeat30 }
Assert-Throws { & $runner -RunId 'invalidcontrast' -ComposeProject 'ticketon-controlled172-r14' -ContrastHotspots -Probe30 }
Assert-Throws { & $runner -RunId 'invalidcontrast' -ComposeProject 'ticketon-controlled172-r14' -ContrastHotspots -Repeats 3 }
Assert-Throws { & $runner -RunId 'invalidsampling' -ComposeProject 'ticketon-controlled172-r14' -CompareSampling -Repeat30 }
Assert-Throws { & $runner -RunId 'invalidsampling' -ComposeProject 'ticketon-controlled172-r14' -CompareSampling -ContrastHotspots }
Assert-Throws { & $runner -RunId 'invalidsampling' -ComposeProject 'ticketon-controlled172-r14' -CompareSampling -Repeats 3 }
$hostSamples = @(1..5 | ForEach-Object { [pscustomobject]@{ HostCpuPercent = [double]($_ * 10); HostFreeMemoryKb = [long](3000000 - $_ * 100000) } })
$hostSummary = New-ControlledSmallSeatHostSummary -Samples $hostSamples
Assert-True ($hostSummary.SampleCount -eq 5 -and $hostSummary.HostCpuPeakPercent -eq 50 -and
    $hostSummary.HostFreeMemoryMinimumKb -eq 2500000)
$hostSamples[0].HostFreeMemoryKb = -1
Assert-Throws { New-ControlledSmallSeatHostSummary -Samples $hostSamples }
$hostSamples[0].HostFreeMemoryKb = 2900000
$hostSamples[0].HostCpuPercent = [double]::NaN
Assert-Throws { New-ControlledSmallSeatHostSummary -Samples $hostSamples }
$badWait = $healthyWait.PSObject.Copy(); $badWait.HikariActivePeak = 24
Assert-Throws { Assert-ControlledSmallSeatProbeBaseline $badWait }
$badWait = $healthyWait.PSObject.Copy(); $badWait.HikariTimeoutDelta = 1
Assert-Throws { Assert-ControlledSmallSeatProbeBaseline $badWait }
$badWait = $healthyWait.PSObject.Copy(); $badWait.DbDeadlocksDelta = 1
Assert-Throws { Assert-ControlledSmallSeatProbeBaseline $badWait }

$console = 'time="2026-10-08T20:00:00+09:00" level=info msg="SEAT_HOLD_FINAL_SNAPSHOT {\"expectedTotalSeats\":20,\"invariantSatisfied\":true}" source=console'
$parsed = ConvertFrom-ControlledK6ConsoleSnapshot $console
Assert-True ($parsed.expectedTotalSeats -eq 20 -and $parsed.invariantSatisfied)
Assert-Throws { ConvertFrom-ControlledK6ConsoleSnapshot 'no snapshot here' }
Assert-Throws { ConvertFrom-ControlledK6ConsoleSnapshot "$console`n$console" }

$summary = [pscustomobject]@{
    Scenario = 'weighted-hotspot-churn'; TargetRatePerSecond = 5; DurationSeconds = 10
    Iterations = 50; DroppedIterations = 0; HoldSuccess = 35; ReleaseSuccess = 35
    ExpectedContention = 15; UnexpectedNonSuccessful = 0; UnexpectedRelease = 0
    HoldDwellMilliseconds = 500
    WeightedHotspot = [pscustomobject]@{ HotSeatCount = 1; HotRequestPercent = 70 }
}
$snapshot = [pscustomobject]@{ invariantSatisfied = $true; activeHeldSeats = 0; holdRows = 0 }
$fresh = [pscustomobject]@{
    invariantSatisfied = $true; expectedTotalSeats = 20; actualSeatCount = 20
    remainingSeats = 20; activeHeldSeats = 0; holdRows = 0; reservations = 0
    bookings = 0; payments = 0
}
$counts = @(20, 0, 0, 20, 0)
Assert-True (Assert-ControlledSmallSeatHoldStage $plan[0] $summary $snapshot $fresh $counts 0)
$probeSummary = $summary.PSObject.Copy(); $probeSummary.TargetRatePerSecond = 30
$probeSummary.Iterations = 300; $probeSummary.HoldSuccess = 90
$probeSummary.ReleaseSuccess = 90; $probeSummary.ExpectedContention = 210
Assert-True (Assert-ControlledSmallSeatHoldStage $probePlan[1] $probeSummary $snapshot $fresh $counts 0)
$badProbe = $probeSummary.PSObject.Copy(); $badProbe.DroppedIterations = 1
Assert-Throws { Assert-ControlledSmallSeatHoldStage $probePlan[1] $badProbe $snapshot $fresh $counts 0 }
$bad = $summary.PSObject.Copy(); $bad.ReleaseSuccess = 34
Assert-Throws { Assert-ControlledSmallSeatHoldStage $plan[0] $bad $snapshot $fresh $counts 0 }
$bad = $summary.PSObject.Copy(); $bad.DroppedIterations = 1
Assert-Throws { Assert-ControlledSmallSeatHoldStage $plan[0] $bad $snapshot $fresh $counts 0 }
$bad = $summary.PSObject.Copy(); $bad.UnexpectedNonSuccessful = 1
Assert-Throws { Assert-ControlledSmallSeatHoldStage $plan[0] $bad $snapshot $fresh $counts 0 }
$bad = $summary.PSObject.Copy(); $bad.Iterations = 40
Assert-Throws { Assert-ControlledSmallSeatHoldStage $plan[0] $bad $snapshot $fresh $counts 0 }
$badFresh = $fresh.PSObject.Copy(); $badFresh.holdRows = 1
Assert-Throws { Assert-ControlledSmallSeatHoldStage $plan[0] $summary $snapshot $badFresh $counts 0 }
Assert-Throws { Assert-ControlledSmallSeatHoldStage $plan[0] $summary $snapshot $fresh @(20, 1, 0, 20, 0) 0 }
Assert-Throws { Assert-ControlledSmallSeatHoldStage $plan[0] $summary $snapshot $fresh $counts 1 }

$contrast = @(Get-ControlledSmallSeatHoldContrastPlan)
Assert-True ($contrast.Count -eq 8)
Assert-True ((@($contrast | Where-Object Measured | ForEach-Object HotSeatCount) -join ',') -eq '1,10,10,1,1,10')
Assert-True (@($contrast | Where-Object { -not $_.Measured }).Count -eq 2)
Assert-True (@($contrast | Where-Object { $_.Rate -ne 20 -or $_.DurationSeconds -ne 10 -or $_.HoldDwellMilliseconds -ne 500 }).Count -eq 0)
$contrastSummary = $probeSummary.PSObject.Copy()
$contrastSummary.TargetRatePerSecond = 20
$contrastSummary.Iterations = 200; $contrastSummary.ExpectedContention = 110
$contrastSummary.WeightedHotspot = [pscustomobject]@{ HotSeatCount = 10; HotRequestPercent = 70; Seed = 17; HotSelections = 140; ColdSelections = 60 }
$contrastSummary | Add-Member -NotePropertyName PreAllocatedVus -NotePropertyValue 20
$contrastSummary | Add-Member -NotePropertyName ConfiguredMaxVus -NotePropertyValue 20
Assert-True (Assert-ControlledSmallSeatHoldStage $contrast[3] $contrastSummary $snapshot $fresh $counts 0 -ContrastHotspots)
$sampling = @(Get-ControlledSmallSeatSamplingPlan)
$samplingSummary = $contrastSummary.PSObject.Copy()
$samplingSummary.DurationSeconds = 15
$samplingSummary.Iterations = 300; $samplingSummary.ExpectedContention = 210
$samplingSummary.WeightedHotspot = [pscustomobject]@{ HotSeatCount = 1; HotRequestPercent = 70; Seed = 17; HotSelections = 210; ColdSelections = 90 }
Assert-True (Assert-ControlledSmallSeatHoldStage $sampling[0] $samplingSummary $snapshot $fresh $counts 0 -CompareSampling)
Assert-Throws { Assert-ControlledSmallSeatHoldStage $sampling[0] $samplingSummary $snapshot $fresh $counts 0 }
$badSampling = $samplingSummary.PSObject.Copy(); $badSampling.DurationSeconds = 10
Assert-Throws { Assert-ControlledSmallSeatHoldStage $sampling[0] $badSampling $snapshot $fresh $counts 0 -CompareSampling }
# Ten-seat plans are allowed only in the new fixed contrast mode, never in legacy modes.
Assert-Throws { Assert-ControlledSmallSeatHoldStage $contrast[3] $contrastSummary $snapshot $fresh $counts 0 }
$badContrast = $contrastSummary.PSObject.Copy(); $badContrast.WeightedHotspot = $contrastSummary.WeightedHotspot.PSObject.Copy()
$badContrast.WeightedHotspot.HotSeatCount = 1
Assert-Throws { Assert-ControlledSmallSeatHoldStage $contrast[3] $badContrast $snapshot $fresh $counts 0 -ContrastHotspots }
$badContrast.WeightedHotspot.HotSeatCount = 10; $badContrast.WeightedHotspot.Seed = 18
Assert-Throws { Assert-ControlledSmallSeatHoldStage $contrast[3] $badContrast $snapshot $fresh $counts 0 -ContrastHotspots }
$badContrast.WeightedHotspot.Seed = 17; $badContrast.WeightedHotspot.HotSelections = 139
Assert-Throws { Assert-ControlledSmallSeatHoldStage $contrast[3] $badContrast $snapshot $fresh $counts 0 -ContrastHotspots }
$badContrast = $contrastSummary.PSObject.Copy(); $badContrast.ConfiguredMaxVus = 100
Assert-Throws { Assert-ControlledSmallSeatHoldStage $contrast[3] $badContrast $snapshot $fresh $counts 0 -ContrastHotspots }
$badPlan = $contrast[3].PSObject.Copy(); $badPlan.Rate = 30
Assert-Throws { Assert-ControlledSmallSeatHoldStage $badPlan $contrastSummary $snapshot $fresh $counts 0 -ContrastHotspots }
Assert-Throws { Assert-ControlledSmallSeatHoldStage $contrast[3] $contrastSummary $snapshot $fresh @(20, 1, 0, 20, 0) 0 -ContrastHotspots }
Assert-Throws { Assert-ControlledSmallSeatHoldStage $contrast[3] $contrastSummary $snapshot $fresh $counts 1 -ContrastHotspots }

Write-Output "CONTROLLED_SMALL_SEAT_HOLD_RAMP_TESTS_PASSED assertions=$assertions"
