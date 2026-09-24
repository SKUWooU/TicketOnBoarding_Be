# 고경합 측정 근거 MCP 로컬 연동

## 목적

`ticketonEvidence`는 고경합 측정 JSON을 사람이 직접 열어 해석하던 과정을 보조하는 Codex 로컬 MCP 서버다. 서버는 저장소의 고정된 결과 루트와 allowlist summary만 읽으며, 외부 서비스·운영 데이터·실행 권한을 갖지 않는다.

## 등록 계약

저장소에서 의존성을 설치하고 빌드한 뒤, Codex CLI에 다음과 같이 등록한다.

```powershell
Set-Location D:\project2\TicketOnBoarding_Be\tools\ticketon-evidence-mcp
npm.cmd ci
npm.cmd run build
codex mcp add ticketonEvidence -- node D:\project2\TicketOnBoarding_Be\tools\ticketon-evidence-mcp\dist\index.js
codex mcp list
```

`codex mcp list`에서 `ticketonEvidence`가 `enabled`인지 확인한다. 이 등록은 사용자의 로컬 Codex 설정에만 적용되며, 저장소에 개인 설정이나 인증 정보는 커밋하지 않는다. 이미 열려 있는 Codex 세션에는 도구가 즉시 추가되지 않을 수 있으므로 새 로컬 세션에서 목록을 다시 확인한다.

## 조회 순서

1. `list_evidence_runs`로 실행 ID와 허용된 summary artifact를 확인한다.
2. `get_run_summary`로 목표 RPS·k6 결과·Hikari·MariaDB 관측 요약을 읽는다.
3. `verify_domain_invariants`로 `ValidMeasurement`, k6 종료, 최종 snapshot, 예상/실제 좌석 수를 확인한다.

질문의 예: “`i132-repeat-01`의 각 summary를 비교하기 전에 실행 목록과 r1의 도메인 불변식 결과를 보여줘. 결과는 로컬 fixture 근거로만 해석해.”

## 해석 경계

- `PASS`는 선택한 로컬 fixture summary의 필수 필드가 모두 충족됐다는 의미다.
- `INSUFFICIENT_EVIDENCE`는 누락된 필드를 성공으로 해석하지 않는 안전한 실패다.
- 결과는 실제 공연장 규모, 운영 SLA, 최대 처리량, 실제 PG/KOPIS 성능의 근거가 아니다.
- 서버는 DB·Docker·k6·KOPIS·PG·OAuth를 호출하거나 실행하지 않는다.

## 검증

- `npm.cmd test`: summary allowlist, 민감 키 제거, traversal·run/root junction 거부, BOM, 불변식 판정, stdio 지침·도구 주석 계약
- `codex mcp list`: 로컬 Codex 등록 상태
- stdio protocol smoke: 실제 로컬 `i132-repeat-01-r1-summary.json`에서 불변식 `PASS`
