[CmdletBinding()]
param([string]$BatchId='',[ValidateRange(3,10)][int]$Repeats=3,[ValidateRange(1,60)][int]$DurationSeconds=10,[ValidateRange(1,500)][int]$PreAllocatedVus=100,[ValidateRange(1,500)][int]$MaxVus=200,[string]$BaseUrl='http://127.0.0.1:18080',[string]$ManagementBaseUrl='http://127.0.0.1:18081')
Set-StrictMode -Version Latest; $ErrorActionPreference='Stop'
$root=(Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path; Import-Module (Join-Path $PSScriptRoot 'CheckoutTimingCorrelation.psm1') -Force; Import-Module (Join-Path $PSScriptRoot 'CheckoutSaturationAttribution.psm1') -Force
$measure=Join-Path $PSScriptRoot 'Measure-CheckoutContention.ps1'
if($PreAllocatedVus -gt $MaxVus){throw 'PreAllocatedVus must not exceed MaxVus.'}; if([string]::IsNullOrWhiteSpace($BatchId)){$BatchId='i136-'+(Get-Date).ToUniversalTime().ToString('MMddHHmmss')}; if($BatchId -notmatch '^[A-Za-z0-9-]{1,32}$'){throw 'BatchId must contain 1-32 letters, numbers, or hyphens.'}
$output=Join-Path $root "load-test\results\$BatchId"; if(Test-Path -LiteralPath $output){throw "Refusing to reuse a timing batch: $output"}; New-Item -ItemType Directory -Path $output -Force|Out-Null
& docker compose -f (Join-Path $root 'compose.yml') -f (Join-Path $root 'compose.statement-diagnostics.yml') up -d --force-recreate mariadb
if ($LASTEXITCODE -ne 0) { throw 'Failed to start MariaDB with statement diagnostics overlay.' }
$deadline=(Get-Date).AddSeconds(60)
do { try { $enabled=& docker compose -f (Join-Path $root 'compose.yml') -f (Join-Path $root 'compose.statement-diagnostics.yml') exec -T mariadb mariadb -uroot -ponticket-root -N -e "SHOW VARIABLES LIKE 'performance_schema';" 2>$null } catch { $enabled=@() }; if (($enabled -join "`n") -match 'performance_schema\s+ON') { break }; Start-Sleep -Seconds 2 } while ((Get-Date) -lt $deadline)
if (($enabled -join "`n") -notmatch 'performance_schema\s+ON') { throw 'MariaDB statement diagnostics overlay did not enable performance_schema.' }
$deadline=(Get-Date).AddSeconds(60)
do { try { if ((Invoke-RestMethod -Uri "$ManagementBaseUrl/actuator/health" -TimeoutSec 2).status -eq 'UP') { break } } catch {}; Start-Sleep -Seconds 2 } while ((Get-Date) -lt $deadline)
try { if ((Invoke-RestMethod -Uri "$ManagementBaseUrl/actuator/health" -TimeoutSec 2).status -ne 'UP') { throw 'Backend did not recover after MariaDB diagnostic restart.' } } catch { throw 'Backend did not recover after MariaDB diagnostic restart.' }
$records=New-Object 'Collections.Generic.List[object]'
foreach($stage in @(New-CheckoutSaturationAttributionPlan -Repeats $Repeats)){
 $label=if($stage.Warmup){'warmup'}else{"r$($stage.Repeat)"};$runId="$BatchId-$($stage.Rate)-$label";Write-Output "CHECKOUT_TIMING_START runId=$runId rate=$($stage.Rate) warmup=$($stage.Warmup)"
 & $measure -Scenario distributed -Rate $stage.Rate -DurationSeconds $DurationSeconds -PreAllocatedVus $PreAllocatedVus -MaxVus $MaxVus -RunId $runId -BaseUrl $BaseUrl -ManagementBaseUrl $ManagementBaseUrl -OutputDirectory $output -EnableStatementDiagnostics -DisablePerformanceThresholds
 $path=Join-Path $output "$runId-summary.json";$summary=Get-Content -LiteralPath $path -Raw -Encoding UTF8|ConvertFrom-Json;Assert-CheckoutTimingCorrelationSummary -Summary $summary -RunId $runId -Rate $stage.Rate|Out-Null;$records.Add([pscustomobject]@{Sequence=$stage.Sequence;Rate=$stage.Rate;Repeat=$stage.Repeat;Warmup=$stage.Warmup;RunId=$runId;SummaryFile=[IO.Path]::GetFileName($path);Summary=$summary})
}
$aggregate=@(New-CheckoutTimingCorrelationAggregate $records.ToArray());$manifest=[ordered]@{SchemaVersion=1;BatchId=$BatchId;Rates=@(50,75,100);Repeats=$Repeats;WarmupExcluded=$true;Records=$records.ToArray();Aggregate=$aggregate};$manifest|ConvertTo-Json -Depth 14|Set-Content -LiteralPath (Join-Path $output 'checkout-timing-correlation-manifest.json') -Encoding UTF8;$aggregate|ConvertTo-Json -Depth 12|Set-Content -LiteralPath (Join-Path $output 'checkout-timing-correlation-aggregate.json') -Encoding UTF8;Write-Output "CHECKOUT_TIMING_COMPLETE aggregate=$output\checkout-timing-correlation-aggregate.json"
