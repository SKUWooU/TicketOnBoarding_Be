[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'ControlledSmallFixture.psm1') -Force
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

$config = [pscustomobject]@{ rows = 2; seatsPerRow = 10; totalSeats = 20 }
Assert-True (Assert-ControlledSmallFixtureConfig $config)
Assert-Throws { Assert-ControlledSmallFixtureConfig ([pscustomobject]@{ rows = 50; seatsPerRow = 40; totalSeats = 2000 }) }

$fixture = [pscustomobject]@{
    runId = 'smallfixture180'; concertId = 'LOAD-TEST-smallfixture180'
    concertTimeId = 1; rows = 2; seatsPerRow = 10; totalSeats = 20
}
$snapshot = [pscustomobject]@{
    expectedTotalSeats = 20; actualSeatCount = 20; remainingSeats = 20
    reservedSeats = 0; reservations = 0; bookings = 0; payments = 0; invariantSatisfied = $true
}
$holds = [pscustomobject]@{
    expectedTotalSeats = 20; actualSeatCount = 20; remainingSeats = 20
    activeHeldSeats = 0; holdRows = 0; partialHoldStates = 0; invariantSatisfied = $true
}
$counts = @(1, 1, 20, 0, 0, 0, 0, 0)
Assert-True (Assert-ControlledSmallFixtureState 'smallfixture180' $fixture $snapshot $holds $counts)
Assert-Throws { Assert-ControlledSmallFixtureState 'other' $fixture $snapshot $holds $counts }
$invalidInventory = $snapshot.PSObject.Copy()
$invalidInventory.remainingSeats = 19
Assert-Throws { Assert-ControlledSmallFixtureState 'smallfixture180' $fixture $invalidInventory $holds $counts }
Assert-Throws { Assert-ControlledSmallFixtureState 'smallfixture180' $fixture $snapshot $holds @(1, 1, 21, 0, 0, 0, 0, 0) }
$activeHold = $holds.PSObject.Copy()
$activeHold.activeHeldSeats = 1
Assert-Throws { Assert-ControlledSmallFixtureState 'smallfixture180' $fixture $snapshot $activeHold $counts }

$source = Get-Content -Raw -Encoding UTF8 -LiteralPath (Join-Path $PSScriptRoot 'Run-ControlledSmallFixture.ps1')
$preflightIndex = $source.IndexOf('$preflightOutput = & $preflight')
$configIndex = $source.IndexOf('Assert-ControlledSmallFixtureConfig -Config $configuration')
$postIndex = $source.IndexOf('Invoke-RestMethod -Method Post')
Assert-True ($preflightIndex -gt 0 -and $configIndex -gt $preflightIndex -and $postIndex -gt $configIndex)
Assert-True ($source.Contains("'http://127.0.0.1:18080'") -and -not $source.Contains('-BaseUrl'))
Assert-True (@([regex]::Matches($source, 'Invoke-RestMethod -Method Post')).Count -eq 1)

Write-Output "CONTROLLED_SMALL_FIXTURE_TESTS_PASSED assertions=$assertions"
