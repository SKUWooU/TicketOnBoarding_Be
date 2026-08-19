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
