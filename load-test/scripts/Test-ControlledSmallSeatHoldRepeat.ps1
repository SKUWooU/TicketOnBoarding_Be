[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'ControlledSmallSeatHoldRepeat.psm1') -Force
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

$plan = @(Get-ControlledSmallSeatHoldRepeatPlan)
Assert-True ($plan.Count -eq 9)
Assert-True ((@($plan | ForEach-Object Rate) -join ',') -eq '5,10,20,20,10,5,5,10,20')
Assert-True ((@($plan | ForEach-Object Round) -join ',') -eq '1,1,1,2,2,2,3,3,3')
Assert-True (@($plan | Where-Object { $_.DurationSeconds -ne 10 -or $_.HotSeatCount -ne 1 -or
    $_.HoldDwellMilliseconds -ne 500 }).Count -eq 0)

$samples = @(0..5 | ForEach-Object {
    [pscustomobject]@{
        ElapsedMilliseconds = $_ * 1000
        HikariPending = if ($_ -eq 2) { 3 } else { 0 }
        HikariActive = $_ + 1
        HikariMax = 24
        HikariAcquireCount = $_ * 10
        HikariAcquireSeconds = $_ * 0.01
        HikariTimeoutCount = 0
        DbRowLockCurrentWaits = if ($_ -eq 3) { 1 } else { 0 }
        DbRowLockWaits = $_ * 2
        DbRowLockTimeMs = $_ * 4
        DbDeadlocks = 0
    }
})
$result = New-ControlledSmallSeatHoldWaitSummary $samples
Assert-True ($result.SampleCount -eq 6 -and $result.MaxSampleGapMs -eq 1000)
Assert-True ($result.HikariPendingPeak -eq 3 -and $result.HikariActivePeak -eq 6)
Assert-True ($result.HikariAcquireCount -eq 50 -and [math]::Abs($result.HikariAcquireWaitAverageMs - 1) -lt 0.0001)
Assert-True ($result.DbRowLockCurrentWaitsPeak -eq 1 -and $result.DbRowLockWaitsDelta -eq 10)
Assert-True ($result.DbRowLockTimeMsDelta -eq 20 -and $result.DbDeadlocksDelta -eq 0)
Assert-Throws { New-ControlledSmallSeatHoldWaitSummary @($samples | Select-Object -First 4) }
$bad = @($samples | ForEach-Object { $_.PSObject.Copy() }); $bad[2].ElapsedMilliseconds = 4001
Assert-Throws { New-ControlledSmallSeatHoldWaitSummary $bad }
$bad = @($samples | ForEach-Object { $_.PSObject.Copy() }); $bad[4].DbRowLockWaits = 0
Assert-Throws { New-ControlledSmallSeatHoldWaitSummary $bad }
$bad = @($samples | ForEach-Object { $_.PSObject.Copy() }); $bad[4].HikariAcquireCount = 0
Assert-Throws { New-ControlledSmallSeatHoldWaitSummary $bad }
$bad = @($samples | ForEach-Object { $_.PSObject.Copy() }); foreach ($sample in $bad) { $sample.HikariAcquireCount = 0 }
Assert-Throws { New-ControlledSmallSeatHoldWaitSummary $bad }

$samplingPlan = @(Get-ControlledSmallSeatSamplingPlan)
Assert-True ($samplingPlan.Count -eq 8)
Assert-True ((@($samplingPlan | ForEach-Object SamplingIntervalMilliseconds) -join ',') -eq '1000,2000,1000,2000,2000,1000,1000,2000')
Assert-True (@($samplingPlan | Where-Object Measured).Count -eq 6)
Assert-True (@($samplingPlan | Where-Object { $_.Rate -ne 20 -or $_.HotSeatCount -ne 1 -or $_.DurationSeconds -ne 15 }).Count -eq 0)
$costs = @(1..5 | ForEach-Object { [pscustomobject]@{ CollectionDurationMs = $_ * 10 } })
$cost = New-ControlledSamplingCostSummary $costs
Assert-True ($cost.SampleCount -eq 5 -and $cost.CollectionTotalMs -eq 150 -and $cost.CollectionMaxMs -eq 50 -and $cost.CollectionAverageMs -eq 30)
Assert-Throws { New-ControlledSamplingCostSummary @($costs | Select-Object -First 4) }
$badCosts = @($costs | ForEach-Object { $_.PSObject.Copy() }); $badCosts[0].CollectionDurationMs = -1
Assert-Throws { New-ControlledSamplingCostSummary $badCosts }
$badCosts[0].CollectionDurationMs = [double]::NaN
Assert-Throws { New-ControlledSamplingCostSummary $badCosts }
$badCosts[0].CollectionDurationMs = [double]::PositiveInfinity
Assert-Throws { New-ControlledSamplingCostSummary $badCosts }
$badCosts[0].CollectionDurationMs = $null
Assert-Throws { New-ControlledSamplingCostSummary $badCosts }
Write-Output "CONTROLLED_SMALL_SEAT_HOLD_REPEAT_TESTS_PASSED assertions=$assertions"
