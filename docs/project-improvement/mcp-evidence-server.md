# 고경합 측정 근거 MCP

## 목적

고경합 예약을 설명할 때 모델이 원시 로그나 추측 대신, 저장소에 남은 로컬 fixture 측정 요약을 읽도록 한다. `tools/ticketon-evidence-mcp`는 stdio 기반의 읽기 전용 MCP 서버다.

## 경계

허용:

- `load-test/results/<runId>/*-summary.json`의 목록·요약·불변식 판정
- 저장소 내부의 고정 결과 루트 사용(실행 인자로 다른 경로 지정 불가)
- JWT, cookie, authorization, token, password, secret 및 request/response body 키 제거
- run ID와 artifact 이름의 allowlist·실제 경로(`realpath`) 검증, 결과 루트 자체의 Windows junction 거부로 traversal 우회 차단

제외:

- KOPIS·PG·OAuth·운영 DB 호출
- k6 실행, Docker 제어, DB 변경
- CSV·stdout·stderr·원시 요청 데이터 노출
- 결과를 실제 공연장 또는 운영 성능으로 해석

## 도구별 해석

| 도구 | 답하는 질문 | 한계 |
| --- | --- | --- |
| `list_evidence_runs` | 어떤 로컬 실행을 조회할 수 있는가 | 실행 성공 여부를 보장하지 않음 |
| `get_run_summary` | 해당 실행의 k6·Hikari·DB 관측 요약은 무엇인가 | summary에 기록된 값만 반환 |
| `verify_domain_invariants` | 종료 시 좌석 수·상태 불변식이 충족됐는가 | `PASS`는 fixture 요약의 검증 결과일 뿐 운영 보증이 아님 |

## 사용 전제

결과 디렉터리는 측정 스크립트가 만든 로컬 산출물이며 Git에서 제외된다. 도구의 `PASS`는 `ValidMeasurement`, k6 exit code, `FinalSnapshot.invariantSatisfied`, 예상/실제 좌석 수를 모두 확인할 수 있을 때만 나온다. 필드가 없으면 `INSUFFICIENT_EVIDENCE`로 반환해 근거 부족을 성공으로 바꾸지 않는다.

## 도입 판단

현재는 사람이 문서와 JSON을 직접 대조하던 반복 작업을 줄이기 위한 읽기 모델이다. 결과 비교, 시나리오 실행, 운영 관측 연동은 반복적인 분석 수요·권한 경계·실행 승인 조건이 확인된 뒤 별도 ADR로 검토한다.
