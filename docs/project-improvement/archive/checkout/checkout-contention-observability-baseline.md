# Checkout 고경합 시나리오별 관측 기준선

## 질문

Checkout에서 많은 409가 발생하면 DB 또는 connection pool 포화로 보아야 하는가?

아니다. 동일 좌석을 반복 선택하는 `hot-seat`의 409은 정상적인 선점 충돌일 수 있다. 반면 서로 다른 좌석을 확정하는 `distributed`에서는 409·dropped iteration·Hikari pending·MariaDB lock delta를 함께 보아야 실제 포화 신호를 구분할 수 있다.

## 측정 계약

`Measure-CheckoutContention.ps1`은 fixture 준비를 metric sampling 전에 끝내고, 각 run에서 다음을 묶는다.

- k6 Checkout 결과: 확정·의도된 hold 409·예상 밖 비정상 응답·dropped iteration·p95
- final snapshot: reserved seat·Reservation·Booking·Payment
- commit 기준 transition delta: `verification_claimed`, `reservation_confirmed`
- 약 1초 cadence의 Hikari peak와 MariaDB row lock/deadlock delta

품질 threshold가 실패해도 `k6` exit code 99 자체를 측정 실패로 취급하지 않는다. 대신 응답 결과와 domain state가 일치하는지 별도로 검증하고, threshold 실패를 명시적으로 결과에 남긴다. 실행 오류·snapshot 불변식 위반·counter 불일치는 여전히 측정을 실패시킨다.

## 로컬 결과

조건: Windows 로컬 단일 Backend, Java 21, Docker Compose MariaDB 10.11.8, Hikari 24, local mock PG, 가상 좌석 2,000개, 10초. 실제 PG·운영 좌석·운영 트래픽은 사용하지 않았다.

| 시나리오 | 목표 | 실행/확정/기대 경합 | Checkout p95* | dropped | 예상 밖 실패율 | Hikari pending peak | DB deadlock delta |
| --- | ---: | --- | ---: | ---: | ---: | ---: | ---: |
| distributed | 20 RPS | 200 / 200 / 0 | 132 ms | 0 | 0% | 0 | 0 |
| distributed | 100 RPS | 672 / 542 / 0 | 4,804 ms | 328 | 19.35% | 174 | 260 |
| hot-seat | 100 RPS | 1,001 / 1 / 1,000 | 112 ms | 0 | 0% | 0 | 0 |

distributed 100 RPS에서는 pool이 24/24 active에 도달하고 row lock wait 654회·128,083ms가 증가했다. 하지만 확정 542건에 대해 reserved seat·Reservation·Booking·Payment 및 두 commit transition delta가 모두 542로 일치했다. 따라서 이 fixture에서 확인한 것은 **고부하 시 품질 저하와 DB 경합 신호**, 그리고 그 와중에도 확정된 재고 상태가 수렴한다는 사실이다.

`*` Checkout p95는 `verified-reservation` 호출까지 도달한 요청의 경과 시간이다. hold 409이나 Checkout 준비 단계에서 끝난 요청은 이 Trend에 포함되지 않으므로, 전체 시도 요청의 end-to-end p95로 해석하지 않는다.

hot-seat의 다수 409은 동일 좌석이 첫 성공 뒤 이미 HELD/RESERVED여서 생긴 의도된 충돌이다. Hikari pending과 deadlock delta가 0이므로 이를 distributed 100 RPS의 포화와 같은 현상으로 해석하지 않는다.

## 해석 경계

- 10초 단일 로컬 run은 운영 처리량·SLA·다중 인스턴스 성능을 의미하지 않는다.
- metric 표본 간격은 distributed 100 RPS에서 최대 6,784ms까지 늘어났다. Hikari peak와 DB delta는 포화 신호지만 정밀 시계열이라고 주장하지 않는다.
- `Threads_connected/running`은 observer CLI와 Compose healthcheck 영향을 받을 수 있어 pool 근거로 사용하지 않는다.
- Grafana·Prometheus server는 아직 도입하지 않는다. 반복 run에서 표본 간격·지표가 의사결정에 충분한지 확인하거나, 장기 보관·alert 요구가 생길 때 다시 판단한다.
- Kafka·outbox·대기열은 이 DB 경합 결과만으로 도입하지 않는다. 독립 consumer, 비동기 재시도, 다중 인스턴스 수요가 재현되어야 한다.

## 다음 판단

현재 후보는 Checkout `distributed` 100 RPS에서 확인된 deadlock·예상 밖 실패의 원인을 transaction/lock 순서 단위로 재현하는 것이다. 단, #124의 측정 결과만으로 lock 정책이나 pool 값을 변경하지 않는다. 재현 fixture와 SQL·transaction 근거를 분리한 Issue에서 먼저 원인을 확인한다.
