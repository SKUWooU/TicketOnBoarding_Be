# 시나리오별 Hold 근거 판정

## 목적과 입력

`assess_controlled_hold_evidence`는 새 부하를 실행하지 않는 read-only MCP 도구다. `scenarioId`와 `batchId`를 명시해야 하며, 임의 파일명·경로·실행 명령은 받지 않는다.

| 시나리오 | 보관 배치 | 통제 조건 | 판정 단위 |
| --- | --- | --- | --- |
| `small-seat-repeat` | `smallrepeat186d` | 가상 20석, 인기 1석/70%, Hold 500ms, 5·10·20 RPS 교차 3회, 각 10초 | 9개 run의 완료·성공/409·해제·Hikari/DB 대기·최종 재고 |
| `large-seat-churn` | `churn172a`, `churn172b` | 가상 2,000석, 인기 20·40·200석/70%, Hold 100ms, 고정 50 또는 100 RPS 각 6회 | 기존 6-run manifest와 summary의 조건·상태 수렴 |

20석 결과는 생성된 `small-seat-hold-ramp.json`의 수치 필드만 버전 관리한다. JWT·쿠키·요청 본문·원시 k6 로그는 포함하지 않는다. 20석 판정은 9회 순서·목표 RPS·표본 간격·Hikari acquire 증가·Hold=Release·종료 재고 `20,0,0,20,0`·오류/누락/timeout/deadlock 0을 확인한다. 파일이나 필드가 빠지면 `INSUFFICIENT_EVIDENCE`, 완성된 측정에서 상태 게이트가 깨지면 `NOT_COMPARABLE`로 관측값을 숨긴다.

두 시나리오는 좌석 수, 집중도, Hold 지속 시간, RPS가 모두 다르다. MCP의 `COMPARABLE`은 **해당 배치 내부의 통제 근거가 완전하다**는 뜻이며, 20석과 2,000석의 p95 차이나 운영 처리량 개선을 뜻하지 않는다. DB row-lock wait는 전역 counter이므로 특정 SQL의 대기 원인으로 단정하지 않는다.

## 검증과 다음 단계

`tools/ticketon-evidence-mcp`에서 `npm.cmd test`로 TypeScript build, 보관 실측 두 시나리오, 누락·변조·경로 이탈·stdio 계약을 검사한다. 이 단계에서는 Docker, k6, PG·KOPIS 호출을 실행하지 않았다.

20석 20 RPS 반복에서는 dropped·예상 밖 오류·Hikari pending이 0이었지만 DB row-lock wait가 관측됐다. 다음 증량은 새 Issue에서 전용 DB·동일 fixture·짧은 시간·중단 게이트를 먼저 정하고 8GB 로컬의 CPU/메모리 여유를 확인한 뒤 결정한다. 여유가 없으면 16GB PC로 동일 조건을 옮겨 측정하며, 두 환경의 수치를 직접 전후 개선으로 비교하지 않는다.
