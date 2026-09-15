# 결제 검증 중 Checkout 만료 경합 기준선

## 1. 조사 목적

Checkout 예약 확정은 서버가 소유한 `READY` 주문을 먼저 확인하고, transaction 밖의 `PaymentVerificationPort`를 호출한 뒤, 새 transaction에서 Checkout을 다시 잠가 예약을 확정한다. 외부 I/O 동안 DB transaction을 열지 않는 경계 자체는 적절하지만, 검증을 시작한 Checkout이 응답 전에 만료되는 경우의 처리 정책은 없다.

명시적 Checkout 취소를 추가하면 같은 시간 경합이 하나 더 생긴다. 취소 API를 먼저 구현하지 않고 현재 존재하는 검증·만료 경합의 결과를 결정적 fixture로 확인한다.

## 2. 현재 실행 경계

```text
CheckoutVerifiedReservationService
  1. Checkout 단순 조회와 READY·소유자·payload 확인
  2. PaymentVerificationPort.verify() 호출       ← DB transaction 밖 외부 I/O
  3. 승인 응답 필드 검증
  4. VerifiedReservationTransactionService
       ├─ Checkout PESSIMISTIC_WRITE
       ├─ 만료·상태 재검사
       └─ Payment·Booking·Reservation·Seat 확정
```

1번 조회는 Checkout을 결제 검증 중 상태로 claim하지 않는다. 따라서 2번이 대기하는 동안 다른 요청이 같은 Checkout을 `EXPIRED`로 확정할 수 있다.

## 3. 재현 환경과 한계

- Java 21, Spring Boot 3.2.5
- MariaDB 10.11.8 Testcontainers
- 고정 `Clock`: 2030-01-01 12:00
- 가상 공연 1개·회차 1개·좌석 A1/A2, 5분 hold
- 좌석당 서버 가상 가격 30,000원
- `PaymentVerificationPort` Mockito mock과 두 latch
- 실제 PortOne·KOPIS·SMS·운영 데이터 호출 없음

문서의 “승인 응답”은 mock 객체가 `approved=true`를 반환했다는 뜻이다. 실제 결제 승인, 금전 이동, 취소 또는 환불 결과가 아니다.

## 4. 결정적 경합 순서

각 반복에서 다음 순서를 latch와 고정 Clock으로 강제했다.

1. A1을 hold하고 `READY` Checkout을 생성한다.
2. 예약 thread가 사전 검증을 통과하고 `PaymentVerificationPort.verify()`에 진입한다.
3. mock은 검증 진입 latch를 열고 승인 응답 반환을 대기한다.
4. 주 thread가 Clock을 Checkout의 정확한 만료 시각으로 이동한다.
5. 같은 멱등 키의 Checkout 재시도로 실제 만료 경로를 실행해 `EXPIRED`를 DB에 반영한다.
6. mock 대기를 해제해 `approved=true` 응답을 반환한다.
7. 예약 transaction이 Checkout row를 잠그고 `EXPIRED`를 확인해 `CheckoutExpiredException`으로 종료된다.

승인 응답의 `approvedAt`은 기준 시각 +1분이고 Checkout 만료는 +5분으로 구성했다. 이는 “유효 시간 안에 승인됐다고 적힌 응답이 서버에는 만료 뒤 도착한 상황”을 표현하는 fixture일 뿐, 특정 PG의 실제 동작을 재현한다는 뜻은 아니다.

## 5. 관찰 결과

동일 시나리오를 3회 반복했다.

| 항목 | 매회 결과 |
| --- | --- |
| mock 검증 호출 | 1회 |
| mock 응답 | `approved=true` |
| 서버 최종 예외 | `CheckoutExpiredException` |
| Checkout | `EXPIRED`, Booking 연결 없음 |
| Payment | 0 |
| Booking | 0 |
| Reservation | 0 |
| reserved Seat | 0 |
| 회차 잔여 좌석 | 2, 변화 없음 |
| CheckoutSeatAssignment | 이력 1, 만료 경계에서는 비활성 |
| A1 활성 hold | 0 |
| A1 논리 상태 | `AVAILABLE` |
| 예상 밖 예외·timeout | 0 |

좌석 A1의 `held_by`·`held_until` 원시 컬럼은 lazy 만료 정책 때문에 남아 있지만 `held_until == now`이므로 활성 hold 수는 0이고 도메인 상태는 `AVAILABLE`이다. 이는 Issue #63에서 정한 기존 만료 정책과 일치하며 이번 문제의 원인은 아니다.

대상 fixture 3개 invocation과 Backend 전체 165 tests가 failures 0, errors 0, skipped 0으로 통과했다.

## 6. 확인된 사실과 추론

확인된 사실:

- 결제 검증 시작과 예약 transaction 사이에 Checkout 상태를 소유하는 중간 상태가 없다.
- mock 승인 응답이 반환되어도 최종 만료 검사에서 거부된다.
- 거부된 응답을 나타내는 Payment 또는 별도 시도 row가 서버 DB에 남지 않는다.
- 좌석·예약·잔여 수량의 로컬 DB 정합성은 유지된다.

근거에서 가능한 추론:

- 실제 PG adapter가 승인 응답을 반환하는 구조라면, 같은 경합에서 외부 승인 여부를 서버 DB만으로 추적하거나 보상하기 어려울 수 있다.

이번 fixture로 확인하지 않은 것:

- 실제 PG가 만료된 주문을 승인한다는 사실
- 실제 금전 손실·발생률·운영 장애 규모
- 운영 트래픽에서의 timeout 또는 처리 성능

## 7. 후속 불변식과 설계 후보

후속 개선은 최소한 다음 불변식을 만족해야 한다.

```text
유효한 Checkout의 결제 검증을 서버가 수락한 뒤에는,
그 시도를 만료·취소와 구분해 최종 성공·실패·보상 대상으로 추적할 수 있어야 한다.
```

검토할 최소 구성은 다음과 같다.

1. 짧은 transaction에서 Checkout을 `READY → PAYMENT_VERIFYING`으로 claim
2. transaction 밖에서 결제 검증 실행
3. 승인·거절·호출 실패에 따른 terminal 또는 복구 상태 전이
4. `PAYMENT_VERIFYING` 중 만료·사용자 취소의 허용 여부 명시
5. 승인 뒤 예약 실패 시 Payment 시도와 보상 필요 상태 보존
6. 프로세스 중단으로 남은 검증 중 상태의 timeout·재조회 조건

단순 enum 추가만으로는 crash recovery와 승인 후 예약 실패를 설명하지 못한다. 다음 Issue에서는 상태와 Payment 시도 이력 중 어느 모델이 최소한으로 필요한지 먼저 설계한다. Redis, 대기열, outbox, 메시지 브로커는 이 단일 DB 상태 전이 문제의 선행 해결책으로 도입하지 않는다.

## 8. 제외 범위

- 운영 코드 변경
- Checkout 취소 API와 Frontend 연동
- 실제 PG 승인·조회·취소·환불 adapter
- scheduler와 자동 만료 정리
- 운영 DB migration
- TPS·p95·lock wait 성능 측정
- Redis·분산 lock·대기열·outbox·메시지 브로커
