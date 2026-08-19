# 복수 좌석 잠금 순서와 deadlock 기준선

## 목적

현재 `SeatReservationService.reserveSeat()`는 요청의 `seatNumberList` 순서대로 좌석을 `PESSIMISTIC_WRITE` 조회한다. 같은 회차에서 `[A1, A2]`와 `[A2, A1]`을 동시에 예약할 때 실제 MariaDB deadlock이 발생하는지, 발생하지 않는다면 어떤 잠금 동작 때문인지 운영 코드를 바꾸기 전에 고정한다.

이 결과는 로컬 단일 JVM·MariaDB 10.11.8·가상 좌석 24개의 transaction 기준선이다. 실제 예매처 성능이나 운영 환경의 deadlock 발생률을 의미하지 않는다.

## 선행 조건

- Java 21
- Spring Boot 3.2.5, Spring Data JPA
- MariaDB Testcontainers `mariadb:10.11.8`
- 공연 1개, 회차 1개
- 좌석 `A1`~`C8` 24개, 모두 `reserved=false`
- 회차 잔여 수량 `seatAmount=24`
- MariaDB 기본 transaction 격리 설정
- 운영 KOPIS·CoolSMS·OAuth·결제·운영 DB 호출 없음

좌석 잠금 query 조건은 `concertTime.id`와 `seatNumber`지만 `(concert_time_id, seat_number)` 복합 인덱스와 유일 제약은 없다. Issue #3의 기존 `EXPLAIN` 기준선에서는 해당 `SELECT ... FOR UPDATE`가 `type=ALL`, 선택 index `null`이었다.

## 시나리오

두 개의 service transaction을 고정 thread pool에서 동시에 시작한다.

```text
Transaction 1: [A1, A2]
Transaction 2: [A2, A1]
```

단순한 시작 latch만으로는 첫 transaction이 모든 좌석을 예약한 후 두 번째 transaction이 시작할 수 있어 잠금 순서를 검증할 수 없다. 따라서 test 전용 Repository proxy가 각 transaction의 첫 `findByConcertTimeIdAndSeatNumberWithLock()` 반환 직후 barrier를 기다린다.

Repository 호출이 반환됐다는 것은 해당 transaction이 첫 좌석의 DB lock을 획득했다는 뜻이다. 두 transaction이 서로 다른 첫 lock을 모두 획득하면 barrier가 열리고 반대편 좌석을 요청해 순환 대기를 만들 수 있다.

반대로 첫 잠금 조회 자체가 넓게 직렬화되면 한 transaction만 barrier에 도달한다. 이때 2초 후 barrier 대기를 끝내되 예외를 발생시키지 않고 transaction을 계속 진행한다. 따라서 probe 때문에 첫 transaction이 rollback되는 것을 막고 현재 서비스 결과를 그대로 관찰한다.

## 관찰 결과

한 test 실행 안에서 같은 시나리오를 3회 반복했다.

| 반복 | 서로 다른 첫 lock 동시 획득 | 성공 | 실패 | SQL state / error code | 좌석 | 예약 | 잔여 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 아니요 | 1 | 1, `이미 예약된 좌석입니다.` | 없음 | 2 | 2 | 22 |
| 2 | 아니요 | 1 | 1, `이미 예약된 좌석입니다.` | 없음 | 2 | 2 | 22 |
| 3 | 아니요 | 1 | 1, `이미 예약된 좌석입니다.` | 없음 | 2 | 2 | 22 |

세 번 모두 `firstLocksConcurrent=false`였고 deadlock SQL exception은 발생하지 않았다. 먼저 첫 lock을 얻은 transaction이 두 좌석을 예약하고 commit한 후, 대기하던 transaction은 자신의 첫 좌석 조회를 마치고 이미 예약된 상태를 확인해 business exception으로 실패했다.

최종 상태는 매회 다음 불변식을 충족했다.

