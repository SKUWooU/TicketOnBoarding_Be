# Checkout 결제 검증 claim과 bounded 좌석 lease

## 1. 문제와 개선 결과

Issue #79의 기준선에서는 `READY` Checkout을 읽은 뒤 transaction 밖에서 mock 결제 검증을 기다리는 동안 원래 hold가 만료될 수 있었다. 이후 `approved=true`가 반환되어도 최종 transaction은 Checkout을 `EXPIRED`로 바꾸고 거부했다. 로컬 좌석·재고 정합성은 유지됐지만, 외부 승인을 가정한 응답과 서버의 Payment 증빙 사이에는 공백이 남았다.

이번 개선은 실제 PG나 결제 시도 원장을 추가하지 않고 단일 DB Checkout을 짧게 claim한다.

```text
READY
  │ 짧은 transaction: Checkout → Seat → CheckoutSeatAssignment 잠금
  ▼
PAYMENT_VERIFYING ── transaction 밖 PaymentVerificationPort.verify()
  ├─ 검증 기한 안의 유효 승인 ──────────────> RESERVATION_CONFIRMED
  ├─ 명확한 거절·미구성 adapter ───────────> READY 또는 EXPIRED
  └─ 승인처럼 보이나 불일치·예상 밖 결과·기한 초과 ─> PAYMENT_VERIFICATION_UNKNOWN
```

`PAYMENT_VERIFYING`은 결제가 성공했다는 뜻이 아니다. 한 요청이 검증 중이라는 서버 내부 claim이다. `PAYMENT_VERIFICATION_UNKNOWN`도 결제 성공이나 실패를 단정하지 않고, 실제 PG 재조회·수동 확인이 필요한 상태를 나타낸다.

## 2. 구현 경계

### 짧은 claim transaction

`CheckoutPaymentVerificationTransactionService.claim`은 다음 순서를 지킨다.

1. merchantUid로 Checkout row를 `PESSIMISTIC_WRITE` 잠금한다.
2. 사용자·공연·회차·Checkout fingerprint와 현재 상태를 검사한다.
3. Checkout에 귀속된 좌석 목록을 확인한다.
4. canonical 좌석 번호 순서로 Seat row를 잠근다.
5. 같은 순서로 CheckoutSeatAssignment row를 잠근다.
6. Checkout을 `PAYMENT_VERIFYING`으로 바꾸고 결제 ID, 예약 멱등 키, 요청 fingerprint, 시작 시각과 검증 기한을 저장한다.
7. 좌석과 귀속의 보호 기한을 제한된 검증 기한까지 연장하고 commit한다.

그 뒤 `PaymentVerificationPort.verify`를 호출하므로 네트워크 대기 동안 DB transaction과 row lock을 유지하지 않는다. 같은 Checkout의 후속 요청은 이미 저장된 상태를 보고 외부 검증에 진입하지 않는다.

### 좌석별 원래 만료 시각 보존

복수 좌석을 시차를 두고 hold하면 각 Seat의 `heldUntil`이 다를 수 있지만 Checkout의 원래 만료는 가장 이른 시각이다. 검증 lease를 단순히 원래 시각 위에 덮어쓰면 명확한 실패 뒤 각 좌석의 시간을 정확히 복원할 수 없다.

따라서 CheckoutSeatAssignment는 다음 두 정보를 분리한다.

- `originalHoldExpiresAt`: Checkout 생성 당시 해당 Seat의 원래 만료 시각
- `verificationLeaseUntil`: 검증 중에만 사용하는 nullable 보호 기한

기존 `activeUntil`과 `(seat_id, active_until)` unique는 변경하지 않는다. 활성 귀속 조회는 `COALESCE(verificationLeaseUntil, activeUntil) > now`로 판단한다. Seat 자체는 현재 만료가 검증 기한보다 짧을 때만 연장하며, 알려진 실패 때 현재 값이 해당 검증 기한과 정확히 일치하는 좌석만 원래 값으로 복구한다. 이는 다른 쓰기가 개입한 값을 맹목적으로 덮어쓰지 않기 위한 조건부 복구다.

