# 동일 활성 hold의 Checkout 단일화

## 1. 문제

Issue #67에서 Checkout 생성은 `(username, idempotency_key)` unique 제약으로 같은 키의 재시도를 멱등 처리했다. 그러나 사용자가 같은 좌석 선택을 유지한 채 다른 키를 보내면 요청마다 새로운 `merchantUid`와 `READY` Checkout이 생성됐다.

결제 제공자 입장에서는 서로 다른 `merchantUid`가 서로 다른 주문이다. 두 주문이 모두 승인되면 예약 transaction은 Checkout 하나만 소비할 수 있으므로, 나머지 승인은 예약으로 전환되지 못하고 취소·환불이 필요한 상태가 될 수 있다. 실제 PG adapter를 붙이기 전에 같은 결제 준비 대상을 서버에서 단일화해야 한다.

## 2. 개선 전 재현

환경은 Java 21, Spring Boot 3.2.5, MariaDB 10.11.8 Testcontainers, 고정 `Clock`, 가상 좌석 `A1`이다. 실제 PG·KOPIS·SMS 호출은 없다.

같은 사용자가 `A1`을 한 번 점유한 뒤 같은 공연·회차·좌석 payload에 서로 다른 키를 사용했다.

```text
checkout-hold-key-1 -> ticket_bfa7...
checkout-hold-key-2 -> ticket_bd5b...
```

순차 요청과 두 thread 동시 요청 모두 서로 다른 `merchantUid`를 반환했고 Checkout row가 2개 생성됐다. 좌석 hold는 재요청으로 연장되지 않았으므로 두 Checkout의 만료 시각도 같았다.

## 3. hold identity

이 프로젝트에는 여러 좌석을 하나로 묶는 별도 Hold aggregate가 없고, 각 `Seat` row가 소유자와 `heldUntil`을 보관한다. 이번 작업에서는 결제 화면에 전달된 정확히 같은 좌석 선택을 다음 세 값으로 식별한다.

```text
hold identity = username + requestFingerprint + expiresAt
```

- `username`: 인증된 점유 소유자
- `requestFingerprint`: 공연·회차·정렬된 좌석 목록
- `expiresAt`: 선택 좌석 중 가장 이른 `heldUntil`

같은 사용자의 같은 payload는 hold가 유지되는 동안 세 값이 동일하다. hold가 만료된 뒤 다시 점유하면 `expiresAt`이 바뀌므로 새 Checkout을 만들 수 있다.

## 4. 선택한 해결책

`reservation_checkout`에 다음 복합 unique 제약을 추가했다.

```text
uk_checkout_hold_identity
  (username, request_fingerprint, expires_at)
```

Checkout 생성 transaction은 기존 canonical 순서로 좌석을 잠그고 소유권·만료를 검증한 뒤, 계산된 hold identity의 Checkout이 있으면 새 row를 만들지 않고 기존 응답을 반환한다.

또한 `reservation_checkout_request_key`에 사용자가 보낸 모든 Checkout 멱등 키를 기록한다.

```text
uk_checkout_request_key_username_idempotency
  (username, idempotency_key)

Checkout 1 --- N CheckoutRequestKey
```

최초 키뿐 아니라 같은 hold를 재사용한 후속 키도 같은 Checkout에 귀속된다. 키 row에는 요청 fingerprint를 함께 보관한다. 새 Checkout과 최초 키는 한 transaction에서 생성하므로 키 unique 충돌 시 새 Checkout도 함께 rollback되어 고아 주문이 남지 않는다. 기존 Checkout의 후속 키는 좌석 검증 transaction이 끝난 뒤 별도 transaction에서 귀속한다.

이 방식은 Frontend 계약을 바꾸지 않는다. 다른 멱등 키를 사용했더라도 응답은 HTTP 200이며 같은 `merchantUid`, 금액, 만료 시각과 상태를 받는다.

## 5. 동시 요청과 transaction 경계

동시 요청에서는 두 transaction이 같은 좌석 row를 잠근다. 일반적으로 후발 요청은 선발 요청이 commit한 뒤 기존 Checkout을 재사용한다. 다만 MariaDB 기본 `REPEATABLE READ`의 일관 읽기 snapshot이 선발 commit보다 먼저 만들어지면 후발 transaction이 기존 row를 조회하지 못할 수 있다.

이 경우 DB unique 제약이 두 번째 insert를 `DataIntegrityViolationException`으로 차단한다. 실패 transaction이 rollback된 뒤 서비스 경계에서 hold identity를 다시 조회하고, 새 키를 승자의 Checkout에 별도 transaction으로 귀속한 다음 같은 응답을 반환한다. 기존 Checkout을 정상 조회한 경로도 좌석 transaction을 먼저 commit한 뒤 같은 키 귀속 transaction을 사용한다. 키 귀속이 동시에 경쟁하면 `(username, idempotency_key)` unique 제약의 승자를 다시 조회한다. 따라서 DB 제약 위반을 HTTP 500으로 노출하지 않고 Checkout과 각 요청 키를 하나의 결과로 수렴시킨다.

### Reviewer Blocking과 수정

첫 Reviewer Blocking에서 최초 구현은 다른 키로 기존 Checkout을 재사용하면서 그 키 자체는 저장하지 않았다. 예를 들어 `K1+A1` 뒤 `K2+A1`은 같은 주문을 반환하지만, 이후 `K2+A2`를 보내면 서버가 K2의 이전 payload를 기억하지 못해 새 주문을 허용할 수 있었다. 예약 확정 뒤 `K2+A1` 재시도 역시 안정적으로 기존 확정 결과를 찾지 못했다.

