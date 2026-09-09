# 결제 검증 UNKNOWN 재조회·복구 부재 기준선

## 1. 조사 목적

`PAYMENT_VERIFICATION_UNKNOWN`은 결제 성공도 실패도 단정할 수 없을 때 금전 상태를 안전하게 격리하기 위해 추가된 Checkout 상태다. UNKNOWN 자체는 오류가 아니다. 문제는 외부 상태가 나중에 확정되었을 때 이를 다시 읽고 로컬 상태를 수렴시키는 경로가 있는가이다.

Issue #94는 복구를 구현하지 않는다. 실제 PG 조회·취소 계약이 없는 상태에서 임의 정책을 만들기 전에 다음 질문의 현재 답을 fixture로 고정한다.

- 첫 provider 조회의 응답을 잃은 뒤 같은 요청을 재시도하면 provider를 다시 조회하는가?
- provider에서 다음 조회 시 승인 또는 거절을 반환할 수 있어도 Checkout이 수렴하는가?
- verification deadline 뒤 좌석을 다른 사용자가 선택할 수 있을 때 원래 UNKNOWN은 어떻게 남는가?
- 자동·수동 reconciliation에 필요한 조회 진입점이 존재하는가?

## 2. 현재 코드 흐름

```text
READY
  └─ claim commit → PAYMENT_VERIFYING
       └─ transaction 밖 PaymentVerificationPort.verify(paymentId)
            ├─ 명확한 미승인/adapter 미구성 → READY 또는 EXPIRED 복구
            ├─ 정상 승인·검증 일치 → RESERVATION_CONFIRMED
            └─ 예상 밖 예외·승인 정보 불일치·최종 transaction 실패
                 → PAYMENT_VERIFICATION_UNKNOWN

PAYMENT_VERIFICATION_UNKNOWN
  └─ 같은 Checkout 재요청 → provider 호출 전 PaymentVerificationUnknownException(409)
```

확인된 구조:

- `PaymentVerificationPort`에는 `verify(paymentId)`만 있다. 실제 운영 adapter는 없고 일반 profile은 미구성 예외를 반환한다.
- `CheckoutPaymentVerificationTransactionService.claim`은 UNKNOWN을 확인하면 즉시 예외를 던진다.
- `CheckoutRepository`에는 merchantUid·사용자 key 조회만 있고 status별 UNKNOWN 목록 조회가 없다.
- UNKNOWN을 처리하는 controller, webhook, batch, scheduler, 운영자 command가 없다.
- UNKNOWN은 paymentId·예약 멱등 key·fingerprint·검증 시작·deadline을 보존한다.
- 좌석과 assignment의 verification lease는 deadline까지만 논리적으로 활성이다. deadline 이후 row는 남지만 다른 사용자가 좌석을 점유하고 새 Checkout을 만들 수 있다.

## 3. 명시적 fixture

| 항목 | 값 |
| --- | --- |
| Runtime | Java 21, Spring Boot 3.2.5 |
| DB | MariaDB 10.11.8 Testcontainers |
| 시간 | 변경 가능한 고정 Clock, 2030-01-01 12:00 기준 |
| 데이터 | 가상 공연 1·회차 1·A1/A2 2석 |
| 가격 | 서버 가상 단가 30,000원 |
| 결제 | Mockito `PaymentVerificationPort`, 외부 호출 없음 |
| Checkout TTL | 5분 hold, 이후 verification grace 30초 |

mock provider는 같은 paymentId에 대해 다음 순서로 동작하게 했다.

1. 첫 조회: 응답 유실을 표현하는 예상 밖 runtime exception
2. 두 번째 조회가 있다면: 정상 승인 또는 명확한 거절

두 번째 결과는 “실제 승인/거절이 발생했다”는 뜻이 아니다. 서버가 재조회했다면 관찰할 수 있도록 준비된 결정적 mock 상태다.

## 4. 기준선 결과

### 다음 조회가 승인인 경우

- 첫 예약 시도 1회에서 provider가 호출되고 runtime exception 뒤 Checkout은 UNKNOWN이 됐다.
- deadline 전 동일 요청 3회와 deadline 뒤 동일 요청 1회는 모두 `PaymentVerificationUnknownException`이었다.
- provider 총 호출은 1회였다. 준비된 두 번째 승인 결과는 읽히지 않았다.
- 원래 Checkout은 UNKNOWN, paymentId와 verification deadline을 그대로 보존했다.
- 정확한 deadline에 다른 사용자가 A1을 점유하고 별도 READY Checkout을 만들었다.
- 최종 Checkout 2개, Payment·Booking·Reservation 0개, reserved seat 0개, 회차 잔여 2였다.

