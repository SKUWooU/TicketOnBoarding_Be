# 좌석 hold churn 고경합 포화 기준선

## 목적

기존 hold-only 부하는 성공한 점유를 계속 누적하므로, 재고 소진 뒤의 `409`와 서버 자원 포화를 구분할 수 없었다. 이 문서는 같은 사용자 토큰이 `hold → release`를 반복하는 churn workload로 두 현상을 구분한 로컬 기준선이다.

## 변경

- `distributed-churn`: VU별 토큰·좌석을 사용해 hold 뒤 같은 좌석을 release한다. 정상 경합 없이 지속 처리 경로와 connection pool 대기를 관찰한다.
- `hot-seat-churn`: 여러 토큰이 한 좌석을 경쟁적으로 hold하고, 성공한 요청만 release한다. `409`는 예상된 도메인 경합으로 분리한다.
- k6 결과에 hold/release 성공, release 예상 밖 실패, hold 요청 시간과 전체 cycle 시간을 추가했다.
- Prometheus의 commit 기준 hold/release Timer·transition Counter, Hikari, MariaDB 상태, 마지막 fixture snapshot을 하나의 gate에서 교차 확인한다.

기존 Controller·Service·DB schema는 변경하지 않았다.

## 측정 조건

- Windows local 단일 Spring Boot 인스턴스, MariaDB 10.11.8 Compose, Hikari max 10
- `local,loadtest` profile, loopback HTTP, 가상 2,000석 fixture
- fixture 준비 시간은 metric sample에서 제외
- `distributed-churn`은 토큰별 좌석을 사용하고, `hot-seat-churn`만 `409`를 정상 결과로 허용
- 각 수치는 단일 실행의 로컬 관찰값이며 운영 예매처의 성능·SLA·최대 처리량이 아니다.

## 결과

| Scenario | 목표 | 완료율 | hold/release | 정상 409 | cycle p95 | Hikari pending peak | DB lock wait / deadlock |
| --- | ---: | ---: | --- | ---: | ---: | ---: | --- |
| distributed-churn | 100 RPS, 10초 | 100% | 1,000 / 1,000 | 0 | 280.05ms | 16 | 0 / 0 |
| distributed-churn | 200 RPS, 10초, diagnostic | 96.55% | 1,931 / 1,931 | 0 | 1,333ms | 188 | 0 / 0 |
| hot-seat-churn | 100 RPS, 10초 | 100% | 493 / 493 | 508 | 37ms | 0 | 262 / 0 |

200 RPS의 threshold-enabled 실행은 cycle p95 2,190.8ms·dropped 197로 실패했다. 동일 조건을 `-DisablePerformanceThresholds`로 다시 실행한 diagnostic 값이 표의 200 RPS 행이다. 이 재측정은 임계값을 통과했다는 뜻이 아니라 포화 신호를 수집하기 위한 것이다.

모든 실행에서 마지막 snapshot은 `activeHeldSeats=0`, `holdRows=0`, 예약·결제·재고 불변식 정상으로 수렴했다. Prometheus domain metric도 각 hold 성공과 acquired, 각 release 성공과 released가 일치했다.

## 해석과 후속 판단

- hot-seat-churn의 `409`와 row lock wait는 예상된 한 좌석 경쟁이며, dropped·Hikari pending 없이 낮은 p95와 함께 나타났다.
- distributed-churn 200 RPS는 `409`·DB lock wait 없이 Hikari pending과 dropped iteration이 증가했다. 이 조건에서는 좌석 lock보다 10개 pool의 요청 대기가 먼저 관찰된다.
- 단일 실행·단일 인스턴스이므로 Hikari 크기 조정은 아직 개선으로 확정하지 않는다. 재현 가능한 반복 측정과 CPU·GC·DB CPU 관찰이 생길 때만 pool size matrix를 다음 후보로 검토한다.
- Redis·대기열·Kafka는 이 기준선만으로 도입하지 않는다. 다중 인스턴스, 공정한 입장 순서, 독립 비동기 처리 요구가 재현되어야 한다.

## 검증 경로

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File load-test/scripts/Test-SeatHoldContention.ps1

powershell -NoProfile -ExecutionPolicy Bypass -File load-test/scripts/Measure-SeatHoldContention.ps1 `
  -Scenario distributed-churn -Rate 100 -DurationSeconds 10 `
  -RunId example-churn-d100 -FixtureRunId example-churn-d100
```

실행 전 Docker MariaDB와 `local,loadtest` Spring Boot 서버가 각각 health `UP`이어야 한다. 실제 KOPIS·PG·SMS·OAuth 호출은 포함하지 않는다.