```text
reserved seats = 2
reservation rows = 2
remaining seats = 22
24 = reserved seats + remaining seats
```

## 해석

현재 schema와 query plan에서는 서로 반대 순서의 두 transaction이 각각 첫 좌석 lock을 동시에 보유하는 단계까지 도달하지 못했다. 따라서 이번 fixture에서는 순환 대기와 DB deadlock이 만들어지지 않았다.

이는 `입력 순서대로 잠가도 안전하다`는 증명이 아니다. `seat_number`를 포함한 선택적 index가 없고 기존 query plan이 full scan인 사실을 함께 보면, 잠금 조회가 목표 좌석보다 넓은 범위를 잠가 같은 회차의 첫 요청부터 직렬화한 것으로 해석할 수 있다. 이 인과관계는 현재 관찰과 query plan에 기반한 추론이며 실제 lock row 범위 자체를 별도 계측한 결과는 아니다.

현재 동작의 trade-off는 다음과 같다.

- 장점처럼 보이는 결과: 반대 순서 transaction이 동시에 첫 lock을 보유하지 못해 이번 fixture에서 deadlock이 없음
- 비용: 서로 다른 좌석 예약도 첫 잠금부터 대기할 수 있어 좌석 단위 동시성이 제한됨
- 잠재 변화: 복합 index를 추가해 잠금 범위를 좁히면 서로 다른 첫 lock을 동시에 획득해 기존 입력 순서에서 deadlock cycle이 드러날 수 있음

따라서 `deadlock이 관찰되지 않음`을 개선 완료나 고경합 처리 성능으로 표현하지 않는다.

## 자동화한 회귀 조건

`SeatReservationConcurrencyIntegrationTest.oppositeSeatOrderConcurrentReservationSerializesAtFirstLockWithoutDeadlock()`은 다음을 검증한다.

1. `[A1,A2]`, `[A2,A1]` 두 요청 중 1개 성공
2. 다른 1개는 SQL deadlock이 아니라 `이미 예약된 좌석입니다.`로 실패
3. 두 transaction이 서로 다른 첫 좌석 lock을 동시에 획득하지 못함
4. 실패 transaction에 부분 예약이 없음
5. 좌석 2, 예약 2, 잔여 22
6. 좌석·예약·집계 불변식 충족
7. 같은 시나리오 3회 반복

## 다음 Issue의 검증 순서

복합 index와 lock ordering은 독립적으로 적용하면 해석이 왜곡될 수 있으므로 다음 순서로 함께 비교한다.

1. 기존 데이터에서 `(concert_time_id, seat_number)` 중복 여부 확인
2. 복합 unique index를 적용한 test schema에서 잠금 query plan 확인
3. 입력 순서를 유지한 반대 순서 fixture로 deadlock이 드러나는지 확인
4. 좌석 번호를 canonical order로 정렬한 뒤 같은 fixture 재실행
5. 동일 좌석 1건 성공, 서로 다른 좌석의 동시 처리, 잔여 집계 회귀 확인
6. migration과 운영 로직은 비교 근거가 확인된 뒤 별도 승인

무제한 retry, Redis 분산 lock과 대기열은 이 기준선만으로 도입하지 않는다. 좌석 임시 점유는 결제 전 `AVAILABLE → HELD → RESERVED` 전이를 다루는 별도 상태 모델이며 이번 최종 예약 잠금 기준선에는 포함하지 않는다.

## 연결

- [Issue #9](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/9)
- [예매 transaction·경합 기준선](reservation-transaction-concurrency-baseline.md)
- [예약 원자성·잔여 좌석 정합성 개선](reservation-atomicity-inventory-consistency.md)
- [Backend 아키텍처 학습 기준선](backend-architecture-learning-baseline.md)
- [Jakarta Persistence lock mode](https://jakarta.ee/specifications/persistence/3.1/jakarta-persistence-spec-3.1#locking-and-concurrency)
