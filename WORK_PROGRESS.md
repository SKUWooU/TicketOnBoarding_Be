# 작업 진행 기록

이 문서는 완료·진행 중인 Issue와 검증 상태를 기록하는 단일 진행 상태 원본입니다. 상세 문제와 결정은 연결된 Issue, PR, EVIDENCE_MAP과 ADR에서 확인합니다.

## 저장소 기준선

| 구분 | 저장소 | 기준 Branch | 조사 기준 commit |
| --- | --- | --- | --- |
| Backend | [TicketOnBoarding_Be](https://github.com/SKUWooU/TicketOnBoarding_Be) | `main` | `bcfa7e30c947975e1f7f89d5eef0e8bffe36b9a1` |
| Frontend | [TicketOnBoarding_Fe](https://github.com/SKUWooU/TicketOnBoarding_Fe) | `main` | `1f9678be7a3a66ec610c6ef4ea335e9d6f5cbafd` |

두 저장소는 독립된 Issue와 PR을 사용합니다. 교차 변경은 각 작업의 링크를 양쪽 Issue 또는 PR에 남깁니다.

## 진행 중

없음.

## 완료

### Backend Issue #7 — 백엔드 아키텍처 학습 기준선

- Issue: [#7](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/7)
- PR: [#8](https://github.com/SKUWooU/TicketOnBoarding_Be/pull/8)
- Branch: `docs/#7-backend-architecture-baseline`
- 상태: 완료
- 계획 승인: 완료
- 구현: 완료
- 검증: 전체 Gradle test 실제 재실행 성공, 문서 상대 경로 확인, `git diff --check` 통과
- Reviewer: 최종 검토 HEAD `9088dbe`, Blocking·Non-blocking 없음, `MERGE_READY: YES`
- 사용자 최종 승인 후 2026-08-20 squash merge
- 범위: Backend 기술 스택, KOPIS 수집, 도메인·DB·인증·예약·결제·취소·FE 연동과 차별화 Phase를 단일 학습 문서로 정리
- 제외: README, 애플리케이션·Frontend 코드, 외부 API·결제·운영 데이터 실행

### Backend Issue #5 — 예약 원자성·잔여 좌석 갱신 정합성

- Issue: [#5](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/5)
- PR: [#6](https://github.com/SKUWooU/TicketOnBoarding_Be/pull/6)
- Branch: `fix/#5-reservation-atomicity-inventory`
- squash commit: `ab9a103cd5306ca6f25515f1292bf0e0318586c0`
- 상태: 완료
- 계획 승인: 완료
- 구현: 완료
- 검증: 예약 통합 테스트 invocation 8개 통과, 전체 테스트 invocation 9개 통과, `git diff --check` 통과
- Reviewer: 최종 HEAD Blocking 없음, `MERGE_READY: YES`
- 사용자 최종 승인 후 2026-08-20 squash merge
- 범위: checked exception 전체 rollback, 회차 잔여 수량 조건부 원자 감소, Issue #3 회귀 테스트 전환
- 제외: deadlock·인덱스·취소·결제·멱등성·인증·Frontend·부하 테스트와 분산 기술
- 결과: 상이 좌석 8개 반복 예약 시 잔여 16, checked exception과 잔여 부족 시 좌석·예약 전체 rollback

### Backend Issue #3 — 예매 트랜잭션·경합 기준선

- Issue: [#3](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/3)
- PR: [#4](https://github.com/SKUWooU/TicketOnBoarding_Be/pull/4)
- Branch: `test/#3-reservation-concurrency-baseline`
- squash commit: `a5db362373833c3847e28a47ad082a265bc1429d`
- 상태: 완료
- 계획 승인: 완료
- 구현: 완료
- 검증: 집계 조회 후 잠금 호출 전 결정적 barrier에서 상이 좌석 시나리오 3회 통과, 전체 `test` invocation 8개 통과, `git diff --check` 통과
- Reviewer: 최종 HEAD `ca069e1`, Blocking·Non-blocking 없음, `MERGE_READY: YES`
- 사용자 최종 승인 후 2026-08-19 squash merge
- 범위: MariaDB Testcontainers, 가상 좌석 fixture, 단건·동일 좌석·상이 좌석·복수 좌석 실패 기준선
- 제외: 운영 로직 수정, deadlock 해결, 취소·결제·인증·Frontend와 부하 테스트
- 관찰: 동일 좌석 8개 요청은 1개만 성공, 상이 좌석 8개는 모두 성공했으나 잔여 수량은 1만 감소, checked exception에서 첫 좌석·예약 부분 commit

### Backend Issue #1 — 문서·협업 절차 부트스트랩

- Issue: [#1](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/1)
- PR: [#2](https://github.com/SKUWooU/TicketOnBoarding_Be/pull/2)
- squash commit: `9b9b18405ce29a110cd67fcef6c923d5d9505d65`
- 상태: 완료
- Reviewer: 최신 검토 HEAD `485c071`, Blocking 없음, `MERGE_READY: YES`
- 사용자 최종 승인 후 2026-08-19 squash merge
- Frontend 변경과 외부 API·결제·운영 데이터 호출 없음

## 다음 후보

1. 복수 좌석 lock ordering과 deadlock 재현
2. `(concert_time_id, seat_number)` 복합·유일 인덱스 검증
3. Frontend: 별도 Issue로 Issue·PR template과 개선 문서 진입점 구성
4. 예약·결제·취소 멱등성과 상태 전이 Issue 분리
