# TicketOnBoarding Evidence MCP

로컬 `load-test/results`의 **허용된 `*-summary.json` 측정 결과와 고정된 통제 실험 manifest만 읽는** stdio MCP 서버입니다. manifest는 실험 조건·완료·재고 검증에만 사용합니다. 원시 k6 로그, CSV, JWT·쿠키·요청 본문 및 운영 데이터에는 접근하지 않습니다.

## 제공 도구

- `list_evidence_runs`: 실행 ID와 조회 가능한 summary artifact 목록
- `get_run_summary`: 민감 필드를 제거한 단일 측정 요약
- `verify_domain_invariants`: 측정 유효성, k6 종료 상태, 좌석 수 및 최종 상태 불변식 확인
- `compare_controlled_hotspot_runs`: 같은 통제 배치의 두 summary가 비교 가능한지 판정하고, 통과할 때만 성공·좌석 충돌의 관측 차이 제공
- `assess_seat_hold_churn_batch`: Hold→Release 6-run 배치의 조건·상태 수렴을 검증하고 같은 RPS 내 관측값만 제공
- `assess_controlled_hold_evidence`: `small-seat-repeat`(20석·9회) 또는 `large-seat-churn`(2,000석·6회)을 명시해 각각의 저장된 배치를 검증. 조건과 재고 게이트를 통과한 해당 시나리오의 관측값만 제공하며 시나리오 사이 p95 비교는 제공하지 않음
- `list_controlled_experiments`: 고정된 Hold→Release 50/100 RPS 실험 조건 목록
- `preflight_controlled_experiment`: 새 배치 ID와 전용 Compose 프로젝트를 받아 기존 runner의 `-CheckOnly`로 로컬 준비 상태만 확인. 부하·fixture·결과 디렉터리를 생성하지 않음

`verify_domain_invariants`의 `PASS`는 선택한 로컬 fixture 요약의 불변식만 뜻합니다. 실제 공연장이나 운영 환경의 성능·정합성을 보장하지 않습니다.

비교 예: `batchId=c166-repeat`, `firstArtifact=c166-repeat-r1-h20-summary.json`, `secondArtifact=c166-repeat-r2-h20-summary.json`. 조건이 같으면 `SAME_CONDITION_REPEAT`, 인기 좌석 수만 다르면 `HOT_SEAT_COUNT_ONLY`로 표시합니다. manifest·초기 재고·재고 불변식·dropped iteration·SQL digest 관측 조건이 빠지거나 맞지 않으면 비교 수치를 제공하지 않습니다. 목표 RPS × 지속 시간 대비 완료 건수가 99% 미만이거나, raw SQL 실행 건수와 기록된 관측률·예약 성공 건수가 불일치해도 비교를 차단합니다. 0 RPS·0 완료 건수처럼 유효한 부하 측정이 아닌 요약에서도 p95 차이를 제공하지 않습니다. `COMPARABLE`은 인과적 성능 개선 판정이 아닙니다.

## 실행

```powershell
npm install
npm test
npm start
```

결과 경로는 저장소의 `load-test/results`로 고정됩니다.

경로는 의도적으로 고정되어 있습니다. 임의의 디렉터리나 원시 로그를 MCP의 입력으로 지정할 수 없도록 하여, 이 도구는 저장소의 허용된 측정 summary만 읽습니다.

## Codex MCP 등록 예시

빌드 후 사용자의 Codex 로컬 설정에서 아래 명령을 등록합니다. 이 저장소에는 개인 전역 설정을 커밋하지 않습니다.

```powershell
codex mcp add ticketonEvidence -- node D:\project2\TicketOnBoarding_Be\tools\ticketon-evidence-mcp\dist\index.js
codex mcp list
```

등록 후 새 Codex 로컬 세션에서 측정 결과 조회와 고정 실험의 사전 점검을 사용할 수 있습니다. 사전 점검은 Windows PowerShell, 별도 빈 MariaDB, 그 DB를 바라보는 `loadtest` Backend와 18080 loopback 연결이 준비된 경우에만 `READY`를 반환합니다. 실패 원인의 원시 stderr·DB 연결 정보는 MCP에 반환하지 않습니다. `READY`는 **실행 승인이나 성능 보증이 아닙니다**. 부하 실행·DB 변경·외부 API 호출 도구는 제공하지 않습니다. 결과 해석 범위와 한계는 [실험 요약](../../docs/project-improvement/EXPERIMENTS.md)을 확인합니다.
