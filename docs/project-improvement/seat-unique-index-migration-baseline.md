# 좌석 복합 unique index migration 안전성 기준선

## 목적

`seat`에는 `(concert_time_id, seat_number)` unique constraint가 없다. Issue #11은 clean fixture에서 복합 index가 잠금 query를 `ALL/key=null`에서 `const/복합 key/rows=1`로 바꾸는 것을 확인했고, Issue #15는 index 조건에서 canonical lock ordering을 검증했다.

운영 schema에 index를 추가하기 전에는 기존 중복 데이터가 DDL을 막는지, 실패 시 데이터와 index가 어떤 상태로 남는지 확인해야 한다. 실제 운영 데이터는 제공되지 않으므로 MariaDB Testcontainers의 현재 JPA 생성 schema에 명시적인 중복 좌석을 구성한다.

## 조건

- Java 21, Spring Boot 3.2.5
- MariaDB Testcontainers `mariadb:10.11.8`
- Hibernate `ddl-auto=create`로 현재 10개 Entity schema 생성
- 공연 1개, 회차 1개, 가상 좌석 24개
- 중복 fixture: 같은 회차에 기존 `A1`과 추가 `A1`
- 운영 DB·KOPIS·SMS·OAuth·결제 호출 없음

## migration 전 사전 점검

```sql
SELECT concert_time_id,
       seat_number,
       COUNT(*) AS duplicate_count
FROM seat
GROUP BY concert_time_id, seat_number
HAVING COUNT(*) > 1;
```

중복 fixture에서는 `(concert_time_id, A1, 2)` 한 행을 반환했다. 운영 migration에서는 결과가 0행인지 먼저 확인해야 한다. 결과가 존재하면 어떤 row를 보존할지 예약 참조와 좌석 상태를 함께 조사해야 하며 자동 삭제하지 않는다.

## 중복이 있는 schema

적용을 시도한 DDL은 다음과 같다.

```sql
CREATE UNIQUE INDEX uk_seat_concert_time_number
ON seat (concert_time_id, seat_number);
```

MariaDB는 다음 오류로 DDL을 거부했다.

```text
SQL state: 23000
error code: 1062
duplicate key: concert_time_id와 seat_number의 A1 조합
```

실패 후 확인 결과는 다음과 같다.

- `uk_seat_concert_time_number` index 없음
- 같은 회차의 `A1` row 2개 그대로 유지
- 중복 row 자동 삭제·병합 없음

따라서 migration 실패를 데이터 정리 수단으로 사용할 수 없다. DDL 이전에 중복 탐지와 보존 정책이 필요하다.

## 중복이 없는 schema

clean fixture에서는 index가 생성됐다.

- column 순서: `concert_time_id`, `seat_number`
- `SHOW INDEX.Non_unique = 0`
- 잠금 query plan: `type=const`, `key=uk_seat_concert_time_number`, `rows=1`

index 생성 후 같은 회차에 `A1`을 다시 insert하면 SQL state `23000`, error code `1062`로 거부됐고 기존 `A1`은 1개로 유지됐다. 이 결과는 애플리케이션 검증과 별개로 DB가 좌석 식별 중복을 막는 것을 확인한다.

전체 회귀는 동시성·migration test invocation 18개와 application context 1개, 총 19개가 통과했다. 실패한 DDL은 후속 무인덱스·indexed·canonical ordering 테스트의 schema 상태를 오염시키지 않았다.

## schema ownership 판단

현재 저장소의 `application.yml`은 비어 있고 운영 Hibernate `ddl-auto` 값이 없다. 테스트는 Hibernate가 schema를 매번 생성한다. Flyway를 dependency에 추가하고 `ALTER TABLE seat` migration만 두면 다음 문제가 생긴다.

- 기존 DB에는 `seat`가 있을 수 있지만 실제 schema version을 알 수 없다.
- 신규 빈 DB에서는 Flyway가 Hibernate보다 먼저 실행되어 `seat` 부재로 실패한다.
- `baseline-on-migrate`는 기존 DB의 시작 version을 표시할 뿐 신규 DB의 전체 schema를 만들지 않는다.
- Entity 10개의 schema 소유권을 Hibernate에서 Flyway로 넘기는 변경이 좌석 index보다 큰 범위가 된다.

따라서 이번 Issue에서는 Flyway를 활성화하지 않는다. 결정과 재검토 조건은 [ADR-0001](adr/0001-schema-migration-ownership.md)에 기록한다.

## 다음 구현 조건

1. 현재 Entity 전체의 MariaDB DDL을 versioned baseline으로 고정한다.
2. 신규 DB는 baseline migration만으로 생성되고 Hibernate `validate`를 통과해야 한다.
3. 기존 DB는 실제 schema diff와 적용 시작 version을 확인해야 한다.
4. 운영 전 중복 점검 query 결과가 0행이어야 한다.
5. 중복 발견 시 Reservation의 `seat_id`, reserved 상태와 좌석 식별을 확인한 수동 정리 정책이 있어야 한다.
6. 복합 unique index 적용 후 예약 동시성 19개 test invocation과 EXPLAIN을 재검증한다.

## 한계

이 결과는 가상 좌석 25행의 migration 정확성 기준선이다. 운영 데이터의 중복 여부, DDL 실행 시간, metadata lock 시간이나 무중단 적용 가능성을 의미하지 않는다. 대용량 운영 테이블의 online DDL 전략은 실제 row 수와 쓰기 부하가 확인된 뒤 결정한다.

## 연결

- [Issue #17](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/17)
- [좌석 복합 인덱스와 deadlock 비교 기준선](seat-composite-index-deadlock-comparison.md)
- [복수 좌석 canonical 잠금 순서와 요청 검증](canonical-seat-lock-order.md)
- [개선 근거 연결표](EVIDENCE_MAP.md)
