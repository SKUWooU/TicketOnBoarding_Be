# ADR-0001: 전체 schema baseline 전까지 Flyway 운영 활성화 보류

## 상태

보류

## 맥락과 재현 근거

저장소에는 10개 JPA Entity가 있지만 versioned schema migration이 없다. 추적된 `application.yml`은 비어 있고 운영 Hibernate `ddl-auto` 정책과 기존 DB schema version도 확인되지 않는다. 테스트는 `ddl-auto=create`로 disposable MariaDB schema를 생성한다.

좌석 복합 unique index는 clean fixture에서 정상 생성되지만 같은 회차·같은 좌석 번호 중복이 있으면 SQL state `23000`, MariaDB error `1062`로 실패한다. 실패 후 index는 없고 중복 row는 유지된다.

## 결정

Issue #17에서는 Flyway runtime dependency와 운영 migration을 추가하지 않는다. 좌석 `ALTER TABLE`만 첫 migration으로 두거나 `baseline-on-migrate`만 활성화하지 않는다.

다음 schema 구현은 Hibernate가 암묵적으로 생성하던 전체 schema를 versioned baseline DDL로 먼저 고정해야 한다. 신규 DB는 Flyway만으로 생성하고 Hibernate `validate`로 Entity와 일치함을 확인한다. 기존 DB 적용은 실제 schema diff와 시작 version을 확인한 뒤 별도 절차로 다룬다.

## 검토한 대안

### 좌석 ALTER migration만 추가

기존 DB에 `seat`가 있다는 가정에서는 작지만 신규 빈 DB에서 Flyway가 Hibernate보다 먼저 실행되어 실패한다.

### `baseline-on-migrate=true` 사용

기존 비어 있지 않은 DB에 history 시작점을 만들 수 있지만 전체 schema DDL을 제공하지 않으며 실제 schema 일치도 보장하지 않는다.

### JPA `@Table(uniqueConstraints=...)`만 추가

test `ddl-auto=create`에서는 제약이 생기지만 운영 `ddl-auto` 정책이 없어 배포 가능한 migration 이력이 되지 않는다.

### 이번 Issue에서 전체 Flyway baseline 구현

좌석 index 안전성 검증과 10개 Entity schema 소유권 전환이 섞여 범위와 회귀 위험이 커진다.

## 결과와 trade-off

운영 DB에는 아직 복합 unique index가 적용되지 않는다. 대신 중복 사전 점검, DDL 실패 상태와 다음 migration의 필수 조건이 검증된다. 후속 Issue는 전체 baseline DDL이라는 더 큰 범위를 명시적으로 다루게 된다.

## 적용 범위와 한계

결정은 현재 저장소의 schema 정보와 MariaDB 10.11.8 fixture에 한정한다. 실제 운영 schema dump나 배포 설정이 제공되면 기존 DB 전략을 다시 검토한다.

## 재검토 조건

- 전체 Entity의 MariaDB baseline DDL 확보
- 신규 DB Flyway migrate 후 Hibernate validate 통과
- 기존 DB schema dump와 migration 시작 version 확인
- 좌석 중복 사전 점검 0행 또는 승인된 정리 절차 확보

## 관련 Issue·PR·측정

- [Issue #17](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/17)
- [PR #18](https://github.com/SKUWooU/TicketOnBoarding_Be/pull/18)
- [좌석 복합 unique index migration 안전성 기준선](../seat-unique-index-migration-baseline.md)
