# 작업 진행 기록

이 문서는 완료·진행 중인 Issue와 검증 상태를 기록하는 단일 진행 상태 원본입니다. 상세 문제와 결정은 연결된 Issue, PR, EVIDENCE_MAP과 ADR에서 확인합니다.

## 저장소 기준선

| 구분 | 저장소 | 기준 Branch | 조사 기준 commit |
| --- | --- | --- | --- |
| Backend | [TicketOnBoarding_Be](https://github.com/SKUWooU/TicketOnBoarding_Be) | `main` | `bcfa7e30c947975e1f7f89d5eef0e8bffe36b9a1` |
| Frontend | [TicketOnBoarding_Fe](https://github.com/SKUWooU/TicketOnBoarding_Fe) | `main` | `1f9678be7a3a66ec610c6ef4ea335e9d6f5cbafd` |

두 저장소는 독립된 Issue와 PR을 사용합니다. 교차 변경은 각 작업의 링크를 양쪽 Issue 또는 PR에 남깁니다.

## 진행 중

### Backend Issue #1 — 문서·협업 절차 부트스트랩

- Issue: [#1](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/1)
- PR: [#2](https://github.com/SKUWooU/TicketOnBoarding_Be/pull/2)
- Branch: `docs/#1-improvement-baseline`
- 상태: PR 생성, Reviewer 대기
- 계획 승인: 완료
- 구현: 완료
- 검증: Markdown 필수 항목·상대 링크·변경 범위·`git diff --check` 확인 완료
- Reviewer: 별도 Reviewer 검토 대기
- 사용자 merge 승인: 대기
- 범위: Template, workflow, 기준선, backlog와 근거 문서 진입점
- 제외: 애플리케이션 코드, Frontend 파일, 외부 API, 부하 테스트와 성능 개선

## 완료

아직 병합 완료된 개선 Issue가 없습니다.

## 다음 후보

1. Backend: MySQL Testcontainers와 명시적 가상 좌석 fixture로 기존 비관적 잠금 및 잔여 좌석 정합성 기준선 검증
2. Frontend: 별도 Issue로 Issue·PR template과 개선 문서 진입점 구성
3. 재현 결과에 따라 잔여 좌석 집계, 복수 좌석 deadlock, 멱등성과 상태 전이 Issue 분리
