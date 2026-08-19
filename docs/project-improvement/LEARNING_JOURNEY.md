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
