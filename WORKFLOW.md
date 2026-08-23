# 작업 절차

## 기본 흐름

모든 변경은 다음 순서를 따릅니다.

1. `main` 최신화와 작업 트리 확인
2. 열린 Issue·PR·Branch 및 관련 문서 조사
3. Markdown body-file로 Issue 생성
4. Issue 번호가 포함된 Branch 생성
5. 기존 코드와 재현 근거 조사
6. 작업 범위, 제외 범위, 검증 방법을 포함한 계획 작성
7. 사용자 계획 승인
8. `WORK_PROGRESS.md`에 시작 상태 기록 후 구현
9. 테스트·측정·문서 검증
10. Markdown body-file로 PR 생성
11. 별도 Reviewer Agent 검토
12. Blocking 수정 및 재검토
13. Reviewer가 검토한 40자리 `REVIEWED_HEAD`와 `MERGE_READY: YES` 기록
14. GitHub Actions가 최신 HEAD·Backend CI·PR 경계를 검증하고 squash merge한 뒤 같은 저장소의 열린 연결 Issue 종료
15. `WORK_PROGRESS.md`, 근거 문서와 `main` 갱신
16. 로컬·원격 작업 Branch 정리
17. 다음 Issue 후보 제안

조사와 계획을 승인받기 전에는 저장소 파일을 구현하지 않습니다. 사용자는 Reviewer gate를 통과한 PR의 squash merge를 상시 승인했습니다. 따라서 기술 검토와 CI가 통과하면 Issue마다 최종 승인을 다시 묻지 않습니다.

## Issue와 PR

- Issue·PR 본문은 Markdown 파일로 작성하고 `--body-file`로 전달합니다.
- Issue 하나의 결과는 최종적으로 squash commit 하나로 정리합니다.
- Issue·PR 본문은 목적, 범위, 작은 작업 항목, 완료 기준, 테스트와 참고 링크만 간결하게 기록합니다.
- 상세한 전후 코드, 측정 조건, 결과와 한계는 `docs/project-improvement`에 기록하고 Issue·PR에서는 해당 문서를 연결합니다.
- Reviewer에게 전달할 검토 요청 문구와 확인 항목은 PR 본문에 복제하지 않습니다. Reviewer의 최종 판단만 PR comment로 남깁니다.
- Issue 범위를 바꿀 때에는 먼저 Issue body-file을 고치고 GitHub 본문을 갱신합니다.
- Backend와 Frontend는 각각 독립된 Issue·Branch·PR을 사용하고 관련 작업을 상호 링크합니다.

README는 매 Issue의 작업 일지로 수정하지 않습니다. 실제 코드와 설명이 달라졌거나 Phase 단위의 기능·실행 방법·문서 진입점을 정리할 시점에 묶어서 갱신합니다.

첫 부트스트랩 Issue에서는 `WORK_PROGRESS.md` 자체가 존재하지 않습니다. 이 경우 승인 후 작업 Branch에서 파일을 만들고 최초 항목에 작업 시작 상태를 기록합니다.

## Branch와 Commit

- 기본 Branch: `main`
- 권장 Branch: `<type>/#<issue-number>-<short-description>`
- Commit: `[TYPE/#Issue번호] 내용`
- 기존 변경을 임의로 되돌리지 않습니다.
- 작업 전후 `git status`와 diff를 확인합니다.

## 승인 경계

다음 작업은 auto-merge 상시 승인에서 제외하며 사용자에게 먼저 알립니다.

- 외부 API 호출
- 결제 승인·취소·환불
- 운영 데이터 접근 또는 변경
- 비용이 발생할 수 있는 작업
- 새로운 인프라 또는 외부 서비스 도입
- 배포 실행 또는 배포 환경·파이프라인 변경
- 파괴적이거나 복구하기 어려운 작업
- Reviewer가 확인한 범위를 벗어난 변경
- 테스트·CI 실패, merge conflict 또는 여러 설계안 중 사용자 선택이 필요한 작업

## 구현 판단

모든 후보에 대해 “지금 구현하면 과한가?”를 먼저 판단합니다.

1. 문제를 현재 코드에서 재현합니다.
2. fixture, 환경과 측정 조건을 고정합니다.
3. 개선 전 실패 양상이나 기준선을 기록합니다.
4. 가장 작은 해결책부터 비교합니다.
5. 같은 조건으로 개선 후를 검증합니다.
6. 결과를 적용 범위 밖으로 일반화하지 않습니다.

대기열, outbox, 메시지 브로커, 분산 락은 재현된 문제와 단순한 대안의 한계가 확인된 경우에만 ADR로 검토합니다.

## Reviewer

Reviewer Agent는 구현하지 않고 Issue와 PR diff만 검토합니다. PR comment는 다음 제목과 결론을 사용합니다.

```markdown
## AI Reviewer 검토 결과

### Blocking

### Non-blocking

REVIEWED_HEAD: <40자리 commit SHA>
MERGE_READY: YES/NO
```

주요 검토 대상은 Issue 범위, 데이터 정합성, transaction·lock·deadlock·멱등성, 인증·인가, 예외와 rollback, 경쟁 조건 테스트, 측정 표현과 과도한 기술 도입 여부입니다.

`MERGE_READY: YES` comment는 허용된 Reviewer가 작성해야 하며 `REVIEWED_HEAD`는 PR의 현재 HEAD와 정확히 일치해야 합니다. Backend CI의 `Backend test`가 성공하지 않았거나 CI 대기 중 HEAD가 바뀌면 auto-merge하지 않습니다. Blocking 수정으로 HEAD가 바뀐 경우 Reviewer는 새 HEAD를 다시 검토합니다. merge 후에는 PR의 `closingIssuesReferences` 중 같은 저장소에 속한 열린 Issue만 명시적으로 종료하며, 연결 Issue가 없거나 이미 닫혔다면 건너뜁니다.

GitHub Actions는 별도 Reviewer 채팅이나 Implementer 채팅을 호출하지 못합니다. 자동 병합 결과는 PR comment로 남기며, Implementer가 다음 활성 turn에서 main 최신화·로컬 branch 정리·다음 Issue 제안을 이어갑니다.
