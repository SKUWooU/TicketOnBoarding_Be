# 좌석 hold churn Hikari pool matrix 검증

## 목적

Issue #110의 distributed churn 200 RPS에서는 정상 `409`와 MariaDB row lock wait 없이 Hikari pending이 증가했다. 따라서 pool 크기를 바로 운영 설정으로 바꾸지 않고, 같은 로컬 fixture·도착률에서 pool `10·16·24`가 완료율과 대기, JVM 보조 지표에 미치는 영향을 확인했다.

## 변경

- `local` profile에 `ONTICKET_HIKARI_MAXIMUM_POOL_SIZE` 환경 변수를 추가했다. 변수가 없으면 기존 Hikari 기본값과 같은 `10`을 사용한다.
- 측정 runner가 Prometheus의 실제 `hikaricp_connections_max`를 읽어 요청한 matrix 값과 다르면 실패하도록 했다.
- 기존 Hikari·MariaDB 표본에 process/system CPU, JVM heap 사용량 peak를 추가했다.

애플리케이션의 운영 profile, DB schema, 예약·결제 로직은 바꾸지 않았다.

## 측정 조건

- Windows local 단일 Spring Boot 인스턴스, MariaDB 10.11.8 Compose, loopback HTTP
- `local,loadtest` profile, 가상 2,000석 fixture, `distributed-churn`
- 각 pool에 대해 200 RPS·10초, `preAllocatedVUs=150`, `maxVUs=500`으로 1회 진단 실행
- 포화 신호 자체를 수집하기 위해 `-DisablePerformanceThresholds`를 사용했다. 이는 threshold 통과를 뜻하지 않는다.
- fixture 준비·reset 시간은 표본에서 제외했고, 모든 실행의 hold/release와 최종 snapshot 불변식을 확인했다.

## 결과

| Hikari max | 완료율 | 완료 / dropped | hold p95 | cycle p95 | pending peak | process CPU peak | heap peak | DB lock wait / deadlock |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| 10 | 86.21% | 1,725 / 276 | 1,312.32ms | 2,451ms | 188 | 40.0% | 220.4MiB | 0 / 0 |
| 16 | 84.81% | 1,697 / 304 | 1,351.98ms | 2,223ms | 183 | 49.6% | 265.9MiB | 0 / 0 |
| 24 | 93.46% | 1,871 / 131 | 980.26ms | 1,640.5ms | 176 | 50.9% | 217.5MiB | 0 / 0 |

세 실행 모두 Prometheus가 보고한 Hikari max는 요청한 값과 일치했고, 예상 밖 hold/release 실패는 0이었다. 마지막 snapshot도 `activeHeldSeats=0`, `holdRows=0`, 예약·결제·재고 불변식 정상으로 수렴했다.

## 해석과 결정

- 이 조건에서 pool 16은 pool 10보다 뚜렷한 개선을 보이지 않았고, pool 24는 이번 단일 진단에서 완료율과 p95가 더 좋았다.
- 그러나 각 변형이 1회뿐이고 local 단일 인스턴스의 system CPU peak가 모두 약 100%다. 이 값만으로 기본 pool을 24로 변경하거나 운영 최적값을 주장할 수 없다.
- 따라서 local 기본값은 10으로 유지한다. 다음 pool 조정 후보는 JVM/DB CPU가 포함된 반복 matrix(최소 3회, warm-up 분리)에서 중앙값·범위를 비교한 뒤 별도 Issue로 결정한다.
- `pending`이 pool을 늘려도 176까지 남아 있으므로, 이 결과는 queue·Redis·Kafka 도입 근거가 아니다. 다중 인스턴스 공정성 또는 독립 비동기 처리 요구가 재현되어야 한다.

## 재현 명령

각 변형은 서버를 다음처럼 별도로 기동한 뒤 실행한다. `N`에는 `10`, `16`, `24` 중 하나를 넣는다.

```powershell
$env:ONTICKET_HIKARI_MAXIMUM_POOL_SIZE = 'N'
cd onticket
.\gradlew.bat bootRun --args='--spring.profiles.active=local,loadtest'
```

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File load-test/scripts/Measure-SeatHoldContention.ps1 `
  -Scenario distributed-churn -Rate 200 -DurationSeconds 10 `
  -RunId example-pool-N -FixtureRunId example-pool-N `
  -PreAllocatedVus 150 -MaxVus 500 -ExpectedHikariMax N `
  -DisablePerformanceThresholds
```

실제 KOPIS·PG·SMS·OAuth 호출은 포함하지 않는다.
