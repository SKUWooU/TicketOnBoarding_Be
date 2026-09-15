# 서버 소유 Checkout과 예약 검증 경계

## 1. 문제

기존 검증 예약 API는 브라우저가 전달한 결제 식별자를 검증 포트에 넘긴 뒤 곧바로 `Booking`, `Payment`, `Reservation`을 생성했다. 그러나 결제 전에 서버가 보관한 주문이 없어서 다음 값을 서로 대조할 기준이 없었다.

- 현재 로그인 사용자
- 공연과 회차
- 정렬된 가상 좌석 목록
- 서버가 계산한 기대 결제 금액
- 결제 제공자에 전달할 `merchantUid`
- 좌석 임시 점유의 만료 시각

따라서 향후 실제 결제 검증 adapter를 연결하더라도 브라우저 callback 또는 결제 제공자의 구매자 문자열만으로 사용자와 예약 대상을 연결하게 될 위험이 있었다. 이번 작업은 외부 결제 연동보다 먼저 이 소유권 경계를 만든다.

## 2. 적용 범위와 과도함 판단

현재 필요한 최소 단위는 단일 DB 안의 `Checkout` aggregate와 기존 예약 transaction의 연결이다. 대기열, outbox, 메시지 브로커, 분산 lock은 이 문제를 해결하는 선행 조건이 아니므로 도입하지 않았다.

실제 PortOne 호출, credentials, 취소·환불 보상, 운영 DB migration도 포함하지 않는다. 테스트의 결제 승인은 mock이며 측정 결과는 MariaDB Testcontainers의 가상 좌석 fixture에만 해당한다.

## 3. 변경 전후 흐름

### 변경 전

```text
브라우저 callback
  -> paymentId 전달
  -> PaymentVerificationPort
  -> Booking/Payment/Reservation 생성
```

서버가 결제 전에 소유한 주문 원본이 없었다.

### 변경 후

```text
좌석 HELD
  -> POST /main/detail/{concertId}/checkouts
  -> 사용자·공연·회차·좌석 점유 재검증
  -> 서버 금액 계산 + merchantUid 발급
  -> Checkout(READY, expiresAt = 가장 이른 좌석 점유 만료)

결제 승인 후
  -> POST /main/detail/{concertId}/checkouts/{merchantUid}/verified-reservation
  -> Checkout 소유자·payload·상태·만료 사전 검증
  -> PaymentVerificationPort 호출
  -> 승인 paymentId·merchantUid·금액·승인 시각 검증
  -> Checkout row PESSIMISTIC_WRITE 재검증
  -> Booking/Payment/Reservation/재고 변경
  -> Checkout RESERVATION_CONFIRMED
```

외부 검증은 DB transaction 밖에서 수행한다. 느린 외부 I/O 동안 DB lock과 connection을 점유하지 않기 위해서다. 검증 후 transaction 안에서 Checkout을 다시 잠그고 상태를 확인하므로 사전 검증과 확정 사이의 경쟁도 차단한다.

## 4. 도메인 계약

### 상태

| 상태 | 의미 | 허용 전이 |
| --- | --- | --- |
| `READY` | 서버 주문이 생성됐고 좌석 점유가 유효함 | `RESERVATION_CONFIRMED`, `EXPIRED` |
| `RESERVATION_CONFIRMED` | 하나의 Booking과 연결되어 소비됨 | 없음 |
| `EXPIRED` | Checkout 또는 좌석 점유 기한이 지남 | 없음 |

만료 시각은 새 TTL을 부여하지 않고 선택 좌석들의 `heldUntil` 중 가장 이른 값으로 정한다. Checkout 생성만 반복해 좌석 점유를 연장할 수 없게 하기 위함이다. 정확히 만료 시각에 도달하면 만료로 판정한다.

### 서버 소유 값

- `merchantUid`: `ticket_` 접두어와 UUID로 서버가 발급
- `expectedAmount`: `VirtualTicketPricePolicy`의 가상 좌석 단가 × 중복 제거된 좌석 수
- `requestFingerprint`: 공연 ID, 회차 ID, 자연순 정렬한 좌석 목록의 SHA-256
- `username`: JWT에서 얻은 인증 사용자
- `expiresAt`: 선택 좌석 점유 중 가장 이른 만료 시각

### 멱등성

Checkout 생성은 `(username, idempotency_key)` unique 제약을 사용한다.

- 같은 키 + 같은 payload: 기존 `merchantUid`, 금액, 만료, 상태 반환
- 같은 키 + 다른 payload: HTTP 409
- 동시에 같은 키 생성: DB unique 제약의 패자를 기존 Checkout 조회로 회복
- 만료된 Checkout 재시도: 상태를 `EXPIRED`로 영속화하고 HTTP 410

