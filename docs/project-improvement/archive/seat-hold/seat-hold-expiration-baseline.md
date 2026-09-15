# 결제 전 좌석 임시 점유·만료 부재 기준선

## 목적

좌석을 화면에서 선택한 시점부터 검증된 예약이 확정되기 전까지 서버가 해당 좌석을 다른 사용자에게 숨기거나 임시 소유하는지 확인한다. 이번 Issue는 개선 구현이 아니라 현재 경계를 재현하는 기준선이다.

## 현재 흐름

1. 좌석 조회 API는 회차의 좌석 ID·번호·`reserved` snapshot을 반환한다.
2. Frontend의 좌석 클릭은 로컬 `selectedSeats`와 표시 상태만 변경한다.
3. 서버 호출은 검증된 예약 요청 시점에 처음 발생한다.
4. 예약 transaction은 결제를 검증한 뒤 좌석을 잠그고 이미 예약된 좌석이면 `SeatReservationConflictException`을 발생시킨다.

현재 모델에는 `HELD` 상태, 점유 소유자, `expiresAt`, 선택·해제 API, 만료 회수 작업이 없다. 따라서 결제 화면에 먼저 도달한 사용자에게 좌석이 보장되지는 않으며, 최종 transaction에 먼저 성공한 요청만 예약된다.

## 재현 조건

- DB: MariaDB Testcontainers
- fixture: 공연 1개·회차 1개·가상 좌석 24개
- 경쟁 좌석: `A1` 한 개
- 사용자: 서로 다른 두 사용자
- 요청 식별: 서로 다른 payment ID와 idempotency key
- 외부 연동: mock 결제 검증만 사용
- 동시성 제어: 결제 검증은 transaction 밖에서 수행하고, 실제 예약 transaction 안에서 양쪽 `Booking.saveAndFlush`가 끝난 직후 barrier로 맞춘 후 좌석 예약 진행
- 반복 횟수보다 운영 transaction 경계의 결정적 동기화를 우선한 단일 동일 좌석 경쟁

2,000석 fixture는 query plan과 처리량 측정용이다. 이번 검증은 상태 경계와 row 불변식이 목적이므로 가장 작은 경쟁 단위인 한 좌석만 사용한다.

## 검증 결과

### 반복 조회

- 두 번의 좌석 조회에서 `A1`은 같은 ID와 `reserved=false`로 반환됐다.
- 조회 전후 Payment·Booking은 0건이었다.
- 잔여 좌석 24, 예약 좌석 0, Reservation 0이 유지됐다.

### 독립 사용자 경쟁

- 경쟁 결과 성공 1건, `SeatReservationConflictException` 1건이었다.
- 실패 메시지는 `이미 예약된 좌석입니다.`였다.
- Payment·Booking·Reservation·reserved seat는 각각 1개만 남았다.
- Payment provider ID와 Payment·Booking·Reservation 사용자가 실제 성공 요청과 일치했다.
- 잔여 좌석은 23, 예약 좌석은 1이었다.
- 기존 HTTP 예외 계약 테스트가 이 예외를 409 Conflict로 매핑한다.

대상 테스트 클래스는 총 21개 invocation, Backend 전체 회귀는 105개 test로 실패·오류·skip 0을 확인했다. 기존 부하 도구도 PowerShell 153개 assertion, k6 inspect, Compose config를 통과했다.

## 해석

현재 비관적 잠금과 DB unique 제약은 최종 초과 판매를 막지만 결제 전 사용자 경험의 임시 소유권은 제공하지 않는다. 두 사용자는 같은 좌석을 선택하고 결제 단계까지 진행할 수 있으며, 늦게 확정된 사용자는 마지막에 409를 받는다. 이는 최종 재고 정합성과 임시 점유가 서로 다른 문제임을 보여준다.

## 후속 구현 조건

- 상태 전이: `AVAILABLE → HELD → RESERVED`, 만료·해제 시 `HELD → AVAILABLE`
- 소유권: 인증 사용자 또는 안전한 점유 token
- 시간: `expiresAt`과 테스트 가능한 `Clock`
- 원자성: 가용하거나 만료된 좌석만 조건부 점유하고 영향 row 수로 성공을 판정
- 회수: 우선 요청 시점의 lazy expiration/reclaim, scheduler는 필요성이 확인된 뒤 검토
- 멱등성: 동일 사용자의 같은 선택 재요청은 같은 결과를 반환
- 결제 연계: 성공 시 RESERVED, 실패·취소 시 점유 해제, 만료 뒤 성공 callback 처리 정책
- HTTP 계약: 이미 점유된 좌석과 이미 예약된 좌석을 구분 가능한 409 응답

DB 기반 단일 인스턴스 구현과 경합 fixture를 먼저 검증한다. Redis, 분산 lock, 메시지 브로커, 대기열은 다중 인스턴스 또는 측정된 병목 근거가 생길 때 검토한다.

## 한계

- 실제 공연장 좌석이나 운영 트래픽이 아닌 명시적 가상 좌석 fixture다.
- 실제 KOPIS·PG·SMS를 호출하지 않았다.
- 아직 임시 점유가 없으므로 만료 시간이나 회수 지연을 측정하지 않았다.
- transaction 내부 barrier는 두 요청이 겹쳐 실행됨을 고정하지만 DB lock wait 시간 자체를 계측한 성능 실험은 아니다.
- 기능 정합성 기준선이며 TPS·p95 성능 결과가 아니다.

## 실행 명령

```powershell
.\gradlew.bat test --tests com.onticket.concert.service.VerifiedReservationPaymentIntegrationTest --rerun-tasks --no-daemon
```

## 연결

- [Backend Issue #61](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/61)
