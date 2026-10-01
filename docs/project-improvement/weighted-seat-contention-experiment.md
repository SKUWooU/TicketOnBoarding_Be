# 인기 좌석 집중도별 예약 경합 실험

## 기준선과 질문

기존 loadtest fixture는 한 회차의 가상 좌석 2,000개(50행 × 40석)를 10개 구역으로 나눈다. `distributed`는 전체 좌석을 순회하고, `hot-section`은 첫 행 40석, `hot-seat`는 첫 좌석 하나를 반복 선택한다. 이 세 시나리오만으로는 동일한 부하율에서 인기 좌석 집합의 크기와 요청 집중 비율을 따로 바꿔 비교하기 어렵다.

`weighted-hotspot`은 인기 좌석 수, 해당 좌석으로 향하는 요청 비율, 선택 시드를 입력으로 받는다. 질문은 “같은 목표 RPS에서 요청이 일부 좌석에 집중되면 정상적인 좌석 충돌과 DB 대기 지표가 어떻게 달라지는가?”이다. 아래 로컬 fixture 실측은 이 질문의 첫 기준선이며, 성능 개선 전후 비교는 아니다.

## 선택 규칙

- 인기 좌석은 현재 fixture의 좌석 번호 순서에서 앞쪽 `HotSeatCount`개다. VIP 등급이나 실제 공연장 좌석을 뜻하지 않는다.
- 각 k6 iteration의 고유 번호와 `SelectionSeed`를 해시해 인기 좌석 선택 여부와 좌석 인덱스를 결정한다. 동일 fixture·설정·시드·iteration은 동일 좌석을 선택한다.
- `HotRequestPercent`는 목표 비율이다. 실제 선택 건수와 비율은 k6 Counter로 별도 기록하며, 목표치와 정확히 같다고 가정하지 않는다.
- `HOT_SEAT_COUNT`는 1~2,000, `HOT_REQUEST_PERCENT`는 0~100, `SELECTION_SEED`는 부호 없는 32비트 정수다. 인기 좌석 집합이 전체 2,000석이면 비율은 100이어야 한다.
- HTTP 409 중 `X-Reservation-Conflict: SEAT_UNAVAILABLE` 응답만 별도 Counter에서 예상 가능한 좌석 충돌로 분리한다. 결제 ID·멱등성 충돌 등 코드가 없는 409는 예상 밖 오류로 집계한다. k6 기본 `http_req_failed`는 모든 409를 실패로 유지하므로 도메인 경합 판단에는 별도 Counter를 사용한다. dropped iteration과 최종 재고 불변식도 별도로 확인한다.

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
- Issue #162에서는 Docker daemon과 Backend가 꺼져 있어 실제 DB 경합을 측정하지 못했다. Issue #164에서 격리된 로컬 DB·Mock PG로 첫 측정을 수행했다. 아래 결과는 가상 fixture의 로컬 기준선이지 운영 예매 성능이 아니다.

현재 `Measure-Contention.ps1`의 기본 출력은 `load-test/results` 바로 아래에 있고, MCP의 조회 계약은 run ID 하위 디렉터리의 summary만 대상으로 한다. 따라서 이 Issue의 측정 요약을 MCP가 곧바로 조회할 수 있다고 주장하지 않는다. 실행 조건 검증·승인 및 k6 실행 도구도 이번 범위에 포함하지 않는다. 반복 측정이 쌓인 뒤 결과 경로 계약과 비교 가능성 판정을 별도 Issue에서 검토한다.

## Issue #164 — 로컬 측정 기준선 (2026-10-01)

### 조건과 원시 근거

- Windows 로컬 단일 Backend(Java 21·Spring Boot 3.2.5), 4코어/8스레드 i5-10210U, 호스트 메모리 약 15.8 GiB. Docker Desktop 25.0.2의 격리 Compose 프로젝트 `ticketon-weighted164`에서 MariaDB 10.11.8을 사용했다. 기존 프로젝트 볼륨은 건드리지 않았다.
- `local,loadtest` 프로필, Hikari 최대 24, 로컬 Mock PG, 회차당 가상 2,000석(50행×40석). k6 v2.1.0 `constant-arrival-rate`, 집중 목표 70%, 시드 17, 측정 20초. KOPIS·실 PG·운영 데이터 호출은 없다.
- 각 run은 새 `runId`와 회차·좌석 2,000행을 생성한다. 같은 DB 인스턴스를 쓰되 물리 seat 테이블은 run마다 커져 마지막에는 30,000행이 됐다. run 사이에 DB cache·호스트 상태도 동일하게 초기화하지 않았다. 따라서 결과는 집중도 변화만의 인과 효과로 단정할 수 없다.
- 20 RPS와 100 RPS의 첫 세트는 MariaDB Docker CPU·메모리 표본을 수집했다. Docker stats 호출로 실제 최대 표본 간격이 약 5~8초까지 늘어 CPU peak는 참고값이다. 100 RPS 역순 반복과 50 RPS는 Docker stats를 끄고 Hikari·DB 상태를 수집했다.
- 다음 표의 run ID는 추적된 `load-test/results/<runId>-summary.json` 원시 요약과 대응한다. stdout·stderr·CSV는 로컬에만 남기고 공개 저장소에 올리지 않았다. 요약 JSON에서 token·비밀번호·사용자 식별자를 검사해 포함되지 않음을 확인했다.

