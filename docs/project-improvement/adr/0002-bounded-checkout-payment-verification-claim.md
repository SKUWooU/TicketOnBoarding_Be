# ADR-0002: Checkout에 bounded 결제 검증 claim을 둔다

## 상태

채택

## 맥락

Issue #79에서 transaction 밖의 mock 결제 검증 중 Checkout이 만료되고, 이후 `approved=true` 응답이 반환돼도 로컬 Payment 증빙 없이 거부되는 경합을 재현했다. 외부 I/O 동안 row lock을 유지하는 방식은 DB connection과 lock wait를 네트워크 지연에 결합하므로 사용할 수 없다.

현재는 실제 PG adapter·webhook·환불 API가 없고 `Payment`도 확정 Booking에 종속된다. 이 단계에서 범용 PaymentAttempt 원장, outbox나 메시지 브로커를 추가하면 검증되지 않은 운영 흐름을 먼저 설계하게 된다.

## 결정

- 단일 DB Checkout에 `PAYMENT_VERIFYING`과 `PAYMENT_VERIFICATION_UNKNOWN` 상태를 추가한다.
- 짧은 transaction에서 Checkout → canonical Seat → CheckoutSeatAssignment 순서로 잠그고 claim 정보를 저장한다.
- 원래 Checkout 만료 뒤 30초의 configurable verification grace를 둔다.
- Seat의 원래 hold 만료와 검증 lease를 분리해 알려진 실패 시 원상 복구한다.
- `PaymentVerificationPort`는 claim commit 뒤 transaction 밖에서 호출한다.
- 명확한 미승인·미구성 adapter만 READY/EXPIRED로 복구한다.
- 승인처럼 보이는 불일치, 예상 밖 결과와 최종 예약 실패는 UNKNOWN으로 보존하고 deadline까지 좌석 재선택을 막는다.
- deadline 뒤에는 bounded lease를 끝내 좌석 재선택을 허용한다. 늦게 돌아온 기존 승인은 예약을 확정하지 않고 UNKNOWN으로 남겨 향후 reconciliation·환불 대상으로 구분한다.

## 검토한 대안

### 외부 검증 동안 Checkout row lock 유지

동시 진입은 막을 수 있지만 네트워크 지연 동안 DB transaction, connection과 row lock을 점유한다. 고경합 예매 경로의 병목과 장애 전파 범위를 키우므로 기각한다.

### 상태 없이 원래 만료를 무시

어떤 요청이 검증을 시작했는지, 중복 검증인지, 언제까지 좌석을 보호할지 추적할 수 없다. crash와 불명 결과도 구분하지 못하므로 기각한다.

### 즉시 PaymentAttempt 원장·outbox·브로커 도입

실제 provider 요청·webhook·환불 계약이 없어 필요한 상태와 전달 보장을 검증할 근거가 부족하다. 현재 문제를 해결하는 범위를 넘어 보류한다.

### 불일치 응답을 즉시 실패 처리하고 좌석 반환

`approved=true` 응답의 식별자나 금액이 예상과 다르면 서버가 금전 상태를 단정할 수 없다. 즉시 좌석을 재판매할 가능성을 피하기 위해 UNKNOWN으로 격리하고 bounded deadline까지 좌석을 보호한다.

## 결과와 trade-off

같은 Checkout의 외부 검증 진입을 한 요청으로 제한하고, 원래 hold 만료가 검증 중 요청을 즉시 무효화하지 않는다. 외부 I/O와 DB transaction은 계속 분리된다.

반면 Checkout row가 검증 시도 정보까지 일부 소유하고, crash 뒤 상태 전이는 요청 접근에 의존한다. deadline 뒤 좌석은 다시 선택될 수 있으므로 늦은 실제 승인이 있다면 예약 없는 결제와 후속 판매가 함께 존재할 수 있다. 현재는 UNKNOWN reconciliation과 실제 환불을 제공하지 않으므로 운영 PG 적용 전 반드시 보완해야 하는 의도적인 한계다.

## 재검토 조건

- 실제 PG 조회·승인·취소·webhook 계약 확정
- 한 Checkout 또는 결제에 복수 시도 이력이 필요
- 프로세스 중단 뒤 자동 재조회와 운영자 reconciliation 필요
- 승인 후 예약 실패의 자동 보상과 재시도 전달 보장 필요
- 독립 consumer·replay가 필요하다는 유실 재현 근거 확보

## 관련 근거

- [Issue #79 기준선](../checkout-verification-expiry-race-baseline.md)
- [Issue #82 개선 근거](../checkout-payment-verification-claim.md)
- [ADR-0001 schema migration 소유권](0001-schema-migration-ownership.md)
