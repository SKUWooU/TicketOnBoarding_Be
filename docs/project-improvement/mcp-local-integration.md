# 고경합 측정 근거 MCP 로컬 연동

## 목적

`ticketonEvidence`는 고경합 측정 JSON을 사람이 직접 열어 해석하던 과정을 보조하는 Codex 로컬 MCP 서버다. 서버는 저장소의 고정된 결과 루트와 allowlist summary를 읽고, 통제 배치 비교 시 고정 manifest를 검증한다. 외부 서비스·운영 데이터·실행 권한을 갖지 않는다.

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
4. 통제된 인기 좌석 배치라면 `compare_controlled_hotspot_runs`로 두 summary의 조건·manifest 멤버십을 검사한 후 관측 차이를 읽는다.
5. Hold→Release 지속 경합 배치라면 `assess_seat_hold_churn_batch`로 6개 summary의 조건·상태 수렴을 함께 검사한다. [판정 계약과 한계](mcp-seat-hold-churn-assessment.md)

질문의 예: “`i132-repeat-01`의 각 summary를 비교하기 전에 실행 목록과 r1의 도메인 불변식 결과를 보여줘. 결과는 로컬 fixture 근거로만 해석해.”

비교 예: `batchId=c166-repeat`, `firstArtifact=c166-repeat-r1-h20-summary.json`, `secondArtifact=c166-repeat-r2-h20-summary.json`. 두 run 모두 성공 159건, 성공 p95 76.165ms·77.687ms로 `SAME_CONDITION_REPEAT`다. 첫 run을 `c166-repeat-r1-h200-summary.json`과 비교하면 인기 좌석 수 20→200만 조건 차이로 허용하고 성공 159→307건, 예상 좌석 충돌 342→193건을 `HOT_SEAT_COUNT_ONLY` 관측값으로 제공한다. p95 차이를 개선이라고 판정하지 않는다.

비교 도구는 고정된 `controlled-hotspot-manifest.json`의 완료·전용 프로젝트·fixture 격리·두 summary 멤버십을 확인한다. 각 run의 물리 좌석 2,000행·초기 재고 2,000·최종 불변식·예상 밖 응답/누락/deadlock 0·digest 관측률을 검사하고, 목표 RPS·지속 시간·집중 비율·시드·VU 설정이 맞아야 한다. 목표 도착 건수 대비 완료율 99% 미만이나 raw SQL 실행 건수·관측률·예약 성공 건수 불일치도 차단한다. 근거 누락은 `INSUFFICIENT_EVIDENCE`, 잘못된 측정 또는 조건 불일치는 `NOT_COMPARABLE`이며 양쪽 모두 비교 수치를 내지 않는다. 같은 배치여도 DB cache·호스트 부하 통제까지 증명하지 못한다.

## 해석 경계

- `PASS`는 선택한 로컬 fixture summary의 필수 필드가 모두 충족됐다는 의미다.
- `INSUFFICIENT_EVIDENCE`는 누락된 필드를 성공으로 해석하지 않는 안전한 실패다.
- 결과는 실제 공연장 규모, 운영 SLA, 최대 처리량, 실제 PG/KOPIS 성능의 근거가 아니다.
- 서버는 DB·Docker·k6·KOPIS·PG·OAuth를 호출하거나 실행하지 않는다.
- 비교 가능성은 통제된 summary 계약의 판정이다. InnoDB row-lock wait의 원인 SQL, 운영 처리량, 통계적 유의성을 자동 추론하지 않는다.

## 검증

- `npm.cmd test`: summary allowlist, 민감 키 제거, traversal·run/root junction 거부, BOM, 불변식 판정, stdio 지침·도구 주석 계약
- `codex mcp list`: 로컬 Codex 등록 상태
- stdio protocol smoke: 실제 로컬 `i132-repeat-01-r1-summary.json`에서 불변식 `PASS`
- `npm.cmd test`: 실제 `c166-repeat` 같은 조건/인기 좌석 수 단일 변수 비교, manifest·재고·관측 품질 실패, stdio 비교 호출 계약
