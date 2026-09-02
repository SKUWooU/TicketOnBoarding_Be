# 활성 Checkout의 좌석 귀속과 부분 중첩 차단

## 1. 문제와 개선 목표

Issue #73의 MariaDB fixture에서는 같은 사용자가 동일한 hold로 `A1`과 `A1+A2` Checkout을 각각 만들 수 있었다. 두 mock 결제 승인이 모두 성공한 뒤 예약 transaction에서 한 요청만 확정되고, 패자는 Payment·Booking rollback과 함께 Checkout `READY`로 남았다.

원인은 Checkout이 좌석 목록을 SHA-256 fingerprint로만 보관해 서로 다른 fingerprint 사이의 좌석 교집합을 판별할 수 없다는 점이었다. 이번 개선의 불변식은 다음과 같다.

```text
활성 hold의 한 좌석은 동시에 둘 이상의 결제 가능한 Checkout에 귀속될 수 없다.
```

## 2. 적용 범위와 표현 한계

- Java 21, Spring Boot 3.2.5, Spring Data JPA
- MariaDB 10.11.8 Testcontainers
- 고정 `Clock`, 가상 좌석 `A1`, `A2`, 5분 hold
- `PaymentVerificationPort` mock
- 실제 PortOne·KOPIS·SMS·운영 데이터 호출 없음

결과는 로컬 가상 좌석의 기능·동시성 fixture다. 실제 예매처의 트래픽, 결제 승인 결과 또는 운영 성능을 뜻하지 않는다.

## 3. 모델 선택

`Seat`에 현재 Checkout FK를 직접 추가하면 좌석 가용 상태와 주문 이력이 결합되고 만료·예약 확정 때 관계를 지워야 한다. 대신 `CheckoutSeatAssignment`를 별도 관계로 두어 Checkout 생성 당시의 좌석과 hold 만료 시각을 보존했다.

```text
Checkout 1 ── N CheckoutSeatAssignment N ── 1 Seat
                    ├─ requestFingerprint
                    └─ activeUntil (= Checkout.expiresAt)
```

관계 table에는 두 unique 제약을 둔다.

- `(checkout_id, seat_id)`: 한 Checkout 안의 같은 좌석 중복 방지
- `(seat_id, active_until)`: 같은 좌석·같은 Checkout 활성 구간의 복수 귀속에 대한 DB 최종 방어선

`active_until`은 개별 좌석의 만료 시각이 아니라 복수 좌석 중 가장 이른 `Checkout.expiresAt`으로 통일한다. Checkout이 결제 불가능해진 뒤 늦게 만료되는 좌석 귀속만 남아 release나 새 Checkout을 막지 않게 하기 위함이다. 이전 귀속 이력은 보존하면서 만료 후 새 활성 종료 시각의 Checkout을 만들 수 있다. 운영 중인 기존 DB에 이 schema를 반영하는 versioned migration은 전체 schema baseline과 함께 별도 Issue로 둔다.

## 4. Checkout 준비 transaction

Checkout 생성은 다음 순서로 처리한다.

1. 요청 좌석 번호를 canonical 정렬한다.
2. 정렬된 순서로 각 `Seat` row를 `PESSIMISTIC_WRITE` 잠근다.
3. 예약 여부와 인증 사용자 소유의 활성 hold인지 검사한다.
4. 동일 사용자·fingerprint·만료 시각의 Checkout이 있으면 기존 결과를 재사용한다.
5. 요청 좌석의 활성 `CheckoutSeatAssignment`를 잠금 조회한다.
6. 한 좌석이라도 다른 payload의 활성 Checkout에 속하면 `CheckoutConflictException`으로 409를 반환한다.
7. 충돌이 없으면 Checkout, 좌석 귀속, 최초 멱등 키를 한 transaction에서 저장한다.

좌석 row를 먼저 동일 순서로 잠그므로 `A1`과 `A1+A2` 동시 요청도 A1에서 직렬화된다. 애플리케이션 검사가 경쟁을 조정하고, unique 제약은 우회 insert나 예상하지 못한 race의 최종 방어선으로 동작한다.

