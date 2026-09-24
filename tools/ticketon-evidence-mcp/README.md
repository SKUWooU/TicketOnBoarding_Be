# TicketOnBoarding Evidence MCP

로컬 `load-test/results`의 **허용된 `*-summary.json` 측정 결과만 읽는** stdio MCP 서버입니다. 원시 k6 로그, CSV, JWT·쿠키·요청 본문 및 운영 데이터에는 접근하지 않습니다.

## 제공 도구

- `list_evidence_runs`: 실행 ID와 조회 가능한 summary artifact 목록
- `get_run_summary`: 민감 필드를 제거한 단일 측정 요약
- `verify_domain_invariants`: 측정 유효성, k6 종료 상태, 좌석 수 및 최종 상태 불변식 확인

`verify_domain_invariants`의 `PASS`는 선택한 로컬 fixture 요약의 불변식만 뜻합니다. 실제 공연장이나 운영 환경의 성능·정합성을 보장하지 않습니다.

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
codex mcp add ticketon-evidence -- node D:\project2\TicketOnBoarding_Be\tools\ticketon-evidence-mcp\dist\index.js
```

등록 후에는 이 서버의 세 도구로 측정 결과를 근거로 조회할 수 있습니다. 실행·DB 변경·외부 API 호출 도구는 의도적으로 제공하지 않습니다.