| 목표 RPS | 인기 좌석 | run ID | 완료 / dropped | 예약 성공 / 예상 좌석 충돌 | 전체 p95(ms) | DB row lock wait / 누적 대기(ms) |
| ---: | ---: | --- | ---: | ---: | ---: | ---: |
| 20 | 20 | [w164-r20-h20-a](../../load-test/results/w164-r20-h20-a-summary.json) | 400 / 0 | 134 / 266 | 53.59 | 0 / 0 |
| 20 | 40 | [w164-r20-h40-a](../../load-test/results/w164-r20-h40-a-summary.json) | 401 / 0 | 157 / 244 | 40.91 | 0 / 0 |
| 20 | 200 | [w164-r20-h200-a](../../load-test/results/w164-r20-h200-a-summary.json) | 401 / 0 | 270 / 131 | 40.65 | 0 / 0 |
| 100 | 20 | [w164-r100-h20-a](../../load-test/results/w164-r100-h20-a-summary.json) | 2,001 / 0 | 509 / 1,492 | 72.84 | 29 / 140 |
| 100 | 40 | [w164-r100-h40-a](../../load-test/results/w164-r100-h40-a-summary.json) | 1,984 / 17 | 531 / 1,453 | 73.41 | 24 / 247 |
| 100 | 200 | [w164-r100-h200-a](../../load-test/results/w164-r100-h200-a-summary.json) | 1,999 / 2 | 690 / 1,309 | 144.75 | 164 / 3,035 |
| 100 | 200 | [w164-r100-h200-b](../../load-test/results/w164-r100-h200-b-summary.json) | 1,995 / 6 | 690 / 1,305 | 177.30 | 153 / 5,537 |
| 100 | 40 | [w164-r100-h40-b](../../load-test/results/w164-r100-h40-b-summary.json) | 2,001 / 0 | 534 / 1,467 | 120.64 | 111 / 3,063 |
| 100 | 20 | [w164-r100-h20-b](../../load-test/results/w164-r100-h20-b-summary.json) | 2,001 / 0 | 509 / 1,492 | 121.79 | 95 / 1,102 |

각 run의 `ValidMeasurement=true`, 최종 재고 불변식 정상, 예상 밖 응답 0건, Hikari pending peak 0을 확인했다. 단, `ValidMeasurement`는 dropped iteration 0을 요구하지 않는다. 특히 100 RPS의 40·200석 일부 run은 목표 도착률을 완전히 달성하지 못했으므로 이 표의 p95를 같은 처리량의 엄격한 비교값처럼 읽지 않는다. `DbRowLockWaits`와 `DbRowLockTimeMs`는 MariaDB 전역 counter의 run 전후 차이이며 특정 SQL·좌석 행의 대기라고 단정하지 않는다.

### 성공 쓰기와 409의 지연 분리

전체 `reservation_duration`은 예약 성공과 이미 팔린 좌석의 409를 함께 포함한다. 인기 좌석이 좁으면 빠른 409 비중이 커져 전체 p95가 낮아 보일 수 있으므로, 결과별 Trend를 추가했다. 계측 변경 후 Docker stats 없이 같은 50 RPS·20초·시드 17로 수집한 값은 다음과 같다.

| 인기 좌석 | run ID | 완료 / dropped | 성공 / 좌석 충돌 | 전체 p95 | 성공 p95 | 좌석 충돌 p95 | DB row lock wait |
| ---: | --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 20 | [w164-r50-h20-c](../../load-test/results/w164-r50-h20-c-summary.json) | 1,001 / 0 | 281 / 720 | 60.0ms | 74.6ms | 53.2ms | 3 |
| 40 | [w164-r50-h40-c](../../load-test/results/w164-r50-h40-c-summary.json) | 1,000 / 0 | 306 / 694 | 61.5ms | 73.2ms | 52.7ms | 1 |
| 200 | [w164-r50-h200-c](../../load-test/results/w164-r50-h200-c-summary.json) | 999 / 0 | 462 / 537 | 74.3ms | 83.9ms | 65.8ms | 5 |

세 run 모두 예상 밖 오류 0, 재고 불변식 정상, Hikari pending peak 0이다. 이 부하에서는 인기 좌석 집합이 넓어질수록 성공한 쓰기가 늘고 정상 충돌은 줄었다. 성공 p95는 200석에서 다소 높았지만 단일 세트·누적 fixture·공유 호스트 조건이므로 성능 회귀나 병목 원인으로 확정하지 않는다.

### 측정 장애와 해석 경계

- 첫 5 RPS smoke(`w164-smoke5-20261001`)는 k6 예약 실행과 재고 불변식은 정상이었지만 요약 생성 시 측정 샘플에 필요한 Docker stats 필드가 없어 `ValidMeasurement=false`로 종료됐다. 이 실패 run은 성능 근거에서 제외했다. 샘플 필드 계약을 수정하고 PowerShell 90 assertions 및 새 로컬 smoke(`w164-collector-regression`)로 재검증했다.
- 20 RPS에서는 DB row lock wait가 0이었다. 100 RPS에서는 두 순서의 반복에서 200석 run이 각각 164·153회로, 20석 run의 29·95회보다 높았다. 이는 200석에서 성공 쓰기가 더 많았던 결과와 함께 관측된 신호다. 특정 좌석 잠금인지 회차 잔여 수량 UPDATE의 경합인지는 이 전역 counter만으로 판별할 수 없다.
- 100 RPS의 200석 p95는 144.75ms와 177.30ms로 차이가 있고, 20석도 72.84ms와 121.79ms로 흔들렸다. 단일 로컬 호스트의 부하 발생기·Backend·DB 공유, fixture 누적, 성공/409 구성 차이, 샘플링 간격 및 dropped iteration이 모두 영향을 줄 수 있다. 개선 전후 성능 수치 또는 운영 처리 한계로 쓰지 않는다.
- 후속 실험은 같은 물리 좌석 행 수와 초기 재고·DB cache 조건을 통제하고, 성공/충돌 각각의 p95와 statement별 대기를 반복 측정해야 한다. 현재 근거만으로 대기열·Kafka·추가 캐시를 도입하지 않는다.
