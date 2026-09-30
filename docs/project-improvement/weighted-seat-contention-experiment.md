# 인기 좌석 집중도별 예약 경합 실험

## 기준선과 질문

기존 loadtest fixture는 한 회차의 가상 좌석 2,000개(50행 × 40석)를 10개 구역으로 나눈다. `distributed`는 전체 좌석을 순회하고, `hot-section`은 첫 행 40석, `hot-seat`는 첫 좌석 하나를 반복 선택한다. 이 세 시나리오만으로는 동일한 부하율에서 인기 좌석 집합의 크기와 요청 집중 비율을 따로 바꿔 비교하기 어렵다.

이번 추가는 `weighted-hotspot`의 인기 좌석 수, 해당 좌석으로 향하는 요청 비율, 선택 시드를 입력으로 받는다. 질문은 “같은 목표 RPS에서 요청이 일부 좌석에 집중되면 정상적인 좌석 충돌과 DB 대기 지표가 어떻게 달라지는가?”이다. 아직 이 질문의 측정 결과는 없다.

## 선택 규칙

- 인기 좌석은 현재 fixture의 좌석 번호 순서에서 앞쪽 `HotSeatCount`개다. VIP 등급이나 실제 공연장 좌석을 뜻하지 않는다.
- 각 k6 iteration의 고유 번호와 `SelectionSeed`를 해시해 인기 좌석 선택 여부와 좌석 인덱스를 결정한다. 동일 fixture·설정·시드·iteration은 동일 좌석을 선택한다.
- `HotRequestPercent`는 목표 비율이다. 실제 선택 건수와 비율은 k6 Counter로 별도 기록하며, 목표치와 정확히 같다고 가정하지 않는다.
- `HOT_SEAT_COUNT`는 1~2,000, `HOT_REQUEST_PERCENT`는 0~100, `SELECTION_SEED`는 부호 없는 32비트 정수다. 인기 좌석 집합이 전체 2,000석이면 비율은 100이어야 한다.
- 이 시나리오의 HTTP 409는 예상 가능한 좌석 충돌로 분리한다. 예상 밖 오류와 dropped iteration, 최종 재고 불변식은 별도로 확인한다.

## 실행 경로

Backend `loadtest` profile·로컬 MariaDB·k6가 실행 중일 때 `Measure-Contention.ps1`에서 다음처럼 호출한다. 스크립트는 기존처럼 fixture와 테스트 사용자를 준비하고, 측정 결과를 `load-test/results` 아래 고유 run ID로 저장한다.

```powershell
.\load-test\scripts\Measure-Contention.ps1 `
  -Scenario weighted-hotspot -RunId weighted-40-17 `
  -Rate 5 -DurationSeconds 10 `
  -HotSeatCount 40 -HotRequestPercent 70 -SelectionSeed 17
```

비교 실험은 목표 RPS·지속 시간·시드·fixture·DB 설정을 고정하고 `HotSeatCount`만 20→40→200으로 변경한다. 각 run에는 서로 다른 ID를 사용한다. 재시도·warm-up 제외 반복, k6 완료율과 생성 호스트·DB 자원 신호를 함께 기록한 뒤에만 병목에 관한 결론을 낸다. 동일 시드는 좌석 선택 규칙을 재현하지만, 동시 요청의 실제 완료 순서나 DB 대기 시간까지 동일하게 만들지는 않는다.

요약 JSON의 `K6.Result.WeightedHotspot`에는 `HotSeatCount`, `HotRequestPercent`, `Seed`, `HotSelections`, `ColdSelections`, `ActualHotSelectionPercent`가 남는다. 선택 건수 합계가 완료 iteration 수와 다르면 측정을 실패로 처리한다.

## 검증과 한계

- Node 계약 테스트: 동일 시드 재현, 다른 시드 변화, 좌석 범위, 집중 비율, 입력 경계값.
- k6 `inspect`: 새 시나리오 import·설정 구문 확인.
- 기존 PowerShell 측정 요약 계약 회귀 확인.
- 현재 로컬 Docker daemon과 Backend가 실행 중이지 않아 이번 단계에서 실제 DB 경합·p95 변화는 측정하지 않았다. 이 문서는 실험 설계이며 성능 개선 결과가 아니다.

현재 `Measure-Contention.ps1`의 기본 출력은 `load-test/results` 바로 아래에 있고, MCP의 조회 계약은 run ID 하위 디렉터리의 summary만 대상으로 한다. 따라서 이 Issue의 측정 요약을 MCP가 곧바로 조회할 수 있다고 주장하지 않는다. 실행 조건 검증·승인 및 k6 실행 도구도 이번 범위에 포함하지 않는다. 반복 측정이 쌓인 뒤 결과 경로 계약과 비교 가능성 판정을 별도 Issue에서 검토한다.
