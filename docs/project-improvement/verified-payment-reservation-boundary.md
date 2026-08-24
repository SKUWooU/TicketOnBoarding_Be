# 서버 가상 가격과 mock 결제 검증 경계

## 문제

기존 Backend 예약 DTO에는 결제 식별자와 승인 금액이 없다. Frontend가 PortOne browser callback의 `success`를 확인한 뒤 예약 API를 호출하면 Backend는 별도 검증 없이 좌석을 점유하고 `결제완료`를 저장했다.

KOPIS의 가격은 `ConcertDetail.price` 자유 문자열이므로 단일 금액으로 안전하게 계산할 수 없다. Frontend 예약 화면은 좌석당 30,000원, 이니시스 요청은 100원으로 각각 고정되어 있어 client 금액을 서버 기준으로 사용할 수도 없다.

## 추가한 Backend 계약

`POST /main/detail/{concertId}/verified-reservation`은 다음 값을 요구한다.

- 로그인 사용자
- 필수 `Idempotency-Key`
- 공연 회차와 좌석 목록
- `paymentId`

기존 `/reservation` 경로는 Frontend 호환을 위해 유지하지만 결제 미검증 legacy 경로다. Backend 우선 작업 후 별도 Frontend Issue에서 신규 계약으로 전환하고, 호환 기간이 끝나면 legacy 경로를 제거해야 한다.

## 서버 금액 계산

`VirtualTicketPricePolicy`는 `onticket.ticket.virtual-seat-unit-price`를 서버 기준 단가로 사용한다. local 기본값은 30,000원이며 기대 금액은 정규화된 좌석 수와 단가의 곱으로 계산한다. client가 보낸 금액은 입력으로 받지 않는다.

## 검증과 transaction 경계

```text
request
  → 멱등 키·payload fingerprint 확인
  → 서버 기대 금액 계산
  → PaymentVerificationPort.verify(paymentId)
  → 식별자·사용자·승인 상태·금액 검증
  → DB transaction 시작
      → Booking unique 멱등 소유권
      → Payment unique 식별자 소유권
      → 좌석 lock·점유
      → Reservation 생성·재고 감소
      → Payment APPROVED → RESERVATION_CONFIRMED
  → commit
```

결제 검증 포트는 DB transaction과 좌석 lock 전에 호출한다. 향후 실제 PG 어댑터로 교체해도 외부 네트워크 대기 동안 DB lock을 붙잡지 않는 경계다.

DB transaction 내에서는 Booking 멱등 소유권을 저장한 뒤 `provider_payment_id` unique 제약을 flush해 동일 결제를 다른 멱등 키로 동시 소비하지 못하게 한다. 좌석·재고 처리가 실패하면 Payment·Booking·Reservation·Seat·ConcertTime 변경이 함께 rollback된다. mock 승인 근거는 외부 상태로 남으므로 같은 결제 ID로 안전하게 예약을 재시도할 수 있다.

## 검증 결과

- 도메인·가격 정책 5개 invocation 통과
- 신규 Controller 계약·adapter 미구성 HTTP 503을 포함한 6개 invocation 통과
- MariaDB 10.11.8·가상 좌석 24개·mock 결제 통합 19개 invocation 통과
- 기존 좌석·멱등성·취소 경합과 application context를 포함한 전체 Backend 92개 invocation 통과
- 2석 정상 승인: 서버 기대 금액 60,000원, Payment·Booking 1건, Reservation·점유 2건, 잔여 22
- 미승인·승인 금액 100원·다른 사용자는 DB 변경 없이 거부
- 동일 결제 ID 동시 재사용 3회: 매회 성공 1건, Payment·Booking·Reservation·점유 각 1건, 잔여 23
- 존재하지 않는 좌석으로 예약 실패: Payment·Booking·Reservation 0건, 점유 0, 잔여 24
- 잔여 1에서 2석 처리 후 감소 query 실패: Payment·Booking·Reservation 0건, 점유 0, 잔여 1로 late-failure 전체 rollback
- 동일 멱등 키를 둘 다 없음으로 읽게 한 barrier: 동일 payload 3회는 동일 시각 재사용, 다른 payload 3회는 성공 1·충돌 1로 수렴

## 한계와 후속 순서

- `PaymentVerificationPort`는 mock fixture로만 검증했다. 실제 어댑터가 없는 환경의 신규 경로는 HTTP 503으로 거부하며 외부 결제를 실행하지 않는다.
- 운영 schema migration은 ADR 0001의 선행 조건이 없으므로 보류했다. local·test의 disposable schema 검증 결과이다.
- 현재 Payment 상태는 승인 증빙의 1회 소비와 예약 확정만 다룬다. 좌석 hold·만료, 실제 승인 취소·환불, outbox는 포함하지 않았다.
- Backend 우선 작업이 정리된 뒤 Frontend가 `paymentId`·`Idempotency-Key`를 신규 경로로 전달하게 하고 legacy 경로를 제거한다.
- 가상 공연장 fixture를 24석 정합성용과 약 2,000석 부하용으로 분리한 뒤, k6로 동일 좌석·인기 구역·분산 좌석 기준선을 측정한다.

## 관련 Issue

- [Backend Issue #43](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/43)
