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

Write-Output "CONTROLLED_SMALL_SEAT_HOLD_RAMP_TESTS_PASSED assertions=$assertions"
