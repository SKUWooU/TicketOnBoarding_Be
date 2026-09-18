Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'CheckoutDeadlockRepeat.psm1') -Force
$assertions = 0
function Assert-Equal($Actual, $Expected, [string]$Message) { $script:assertions += 1; if ($Actual -ne $Expected) { throw "$Message actual=$Actual expected=$Expected" } }

$plan = @(New-CheckoutDeadlockRepeatPlan -Repeats 3)
Assert-Equal $plan.Count 4 'A batch must contain one warmup and three measurements.'
Assert-Equal @($plan | Where-Object Warmup).Count 1 'Only the first run is warmup.'
Assert-Equal $plan[3].Repeat 3 'The third measured run must be retained.'

function New-Summary([string]$RunId, [int]$Deadlocks, [int]$Unexpected, [double]$P95) {
    [pscustomobject]@{
        ValidMeasurement=$true
        Run=[pscustomobject]@{ Id=$RunId; Scenario='distributed'; RatePerSecond=100; DurationSeconds=10 }
        Fixture=[pscustomobject]@{ TotalSeats=2000; PreparedBeforeSampling=$true }
        K6=[pscustomobject]@{
            Result=[pscustomobject]@{ checkoutConfirmed=500; unexpectedNonSuccessful=$Unexpected; unexpectedFailureRate=0; checkoutDurationMs=[pscustomobject]@{ p95=$P95 }; droppedIterations=500 }
            FinalSnapshot=[pscustomobject]@{ invariantSatisfied=$true }
            TransitionDelta=[pscustomobject]@{}
        }
        Metrics=[pscustomobject]@{ Peaks=[pscustomobject]@{ HikariPending=20 }; Deltas=[pscustomobject]@{ DbDeadlocks=$Deadlocks } }
    }
}

$records = foreach ($repeat in 1..3) { $runId = "i132-r$repeat"; [pscustomobject]@{ Warmup=$false; RunId=$runId; Summary=(New-Summary -RunId $runId -Deadlocks 0 -Unexpected 0 -P95 (100+$repeat)) } }
$aggregate = New-CheckoutDeadlockRepeatAggregate -Records $records
Assert-Equal $aggregate.MeasuredRepeatCount 3 'The aggregate must preserve repeat count.'
Assert-Equal $aggregate.Gate.AllDeadlocksZero $true 'Zero deadlocks must pass the deadlock gate.'
Assert-Equal $aggregate.Metrics.CheckoutP95Milliseconds.Median 102 'The aggregate must retain the p95 median.'

$records[1].Summary.Metrics.Deltas.DbDeadlocks = 1
$aggregateWithDeadlock = New-CheckoutDeadlockRepeatAggregate -Records $records
Assert-Equal $aggregateWithDeadlock.Gate.AllDeadlocksZero $false 'A detected deadlock must require follow-up.'
Write-Output "CHECKOUT_DEADLOCK_REPEAT_TESTS_PASSED assertions=$assertions"