정확히 같은 payload의 다른 멱등 키는 기존 정책을 유지해 같은 Checkout과 merchantUid를 재사용하고 새 키만 귀속한다.

## 5. hold 해제와 만료 경계

Checkout 준비 뒤 사용자가 좌석 hold를 명시적으로 해제하면 `AVAILABLE` 좌석과 결제 가능한 `READY` Checkout이 동시에 존재할 수 있다. 따라서 해제 transaction도 좌석을 canonical 순서로 잠근 뒤 활성 귀속을 확인한다.

- 활성 귀속 있음: HTTP 409, hold와 Checkout 유지
- hold 만료 시각 도달: 귀속은 비활성으로 취급, lazy 만료 회수 가능
- 만료 후 새 hold: 새 만료 시각으로 새 Checkout·귀속 생성 가능

이번 범위는 명시적 해제 충돌 정책까지만 다룬다. 사용자 취소 의도를 별도 Checkout 상태로 표현하는 API와 결제 승인 후 취소·환불은 후속 상태 전이 Issue다.

## 6. 개선 전후 결과

| 시나리오 | 개선 전 Issue #73 | 개선 후 Issue #76 |
| --- | --- | --- |
| 순차 `A1` → `A1+A2` | Checkout 2, mock 승인 2, 확정 1·좌석 충돌 1 | Checkout 1, 두 번째 준비 409 |
| 순차 `A1+A2` → `A1` | Checkout 2, mock 승인 2, 확정 1·좌석 충돌 1 | Checkout 1, 두 번째 준비 409 |
| 동시 부분 중첩 3회 | 매회 Checkout 2, 승인 뒤 성공 1·충돌 1 | 매회 성공 1·409 1, Checkout·요청 키 각 1 |
| 결제 검증 호출 | 두 Checkout 모두 mock 호출 | 충돌 요청은 호출 전 차단 |
| 명시적 hold 해제 | Checkout과 독립적으로 해제 가능 | 활성 Checkout 좌석은 409 |
| 만료 후 재점유 | 새 Checkout 가능 | 기존 동작 유지, 새 귀속 추가 가능 |

동시 fixture는 10초 timeout 안에서 3회 모두 완료돼 관찰된 deadlock은 0이었다. 이 수치는 짧은 기능 회귀 반복 결과이며 운영 deadlock 발생률을 의미하지 않는다.

## 7. 검증 내용

- 작은 payload 우선·큰 payload 우선의 양방향 부분 중첩 409
- 부분 중첩 동시 요청 3회: 성공 1·`CheckoutConflictException` 1
- 정확히 같은 payload의 순차·동시 다른 키가 Checkout 1개로 수렴
- 겹치지 않는 `A1`, `A2`는 독립 Checkout 허용
- 활성 Checkout 좌석의 명시적 release 409와 hold·상태 보존
- 정확한 만료 경계 뒤 재점유·새 Checkout 허용
- 서로 다른 좌석 hold 만료 시각에서 최단 Checkout 만료 뒤 남은 좌석 release·재Checkout 허용
- 관계 table 직접 중복 insert의 `DataIntegrityViolationException`
- 충돌 요청의 Payment 검증 미호출과 Payment·Booking·Reservation 0건
- Controller Checkout·release 충돌 HTTP 409
- Backend 전체 162 tests: failures 0, errors 0, skipped 0
- `docker compose config --quiet` 통과
- `git diff --check` 통과

## 8. 남은 한계와 후속 조건

- 운영 DB용 versioned migration과 기존 `READY` Checkout backfill 없음
- 실제 PG 승인·취소·환불 adapter 없음
- Checkout 사용자가 결제 준비를 취소하는 명시적 상태 전이 없음
- Frontend의 부분 중첩·release 409 안내 미연동
- multi-instance 부하에서 DB connection·lock wait·TPS·p95를 측정하지 않음
- Redis, 분산 lock, 대기열, outbox, 메시지 브로커 도입 근거가 아님

다음 단계는 Backend Checkout 취소·만료 상태 전이와 Frontend 연동 가운데 범위를 다시 나눈다. 실제 PG 도입 전에는 승인 결과를 먼저 기록하고 예약 실패 시 보상 대상으로 추적할 모델도 별도 설계해야 한다.
