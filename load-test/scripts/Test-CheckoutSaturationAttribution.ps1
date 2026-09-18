Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'CheckoutSaturationAttribution.psm1') -Force
$assertions = 0
function Assert-Equal($Actual, $Expected, [string]$Message) { $script:assertions += 1; if ($Actual -ne $Expected) { throw "$Message actual=$Actual expected=$Expected" } }

$plan = @(New-CheckoutSaturationAttributionPlan -Repeats 3)
Assert-Equal $plan.Count 12 'Three rates require one warmup and three measurements each.'
Assert-Equal @($plan | Where-Object Warmup).Count 3 'Each rate needs one warmup.'
Assert-Equal @($plan | Where-Object { -not $_.Warmup -and $_.Rate -eq 100 }).Count 3 'Rate 100 needs three measurements.'

function New-Summary([string]$RunId, [int]$Rate, [int]$Dropped, [int]$Allocated, [int]$Pending, [int]$LockWaits) {
    [pscustomobject]@{
        ValidMeasurement=$true
        Run=[pscustomobject]@{ Id=$RunId; Scenario='distributed'; RatePerSecond=$Rate; DurationSeconds=10 }
        Fixture=[pscustomobject]@{ TotalSeats=2000; PreparedBeforeSampling=$true }
        K6=[pscustomobject]@{
            Result=[pscustomobject]@{ schemaVersion=2; iterations=500; droppedIterations=$Dropped; checkoutConfirmed=500; expectedContention=0; unexpectedNonSuccessful=0; unexpectedFailureRate=0; checkoutDurationMs=[pscustomobject]@{ p95=100 }; maxObservedVus=$Allocated; maxAllocatedVus=$Allocated; preAllocatedVus=100; configuredMaxVus=200 }
            FinalSnapshot=[pscustomobject]@{ invariantSatisfied=$true; reservedSeats=500; reservations=500; bookings=500; payments=500 }
            TransitionDelta=[pscustomobject]@{ reservationConfirmed=500; verificationClaimed=500 }
        }
        Metrics=[pscustomobject]@{ Peaks=[pscustomobject]@{ HikariPending=$Pending; DbRowLockCurrentWaits=0 }; Deltas=[pscustomobject]@{ DbRowLockWaits=$LockWaits; DbRowLockTimeMs=($LockWaits*10); DbDeadlocks=0 } }
    }
}

$records = foreach ($rate in 50,75,100) { foreach ($repeat in 1..3) { $runId="i134-$rate-r$repeat"; [pscustomobject]@{ Rate=$rate; Repeat=$repeat; Warmup=$false; RunId=$runId; Summary=(New-Summary -RunId $runId -Rate $rate -Dropped $(if ($rate -eq 100) { 10 } else { 0 }) -Allocated $(if ($rate -eq 100) { 200 } else { 100 }) -Pending $(if ($rate -eq 75) { 5 } else { 0 }) -LockWaits $(if ($rate -eq 75) { 3 } else { 0 })) } } }
$aggregate = @(New-CheckoutSaturationAttributionAggregate -Records $records)
Assert-Equal $aggregate.Count 3 'All configured rates must aggregate.'
Assert-Equal $aggregate[2].SignalRuns.VuCapReached 3 'VU cap signal must be retained.'
Assert-Equal $aggregate[1].SignalRuns.PoolQueueObserved 3 'Pool queue signal must be retained.'
Assert-Equal $aggregate[1].SignalRuns.DbLockObserved 3 'DB lock signal must be retained.'
Assert-Equal $aggregate[2].Metrics.DroppedIterations.Median 10 'Dropped iterations must aggregate.'
$notObservedAtCap = New-Summary -RunId 'i134-not-at-cap' -Rate 100 -Dropped 10 -Allocated 200 -Pending 0 -LockWaits 0
$notObservedAtCap.K6.Result.maxObservedVus = 199
Assert-Equal (Get-CheckoutSaturationSignals -Summary $notObservedAtCap).VuCapReached $false 'Allocated VUs alone must not classify VU cap reached.'
Write-Output "CHECKOUT_SATURATION_ATTRIBUTION_TESTS_PASSED assertions=$assertions"
