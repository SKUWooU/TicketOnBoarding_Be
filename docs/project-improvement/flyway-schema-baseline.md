# Flyway 예약·결제 스키마 baseline

## 문제와 목표

local 실행은 `spring.jpa.hibernate.ddl-auto=create`에 의존했다. 새 DB는 실행할 수 있었지만, 어떤 좌석·hold·Checkout·결제 제약이 schema에 있어야 하는지 versioned artifact로 확인할 수 없었다.

V1 migration은 현재 JPA 모델의 전체 schema와 예약 핵심 제약을 SQL로 고정한다. 이후 Hibernate는 schema 생성자가 아니라 `validate` 검증자로만 동작한다.

## 적용 경로

| 시작 상태 | Flyway 동작 | 의미 |
| --- | --- | --- |
| 빈 DB | `V1__baseline_current_schema.sql` 적용 | 새 local/Testcontainers DB의 재현 가능한 schema 생성 |
| V1과 동등한 기존 schema, history 없음 | 동등성 검사 뒤 `ONTICKET_FLYWAY_BASELINE_ON_MIGRATE=true`, version `1` 기록 | DDL을 재실행하지 않고 이후 V2부터 추적 |
| 구조를 알 수 없거나 V1과 다른 기존 DB | 기본 fail-fast, 적용 금지 | schema diff·data migration·backup/rollback 계획을 별도 승인으로 설계 |

기본 `baseline-on-migrate`는 `false`다. `ONTICKET_FLYWAY_BASELINE_ON_MIGRATE=true`는 기존 schema를 자동으로 V1과 같게 고쳐 주지 않으므로, 구조 비교를 마친 V1-equivalent schema에만 한 번 사용한다. 따라서 이 프로젝트에 실제 운영 DB migration이나 data backfill이 준비됐다는 의미도 아니다.

## 고정한 예약 도메인 제약

- `seat(concert_time_id, seat_number)`의 복합 unique와 layout 조회 index
- Checkout의 merchant UID·사용자 idempotency key·hold identity unique
- Checkout seat assignment의 `(checkout_id, seat_id)` 및 활성 좌석 unique
- Payment provider ID와 Booking 연결 unique

이 제약은 동시성 제어 로직을 대체하지 않는다. 애플리케이션 lock·transaction과 DB 최종 제약을 함께 유지한다.

## 검증

`FlywayMigrationContractTest`는 MariaDB Testcontainers에서 다음을 검증한다.

1. 빈 schema가 V1을 한 번 적용하고 핵심 index를 생성한다.
2. history 없는 non-empty schema는 기본 설정에서 fail-fast한다.
3. V1-equivalent schema에서 history table만 제거한 fixture는 명시적 opt-in일 때만 V1을 재실행하지 않고 `BASELINE` history를 남긴다.
4. 기존 예약·hold·Checkout 통합 테스트는 Flyway 적용 뒤 `ddl-auto=validate`로 실행된다.

local Docker MariaDB에서는 history baseline 뒤 `/actuator/health`가 `UP`임을 확인했다. KOPIS·PG·OAuth 호출과 실제 운영 DB 접속은 수행하지 않았다.

## 후속 판단

실제 legacy DB의 upgrade migration은 schema dump·데이터 중복/NULL 검사·rollback 전략이 확보된 경우에만 별도 Issue로 다룬다. 이 V1 baseline만으로 운영 자동 migration을 실행하지 않는다.
