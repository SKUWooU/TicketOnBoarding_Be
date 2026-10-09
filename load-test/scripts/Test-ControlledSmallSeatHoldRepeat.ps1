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

Write-Output "CONTROLLED_SMALL_SEAT_HOLD_REPEAT_TESTS_PASSED assertions=$assertions"