### 다음 조회가 거절인 경우

- 첫 조회 뒤 UNKNOWN으로 전이하고 deadline 1분 뒤 같은 요청을 3회 재시도했다.
- provider 총 호출은 여전히 1회였으며 준비된 거절 결과는 읽히지 않았다.
- UNKNOWN과 verification 식별 정보, assignment verification lease row가 유지됐다.
- Seat의 `heldBy/heldUntil` 값도 남아 있지만 deadline 이후 `isHeldAt`은 false였다.
- Payment·Booking·Reservation은 모두 0개이고 회차 잔여는 2였다.

대상 `CheckoutVerifiedReservationIntegrationTest` 23 tests가 failures·errors·skipped 0으로 통과했다.

## 5. 문제의 정확한 의미

현재 구현은 불명확한 결과를 성공이나 실패로 잘못 단정하지 않는다. 또한 deadline으로 좌석을 무기한 잠그지 않는다. 이 두 안전장치는 유지되어야 한다.

남은 문제는 UNKNOWN이 provider의 최종 상태와 수렴하지 않는다는 점이다.

- 실제 provider가 거절했다면 UNKNOWN과 검증 lease 정보가 불필요하게 계속 남을 수 있다.
- 실제 provider가 승인했다면 deadline 뒤 같은 좌석의 새 Checkout과 “예약으로 연결되지 않은 외부 승인 가능성”이 공존할 수 있다.
- 현재 fixture는 실제 금전 승인, 중복 결제 또는 초과 판매를 재현한 것이 아니다. 실제 PG가 없으므로 그런 결과를 주장할 수 없다.
- 다만 운영 PG를 연결하기 전에 승인 조회·취소/환불·감사 기록을 포함한 reconciliation이 필요하다는 적용 조건은 확인됐다.

## 6. 후속 구현 전에 필요한 계약

### provider 계약

- paymentId 조회가 멱등이고 반복 호출 가능한가?
- 승인·거절·취소·미존재·처리 중 상태를 어떻게 구분하는가?
- timeout, rate limit, 인증 실패를 최종 실패와 어떻게 구분하는가?
- 조회된 승인 건을 취소·환불할 API와 멱등 key가 있는가?
- webhook이 있다면 조회 결과와 충돌하거나 순서가 뒤바뀔 때 무엇을 신뢰하는가?

### 로컬 상태 전이

- UNKNOWN + 확정 거절: Checkout 종료와 lease 정리 시점
- UNKNOWN + 승인 + 좌석 보호 중: 기존 claim으로 예약 확정을 재개할 조건
- UNKNOWN + 승인 + deadline 경과/좌석 재사용: 새 예약을 덮어쓰지 않고 보상·환불 대상으로 전환할 조건
- provider 미확정: 제한된 backoff와 최대 자동 재시도, 이후 수동 확인 기준

### 동시성·감사

- 같은 UNKNOWN을 scheduler·webhook·운영자가 동시에 처리할 때 단일 claim 보장
- 매 조회 시각·요청 결과·provider 원문 식별자를 기록할 PaymentAttempt 성격의 원장
- 프로세스 중단 뒤 재개 가능한 상태와 실패 횟수
- 성공/거절/재시도/보상 결과의 저카디널리티 메트릭

## 7. “지금 구현하면 과한가?” 판단

전체 reconciliation 자동화는 지금 구현하면 과하다. 운영 PG adapter, 조회 상태 계약, 취소·환불 API가 없기 때문에 실제 정책을 검증할 기준이 없다. scheduler·outbox·브로커를 먼저 추가해도 잘못된 상태 전이를 더 복잡하게 반복할 뿐이다.

다음 단계로 적절한 최소 범위는 provider 중립적인 reconciliation port와 상태 전이 정책을 mock fixture로 먼저 설계하는 것이다. 수동 단건 command에서 동시 claim·승인/거절·deadline 경과 처리를 검증한 뒤, 처리량과 독립 재시도 요구가 확인될 때 batch·outbox·메시지 브로커를 판단한다.

## 8. 관련 코드

- `CheckoutVerifiedReservationService`
- `CheckoutPaymentVerificationTransactionService`
- `Checkout`
- `CheckoutSeatAssignment`
- `PaymentVerificationPort`
- `CheckoutVerifiedReservationIntegrationTest`
