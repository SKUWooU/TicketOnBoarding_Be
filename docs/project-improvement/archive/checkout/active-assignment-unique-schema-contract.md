# active assignment unique schema contract

## 문제

`active_seat_id`는 `@Table`의 이름 있는 unique constraint와 `@Column(unique = true)`를 동시에 선언했다. disposable schema에서도 결과를 보장하지 않는 중복 의도이므로, index 이름과 개수를 명시적으로 고정할 필요가 있었다.

## 변경과 검증

- column-level `unique = true`를 제거하고 `uk_checkout_seat_assignment_active_seat` table constraint만 유지했다.
- MariaDB Testcontainers에서 `SHOW INDEX FROM reservation_checkout_seat_assignment`를 실행했다.
- `active_seat_id`를 대상으로 한 non-unique=0 index가 이름 있는 constraint 하나임을 회귀 테스트로 확인했다.

이 검증은 Hibernate `ddl-auto=create` disposable schema에 한정한다. 기존 DB migration·운영 schema 변환은 포함하지 않는다.
