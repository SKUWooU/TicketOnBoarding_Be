# READY Checkout 취소와 좌석 점유 해제 멱등 경계

## 1. 문제

좌석 hold에 `READY` Checkout이 연결되면 일반 `DELETE /seat-holds`는 결제 준비 중인 좌석이라는 409를 반환한다. 그러나 기존 Backend에는 Checkout 자체를 취소하는 상태와 명령이 없어, 사용자가 결제 전에 이탈해도 hold 만료 전까지 좌석을 명시적으로 반환할 수 없었다.

Issue #100에서는 hold 해제와 결제 검증의 경합에서 기존 경로가 원자성을 지키는 것을 확인했다. 이번 작업은 그 근거 위에서 취소 범위를 `READY`에만 제한하고, 결제 검증과 경쟁할 때 하나의 상태 전이만 획득하도록 구성한다.

## 2. API 계약

```http
DELETE /main/detail/{concertId}/checkouts/{merchantUid}
Cookie: accessToken=<JWT>
```

성공 응답은 `200 OK`와 기존 `CheckoutResponse`다.

```json
{
  "merchantUid": "ticket_...",
  "amount": 60000,
  "expiresAt": "2030-01-01T12:05:00",
  "status": "CANCELED"
}
```

`204 No Content`가 아니라 최종 상태를 반환하는 이유는 응답 유실 뒤 같은 `merchantUid`로 재요청해도 `CANCELED` 결과를 확인할 수 있게 하기 위해서다. 별도의 멱등 키를 추가하지 않고 취소 대상 resource 식별자인 `merchantUid`를 멱등 경계로 사용한다.

인증되지 않은 요청은 401, 다른 사용자·다른 공연·취소 불가 상태는 409, 정확한 만료 시각 이후는 410을 반환한다. 존재하지 않는 Checkout은 기존 결제 요청 검증 계약과 동일하게 400으로 처리한다.

## 3. 상태 전이

| 현재 상태 | 취소 결과 | 좌석 처리 |
| --- | --- | --- |
| `READY`이며 만료 전 | `CANCELED`, 200 | 배정 비활성화·본인 hold 해제 |
| `CANCELED` | 기존 `CANCELED`, 200 | 추가 변경 없음 |
| `EXPIRED` 또는 정확한 만료 경계 | `EXPIRED`, 410 | 논리적으로 만료, raw hold lazy 정리 유지 |
| `PAYMENT_VERIFYING` | 409 | 검증 lease와 hold 보존 |
| `PAYMENT_VERIFICATION_UNKNOWN` | 409 | reconciliation 근거와 lease 보존 |
| `RESERVATION_CONFIRMED` | 409 | 예약·결제·reserved Seat 보존 |

검증 중·UNKNOWN Checkout을 곧바로 취소하면 provider 승인 여부를 모른 채 좌석만 반환할 수 있다. 확정 Checkout은 결제 전 주문 취소가 아니라 기존 예약 취소·환불 경로의 책임이다.

## 4. transaction과 잠금 순서

```text
Checkout PESSIMISTIC_WRITE
  → assignment snapshot에서 좌석 번호 추출
  → 좌석 번호 canonical 정렬(A1 → A2)
  → Seat PESSIMISTIC_WRITE
  → CheckoutSeatAssignment PESSIMISTIC_WRITE
  → Checkout READY → CANCELED
  → assignment.releasedAt 기록
  → Seat hold 제거
  → commit
```

좌석 DB ID가 좌석 번호 순서와 항상 같다는 보장은 없다. 따라서 취소도 결제 검증과 동일한 좌석 번호 canonicalizer를 사용한다. 입력 payload가 없는 DELETE에서는 Checkout 배정 snapshot으로 좌석 번호를 복원하고, Seat과 assignment를 모두 잠근 뒤 snapshot의 좌석 집합과 다시 대조한다.

모든 변경은 한 transaction에 있다. rollback 검증은 test-only로 두 번째 assignment에 비정상 verification lease를 미리 둬 `assignment.release()`에서 예외를 발생시킨다. 첫 번째 assignment를 이미 해제한 뒤 예외가 나더라도 Checkout 상태와 두 assignment 해제 시각이 모두 원복되고 두 Seat hold가 유지됐다. 이는 정상 상태 전이 시나리오가 아니라 중간 RuntimeException에 대한 transaction 원자성 fixture다.

## 5. 이력 보존과 활성 배정

Checkout에는 `canceledAt`, CheckoutSeatAssignment에는 `releasedAt`을 추가했다. 취소 시 assignment row를 삭제하지 않으므로 어떤 Checkout에 어떤 좌석이 연결됐었는지 남는다.

