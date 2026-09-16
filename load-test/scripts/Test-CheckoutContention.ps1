Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'CheckoutContention.psm1') -Force

$assertions = 0
function Assert-Equal { param($Actual, $Expected, $Message) $script:assertions += 1; if ($Actual -ne $Expected) { throw "$Message expected=$Expected actual=$Actual" } }
function Assert-Throws { param([scriptblock]$Action) $script:assertions += 1; try { & $Action; throw 'Expected failure.' } catch { if ($_.Exception.Message -eq 'Expected failure.') { throw } } }

$resultText = 'CHECKOUT_RESULT {"schemaVersion":1,"scenario":"distributed","targetRatePerSecond":5,"duration":"10s","iterations":50,"droppedIterations":0,"checkoutConfirmed":50,"expectedContention":0,"unexpectedNonSuccessful":0,"unexpectedFailureRate":0,"checkoutDurationMs":{"p95":20,"average":10}}'
$snapshotText = 'CHECKOUT_FINAL_SNAPSHOT {"reservedSeats":50,"reservations":50,"bookings":50,"payments":50,"invariantSatisfied":true}'
$deltaText = 'CHECKOUT_TRANSITION_DELTA {"reservationConfirmed":50,"verificationClaimed":50}'
$result = ConvertFrom-CheckoutK6Result $resultText
$snapshot = ConvertFrom-CheckoutFinalSnapshot $snapshotText
$delta = ConvertFrom-CheckoutTransitionDelta $deltaText
Assert-Equal $result.checkoutConfirmed 50 'confirmed parser'
Assert-Equal (ConvertFrom-CheckoutFinalSnapshot 'time="2026-09-16T19:21:54+09:00" level=info msg="CHECKOUT_FINAL_SNAPSHOT {\"reservedSeats\":50,\"reservations\":50,\"bookings\":50,\"payments\":50,\"invariantSatisfied\":true}" source=console').payments 50 'escaped k6 snapshot parser'
Assert-Equal (Assert-CheckoutContentionGate -Result $result -Snapshot $snapshot -TransitionDelta $delta) $true 'healthy gate'
Assert-Throws { Assert-CheckoutContentionGate -Result $result -Snapshot $snapshot -TransitionDelta ([pscustomobject]@{ reservationConfirmed = 49; verificationClaimed = 50 }) }
Assert-Throws { ConvertFrom-CheckoutK6Result 'missing' }
Assert-Throws { ConvertFrom-CheckoutFinalSnapshot 'missing' }
Write-Output "CheckoutContention checks passed: $assertions assertions."
