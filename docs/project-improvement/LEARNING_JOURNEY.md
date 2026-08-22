# 개선 및 학습 기록

이 문서는 작업 순서, 전후 코드와 관련 개념을 Issue 단위로 설명합니다. 진행 상태는 `WORK_PROGRESS.md`, 정량 근거는 `EVIDENCE_MAP.md`를 기준으로 합니다.

## 기록 형식

각 Issue가 병합된 뒤 다음 항목을 추가합니다.

1. 관찰한 기존 동작
2. 문제를 재현한 방법
3. 고려한 대안과 보류 이유
4. 변경 전후의 핵심 코드 경계
5. transaction, lock, rollback 등 관련 개념
6. 테스트와 측정 결과
7. 결과의 한계와 다음 질문
8. Issue, PR과 ADR 링크

## Issue #1 — 개선 기준선과 협업 절차

### 관찰

Backend와 Frontend가 별도 저장소지만 Issue·PR template, 공통 workflow와 개선 근거 문서가 없었습니다. 코드 변경보다 먼저 문제·범위·검증 조건을 일관된 형식으로 축적할 기준이 필요했습니다.

### 결정

Backend 저장소에 전체 개선의 기준 문서와 진행 상태를 두고, Frontend 변경은 독립 Issue·PR로 수행해 상호 링크합니다. 채팅 세션 전달만을 위한 별도 문서는 만들지 않고 `WORK_PROGRESS.md`를 진행 상태의 단일 원본으로 사용합니다.

GlobalTimes의 범용 작업 형식을 기준으로 Issue·PR template을 목적, 범위, 작은 작업 항목, 완료 기준, 테스트와 참고 링크 중심으로 유지합니다. 상세 기술 근거와 측정 한계는 `docs/project-improvement`에 분리하고 Reviewer 전달 문구는 PR 본문에 복제하지 않습니다. README는 Issue마다 갱신하지 않고 실제 코드와 설명이 달라지거나 Phase가 정리되는 시점에 갱신합니다.

참고 자료:

