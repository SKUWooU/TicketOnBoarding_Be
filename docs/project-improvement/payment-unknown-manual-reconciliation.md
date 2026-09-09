# 결제 UNKNOWN 수동 단건 reconciliation 경계

## 1. 문제와 목표

Issue #94는 결제 검증의 첫 응답을 잃어 Checkout이 `PAYMENT_VERIFICATION_UNKNOWN`이 되면, provider가 이후 승인 또는 거절을 반환할 수 있어도 서버가 다시 조회하지 않는다는 사실을 고정했다. UNKNOWN은 불명확한 금전 상태를 성공이나 실패로 단정하지 않는 안전한 격리 상태지만, 영구 상태가 되어서는 안 된다.

Issue #97의 목표는 자동 배치나 실제 환불을 구현하는 것이 아니다. 단일 UNKNOWN Checkout을 명시적으로 선택해 provider 중립 결과와 대조하고, 현재 DB 모델로 안전하게 수렴할 수 있는 최소 application service 경계를 구성하는 것이다.

## 2. 구현 전후 흐름

### 구현 전

```text
PAYMENT_VERIFICATION_UNKNOWN
  └─ 사용자 재요청
       └─ provider 호출 전 HTTP 409
```

조회 port, 수동 command, scheduler, webhook이 모두 없으므로 승인·거절 어느 쪽으로도 수렴하지 않았다.

### 구현 후

```text
수동 단건 reconcile(merchantUid)
  └─ Checkout PESSIMISTIC_WRITE
       ├─ UNKNOWN → PAYMENT_VERIFYING claim commit
       ├─ 이미 RESERVATION_CONFIRMED → 기존 결과 반환
       ├─ READY/EXPIRED → NO_ACTION_REQUIRED
       └─ 처리 중 → conflict

transaction 밖 PaymentReconciliationPort.lookup(paymentId)
  ├─ UNRESOLVED → UNKNOWN 복원
  ├─ 조회 예외 → UNKNOWN 복원 후 예외 전달
  ├─ REJECTED → lease 정리, READY 또는 EXPIRED
  └─ APPROVED
       ├─ 식별자·사용자·금액·승인 시각 불일치 → UNKNOWN + MANUAL_REVIEW_REQUIRED
       ├─ deadline 전 좌석 보호 유효 → 기존 예약 확정 transaction
       └─ deadline 경과·좌석 충돌·최종 확정 실패
            → UNKNOWN + COMPENSATION_REQUIRED
```

## 3. provider 중립 계약

`PaymentReconciliationPort`는 `paymentId` 단건 조회만 제공하며 결과를 세 상태로 제한한다.

| 상태 | 의미 | 로컬 처리 |
| --- | --- | --- |
| `APPROVED` | provider가 승인 정보까지 반환 | Checkout 기대값과 전부 대조한 뒤 예약 확정 시도 |
| `REJECTED` | provider가 미승인을 확정 | 검증 lease를 정리하고 원래 만료 시각에 따라 `READY` 또는 `EXPIRED` |
| `UNRESOLVED` | 처리 중·일시 미확정 등 최종 판단 불가 | UNKNOWN 유지 |

승인 정보는 paymentId, merchantUid, username, 금액, 승인 여부, 승인 시각을 모두 확인한다. 이 중 하나라도 다르면 서버는 해당 승인을 현재 Checkout의 것으로 단정하지 않는다.

일반 profile의 `UnconfiguredPaymentReconciliationAdapter`는 외부 호출을 하지 않고 `PaymentVerificationUnavailableException`을 발생시킨다. 실제 provider adapter가 등록되기 전에는 실수로 네트워크나 금전 작업이 실행되지 않는다.

## 4. 트랜잭션과 잠금 경계

### claim transaction

1. merchantUid로 Checkout row를 `PESSIMISTIC_WRITE` 잠근다.
2. UNKNOWN인지 확인하고 Checkout-assignment fingerprint·기한·verification lease를 검증한다.
3. 기존 paymentId·예약 멱등 key·fingerprint·deadline을 지우지 않은 채 `PAYMENT_VERIFYING`으로 되돌린다.
4. immutable claim을 반환하고 commit한다.

별도 `PAYMENT_RECONCILING` 상태나 새 DB column을 만들지 않았다. reconciliation도 결제 상태를 조회 중이라는 의미에서 기존 `PAYMENT_VERIFYING` claim을 재사용하며, 프로세스가 조회 도중 중단되어도 기존 verification deadline 접근 시 다시 UNKNOWN으로 격리된다.

### provider 조회

provider 조회는 claim commit 뒤 transaction 밖에서 수행한다. 네트워크 지연 동안 Checkout·Seat DB lock이나 connection을 점유하지 않는다.

### 최종 transaction

- 거절은 Checkout → canonical Seat → assignment 순서로 잠그는 기존 `releaseKnownFailure`를 재사용한다.
- 승인은 기존 `reserveWithCheckout`을 재사용한다. Booking·Payment·Reservation·Seat·회차 잔여 수량이 하나의 transaction에서 확정된다.
- 정확한 deadline부터 기존 최종 transaction이 예약 확정을 거부하고 UNKNOWN을 보존한다. 이미 다른 사용자가 같은 좌석을 점유했다면 그 상태를 덮어쓰지 않는다.

