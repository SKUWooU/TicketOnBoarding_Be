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

GlobalTimes의 기존 범용·리팩터링 Issue template과 PR template에서 변경 유형, 영향 범위, 외부 동작·예외 정책, 기술적 배경, 대상 Branch, 리뷰 요구사항과 후속 계획 항목을 참고했습니다. TicketOnBoarding에서 더 중요한 제외 범위, 측정 조건, 정합성, 과도한 기술 도입 통제 항목은 유지했습니다.

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