이를 Blocking으로 분류하고 모든 수락된 키를 `CheckoutRequestKey`로 영속화했다. 이제 K2는 최초 재사용 시점부터 A1 fingerprint와 기존 Checkout에 결합되므로 같은 키의 다른 payload는 409이며, 기존 Checkout이 확정된 뒤에도 K2 재시도는 같은 `merchantUid`와 확정 상태를 반환한다.

두 번째 Reviewer Blocking에서는 후속 키의 FK 검증이 기존 Checkout row에 shared lock을 얻을 수 있다는 점을 확인했다. 좌석 lock을 보유한 transaction 안에서 키를 insert하면 `Seat X → Checkout S`가 되어 예약 확정의 `Checkout X → Seat X`와 deadlock cycle을 만들 수 있다. 따라서 기존 Checkout의 키 귀속을 좌석 transaction 밖으로 분리하고, 실제 MariaDB에서 예약 확정이 Checkout lock을 잡은 시점과 후속 키 저장 시도를 교차시켜 두 작업이 모두 완료되는지 검증했다.

### Checkout row를 추가로 잠그지 않은 이유

예약 확정은 `Checkout → Seat` 순서로 잠근다. Checkout 생성이 좌석을 잠근 뒤 기존 Checkout까지 `PESSIMISTIC_WRITE`로 잠그면 `Seat → Checkout` 역순이 생겨 다음 cycle이 가능하다.

```text
예약 확정: Checkout lock -> Seat lock 대기
Checkout 재생성: Seat lock -> Checkout lock 대기
```

따라서 생성 경로의 기존 Checkout 조회는 MVCC 읽기로 유지하고 좌석 transaction을 먼저 종료한다. 그 뒤 후속 키를 별도 transaction으로 저장해 FK 검증 lock을 얻는 동안 좌석 lock을 보유하지 않는다. 신규 Checkout의 최초 키는 아직 외부에서 참조할 수 없는 새 부모 row와 같은 transaction에 있으므로 원자적으로 저장한다.

## 6. 보존한 계약

- 같은 멱등 키 + 같은 payload: 기존 Checkout 재사용
- 같은 멱등 키 + 다른 payload: HTTP 409
- 같은 hold + 다른 멱등 키: 기존 Checkout 재사용
- 재사용된 다른 키 + 다른 payload: HTTP 409
- 재사용된 다른 키 + 예약 확정 후 같은 payload: 확정된 기존 Checkout 결과 재사용
- 다른 좌석 선택: 독립 Checkout 허용
- 만료된 Checkout을 같은 키로 재시도: HTTP 410
- 만료 처리 후 좌석 재점유 + 새 키: 새 Checkout 허용
- 실제 예약·결제 확정, Booking·Payment·Reservation·재고: Checkout 준비 단계에서 변경 없음

## 7. 검증 결과

| 시나리오 | 결과 |
| --- | --- |
| 같은 hold, 다른 키 순차 요청 | 동일 `merchantUid`, Checkout 1개 |
| 같은 hold, 다른 키 순차 요청 후 키 재사용 | 같은 payload는 같은 주문, 다른 payload는 409, Checkout 1개·키 귀속 2개 |
| 같은 hold, 다른 키 동시 요청 3회 | 매회 동일 `merchantUid`, Checkout 1개·키 귀속 2개 |
| 동일 hold identity 직접 중복 insert | DB unique 제약 위반, row 1개 유지 |
| `A1`과 `A2`의 다른 선택 | 서로 다른 Checkout 2개 |
| 만료 영속화 후 `A1` 재점유 | 기존 `EXPIRED`, 신규 `READY`, 새 `merchantUid` |
| 후속 키로 재사용한 Checkout 예약 확정 뒤 재시도 | 같은 `merchantUid`·`RESERVATION_CONFIRMED`, 다른 payload는 409 |
| 예약 확정의 Checkout lock과 후속 키 저장 교차 3회 | deadlock·DB 예외 0, 예약 확정과 키 귀속 모두 성공 |

최종 Backend 152개 테스트가 실패·오류·skip 없이 통과했다. `docker compose config`와 Backend CI도 PR 단계에서 검증한다. 이 결과는 로컬 가상 좌석 fixture의 기능·경쟁 조건 검증이며 운영 결제 안정성이나 실제 예매처 성능 수치가 아니다.

## 8. 적용 한계와 후속 조건

- 이번 identity는 정확히 같은 좌석 payload를 단일화한다. `A1`과 `A1+A2`처럼 일부 좌석이 겹치지만 payload가 다른 결제 준비 정책은 포함하지 않는다.
- 현재 UI에서 결제 준비 후 선택 좌석을 변경하지 않는 흐름에는 같은 payload 단일화가 직접 대응한다.
- 실제 PG adapter 전에는 부분 중첩 payload를 409로 막을지, Checkout이 좌석을 명시적으로 귀속할지 결정해야 한다.
- Entity 신규 schema에는 Checkout hold identity와 요청 키 unique 제약이 적용되지만 기존 운영 DB migration은 전체 Flyway baseline과 함께 별도 검증해야 한다.
- 실제 결제 승인 뒤 예약 확정 실패의 취소·환불 보상은 별도 상태·재처리 설계가 필요하다.
