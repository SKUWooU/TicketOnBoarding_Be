# 좌석 hold Hikari pool 반복 matrix 기준선

## 목적

#112의 단일 200 RPS 진단에서 Hikari pool 24가 더 나은 방향성을 보였지만, 한 번의 local 실행으로 기본값을 조정할 수는 없었다. 같은 가상 2,000석 churn workload를 warm-up과 본 측정으로 분리해 pool 10·16·24를 각각 세 번 비교했다.

## 변경

- pool별 warm-up 1회와 본 측정 3회를 구분하는 runner·manifest·aggregate를 추가했다.
- aggregate는 완료율, dropped iteration, cycle p95, Hikari pending, process/system CPU, heap, deadlock의 중앙값·최소·최대를 유지한다.
- Prometheus의 실제 `hikaricp_connections_max`가 요청한 pool과 다른 경우 측정을 실패시킨다.
- 본 반복 결과를 근거로 `local` profile의 Hikari 기본값만 10에서 24로 바꿨다. 환경 변수로 10·16·24 등 다른 값을 계속 지정할 수 있다.

## 측정 조건

- Windows local 단일 Spring Boot 인스턴스, MariaDB 10.11.8 Compose, loopback HTTP
- `local,loadtest`, 가상 2,000석, `distributed-churn`, 200 RPS·10초
- 각 pool은 서버를 독립 기동하고 warm-up 1회 뒤 본 측정 3회를 실행했다. warm-up은 집계에서 제외했다.
- `preAllocatedVUs=150`, `maxVUs=500`, `-DisablePerformanceThresholds`
- raw manifest/summary는 로컬 `load-test/results/h114p10n`, `h114p16`, `h114p24`에 생성한다. fixture와 로컬 결과는 Git에 포함하지 않는다.

## 결과

| Hikari max | 완료율 중앙값 (범위) | hold p95 중앙값 (범위) | cycle p95 중앙값 (범위) | pending 중앙값 (범위) | process/system CPU 중앙값 | heap 중앙값 | deadlock |
| ---: | --- | --- | --- | --- | ---: | ---: |
| 10 | 89.71% (87.67–92.35) | 1,109.21ms (883.83–1,112.81) | 2,048.2ms (1,797.40–2,312.25) | 188 (187–190) | 46.3% / 100% | 239.4MiB | 0 |
| 16 | 97.25% (93.85–97.50) | 596.28ms (499.81–937.15) | 1,298.75ms (1,009–1,730.30) | 158 (147–180) | 47.7% / 100% | 225.5MiB | 0 |
| 24 | 99.15% (96.35–99.80) | 431.60ms (406.68–567.59) | 886ms (843–1,372) | 114 (110–159) | 47.8% / 100% | 248.6MiB | 0 |

모든 본 측정은 요청한 Hikari max를 Prometheus에서 확인했고, 예상 밖 hold/release 실패 0, final snapshot의 `activeHeldSeats=0`·`holdRows=0`, 예약·결제·재고 불변식 정상으로 종료했다.

## 판단과 한계

- 24는 10 대비 완료율 중앙값을 9.44%p 높이고 cycle p95 중앙값을 56.7% 낮추며 pending 중앙값을 39.4% 낮췄다. 16도 개선됐지만 24가 세 지표의 중앙값에서 가장 좋았다.
- system CPU 중앙값은 모든 변형에서 100%였다. 즉 pool 확대가 host CPU 여유를 만들었다는 뜻은 아니며, local 단일 인스턴스·Docker Desktop에서의 connection 대기 감소 결과로만 해석한다.
- 따라서 변경은 `application-local.properties`의 기본값에 한정한다. production pool size, 운영 SLA, 최대 처리량은 이 결과로 주장하지 않는다.
- Redis·대기열·Kafka는 필요하지 않다. 다중 인스턴스의 공정한 입장 순서나 독립 비동기 처리의 재현이 별도로 필요하다.

## 재현

각 pool은 Spring 서버를 별도 기동한 뒤 실행한다.

```powershell
$env:ONTICKET_HIKARI_MAXIMUM_POOL_SIZE = '24'
cd onticket
.\gradlew.bat bootRun --args='--spring.profiles.active=local,loadtest'
```

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File load-test/scripts/Run-HikariPoolMatrix.ps1 `
  -PoolSize 24 -Repeats 3 -Rate 200 -DurationSeconds 10
```

실제 KOPIS·PG·SMS·OAuth 호출은 포함하지 않는다.
