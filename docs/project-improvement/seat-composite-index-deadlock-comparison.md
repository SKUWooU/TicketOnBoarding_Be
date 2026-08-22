# 좌석 복합 인덱스와 deadlock 비교 기준선

## 목적

Issue #9에서는 `(concert_time_id, seat_number)` 복합 인덱스가 없는 현재 schema에서 반대 순서 복수 좌석 예약이 첫 잠금부터 직렬화되어 deadlock cycle에 도달하지 못했다. 이번 기준선은 같은 MariaDB fixture에 test용 복합 unique index만 적용해 query plan, 좌석 단위 잠금 동시성, deadlock과 rollback 결과가 어떻게 달라지는지 비교한다.

운영 Entity·schema·예약 Service는 변경하지 않는다. 결과는 로컬 단일 JVM·MariaDB 10.11.8·가상 좌석 24개의 비교 실험이며 운영 deadlock 발생률이나 처리량을 의미하지 않는다.

## 비교 조건

- Java 21, Spring Boot 3.2.5
- MariaDB Testcontainers `mariadb:10.11.8`
- 공연 1개, 회차 1개, 가상 좌석 `A1`~`C8` 24개
- `SeatReservationService.reserveSeat()`와 `PESSIMISTIC_WRITE` query는 동일
- 요청 1: `[A1, A2]`
- 요청 2: `[A2, A1]`
- 각 transaction의 첫 좌석 잠금 query 반환 직후 test barrier 적용
- indexed 시나리오는 3회 반복
- KOPIS·CoolSMS·OAuth·결제·운영 DB 호출 없음

비교를 위해 test schema에만 다음 index를 생성한다.

```sql
CREATE UNIQUE INDEX uk_seat_concert_time_number
ON seat (concert_time_id, seat_number);
```

각 indexed test가 끝나면 외래 키용 `concert_time_id` 단일 보조 index를 복원한 뒤 복합 index를 제거한다. InnoDB는 복합 index의 선두 column을 외래 키 지원에 재사용하면서 기존 암시적 단일 index를 제거할 수 있으므로, 보조 index 복원 없이 복합 index를 drop하면 MariaDB error 1553이 발생한다.

## query plan 비교

동일한 잠금 SQL을 비교했다.

```sql
EXPLAIN
SELECT *
FROM seat
WHERE concert_time_id = ?
  AND seat_number = ?
FOR UPDATE;
```

| 조건 | type | key | 예상 검사 rows |
| --- | --- | --- | --- |
| 복합 index 없음 | `ALL` | `null` | fixture 전체 |
| 복합 unique index | `const` | `uk_seat_concert_time_number` | `1` |

복합 index의 column 순서도 `concert_time_id`, `seat_number`로 확인했다. 이는 한 회차의 좌석 번호를 단일 row로 식별할 수 있음을 뜻하며, 실제 query가 해당 index를 선택했다.

## 잠금·deadlock 비교

| 조건 | 첫 좌석 lock 동시 획득 | 두 번째 잠금 결과 | 성공/실패 | 최종 상태 |
| --- | --- | --- | --- | --- |
| 복합 index 없음 | 3회 모두 `false` | SQL deadlock 없음, commit 후 business conflict | 성공 1 / `이미 예약된 좌석` 1 | 좌석 2, 예약 2, 잔여 22 |
| 복합 unique index | 3회 모두 `true` | 3회 모두 DB deadlock | 성공 1 / deadlock victim 1 | 좌석 2, 예약 2, 잔여 22 |

indexed fixture에서 MariaDB와 Spring이 반환한 실패 정보는 세 번 모두 같았다.

```text
Spring exception: CannotAcquireLockException
SQL state:        40001
MariaDB code:     1213
message:          Deadlock found when trying to get lock
```

복합 index가 없을 때는 두 transaction이 서로 다른 첫 좌석 lock을 동시에 보유하지 못했다. 복합 index를 적용하자 각 query가 목표 row를 식별해 `A1`과 `A2`의 첫 잠금을 동시에 획득했고, 입력 순서가 반대인 두 번째 잠금에서 순환 대기가 만들어졌다.

## rollback과 최종 불변식

deadlock victim은 두 번째 좌석 query에서 실패하기 전에 첫 좌석 변경과 예약 저장을 진행할 수 있다. 테스트는 결과 순서에 대응하는 username별 예약 row를 다시 조회한다.

- 성공 transaction 사용자: 예약 row 2개
- deadlock victim 사용자: 예약 row 0개
- 최종 예약 좌석: 2개
- 전체 예약 row: 2개
- 최종 잔여 수량: 22
- `24 = reserved + remaining` 충족

따라서 MariaDB가 victim transaction을 rollback하고 Spring transaction 경계가 부분 예약을 남기지 않는 것을 확인했다. 이 결과는 deadlock 자체를 허용해도 된다는 뜻이 아니다. 한 요청이 실패했고 client가 어떤 재시도 정책을 가져야 하는지도 정의되지 않았다.

## 해석

복합 unique index는 두 가지 효과를 함께 만든다.

1. 한 회차의 같은 좌석 번호 중복을 DB에서 금지할 수 있다.
2. 잠금 query가 목표 row를 직접 찾아 서로 다른 좌석 transaction의 불필요한 직렬화를 줄일 수 있다.

동시에 기존 입력 순서 잠금의 잠재 deadlock을 실제로 드러냈다. 따라서 index만 단독 배포하면 query plan은 개선되지만 반대 순서 복수 좌석 요청에서 error 1213이 발생할 수 있다.

현재 근거가 지지하는 다음 변경은 `복합 unique index + canonical lock ordering`을 같은 migration·Service 개선 범위에서 검증하는 것이다. 모든 요청이 좌석 번호를 정렬하고 같은 순서로 잠그면 반대 입력 payload도 동일한 lock 순서를 사용하므로 순환 대기의 한 조건을 제거할 수 있다.

## 다음 개선 Issue의 완료 조건

1. migration 전 기존 `(concert_time_id, seat_number)` 중복 검사
2. Flyway 또는 명시적 migration으로 복합 unique index 생성
3. null·빈 목록·payload 내 중복 좌석 validation
4. 요청 원본과 무관한 canonical seat order 결정
5. `[A1,A2]`, `[A2,A1]` indexed fixture에서 deadlock 0건
6. 동일 좌석은 계속 1개 요청만 성공
7. 서로 다른 좌석 예약과 잔여 집계 회귀 유지
8. 실패 응답·retry 정책은 별도 API 계약으로 명시

무제한 retry, Redis 분산 lock과 대기열은 이번 비교 결과만으로 도입하지 않는다. 좌석 임시 점유는 결제 전 상태 전이를 다루는 후속 도메인 작업이다.

## 연결

- [Issue #11](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/11)
- [복수 좌석 잠금 순서와 deadlock 기준선](multi-seat-lock-order-deadlock-baseline.md)
- [예약 원자성·잔여 좌석 정합성 개선](reservation-atomicity-inventory-consistency.md)
- [개선 근거 연결표](EVIDENCE_MAP.md)
- [MariaDB InnoDB foreign keys](https://mariadb.com/kb/en/foreign-keys/)
