# 부분 중첩 좌석 Checkout 승인 충돌 기준선

## 1. 조사 목적

Issue #70은 같은 사용자·좌석 payload·만료 시각이 모두 같은 활성 hold를 하나의 Checkout으로 수렴시켰다. 그러나 `A1`과 `A1+A2`처럼 일부 좌석만 겹치는 payload는 fingerprint가 다르므로 서로 다른 주문으로 취급된다.

실제 PG adapter를 연결하기 전에 두 주문이 모두 승인됐다고 가정할 때 예약 transaction과 서버 DB가 어떤 상태로 남는지 확인해야 한다. 이번 Issue는 동작을 바꾸지 않고 이 공백을 MariaDB fixture로 재현한다.

## 2. 환경과 표현 범위

- Java 21, Spring Boot 3.2.5
- MariaDB 10.11.8 Testcontainers
- 고정 `Clock`, 가상 좌석 `A1`, `A2`, 좌석당 가상 가격 30,000원
- 사용자 한 명이 `A1`, `A2`를 같은 만료 시각까지 `HELD`
- `PaymentVerificationPort` mock만 사용
- 실제 PortOne·KOPIS·SMS·운영 데이터 호출 없음

문서의 “승인”은 mock이 승인 응답을 반환했다는 뜻이다. 실제 금전 승인·취소·환불 결과가 아니다.

## 3. Checkout 생성 결과

같은 사용자가 한 번의 hold에서 두 payload로 결제를 준비했다.

```text
C1 = A1       / 30,000원 / merchantUid-1 / READY
C2 = A1 + A2  / 60,000원 / merchantUid-2 / READY
```

`requestFingerprint`가 서로 다르므로 `(username, request_fingerprint, expires_at)` unique 제약은 충돌하지 않는다. 결과적으로 Checkout 2개와 요청 키 귀속 2개가 생성된다.

## 4. 순차 승인 결과

### C1(A1)을 먼저 확정

1. C1 mock 승인 검증과 예약 확정 성공
2. A1이 `RESERVED`, 잔여 좌석 1
3. C2 mock 승인 검증도 성공
4. C2 예약 transaction이 A1에서 `SeatReservationConflictException`
5. C2 transaction의 Booking·Payment는 rollback

최종 상태는 C1 `RESERVATION_CONFIRMED`·Booking 연결, C2 `READY`·Booking 없음, Payment 1, Booking 1, Reservation 1이다. Reservation 좌석은 정확히 A1이며 상태는 `PAYMENT_COMPLETED`이고 승자 Booking과 연결된다. A1은 `RESERVED`가 되면서 hold가 제거되고, A2는 미예약 상태로 기존 사용자와 만료 시각의 hold를 유지한다. Payment도 승자 Booking과 연결된다.

### C2(A1+A2)를 먼저 확정

1. C2 mock 승인 검증과 두 좌석 예약 확정 성공
2. A1·A2가 `RESERVED`, 잔여 좌석 0
3. C1 mock 승인 검증도 성공
4. C1 예약 transaction이 A1에서 `SeatReservationConflictException`
5. C1 transaction의 Booking·Payment는 rollback

최종 상태는 C2 `RESERVATION_CONFIRMED`·Booking 연결, C1 `READY`·Booking 없음, Payment 1, Booking 1, Reservation 2이다. Reservation 좌석 집합은 정확히 A1·A2이며 모두 `PAYMENT_COMPLETED`이고 승자 Booking과 연결된다. 두 Seat 모두 `RESERVED`이고 hold 소유자·만료 시각은 제거된다. Payment도 승자 Booking과 연결된다.

두 순서 모두 mock 승인 호출은 2회지만 서버에 남는 Payment는 승자 1건뿐이다. 실제 PG가 같은 순서로 승인 응답을 반환한다면 패자 승인에 대한 취소·환불 대상을 서버 DB에서 직접 찾을 수 없는 공백이 생길 수 있다는 추론 근거다.

