# Checkout 고경합 결과와 도메인 상태 교차 검증

## 질문

Checkout API의 HTTP 200과 k6의 성공 수만으로 transaction commit 이후 좌석·예약·결제 상태가 같은 수로 수렴했음을 알 수 있는가?

알 수 없다. 네트워크 재시도, rollback, metric 집계 시점 차이에서는 HTTP 응답만 성공이어도 최종 상태가 다를 수 있다. Issue #120에서 추가한 commit 기준 metric을 2,000석 fixture의 Checkout 흐름에 연결해 이 차이를 검증한다.

## 범위와 계약

`checkout-contention.js`는 loadtest profile에서만 다음 순서로 실행한다.

1. 명시한 `runId`로 2,000석 가상 fixture와 전용 사용자 토큰을 생성한다.
2. `seat-hold → Checkout 준비 → mock PG 검증 예약 확정`을 호출한다.
3. 종료 시 fixture snapshot과 Prometheus의 commit 기준 Checkout transition counter를 읽는다.
4. PowerShell gate가 k6 결과, snapshot, counter delta를 함께 비교한다.

성공 응답 수 `confirmed`에 대해 다음을 모두 요구한다.

- `iterations = confirmed + expectedContention + unexpectedNonSuccessful`
- 예상 밖 비정상 응답과 failure rate는 0
- `reservedSeats = reservations = bookings = payments = confirmed`
- `reservation_confirmed delta = verification_claimed delta = confirmed`
- fixture 재고 불변식이 참

`hot-seat`의 hold 단계 409은 한 좌석을 여러 사용자가 선점하려 할 때의 의도된 경합으로 별도 집계한다. HTTP 오류나 transaction 실패와 혼동하지 않는다.

## 로컬 재현 결과

2026-09-16에 Docker Compose MariaDB, `local,loadtest` Spring profile, mock PG에서 아래 smoke를 실행했다.

```powershell
k6 run -e RUN_ID=checkout122d5 -e TEST_SCENARIO=hot-seat -e RATE=20 -e DURATION=10s -e TOKEN_COUNT=100 load-test/k6/checkout-contention.js
```

| 항목 | 결과 |
| --- | ---: |
| iterations | 200 |
| Checkout 확정 | 1 |
| 의도된 seat-hold 경합 | 199 |
| 예상 밖 비정상 응답 | 0 |
| Checkout duration p95 | 142 ms |
| reserved / reservation / booking / payment | 1 / 1 / 1 / 1 |
| `verification_claimed` / `reservation_confirmed` delta | 1 / 1 |

따라서 이 조건에서는 성공 응답 1건과 commit된 도메인 행 및 상태 전이 metric 1건이 일치했다. 이 값은 단일 로컬 인스턴스와 mock PG의 짧은 smoke 결과일 뿐, 실제 PG·운영 좌석·운영 SLA 또는 처리량 성능을 의미하지 않는다.

## 실행 시 유의점

k6 console 로그는 JSON을 escape하여 stderr에 쓰므로, Windows PowerShell의 pipeline formatter를 통과시키지 않고 원문 파일로 저장한 뒤 gate를 실행한다.

```powershell
cmd /c "k6 run -e RUN_ID=checkout122d5 -e TEST_SCENARIO=hot-seat -e RATE=20 -e DURATION=10s -e TOKEN_COUNT=100 load-test/k6/checkout-contention.js > build/checkout-k6.log 2>&1"
powershell -NoProfile -ExecutionPolicy Bypass -Command "& { Import-Module load-test/scripts/CheckoutContention.psm1; $text = Get-Content build/checkout-k6.log -Raw; Assert-CheckoutContentionGate (ConvertFrom-CheckoutK6Result $text) (ConvertFrom-CheckoutFinalSnapshot $text) (ConvertFrom-CheckoutTransitionDelta $text) }"
```

## 제외와 다음 조건

- 실제 PG, webhook, 환불, 운영 데이터, 운영 SLA를 호출하거나 주장하지 않는다.
- Prometheus server·Grafana dashboard·alert rule은 이 Issue 범위가 아니다. 먼저 여러 고경합 시나리오의 반복 측정에서 어떤 metric과 임계값이 의사결정에 유효한지 확인해야 한다.
- Kafka·outbox·대기열은 DB transaction과 fixture 결과만으로 도입하지 않는다. 독립 인스턴스 확장 또는 비동기 후속 처리 요구와 재현 가능한 병목 근거가 생길 때 재검토한다.
