[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'ControlledHotspot.psm1') -Force
$assertions = 0
function Assert-Equal($actual, $expected) {
    $script:assertions++
    if ($actual -ne $expected) { throw "Expected=$expected Actual=$actual" }
}
function Assert-Throws([scriptblock]$action) {
    $script:assertions++
    try { & $action | Out-Null } catch { return }
    throw 'Expected an error.'
}

Assert-Equal (Assert-ControlledHotspotProject 'ticketon-controlled166' 'ticketon-controlled166' 'ticketon-controlled166') $true
Assert-Throws { Assert-ControlledHotspotProject 'ticketon-controlled166' 'ticketon-weighted164' 'ticketon-weighted164' }
Assert-Throws { Assert-ControlledHotspotProject 'ticketon-controlled166' 'ticketon-controlled166' 'ticketon-weighted164' }
Assert-Equal (Assert-ControlledHotspotPreCleanup 2000 2000 0 159 159 159 159 159 159 0 0) $true
Assert-Throws { Assert-ControlledHotspotPreCleanup 2001 2000 0 159 159 159 159 159 159 0 0 }
Assert-Throws { Assert-ControlledHotspotPreCleanup 0 0 1 0 0 0 0 0 0 0 0 }
Assert-Throws { Assert-ControlledHotspotPreCleanup 0 0 0 0 0 1 0 0 0 0 0 }
Assert-Throws { Assert-ControlledHotspotPreCleanup 0 0 0 0 0 0 0 1 0 0 0 }
Assert-Throws { Assert-ControlledHotspotPreCleanup 0 0 0 0 0 0 0 0 0 1 0 }
Assert-Throws { Assert-ControlledHotspotPreCleanup 0 0 0 0 0 0 0 0 0 0 1 }
$plan = @(New-ControlledHotspotPlan)
Assert-Equal $plan.Count 6
Assert-Equal (($plan | ForEach-Object HotSeatCount) -join ',') '20,40,200,200,40,20'
Assert-Throws { New-ControlledHotspotPlan -Rate 1000 -DurationSeconds 10 }
$valid = [pscustomobject]@{
    ValidMeasurement = $true
    K6 = [pscustomobject]@{
        InventoryInvariantSatisfied = $true
        Result = [pscustomobject]@{
            DroppedIterations = 0
            UnexpectedNonSuccessful = 0
            ReservationSuccessDurationMs = [pscustomobject]@{ P95 = 1 }
            ReservationSeatContentionDurationMs = [pscustomobject]@{ P95 = 1 }
        }
    }
    Metrics = [pscustomobject]@{ Deltas = [pscustomobject]@{ DbDeadlocks = 0 } }
    DatabaseStatementDigests = [pscustomobject]@{ Enabled = $true }
    FixturePreparation = [pscustomobject]@{ FreshFixture = [pscustomobject]@{ PhysicalSeatRows = 2000; RemainingSeats = 2000 } }
}
Assert-Equal (Assert-ControlledHotspotResult $valid) $true
$valid.K6.Result.DroppedIterations = 1
Assert-Throws { Assert-ControlledHotspotResult $valid }
$valid.K6.Result.DroppedIterations = 0
$valid.FixturePreparation.FreshFixture.PhysicalSeatRows = 4000
Assert-Throws { Assert-ControlledHotspotResult $valid }
Write-Output "Controlled hotspot checks passed: $assertions assertions."