## 3. 결과별 상태 정책

| 관찰 결과 | Checkout | 좌석·귀속 | 이유 |
| --- | --- | --- | --- |
| `approved=false` 또는 null | 원래 만료 전 `READY`, 이후 `EXPIRED` | 검증 lease 제거, 원래 hold 복구 | 미승인이 명확함 |
| 검증 adapter 미구성(503) | 원래 만료 기준 복구 | 검증 lease 제거, 원래 hold 복구 | 실제 검증을 시작하지 못함 |
| `approved=true`, 모든 식별자·금액 일치 | `RESERVATION_CONFIRMED` | 좌석 예약, 검증 lease 제거 | 서버 예약 transaction까지 완료 |
| `approved=true`, paymentId·merchantUid·금액·승인 시각 불일치 | `PAYMENT_VERIFICATION_UNKNOWN` | deadline까지 보호 | 승인처럼 보이는 응답을 실패로 단정해 재판매하지 않음 |
| 예상 밖 adapter 예외 또는 최종 예약 실패 | `PAYMENT_VERIFICATION_UNKNOWN` | deadline까지 보호 | 실제 외부 결과를 서버만으로 확정할 수 없음 |
| 검증 deadline 도달 | 접근 시 `PAYMENT_VERIFICATION_UNKNOWN` | deadline부터 재선택 가능 | 무기한 좌석 보호 방지, 늦은 승인은 확정하지 않고 reconciliation 대상으로 분리 |

UNKNOWN은 HTTP 409로 노출한다. 현재 범위에는 PG 재조회, 자동 환불, 운영자 reconciliation API가 없으므로 사용자가 같은 Checkout으로 재시도해도 외부 검증을 다시 호출하지 않는다. deadline 전에는 좌석을 보호하고, deadline부터는 다른 사용자가 hold·새 Checkout을 만들 수 있다. 이후 도착한 기존 승인 응답은 예약으로 확정하지 않는다. 이는 무기한 재고 잠금을 피하기 위한 bounded 정책이며, 실제 PG 도입 전에는 늦은 승인 건을 조회·환불하는 reconciliation이 필수다.

## 4. 결정적 검증

환경은 Java 21, Spring Boot 3.2.5, MariaDB 10.11.8 Testcontainers, 고정 `Clock`, 공연 1개·회차 1개·가상 좌석 A1/A2, 좌석당 30,000원, 5분 hold와 30초 검증 grace다. 실제 PG·KOPIS·SMS·운영 데이터는 호출하지 않았다.

핵심 fixture 결과:

- Issue #79와 같은 `검증 진입 → 원래 만료 → mock 승인 반환` 순서를 3회 반복했고 모두 예약 확정 1, Payment 1, Booking 1, Reservation 1, reserved Seat 1, 잔여 1이었다.
- 같은 Checkout에 서로 다른 예약 키 2개를 동시에 요청하면 성공 1·409 1이고 mock 검증은 1회만 호출됐다.
- 원래 만료 1μs 전 claim은 성공하고 정확한 만료 시각의 미claim Checkout은 외부 검증 전 410으로 거부됐다.
- deadline 1ns 전에는 타 사용자의 좌석 hold가 409였고, 정확한 deadline 접근은 UNKNOWN으로 전이된 뒤 타 사용자가 hold·새 READY Checkout을 만들었다. 이후 반환된 기존 mock 승인은 예약을 확정하지 않았다. 이 순서를 3회 반복했으며 mock 검증은 각 fixture에서 1회였다.
- 명확한 거절은 원래 만료 전 READY, 원래 만료 뒤 EXPIRED로 돌아가고 Seat와 assignment의 검증 lease가 제거됐다.
- 미구성 adapter는 503을 유지하면서 READY와 원래 좌석 lease를 복구했다.
- 승인 금액·merchantUid 불일치는 Payment·Booking·Reservation을 만들지 않고 UNKNOWN과 paymentId·deadline을 보존했다.
- 유효 mock 승인 뒤 최종 reservation transaction에서 강제 실패시켰을 때 Booking·Payment·Reservation·좌석 예약·잔여 감소가 모두 rollback되고, 후속 별도 transaction의 UNKNOWN과 paymentId·멱등 키·deadline·좌석 lease만 보존됐다.
- Checkout 상태 단위 테스트와 HTTP UNKNOWN 409 계약을 함께 검증했다. 전체 Backend 177 tests와 Compose config, diff check가 failures·errors·skipped 0으로 통과했다.