## 5. 동시 승인 결과

두 Checkout을 서로 다른 예약 멱등 키와 결제 ID로 동시에 확정하는 시나리오를 3회 반복했다.

| 항목 | 결과 |
| --- | --- |
| 성공 | 매회 1 |
| `SeatReservationConflictException` | 매회 1 |
| 예상 밖 예외 | 0 |
| deadlock | 0 |
| Checkout | `RESERVATION_CONFIRMED` 1, `READY` 1 |
| Payment·Booking | 각 1 |
| Reservation | C1 승리 시 1, C2 승리 시 2 |
| Reservation 좌석·상태 | 승자 payload와 정확히 일치, 모두 `PAYMENT_COMPLETED`·승자 Booking 연결 |
| Checkout 연결 | 승자만 Booking 연결, 패자는 Booking 없는 `READY` |
| Seat별 상태 | C1 승리 시 A1 `RESERVED`·A2 기존 hold 유지, C2 승리 시 A1·A2 `RESERVED`·hold 제거 |
| Payment 연결 | 승자 Booking과 일치 |
| 재고 방정식 | `remaining + reserved = 2` 유지 |

좌석의 canonical 잠금 순서가 A1부터 시작하므로 DB 정합성은 지켜진다. 그러나 DB 정합성이 유지된다는 사실과 외부 승인에 대한 보상 근거가 충분하다는 것은 별개다.

대상 시나리오를 포함한 Backend 전체 157개 테스트가 실패·오류·skip 없이 통과했다. `docker compose config`와 `git diff --check`도 통과했다.

## 6. 확인된 문제와 아닌 것

확인된 문제는 다음과 같다.

- 정확히 같은 payload만 단일화되며 부분 중첩 payload는 Checkout 2개를 허용한다.
- 두 mock 승인 검증이 모두 성공한 뒤 예약은 하나만 확정된다.
- 실패 transaction은 Payment·Booking을 rollback하고 Checkout을 `READY`로 남긴다.
- 실패한 승인 결과를 별도 상태로 보존하거나 취소 대상으로 기록하는 모델이 없다.

이번 결과로 주장하지 않는 것은 다음과 같다.

- 실제 PG에서 두 결제가 실제 승인됐다는 주장
- 운영 트래픽의 발생률이나 금전 손실 규모
- 실제 예매처의 성능·안정성
- Redis·대기열·메시지 브로커가 필요하다는 결론

## 7. 후속 불변식 제안

실제 PG adapter 전 최소 불변식은 다음과 같다.

```text
한 활성 hold의 좌석은 동시에 둘 이상의 결제 가능한 READY Checkout에 속할 수 없다.
```

현재 Checkout은 좌석 목록을 SHA-256 fingerprint로만 보관하므로 fingerprint끼리 부분 중첩을 판별할 수 없다. 후속 구현에서는 요청 좌석과 Checkout의 귀속을 조회할 수 있는 모델이 필요하다.

다음 Issue에서 비교할 최소 선택지는 두 가지다.

1. 좌석 hold에 활성 Checkout 식별자를 귀속하고 만료·해제 시 함께 정리
2. Checkout-좌석 관계를 별도 저장하고 활성 상태의 중복 귀속을 transaction·DB 제약으로 차단

현재 UI는 Checkout 준비 뒤 좌석 구성을 변경하지 않으므로, 기존 활성 Checkout과 한 좌석이라도 겹치는 다른 payload를 HTTP 409로 거부하는 정책이 가장 단순하다. 단, Entity 구조와 만료 정리 경계를 먼저 비교한 뒤 결정한다.

## 8. 제외 범위

- 부분 중첩 Checkout 차단 구현과 신규 schema
- 실제 PortOne 승인·취소·환불
- Frontend 응답 처리
- 운영 migration
- Redis·분산 lock·대기열·outbox·메시지 브로커
- 부하·성능 수치 측정
