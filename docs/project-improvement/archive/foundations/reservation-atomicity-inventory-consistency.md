# 예약 원자성·잔여 좌석 갱신 정합성 개선

## 목적

Issue #3에서 재현한 복수 좌석 부분 commit과 회차 잔여 좌석 갱신 유실을 같은 MariaDB fixture에서 개선한다. 성공한 예약 수와 좌석 상태, `ConcertTime.seatAmount`가 하나의 transaction 결과로 일치하도록 만드는 것이 목적이며 처리량 개선을 주장하지 않는다.

## 변경 전 기준선

환경과 fixture는 [예매 트랜잭션·경합 기준선](reservation-transaction-concurrency-baseline.md)과 같다.

| 시나리오 | 변경 전 결과 | 불변식 |
| --- | --- | --- |
| 서로 다른 `A1`~`A8` 동시 예약 | 좌석 8, 예약 8, 잔여 23 | 위반 |
| `[A1, NOT-EXISTING]` | 첫 좌석·예약 commit, 잔여 24 | 위반 |

## 선택한 변경

### checked exception rollback

`reserveSeat()`의 기존 checked `Exception`과 외부 호출 signature를 유지하고 `@Transactional(rollbackFor = Exception.class)`으로 rollback 규칙을 명시했다. 별도 예외 계층과 HTTP 오류 응답 개편은 이번 정합성 Issue에 포함하지 않았다.

### 조건부 원자 감소

Java에서 먼저 읽은 `seatAmount`를 수정해 저장하는 대신 다음 의미의 단일 update를 실행한다.

```sql
UPDATE concert_time
SET seat_amount = seat_amount - :seatCount
WHERE id = :concertTimeId
  AND seat_amount >= :seatCount;
```

Spring Data JPA `@Modifying(flushAutomatically = true)`로 앞선 좌석·예약 변경을 flush한 뒤 update하며, 영향받은 회차 row가 정확히 1개가 아니면 checked `Exception`을 던져 transaction 전체를 rollback한다. 조건절은 잔여 수량이 음수가 되는 것을 막는다.

현재 service는 bulk update 뒤에 이미 읽은 `ConcertTime`을 다시 사용하지 않는다. 영속성 context의 해당 entity 값이 bulk update 직후 오래된 상태일 수 있으므로 후속 코드가 추가될 때는 재조회·clear 여부를 다시 판단해야 한다.

## 고려했지만 보류한 대안

- 회차 row를 예약 시작부터 `PESSIMISTIC_WRITE`로 잠그는 방식: 단순하지만 서로 다른 좌석 예약까지 회차 단위로 직렬화한다.
- `seatAmount`를 제거하고 좌석 상태에서 매번 계산하는 방식: 읽기 API와 데이터 모델 변경까지 범위가 커진다.
- Runtime domain exception과 공통 오류 응답 도입: 인증·API 예외 정책을 함께 설계해야 하므로 별도 Issue가 적합하다.
- Redis·분산 락·대기열: 단일 DB 원자 연산으로 현재 재현 결함을 해결할 수 있어 근거가 없다.

## 변경 후 검증

- 측정일: 2026-08-20 (Asia/Seoul)
- Java: Eclipse Temurin 21.0.12
- DB: Testcontainers MariaDB 10.11.8
- fixture: 공연 1개, 회차 1개, 가상 좌석 24개
- 외부 연동: KOPIS, CoolSMS, OAuth, 결제와 운영 DB 미사용

| 시나리오 | 변경 후 결과 | 불변식 |
| --- | --- | --- |
| 단일 좌석 `A1` | 좌석 1, 예약 1, 잔여 23 | 충족 |
| 동일 좌석 `A1` 8개 동시 요청 | 성공 1·실패 7, 좌석 1, 예약 1, 잔여 23 | 충족 |
| 서로 다른 `A1`~`A8` 동시 요청, 3회 반복 | 매회 성공 8, 좌석 8, 예약 8, 잔여 16 | 충족 |
| `[A1, NOT-EXISTING]` | 좌석 0, 예약 0, 잔여 24 | 충족 |
| 잔여 1에서 `[A1, A2]` | 조건부 update 0행, 좌석 0, 예약 0, 잔여 1 | 음수·부분 commit 없음 |

잔여 부족 시나리오는 조건절과 service rollback 경계를 검증하기 위해 좌석 row는 예약 가능하지만 회차 집계만 1인 명시적 불일치 fixture를 사용한다. 이는 운영 데이터 상태를 재현하거나 정상 상태라고 가정하는 테스트가 아니다.

예약 통합 테스트 invocation 8개와 기존 context smoke test 1개를 합친 전체 테스트 invocation 9개가 통과했다.

실행 명령:

```powershell
$env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-21.0.12.8-hotspot'
.\gradlew.bat test --tests "com.onticket.concert.service.SeatReservationConcurrencyIntegrationTest" --rerun-tasks
.\gradlew.bat test --rerun-tasks
```

## 한계와 다음 질문

- service 직접 호출 기반의 로컬 정합성 테스트이며 HTTP·TPS·p95·lock wait를 측정하지 않는다.
- 복수 좌석을 반대 순서로 잠그는 deadlock 가능성은 해결하지 않았다.
- 좌석 잠금 조회의 복합·유일 인덱스는 변경하지 않았다.
- 취소 시 잔여 수량 증가와 예약·결제·취소 멱등성은 별도 Issue에서 검증한다.

## 연결

- [Issue #5](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/5)
- [기준선 Issue #3](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/3)
- [Spring transaction rollback 규칙](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/rolling-back.html)
- [Spring Data JPA modifying query](https://docs.spring.io/spring-data/jpa/reference/jpa/query-methods.html#jpa.modifying-queries)