이 수치는 로컬 가상 좌석의 기능·경합 fixture 결과다. 운영 PG 성공률, 실제 예매처 처리량 또는 금전 보상 완료를 의미하지 않는다.

## 5. 개선 전후

| 항목 | Issue #79 기준선 | Issue #82 개선 |
| --- | --- | --- |
| 검증 중 상태 | 없음, `READY` 유지 | `PAYMENT_VERIFYING` claim |
| 같은 Checkout 외부 검증 진입 | 경합 정책 없음 | claim 승자 1회 |
| 원래 만료 뒤 mock 승인 | `CheckoutExpiredException`, 로컬 Payment 0 | 30초 grace 안에서는 예약 확정 |
| 명확한 실패 | 별도 복구 상태 없음 | 원래 만료 기준 READY/EXPIRED 복구 |
| 결과 불명 | 추적 상태 없음 | paymentId·key·fingerprint·startedAt·deadline 보존 |
| 외부 I/O 중 DB lock | 없음 | 없음(그 성질을 유지) |

## 6. 한계와 후속 조건

- 실제 PG adapter가 없으므로 `verify` 호출은 결제 승인 요청이 아니라 mock 조회 경계다.
- 프로세스가 claim 직후 중단되면 scheduler가 없으므로 DB status는 다음 Checkout 접근 전까지 `PAYMENT_VERIFYING`일 수 있다. 다만 assignment와 검증 때문에 연장된 Seat lease는 deadline 이후 논리적으로 비활성이다.
- 시차 hold 중 원래 만료가 verification deadline보다 늦은 Seat는 별도 연장이 필요 없으므로 자기 원래 시각까지 HELD일 수 있다. UNKNOWN Checkout 귀속은 deadline에 끝나며, 이후 그 Seat는 새 Checkout에 귀속될 수 있다.
- UNKNOWN을 실제 PG와 대조하고 확정·환불하는 reconciliation 경로는 없다.
- deadline 뒤 좌석 재선택을 허용하므로 실제 PG의 늦은 승인이 있다면 예약 없는 결제와 새 판매가 동시에 생길 수 있다. 현재 mock 경계에서는 기존 늦은 응답이 DB 예약을 만들지 않는 것까지만 검증했으며, 운영 PG 전에는 재조회·환불 경로가 필수다.
- 별도 PaymentAttempt 원장, outbox, 메시지 브로커, 자동 보상은 아직 필요성을 검증하지 않았다.
- 신규 Entity schema에는 컬럼이 생성되지만 기존 DB migration은 ADR-0001에 따라 전체 schema baseline 전까지 보류한다.
- Frontend는 이번 Issue 범위가 아니므로 새로운 상태 안내 UI는 후속 연동에서 다룬다.

실제 PG adapter를 도입할 때는 provider의 조회 멱등성, 승인/취소 API 계약, webhook 중복·순서 역전, UNKNOWN 재조회 주기와 수동 개입 기준이 확보되어야 한다. 그때 Checkout 단일 row보다 독립 PaymentAttempt 원장이 필요한지 다시 결정한다.