## 5. command 결과

| 결과 | 의미 |
| --- | --- |
| `RESERVATION_CONFIRMED` | 승인 정보가 일치하고 보호 중인 좌석으로 예약까지 확정 |
| `PAYMENT_REJECTED` | provider 거절을 반영하고 verification lease 정리 |
| `STILL_UNRESOLVED` | provider도 최종 상태를 확정하지 못함 |
| `MANUAL_REVIEW_REQUIRED` | null 응답 또는 승인 식별 정보 불일치 |
| `COMPENSATION_REQUIRED` | 승인으로 보이지만 deadline·좌석·최종 transaction 조건 때문에 예약 확정 불가 |
| `NO_ACTION_REQUIRED` | 이미 READY 또는 EXPIRED로 종료된 Checkout의 반복 호출 |

이미 확정된 Checkout을 다시 호출하면 기존 예약 생성 시각을 반환하고 provider를 다시 조회하지 않는다. 거절로 정리된 Checkout도 반복 호출에서 `NO_ACTION_REQUIRED`를 반환해 외부 조회를 반복하지 않는다.

`COMPENSATION_REQUIRED`는 환불 완료를 뜻하지 않는다. 실제 취소·환불 adapter가 없으므로 금전 보상이 필요한 후보를 호출자에게 명시적으로 전달했다는 뜻뿐이다.

## 6. 명시적 fixture와 검증 결과

| 항목 | 값 |
| --- | --- |
| Runtime | Java 21, Spring Boot 3.2.5 |
| DB | MariaDB 10.11.8 Testcontainers |
| 시간 | 변경 가능한 고정 Clock, 2030-01-01 12:00 기준 |
| 데이터 | 가상 공연 1·회차 1·A1/A2 2석 |
| 가격 | 서버 가상 단가 30,000원 |
| 외부 연동 | Mockito verification/reconciliation port, 실제 호출 없음 |
| 반복 | 동시 reconciliation 3회 |

검증한 결과:

- deadline 전 승인: Checkout `RESERVATION_CONFIRMED`, Booking·Payment·Reservation 각 1, reserved seat 1, 잔여 1, assignment lease 정리
- deadline 전 거절: Checkout `READY`, 원래 hold 기한 복원, business row 0, 잔여 2
- 미확정 및 조회 예외: Checkout UNKNOWN과 paymentId·deadline·assignment lease 보존
- 승인 정보 금액 불일치: `MANUAL_REVIEW_REQUIRED`, 예약·결제 row 0
- deadline 이후 승인과 다른 사용자 A1 Checkout 공존: `COMPENSATION_REQUIRED`, 원래 UNKNOWN 유지, 대체 READY Checkout·점유 보존, 예약·결제 row 0
- deadline 이후 거절과 다른 사용자 A1 Checkout 공존: 원래 Checkout `EXPIRED` 및 lease 정리, 대체 READY Checkout·점유 보존
- 동시 단건 command: 3회 모두 provider 조회 1회, 예약 확정 1회, 다른 요청은 처리 중 conflict, deadlock 관찰 0
- 대상 Checkout 도메인 7 tests와 Checkout 통합 33 test invocations가 failures·errors·skipped 0으로 통과
- 전체 Backend 204 tests가 failures·errors·skipped 0으로 통과

위 결과는 2석 로컬 기능 fixture의 정합성 검증이다. TPS, 운영 PG 안정성, 실제 환불 성공 또는 운영 규모 성능을 의미하지 않는다.

## 7. “지금 구현하면 과한가?” 판단

단건 application service 경계는 과하지 않다. Issue #94에서 재현한 비수렴 문제에 직접 대응하면서 새 테이블이나 비동기 인프라 없이 현재 Checkout claim과 예약 transaction을 재사용한다.

반면 다음 항목은 아직 보류한다.

- 공개 운영자 HTTP API: 현재 역할 기반 관리자 권한 계약이 충분하지 않다.
- PaymentAttempt 원장: 실제 provider 응답 식별자·원문·재시도 정책이 정해지지 않았다.
- scheduler·webhook: 조회 주기, backoff, webhook 중복·순서 역전 계약이 없다.
- 실제 보상·환불: 취소 API와 환불 멱등 key가 없다.
- outbox·메시지 브로커: 독립 재시도 처리량과 이벤트 전달 유실이 측정되지 않았다.

실제 PG를 연결할 때는 조회 adapter와 취소/환불 계약, 관리자 권한, 감사 원장을 먼저 설계해야 한다. 이후 UNKNOWN 적체량과 단건 처리 시간을 측정해 자동 batch 또는 메시지 기반 처리가 필요한지 결정한다.

## 8. 관련 코드

- `PaymentUnknownReconciliationService`
- `PaymentReconciliationPort`
- `PaymentReconciliationSnapshot`
- `CheckoutPaymentVerificationTransactionService`
- `VerifiedReservationTransactionService`
- `Checkout`
- `CheckoutVerifiedReservationIntegrationTest`