- [GlobalTimes Issue template](https://github.com/SKU-GlobalTimes/GlobalTimes_BeSide/tree/develop/.github/ISSUE_TEMPLATE)
- [GlobalTimes PR template](https://github.com/SKU-GlobalTimes/GlobalTimes_BeSide/blob/develop/.github/PULL_REQUEST_TEMPLATE.md)

### 범위 제한

이번 Issue에서는 애플리케이션 코드, 동시성 로직, 결제 흐름과 Frontend 파일을 변경하지 않습니다. 확인된 사실과 미검증 가설을 분리하며 개선 효과나 성능 수치를 만들지 않습니다.

### 링크

- [Backend Issue #1](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/1)
- [Backend PR #2](https://github.com/SKUWooU/TicketOnBoarding_Be/pull/2)

## Issue #3 — 예매 트랜잭션·경합 기준선

### 관찰과 재현

MariaDB 10.11.8 Testcontainers에 공연 1개, 회차 1개와 가상 좌석 24개를 만들고 start latch로 8개 service 호출을 동시에 시작했습니다. 실제 외부 연동과 운영 DB는 사용하지 않았습니다.

좌석별 `PESSIMISTIC_WRITE`는 동일 좌석의 중복 성공을 막았지만 별도 `ConcertTime` row의 잔여 좌석 집계까지 보호하지 않았습니다. 서로 다른 8좌석이 모두 예약되어도 각 트랜잭션이 먼저 읽은 24에서 1을 뺀 23을 덮어써 불변식이 깨졌습니다.

또한 메서드가 checked `Exception`을 선언하고 있어 복수 좌석 중 두 번째 좌석에서 실패해도 첫 좌석과 예약 row가 commit됐습니다. 좌석 잠금 SQL은 `(concert_time_id, seat_number)` 복합 인덱스를 사용하지 못하고 fixture 24행 전체를 검사했습니다.

### 범위와 다음 단계

이번 Issue는 현재 결함을 재현하는 characterization test와 근거 문서만 추가하고 운영 로직은 바꾸지 않습니다. 다음 Issue에서 rollback 원자성과 회차 집계 갱신을 먼저 바로잡고, 복수 좌석 lock ordering·deadlock은 별도 시나리오로 분리합니다. 분산 락이나 대기열은 이 결과만으로 도입하지 않습니다.

상세 조건과 결과는 [예매 트랜잭션·경합 기준선](reservation-transaction-concurrency-baseline.md)에서 확인합니다.

### 링크

- [Backend Issue #3](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/3)

## Issue #5 — 예약 원자성·잔여 좌석 갱신 정합성

### 문제 경계

Issue #3에서 같은 예약 transaction 안의 두 결함을 분리해 확인했습니다. checked exception은 앞선 좌석과 예약을 부분 commit했고, 서로 다른 좌석 transaction은 각자 읽은 회차 집계값을 덮어썼습니다.

### 결정

기존 예외 signature를 바꾸지 않고 `rollbackFor = Exception.class`을 명시해 모든 예약 실패를 rollback 대상으로 만들었습니다. 회차 전체를 예약 시작부터 잠그는 대신 조건부 원자 감소 update를 사용해 서로 다른 좌석 작업을 선제적으로 직렬화하지 않았습니다. update 영향 row가 1이 아니면 예외를 발생시켜 앞서 flush된 좌석과 예약도 함께 rollback합니다.

### 전후 결과

같은 가상 좌석 24개와 결정적 barrier에서 서로 다른 8좌석 예약은 `잔여 23·불변식 위반`에서 `잔여 16·불변식 충족`으로 바뀌었습니다. 복수 좌석 checked exception은 첫 좌석·예약 부분 commit에서 전체 rollback으로 바뀌었습니다. 잔여 부족 guard fixture에서도 음수 집계와 부분 commit이 발생하지 않았습니다.

### 범위 제한

이번 변경은 처리량 최적화가 아니며 lock wait를 측정하지 않았습니다. 복수 좌석 lock ordering, 좌석 복합·유일 인덱스, 취소·결제·멱등성과 API 예외 모델은 후속 Issue로 남겼습니다.

상세 조건과 결과는 [예약 원자성·잔여 좌석 갱신 정합성 개선](reservation-atomicity-inventory-consistency.md)에서 확인합니다.

### 링크

- [Backend Issue #5](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/5)

## Issue #9 — 복수 좌석 잠금 순서와 deadlock 기준선

### 관찰

같은 회차의 `[A1,A2]`와 `[A2,A1]`을 동시에 예약하고, test 전용 Repository proxy에서 각 transaction의 첫 좌석 lock 반환 직후 barrier를 기다렸습니다. 세 번 모두 두 transaction이 서로 다른 첫 lock을 동시에 획득하지 못했고 SQL deadlock은 발생하지 않았습니다. 한 transaction이 두 좌석을 commit한 뒤 다른 transaction은 `이미 예약된 좌석입니다.`로 실패했습니다.

### 해석

이 결과는 입력 순서 잠금이 안전하다는 뜻이 아닙니다. 기존 `EXPLAIN`의 full scan과 복합 index 부재를 함께 보면 현재 잠금 조회가 첫 좌석부터 넓게 직렬화된 것으로 추론할 수 있습니다. 좌석 식별 index로 잠금 범위를 좁히면 반대 순서 cycle이 드러날 수 있으므로 index와 canonical lock ordering을 함께 비교해야 합니다.

### 최종 상태

매회 성공 1·business 실패 1, 예약 좌석 2, 예약 row 2, 잔여 22로 `24 = reserved + remaining`을 충족했습니다. 실패 transaction의 부분 commit과 SQL deadlock error는 없었습니다. 결과는 MariaDB 10.11.8 단일 컨테이너와 가상 좌석 24개 조건이며 운영 deadlock 발생률이나 처리량을 의미하지 않습니다.

상세 동기화 방식과 다음 검증 조건은 [복수 좌석 잠금 순서와 deadlock 기준선](multi-seat-lock-order-deadlock-baseline.md)에 기록합니다.

### 링크

- [Backend Issue #9](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/9)
- [Backend PR #10](https://github.com/SKUWooU/TicketOnBoarding_Be/pull/10)

## Issue #11 — 좌석 복합 인덱스와 deadlock 비교 기준선

### 비교

Issue #9와 같은 fixture에 test용 `(concert_time_id, seat_number)` 복합 unique index만 적용했습니다. 잠금 query plan은 `ALL/key=null`에서 `const/복합 key/rows=1`로 바뀌었고, 반대 순서 transaction은 서로 다른 첫 좌석 lock을 동시에 획득했습니다.

### 관찰

`[A1,A2]`와 `[A2,A1]` 시나리오를 3회 반복한 결과 매회 MariaDB error `1213`, SQL state `40001`이 발생했고 Spring은 `CannotAcquireLockException`으로 변환했습니다. 한 transaction만 성공했으며 deadlock victim 사용자의 예약 row는 0개였습니다. 최종 좌석 2·예약 2·잔여 22로 rollback 후 불변식은 유지됐습니다.

### 다음 결정

복합 index는 좌석 식별과 잠금 query plan을 개선하지만 입력 순서 잠금의 cycle을 실제로 드러냈습니다. 따라서 운영 변경에서는 index migration과 canonical lock ordering을 함께 검증해야 합니다. retry·분산 lock·대기열은 도입하지 않습니다.

상세 조건은 [좌석 복합 인덱스와 deadlock 비교 기준선](seat-composite-index-deadlock-comparison.md)에 기록합니다.

### 링크

- [Backend Issue #11](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/11)
