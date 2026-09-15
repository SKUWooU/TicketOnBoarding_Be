Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'HikariPoolMatrix.psm1') -Force
$assertions = 0
function Assert-Equal($Actual, $Expected, [string]$Message) { $script:assertions += 1; if ($Actual -ne $Expected) { throw "$Message actual=$Actual expected=$Expected" } }

$plan = @(New-HikariPoolMatrixPlan -Repeats 3)
Assert-Equal @($plan | Where-Object Warmup).Count 3 'Each pool needs a warmup.'
Assert-Equal @($plan | Where-Object { -not $_.Warmup -and $_.PoolSize -eq 24 }).Count 3 'Pool 24 needs three measured runs.'

$records = foreach ($pool in 10, 16, 24) { foreach ($repeat in 1..3) { [pscustomobject]@{ PoolSize=$pool; Warmup=$false; Summary=[pscustomobject]@{ ValidMeasurement=$true; K6=[pscustomobject]@{ StateInvariantSatisfied=$true; Result=[pscustomobject]@{ ScheduledIterationAttainmentRate=0.9; DroppedIterations=200; HoldDurationMs=[pscustomobject]@{ P95=(500+$pool+$repeat) }; CycleDurationMs=[pscustomobject]@{ P95=(1000+$pool+$repeat) } }; }; Metrics=[pscustomobject]@{ Peaks=[pscustomobject]@{ HikariMax=$pool; HikariPending=180; ProcessCpuUsage=0.4; SystemCpuUsage=0.9; HeapUsedBytes=1000 }; Deltas=[pscustomobject]@{ DbDeadlocks=0 } } } } } }
$aggregate = @(New-HikariPoolMatrixAggregate -Records $records)
Assert-Equal $aggregate.Count 3 'All pool variants must aggregate.'
Assert-Equal $aggregate[2].RepeatCount 3 'Repeat count must be retained.'
Assert-Equal $aggregate[0].CompletionRatePercent.Median 90 'Completion median must be percent.'
Assert-Equal $aggregate[0].HoldP95Ms.Median 512 'Hold median must be retained.'
Assert-Equal $aggregate[2].CycleP95Ms.Median 1026 'Cycle median must be retained.'
Assert-Equal $aggregate[1].SystemCpuUsage.Maximum 0.9 'System CPU range must be retained.'
Write-Output "HIKARI_POOL_MATRIX_TESTS_PASSED assertions=$assertions"