활성 배정 조회는 다음 두 조건을 모두 요구한다.

```text
releasedAt IS NULL
AND COALESCE(verificationLeaseUntil, activeUntil) > now
```

따라서 취소된 배정은 다음 Checkout을 막지 않는다. 취소 뒤 Clock을 1초 진행하고 같은 좌석을 다시 hold했을 때 새 `READY` Checkout이 생성되고, 이전 Checkout과 배정은 취소 이력으로 유지됨을 검증했다.

## 6. 동시성 결과

### 6.1 동일 Checkout 동시 취소 — 3회

두 요청은 Checkout row lock에서 직렬화된다. 매회 두 요청 모두 같은 `merchantUid`와 `CANCELED`를 반환했고, `canceledAt`과 두 assignment의 `releasedAt`은 최초 취소 시각 하나로 유지됐다. 활성 hold는 0이었다.

### 6.2 취소 잠금 선행 — 3회

Repository proxy barrier가 첫 취소 요청의 Checkout 잠금 쿼리 반환 뒤 transaction을 대기시킨다. 두 번째 결제 검증 요청이 같은 Checkout 잠금을 시도한 것을 확인한 뒤 취소를 진행했다.

- 취소: `CANCELED` 성공
- 결제 검증: 409 `CheckoutConflictException`
- mock provider 호출: 0
- assignment 2개: `releasedAt` 기록, verification lease 없음
- Seat 2개: hold 제거, reserved 0
- Payment·Booking·Reservation: 0
- 회차 잔여 좌석: 2

### 6.3 결제 검증 claim 선행 — 3회

결제 검증이 `PAYMENT_VERIFYING`과 verification lease를 commit한 뒤 mock provider에서 대기하는 순서를 고정했다.

- 취소: 409 `CheckoutConflictException`
- 중간 Checkout: `PAYMENT_VERIFYING`, `canceledAt` 없음
- assignment 2개: `releasedAt` 없음, verification deadline 보존
- mock provider 호출: 1
- provider 대기 해제 후 예약·reserved Seat: 각각 2
- 최종 잔여 좌석: 0

두 방향 모두 먼저 Checkout row lock을 획득한 상태 전이만 진행한다. fixture에서 예상 밖 timeout·DB 예외는 관찰되지 않았지만, 이는 MariaDB 전역 deadlock counter나 운영 발생률 측정이 아니다.

## 7. 테스트 결과

| 범위 | 결과 |
| --- | --- |
| Checkout domain | 9 tests, 실패·오류·skip 0 |
| Checkout 생성·취소 통합 | 29 invocations, 실패·오류·skip 0 |
| Checkout 결제 검증 통합 | 49 invocations, 실패·오류·skip 0 |
| Reservation Controller | 28 tests, 실패·오류·skip 0 |
| Backend 전체 | 235 tests, 실패·오류·skip 0 |

테스트 환경은 Java 21, Spring Boot 3.2.5, MariaDB 10.11.8 Testcontainers, 고정 Clock, A1/A2 가상 좌석과 mock provider다. 실제 PG·KOPIS·SMS·운영 데이터 호출은 없다. 이 결과는 로컬 기능·경합 fixture이며 TPS·p95 또는 실제 예매처 성능 수치가 아니다.

## 8. 운영 적용 한계와 후속 조건

테스트와 local profile은 Hibernate `ddl-auto=create`를 사용한다. `canceled_at`, `released_at`, `CANCELED` 상태는 신규 schema에서 검증했지만 기존 운영 DB에 적용할 versioned migration은 아니다. 실제 배포 전에 ADR-0001의 전체 schema baseline과 데이터 호환성 확인이 필요하다.

취소 전용 metric은 이번 범위에서 추가하지 않았다. 현재는 운영 traffic·실패율 기준선이 없고 HTTP status와 persisted 상태로 결과를 검증할 수 있다. 운영 취소율이나 반복 충돌을 관측할 필요가 확인되면 low-cardinality `outcome` counter를 별도 Issue에서 검토한다.

다음 단계에서도 아래 범위는 분리한다.

- `PAYMENT_VERIFYING` 취소 예약과 provider 결과 확인
- `PAYMENT_VERIFICATION_UNKNOWN` reconciliation 후 취소·보상
- `RESERVATION_CONFIRMED` 예약 취소와 실제 환불
- Frontend 취소 버튼·상태 표현
- scheduler·webhook·outbox·메시지 브로커
