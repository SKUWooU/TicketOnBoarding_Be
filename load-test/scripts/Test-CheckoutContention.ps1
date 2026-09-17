Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'CheckoutContention.psm1') -Force

$assertions = 0
function Assert-Equal { param($Actual, $Expected, $Message) $script:assertions += 1; if ($Actual -ne $Expected) { throw "$Message expected=$Expected actual=$Actual" } }
function Assert-Throws { param([scriptblock]$Action) $script:assertions += 1; try { & $Action; throw 'Expected failure.' } catch { if ($_.Exception.Message -eq 'Expected failure.') { throw } } }

$resultText = 'CHECKOUT_RESULT {"schemaVersion":1,"scenario":"distributed","targetRatePerSecond":5,"duration":"10s","thresholdsEnforced":true,"iterations":50,"droppedIterations":0,"checkoutConfirmed":50,"expectedContention":0,"unexpectedNonSuccessful":0,"unexpectedFailureRate":0,"checkoutDurationMs":{"p95":20,"average":10}}'
$snapshotText = 'CHECKOUT_FINAL_SNAPSHOT {"reservedSeats":50,"reservations":50,"bookings":50,"payments":50,"invariantSatisfied":true}'
$deltaText = 'CHECKOUT_TRANSITION_DELTA {"reservationConfirmed":50,"verificationClaimed":50}'
$result = ConvertFrom-CheckoutK6Result $resultText
$snapshot = ConvertFrom-CheckoutFinalSnapshot $snapshotText
$delta = ConvertFrom-CheckoutTransitionDelta $deltaText
Assert-Equal $result.checkoutConfirmed 50 'confirmed parser'
Assert-Equal (Assert-CheckoutRunIdentity -Result $result -Scenario 'distributed' -Rate 5 -DurationSeconds 10 -ThresholdsEnforced $true) $true 'run identity'
Assert-Equal (ConvertFrom-CheckoutFinalSnapshot 'time="2026-09-16T19:21:54+09:00" level=info msg="CHECKOUT_FINAL_SNAPSHOT {\"reservedSeats\":50,\"reservations\":50,\"bookings\":50,\"payments\":50,\"invariantSatisfied\":true}" source=console').payments 50 'escaped k6 snapshot parser'
Assert-Equal (Assert-CheckoutContentionGate -Result $result -Snapshot $snapshot -TransitionDelta $delta) $true 'healthy gate'
Assert-Equal (Assert-CheckoutDomainState -Result ([pscustomobject]@{ iterations = 50; checkoutConfirmed = 49; expectedContention = 0; unexpectedNonSuccessful = 1 }) -Snapshot ([pscustomobject]@{ reservedSeats = 49; reservations = 49; bookings = 49; payments = 49; invariantSatisfied = $true }) -TransitionDelta ([pscustomobject]@{ reservationConfirmed = 49; verificationClaimed = 49 })) $true 'domain state remains verifiable under overload'
Assert-Throws { Assert-CheckoutContentionGate -Result $result -Snapshot $snapshot -TransitionDelta ([pscustomobject]@{ reservationConfirmed = 49; verificationClaimed = 50 }) }
Assert-Throws { ConvertFrom-CheckoutK6Result 'missing' }
Assert-Throws { ConvertFrom-CheckoutFinalSnapshot 'missing' }
$runner = Get-Content -Raw -Encoding utf8 (Join-Path $PSScriptRoot 'Measure-CheckoutContention.ps1')
Assert-Equal ($runner.Contains('Save-MariaDbLatestDeadlock')) $true 'deadlock diagnostic collector contract'
Assert-Equal ($runner.Contains('DeadlockDiagnosticsFile')) $true 'deadlock diagnostic summary contract'
Assert-Equal ($runner.Contains('metrics.Deltas.DbDeadlocks')) $true 'deadlock delta source contract'
Write-Output "CheckoutContention checks passed: $assertions assertions."