예약 확정은 기존 Booking의 `(username, idempotency_key)`와 요청 fingerprint를 사용하며, Checkout row 잠금으로 하나의 Checkout이 한 Booking에만 연결되도록 한다. 서로 다른 멱등 키가 동시에 같은 Checkout을 소비해도 한 요청만 성공한다.

## 5. 실패와 원자성

다음 실패는 `Payment`, `Booking`, `Reservation`, 좌석 예약 여부와 회차 잔여 수를 변경하지 않는다.

- 본인이 점유하지 않은 좌석 또는 이미 예약된 좌석
- 공연·회차·좌석 payload 불일치
- 다른 사용자의 Checkout
- Checkout 만료
- 승인되지 않은 결제
- 결제 식별자 또는 `merchantUid` 불일치
- 서버 기대 금액과 승인 금액 불일치
- 같은 결제 식별자의 재사용

예약 확정 transaction은 Checkout을 먼저 잠그고 Booking과 Payment를 flush한 뒤 좌석 예약과 잔여 수 변경을 수행한다. checked exception도 rollback하도록 기존 원자성 규칙을 유지한다. 만료 전이는 예외가 반환되어도 별도 transaction 또는 `noRollbackFor` 경계로 보존한다.

## 6. HTTP 계약

| 결과 | 상태 |
| --- | --- |
| Checkout 생성 또는 검증 예약 성공 | 200 |
| 잘못된 Checkout 요청·멱등 키 | 400 |
| 인증 없음 | 401 |
| 멱등 payload·소유권·상태 충돌 | 409 |
| Checkout 만료 | 410 |
| 승인 내용·금액 불일치 | 422 |
| 결제 검증 adapter 미구성 | 503 |

신규 Checkout 결합 경로와 별개로 기존 `/verified-reservation`은 이전 로컬 부하 시나리오의 호환성을 위해 유지한다. Frontend와 부하 테스트가 신규 흐름으로 이동한 뒤 제거 여부를 판단한다.

## 7. 테스트 근거

환경은 Java 21, Spring Boot 3.2.5, MariaDB 10.11.8 Testcontainers, 명시적 `Clock`, 가상 좌석 `A1`, `A2`, mock 결제 승인이다. 실제 KOPIS·PortOne·SMS·운영 데이터 호출은 없다.

검증한 항목:

- 서버 금액과 가장 이른 만료 시각
- 동일 멱등 요청 재사용과 payload 충돌
- 본인 점유·타인 점유·미점유·공연 불일치
- 만료 상태 영속화
- 승인 성공과 동일 요청 재시도
- 금액·`merchantUid`·소유자·payload 불일치
- 서로 다른 멱등 키의 동시 Checkout 소비: 성공 1건, 충돌 1건
- 성공/실패 후 Checkout·Payment·Booking·Reservation·좌석·잔여 수 snapshot
- Controller 200·400·401·409·410·422·503

검증 명령:

```powershell
.\gradlew.bat test
```

전체 Backend 142개 테스트가 실패·오류·skip 없이 통과했다. 이 결과는 로컬 가상 fixture의 기능·경쟁 조건 검증이며 운영 결제 안정성이나 실제 예매처 성능을 의미하지 않는다.

## 8. 조사 중 드러난 기존 결함

예약 서비스가 공연 기본 정보를 읽을 때 `ConcertDetail`과 INNER JOIN하는 사용자 정의 조회를 사용했다. 공연 기본 레코드는 있지만 상세 레코드가 없는 명시적 fixture에서는 조회 결과가 `null`이 되어 NPE가 발생했다. 예약에 필요한 것은 `Concert` 기본 레코드이므로 기본키 조회로 바꾸고 명시적 미존재 오류를 반환하도록 했다.

## 9. 후속 조건

1. PortOne V1 조회 adapter를 mock HTTP server로 계약 검증한다. 실제 호출은 credentials와 비용·운영 영향 확인 후 별도 승인한다.
2. 실제 PG adapter 연결 전, 같은 사용자가 동일 hold에 서로 다른 멱등 키로 여러 `READY` Checkout을 만드는 경우를 막거나 기존 Checkout을 재사용하는 정책을 정한다. 이미 승인된 두 번째 결제가 예약 충돌로 남는 경우의 보상도 함께 설계한다.
3. Frontend가 Checkout 생성 응답의 `merchantUid`, 서버 금액, 만료 시각을 사용하도록 전환한다.
4. 기존 검증 예약과 loadtest가 신규 경로로 이동하면 legacy 경로 제거를 검토한다.
5. 실제 결제 성공 후 예약 확정 실패가 관찰되면 자동 취소·환불 보상과 재처리 상태를 별도 Issue로 설계한다.
6. 운영 DB를 다시 구성할 때 versioned migration과 기존 데이터 호환성을 별도로 검증한다.
