# Checkout 점유 해제와 결제 검증 진입 경합 기준선

## 1. 조사 목적

좌석 `release`와 Checkout 결제 검증은 같은 좌석을 서로 다른 진입 순서로 잠근다. 이때 복수 좌석의 부분 해제, 교착, 결제 검증 중 점유 유실 또는 만료 경계의 늦은 승인 처리 문제가 생기는지 확인한다.

현재 공개된 `DELETE /seat-holds`는 좌석 hold 해제이며 Checkout 취소 API가 아니다. 따라서 이번 Issue는 존재하는 두 경로의 정합성만 검증하고, 새로운 Checkout 취소 정책은 구현하지 않는다.

## 2. 현재 잠금·transaction 경계

```text
좌석 점유 해제
  Seat(A1 → A2) PESSIMISTIC_WRITE
    → 만료 hold 정리
    → 활성 CheckoutSeatAssignment PESSIMISTIC_WRITE
    → 활성 Checkout이 있으면 409와 transaction rollback

결제 검증 claim
  Checkout PESSIMISTIC_WRITE
    → Seat(A1 → A2) PESSIMISTIC_WRITE
    → CheckoutSeatAssignment PESSIMISTIC_WRITE
    → PAYMENT_VERIFYING + verification deadline commit
  transaction 밖에서 PaymentVerificationPort 호출
```

두 경로 모두 입력 배열과 무관하게 좌석 번호의 canonical 순서로 Seat을 잠근다. 다만 release는 Seat부터, 검증은 Checkout부터 진입하므로 실제 경합 순서를 고정한 검증이 필요했다.

## 3. 재현 환경과 제어 방식

- Java 21, Spring Boot 3.2.5
- MariaDB 10.11.8 Testcontainers
- 고정·가변 `Clock`, 가상 공연 1개·회차 1개·좌석 A1/A2
- 좌석 입력은 두 경로 모두 `A2, A1` 역순
- Repository proxy와 `CountDownLatch`로 잠금 획득 뒤 진행 시점을 제어
- `PaymentVerificationPort` Mockito mock
- 실제 PG·KOPIS·SMS·운영 데이터 호출 없음

임의의 sleep으로 성공 순서를 추정하지 않았다. 각 barrier는 대상 Checkout의 최초 호출만 가로채며 5초 안에 기대 지점에 도달하지 못하면 테스트를 실패시킨다.

## 4. 검증 시나리오와 결과

### 4.1 결제 검증 claim 선행 — 3회

1. 결제 검증이 `PAYMENT_VERIFYING` claim과 좌석별 verification lease를 commit한다.
2. mock provider 응답은 latch에서 대기한다.
3. 같은 사용자가 A2/A1 점유 해제를 요청한다.
4. release는 활성 Checkout 배정을 확인해 `SeatHoldConflictException`으로 종료된다.
5. provider 대기를 해제하면 예약이 정상 확정된다.

매회 확인한 결과:

- release 결과: `409`에 대응하는 `SeatHoldConflictException`
- 중간 Checkout: `PAYMENT_VERIFYING`
- 배정 2개와 Seat 2개의 만료: verification deadline으로 연장
- provider 검증: 1회
- 최종 예약·reserved Seat: 각각 2
- 최종 잔여 좌석: 0

### 4.2 좌석·배정 잠금 선행 — 3회

1. release가 A1/A2 Seat과 활성 배정을 잠근 뒤 종료 직전에 대기한다.
2. 결제 검증은 Checkout을 잠근 뒤 Seat 잠금을 기다린다.
3. release를 진행시키면 활성 Checkout 충돌로 transaction 전체가 rollback된다.
4. rollback 직후 두 Seat의 소유자와 원래 hold 만료 시각이 모두 보존됨을 확인한다.
5. 결제 검증을 진행시키면 예약이 정상 확정된다.

매회 provider 검증은 1회였고 예약·reserved Seat는 각각 2, 잔여 좌석은 0으로 수렴했다. 테스트가 관찰한 예상 밖 DB 예외와 timeout은 0이었다. 이는 이 결정적 2좌석 fixture에서의 결과이며 MariaDB 전체 deadlock counter 또는 운영 발생률 측정이 아니다.

### 4.3 정확한 verification deadline의 점유 해제 — 1회

1. 결제 검증 claim을 commit하고 provider 응답을 대기시킨다.
2. Clock을 `checkout.expiresAt + 30초`, 즉 verification deadline과 정확히 같게 이동한다.
3. release는 만료된 hold를 정리하고 성공한다.
4. mock이 `approved=true`를 반환해도 최종 예약은 `PaymentVerificationUnknownException`으로 거부된다.

최종 snapshot은 다음과 같다.

- Checkout: `PAYMENT_VERIFICATION_UNKNOWN`
- verification payment ID·배정의 마지막 lease: 보존
- Seat hold: 소유자·만료 시각 모두 제거
- Payment·Booking·Reservation·reserved Seat: 모두 0
- 회차 잔여 좌석: 2

여기서 `approved=true`는 mock 응답이다. 실제 결제 승인이나 금전 이동을 뜻하지 않는다. 실제 provider에서 승인되었다면 Issue #97의 reconciliation 경계가 후속 상태 확인과 보상 필요 여부를 다뤄야 한다.

## 5. 검증 결과

| 범위 | 실행 결과 |
| --- | --- |
| `CheckoutVerifiedReservationIntegrationTest` | 41 invocations, failures·errors·skipped 0 |
| Backend 전체 | 212 tests, failures·errors·skipped 0 |
| 추가 경합 fixture | 7 invocations(3 + 3 + 1) |
| 외부 연동 | 실제 호출 0 |

이번 결과는 로컬 2좌석 기능 fixture다. TPS·p95·운영 처리량이나 실제 예매처 성능을 나타내지 않는다.

## 6. 결론과 Checkout 취소 도입 조건

검증 범위에서는 활성 Checkout이 존재할 때 명시적 hold 해제가 부분 적용되지 않았고, claim 선행·release 잠금 선행 모두 최종 좌석·예약 불변식을 지켰다. 정확한 deadline에서는 좌석이 재사용 가능해지고 늦은 mock 승인으로 예약을 확정하지 않았다. 따라서 이번 Issue에서 고쳐야 할 운영 코드 결함은 확인되지 않았다.

반면 Checkout 자체를 취소하는 명령과 상태는 없다. 지금 이 기준선 Issue에 곧바로 추가하는 것은 범위를 넘고 상태별 정책 근거가 부족해 과하다. 별도 Issue에서 최소한 다음을 먼저 결정해야 한다.

- `READY`: 소유자 확인 뒤 배정과 hold를 함께 해제할지
- `PAYMENT_VERIFYING`: 검증 중 즉시 취소를 막을지, 결과 확인 뒤 취소·보상할지
- `PAYMENT_VERIFICATION_UNKNOWN`: provider reconciliation 전 해제를 허용할지
- `CONFIRMED`: Checkout 취소가 아니라 예약 취소·환불 경로로 위임할지
- `EXPIRED`: 반복 취소 요청을 동일 결과로 처리할지

구현한다면 인증된 소유자, 멱등 결과, Checkout → canonical Seat → assignment 잠금 순서, transaction rollback, provider 보상 경계를 함께 검증해야 한다. Redis·대기열·outbox·메시지 브로커는 이 단일 DB 명령의 선행 조건이 아니다.

## 7. 제외 범위

- Checkout 취소 API·상태 구현
- 실제 PG 승인·조회·취소·환불
- Frontend 연동
- scheduler·webhook·outbox·메시지 브로커
- 운영 DB migration과 운영 성능 주장
