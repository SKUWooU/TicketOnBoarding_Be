# 작업 진행 기록

이 문서는 완료·진행 중인 Issue와 검증 상태를 기록하는 단일 진행 상태 원본입니다. 상세 문제와 결정은 연결된 Issue, PR, EVIDENCE_MAP과 ADR에서 확인합니다.

## 저장소 기준선

| 구분 | 저장소 | 기준 Branch | 조사 기준 commit |
| --- | --- | --- | --- |
| Backend | [TicketOnBoarding_Be](https://github.com/SKUWooU/TicketOnBoarding_Be) | `main` | `bcfa7e30c947975e1f7f89d5eef0e8bffe36b9a1` |
| Frontend | [TicketOnBoarding_Fe](https://github.com/SKUWooU/TicketOnBoarding_Fe) | `main` | `1f9678be7a3a66ec610c6ef4ea335e9d6f5cbafd` |

두 저장소는 독립된 Issue와 PR을 사용합니다. 교차 변경은 각 작업의 링크를 양쪽 Issue 또는 PR에 남깁니다.

## 진행 중

### Backend Issue #3 — 예매 트랜잭션·경합 기준선

- Issue: [#3](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/3)
- PR: [#4](https://github.com/SKUWooU/TicketOnBoarding_Be/pull/4)
- Branch: `test/#3-reservation-concurrency-baseline`
- 상태: PR 생성, Reviewer 검토 대기
- 계획 승인: 완료
- 구현: 완료
- 검증: 시나리오 유효 실행 1회 + 동일 조건 3회 반복 통과, 전체 `test` 6개 통과, `git diff --check` 통과
- Reviewer: PR #4 최신 HEAD 검토 대기
- 사용자 merge 승인: 대기
- 범위: MariaDB Testcontainers, 가상 좌석 fixture, 단건·동일 좌석·상이 좌석·복수 좌석 실패 기준선
- 제외: 운영 로직 수정, deadlock 해결, 취소·결제·인증·Frontend와 부하 테스트
- 관찰: 동일 좌석 8개 요청은 1개만 성공, 상이 좌석 8개는 모두 성공했으나 잔여 수량은 1만 감소, checked exception에서 첫 좌석·예약 부분 commit

## 완료

### Backend Issue #1 — 문서·협업 절차 부트스트랩

- Issue: [#1](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/1)
- PR: [#2](https://github.com/SKUWooU/TicketOnBoarding_Be/pull/2)
- squash commit: `9b9b18405ce29a110cd67fcef6c923d5d9505d65`
- 상태: 완료
- Reviewer: 최신 검토 HEAD `485c071`, Blocking 없음, `MERGE_READY: YES`
- 사용자 최종 승인 후 2026-08-19 squash merge
- Frontend 변경과 외부 API·결제·운영 데이터 호출 없음

## 다음 후보

1. Issue #3 결과에 따른 rollback·잔여 좌석 집계 개선
2. 복수 좌석 lock ordering과 deadlock 재현
3. Frontend: 별도 Issue로 Issue·PR template과 개선 문서 진입점 구성
4. 예약·결제·취소 멱등성과 상태 전이 Issue 분리
