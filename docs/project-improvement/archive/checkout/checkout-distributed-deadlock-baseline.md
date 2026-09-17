# Checkout distributed deadlock 기준선

## 확인한 문제

Issue #124의 distributed Checkout 100 RPS 결과에서 deadlock이 관찰됐지만, HTTP 결과·global counter만으로는 서비스 메서드의 lock order 문제인지 assignment index의 insert 경합인지 구분할 수 없었다.

## 잠금 경로와 이번 기준선

| 경로 | 실제 잠금/쓰기 순서 |
| --- | --- |
| Checkout 준비 | canonical `Seat` → active `CheckoutSeatAssignment` range lock → `Checkout` insert → assignment insert → request key insert |
| 결제 검증 claim | `Checkout` → canonical `Seat` → checkout assignment |
| 예약 확정 | `Checkout` → `Booking`·`Payment` insert → canonical `Seat`/예약 → checkout assignment |
| READY Checkout 취소 | `Checkout` → canonical `Seat` → checkout assignment |

이번 기준선은 준비 경로의 서로 다른 두 Seat가 active assignment range lock을 각각 얻은 직후 barrier로 대기한 뒤 동시에 assignment insert를 시도하게 한다. `Measure-CheckoutContention.ps1`는 run의 MariaDB deadlock delta가 0보다 크면 run 종료 뒤 local Compose 연결로 `SHOW ENGINE INNODB STATUS`의 최신 deadlock을 raw 결과 디렉터리에 남긴다. 진단 수집은 k6 요청 경로나 트랜잭션에 추가 쿼리를 넣지 않는다.

## 관찰 결과

로컬 단일 Backend·Docker Compose MariaDB 10.11.8·mock PG·run별 2,000석 fixture에서 `distributed`, 100 RPS, 10초를 실행했다. threshold는 원인 관찰을 위해 비활성화했다.

| 항목 | 값 |
| --- | --- |
| completed / dropped | 561 / 440 |
| confirmed / unexpected | 446 / 115 |
| verified path p95 | 2,887.75 ms |
| Hikari pending peak | 75 |
| MariaDB row lock waits / deadlocks | 551 / 230 |
| final domain snapshot | reserved·reservation·booking·payment 모두 446, invariant true |

최신 InnoDB deadlock에는 서로 다른 `seat_id`의 두 `insert into reservation_checkout_seat_assignment`가 `uk_checkout_seat_assignment_seat_active_until` index의 `supremum` insert-intention lock에서 서로 대기한 것으로 기록됐다. 이는 좌석 번호 역순 `PESSIMISTIC_WRITE` deadlock의 재발이 아니라 활성 assignment unique index의 끝 gap 경합 후보다.

동일 원인을 MariaDB Testcontainers에서 재현했다. `A1`·`A2` hold 뒤 두 Checkout 준비가 active assignment range lock을 모두 얻은 시점에 barrier를 해제하면, 3회 모두 한 쪽은 `1213/40001` deadlock이고 다른 한 쪽만 READY Checkout·request key·assignment를 commit했다. rollback 뒤 두 Seat hold는 보존되고 Booking·Payment·Reservation·reserved Seat는 모두 0이다.

## 한계와 다음 판단

- `SHOW ENGINE INNODB STATUS`는 최신 1건만 보존하므로 모든 deadlock의 비율이나 원인을 대표하지 않는다.
- 이 결과는 로컬 mock fixture이며 운영 트래픽·PG·SLA를 의미하지 않는다.
- 다음 Issue에서 index 제약의 역할(동시 활성 assignment 차단)과 query가 필요한 uniqueness 범위를 분리한 뒤, 같은 조건 A/B와 rollback 불변식으로 변경을 검증한다. 이 근거 전에는 Hikari 값·lock order·재시도 정책을 변경하지 않는다.

## 검증

- `CheckoutVerifiedReservationIntegrationTest`: MariaDB Testcontainers barrier deadlock·rollback 3회
- `Test-CheckoutContention.ps1`: collector·summary contract 포함 11 assertions
- `k6 inspect load-test/k6/checkout-contention.js`
- local measurement: `checkout126d2` (raw 결과는 Git 제외)
