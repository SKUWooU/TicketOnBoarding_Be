# 인기 좌석 Hold 경합 배치의 MCP 근거 판정

## 문제와 범위

Issue #172의 로컬 가상 좌석 측정은 50·100 RPS 배치마다 manifest 1개와 summary 6개로 나뉜다. 파일 하나의 p95나 409 수만 읽으면 Hold→Release가 실제로 수렴했는지, 다른 RPS·반복 조건을 섞었는지 알 수 없다. 기존 MCP의 `get_run_summary`는 단일 파일 조회, `compare_controlled_hotspot_runs`는 예약 생성 경합용 두 파일 비교에 한정된다.

Issue #174는 서버에 `assess_seat_hold_churn_batch(batchId)`를 추가한다. 도구는 고정 결과 루트의 `controlled-seat-hold-churn-manifest.json`과 예상한 6개 summary만 읽으며, k6·Docker·DB·외부 API 실행 권한은 갖지 않는다. 원시 로그·임의 경로·JWT는 반환하지 않는다.

## 판정 계약

- `INSUFFICIENT_EVIDENCE`: manifest·summary가 없거나 6-run 순서·멤버십·필수 필드가 맞지 않음. 비교 수치 없음.
- `NOT_COMPARABLE`: 측정 도착률, dropped·예상 밖 오류·deadlock, Hold/Release 서버 전이, 최종 HELD=0 또는 배치 내 RPS·시드·VU 조건이 맞지 않음. 비교 수치 없음.
- `COMPARABLE`: 동일 배치의 20→40→200→200→40→20석 교차 순서, 같은 RPS·10초·100ms 점유, 2,000석 최종 재고와 6회 수렴을 확인. 각 run의 성공·예상 409·성공/충돌 p95·Hikari pending peak·DB 전역 row-lock wait만 반환.

실제 `churn172a`는 50 RPS, `churn172b`는 100 RPS로 각각 `COMPARABLE`이다. 두 배치를 서로 직접 비교하거나 p95 차이를 최적화 효과로 판정하는 도구는 아니다. 100 RPS·200석 성공 p95는 반복 1·2에서 55.25ms와 139.00ms로 달랐다. 각 조건 2회, warm-up 없음, 별도 DB·공유 호스트 부하의 한계가 남는다. DB row-lock wait도 전역 counter여서 특정 SQL 원인으로 단정하지 않는다. [측정 조건과 12회 원자료](weighted-seat-hold-churn-evidence.md)를 함께 확인한다.

## 검증

`tools/ticketon-evidence-mcp`에서 `npm test`를 실행한다. 실제 두 배치의 판정, manifest 멤버십·순서 위조, Hold/Release·최종 점유 불일치, dropped·RPS 혼합, 경로 이탈, stdio MCP 호출을 확인한다. 이 판정은 이미 저장된 근거를 안전하게 해석하기 위한 것이며 추가 부하 측정이나 운영 성능 보증이 아니다.
