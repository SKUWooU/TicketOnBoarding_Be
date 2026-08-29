# 가상 좌석 고경합의 단계별 성능 기준선

## 1. 목적

이 문서는 가상 좌석 예약의 무경합 성공 처리량과 동일 자원 경합 비용을 분리해 단계적으로 측정한 최초 반복 기준선을 기록한다. 단일 smoke의 최고값을 선택하지 않고 같은 조건을 3회 반복해 중앙값과 최소·최대 범위를 함께 남긴다.

이 결과는 Windows 로컬 단일 Backend·Docker MariaDB·명시적 fixture에서 얻은 개발 기준선이다. 실제 예매처 처리량, 운영 SLA, 다중 instance 또는 분산 환경 성능을 나타내지 않는다.

## 2. 기존 측정의 해석 공백

Issue #51의 단일 run collector는 k6와 Hikari·MariaDB를 같은 run ID로 연결했지만 단계별 비교 전에는 다음 공백이 있었다.

1. k6 콘솔의 처리율은 setup을 포함한 전체 실행시간의 영향을 받았다. hot-seat 100 RPS·10초 smoke는 부하 구간에서 1,001 iterations를 완료했지만 콘솔 rate는 약 77 RPS였다.
2. 2,000석 fixture 생성 transaction이 첫 Hikari·DB 표본 뒤에 실행돼 부하 구간의 peak·counter delta와 섞일 수 있었다.
3. `distributed`는 서로 다른 좌석의 성공 처리량이지만 `hot-section`·`hot-seat`는 좌석 소진 뒤 대부분 정상 HTTP 409이므로 같은 TPS 의미로 비교할 수 없다.
4. 기존 k6 performance threshold가 위반되면 collector도 run 전체를 무효화해, 병목 변곡점의 수치 자체를 정상 기준선으로 보존하기 어려웠다.

## 3. 측정 경계 보완

### fixture 준비 분리

`Measure-Contention.ps1`은 2,000석 fixture를 먼저 생성하고 준비 시간을 별도로 기록한 뒤 stopwatch와 첫 Hikari·DB 표본을 시작한다. 이후 k6 setup은 이미 준비된 fixture를 조회하고 token을 발급한다. 무거운 좌석 insert는 metric 표본에서 제외된다.

k6 setup의 fixture 조회·token 발급과 teardown의 snapshot 조회는 여전히 Hikari·DB 표본 시간창에 포함된다. 다만 예약 전용 `reservation_duration`에는 포함되지 않으며, 이 짧은 보조 요청이 gauge peak에 미칠 수 있는 영향은 한계로 남긴다.

### 구조화 k6 결과

k6 `handleSummary()`가 다음 값만 `LOADTEST_RESULT` JSON으로 출력한다.

- 완료·dropped iterations와 목표 도달률
- 예약 성공·예상 409·예상 밖 실패
- 예약 전용 avg·median·p95·max
- 관측·할당·사전 할당·구성 상한 VU
- performance threshold 적용 여부

JWT·cookie·setup data·HTTP body는 구조화 결과에 포함하지 않는다. raw k6 로그와 CSV·JSON은 Git에서 제외된 `load-test/results/`에만 저장한다.

k6 공식 문서상 `constant-arrival-rate`는 응답 완료와 독립적으로 iteration 시작을 예약하며, 가용 VU가 부족하면 `dropped_iterations`가 발생한다. 이번 기준선은 완료 iterations를 10초로 나눈 값과 `iterations / (iterations + dropped)`를 함께 기록한다.

- [Constant arrival rate](https://grafana.com/docs/k6/latest/using-k6/scenarios/executors/constant-arrival-rate/)
- [Dropped iterations](https://grafana.com/docs/k6/latest/using-k6/scenarios/concepts/dropped-iterations/)
- [Custom summary](https://grafana.com/docs/k6/latest/results-output/end-of-test/custom-summary/)

### threshold와 측정 유효성 분리

일반 단일 smoke는 기존 p95·예상 밖 실패 threshold를 유지한다. 단계별 baseline runner만 k6 performance threshold를 끄고 다음 조건을 사후 판정한다.

- 예상 밖 실패율 5% 이상
- dropped iteration 비율 1% 이상
- 예약 p95 2,000ms 이상

같은 단계 3회 중 2회 이상 조건을 넘으면 해당 시나리오의 상위 단계를 생략한다. 반면 수집 실패, k6 실행 실패, 재고 불변식 위반은 성능 저하가 아니라 유효하지 않은 측정이므로 batch 전체를 즉시 실패 처리한다.

## 4. 실행 조건

| 항목 | 값 |
| --- | --- |
| 실행일 | 2026-08-29 |
| 환경 | Windows 로컬 단일 Backend·Docker DB, 사용자 제공 기준 메모리 8GB |
| Runtime | Java 21, Spring Boot 3.2.5 |
| 부하 도구 | k6 v2.1.0, `constant-arrival-rate` |
| 부하 발생기 VU | `preAllocatedVUs=200`, `maxVUs=200` |
| DB·pool | MariaDB 10.11.8, Hikari max 10 |
| fixture | run별 50행 × 40석 = 가상 좌석 2,000석 |
| 결제 | `loadtest` profile의 로컬 mock 검증 |
| 측정 시간 | run당 10초, 목표 표본 cadence 1초 |
| 반복 | warmup 1회 제외, 단계별 3회 |
| 외부 연동 | KOPIS·실제 PG·SMS·운영 DB 호출 없음 |

계획한 단계는 다음과 같다.

- warmup: distributed 20 RPS 1회, 집계 제외
- distributed: 20·50·100·150 RPS
- hot-section: 50·100·150 RPS
- hot-seat: 100·150 RPS

distributed 100 RPS에서 3회 모두 dropped iteration 조건을 넘어 150 RPS 3회는 자동 생략됐다. 최종 batch는 warmup 1회와 유효 측정 24회, 생략 3회다.

최종 공개 수치는 `issue53-base-03` batch만 사용한다. 앞선 두 탐색 batch는 각각 `maxVUs=100`, `preAllocatedVUs=100/maxVUs=200` 설정으로 VU 상한 또는 동적 할당 지연이 서버 병목과 섞일 수 있음을 확인해 폐기했다. 최종 batch는 필요한 200 VU를 시작 전에 모두 할당해 이 부하 발생기 변수를 제거했다.

## 5. 반복 측정 결과

아래 값은 각 단계 3회의 중앙값이며 괄호는 최소~최대다. 도달률은 완료 iterations를 완료+dropped로 나눈 비율이다. `완료/설정초`는 완료 iterations를 설정한 10초로 나눈 비교 지표이며, graceful stop 중 완료되는 요청이 있어 실제 wall-clock TPS로 해석하지 않는다.

| 시나리오 | 목표 RPS | 완료/설정초 | 도달률 | 예약 p95 ms | Hikari active / pending peak | DB lock waits delta | DB lock time delta ms |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| distributed | 20 | 20.0 (20.0~20.1) | 100% | 33.57 (33.42~33.93) | 1 / 0 | 0 | 0 |
| distributed | 50 | 50.1 | 100% | 37.42 (37.18~43.77) | 2 (1~2) / 0 | 10 (2~19) | 66 (4~429) |
| distributed | 100 | 95.0 (93.6~96.0) | 94.91% (93.51~95.90%) | 2,523.26 (2,485.19~2,641.98) | 10 / 189 (188~189) | 949 (935~959) | 93,346 (93,132~94,062) |
| hot-section | 50 | 50.1 | 100% | 28.41 (27.43~31.72) | 1 (1~2) / 0 | 1 (0~2) | 1 (0~5) |
| hot-section | 100 | 100.1 | 100% | 47.75 (40.11~67.31) | 2 / 0 | 75 (68~140) | 2,313 (1,374~3,210) |
| hot-section | 150 | 144.0 (125.1~148.0) | 95.94% (83.34~98.60%) | 1,725.87 (1,472.46~1,820.48) | 10 / 188 (188~189) | 1,439 (1,250~1,479) | 76,426 (73,637~78,747) |
| hot-seat | 100 | 100.1 | 100% | 37.57 (34.20~37.57) | 2 (2~3) / 0 | 44 (23~56) | 358 (39~384) |
| hot-seat | 150 | 145.8 (129.6~150.1) | 97.20% (86.40~100%) | 1,549.91 (1,185.51~2,099.30) | 10 / 188 (145~189) | 1,455 (1,272~1,455) | 68,478 (67,786~72,540) |

모든 유효 run의 예상 밖 실패율과 deadlock delta는 0이었고 종료 재고 불변식은 충족됐다.

### 응답 구성

- distributed는 완료된 모든 iteration이 예약 성공이다. 100 RPS에서는 성공 950·960·936회, dropped 51·41·65회였다.
- hot-section은 run마다 40개 좌석만 성공하고 나머지는 예상된 409다. 100 RPS에서는 성공 40회와 예상 409 960~961회를 모두 처리했다.
- hot-seat은 run마다 1회만 성공하고 나머지는 예상된 409다. 100 RPS에서는 성공 1회와 예상 409 999~1,000회를 모두 처리했다.
- 따라서 hot scenario의 완료 RPS는 성공 예약 처리량이 아니라 동일 자원 충돌을 판정하고 409로 거부한 처리율이다.

## 6. 확인된 변곡점과 병목 후보

### distributed 50 → 100 RPS

서로 다른 좌석을 예약하는 distributed는 50 RPS까지 p95 약 37ms·Hikari pending 0·요청 도달률 100%였다. 100 RPS에서는 다음 현상이 3회 반복됐다.

- 완료/설정초 중앙값 95.0과 도달률 94.91%
- p95 2.52초
- Hikari active 10/10, pending 189
- DB row lock wait 949회·93.35초 누적 증가
- 예상 밖 실패와 deadlock 0

좌석은 서로 다르지만 예약 transaction은 좌석을 잠근 뒤 동일한 `concert_time` 행의 `seat_amount`를 조건부 감소시킨다. 따라서 이 기준선 시점에는 회차 잔여 수량 단일 행 갱신이 transaction을 직렬화하고, 대기 transaction이 Hikari connection을 점유한 채 쌓이는 것을 가장 강한 병목 가설로 두었다.

이는 측정과 코드 경로를 결합한 추론이지 원인 격리 실험의 결론은 아니었다. Issue #55의 SQL별 진단에서는 100 RPS 좌석 잠금 SELECT 평균이 회차 UPDATE보다 약 410.63배 길어 회차 단일 행 주병목 가설이 반증됐다. 상세 결과는 [회차 잔여 좌석 단일 행 병목 가설의 SQL별 진단](concert-time-row-bottleneck-diagnosis.md)에 기록한다.

### 경합 시나리오 100 → 150 RPS

hot-section과 hot-seat은 100 RPS에서 목표 도달률 100%와 Hikari pending 0을 유지했다. 150 RPS에서는 두 시나리오 모두 Hikari active 10, pending 최대 189, row lock wait 중앙값 1,400회 이상과 dropped iteration을 반복했다.

대부분 409인 경로도 transaction이 좌석 lock을 얻어 충돌을 확인할 때까지 DB connection을 사용하므로, 극단 경합에서 빠른 실패 자체가 무제한 처리량을 보장하지 않는다. 다만 이는 정상 성공 처리량과 다른 지표이므로 distributed 기준선과 분리해 해석한다.

## 7. 재현 방법

MariaDB와 Backend를 로컬 profile로 실행한 뒤 저장소 root에서 수행한다.

```powershell
docker compose up -d mariadb
cd onticket
./gradlew.bat bootRun --args="--spring.profiles.active=local,loadtest --spring.batch.job.enabled=false"
```

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File `
  .\load-test\scripts\Run-ContentionBaseline.ps1 `
  -BatchId example-base-01
```

원본은 `load-test/results/<batchId>/`에 저장되며 Git에 포함하지 않는다. 같은 batch ID는 덮어쓰지 않는다.

### 자동 검증

- `Test-ContentionMetrics.ps1`: 구조화 k6 결과·실행 조건 일치·재고 snapshot·Hikari·DB parser와 실패 gate 42개 assertion
- `Test-ContentionBaseline.ps1`: 28-run plan·재고 상한·중단 조건·중앙값/범위 집계 19개 assertion
- `k6 inspect`: scenario·threshold·script syntax 확인
- Backend: Testcontainers 포함 전체 102개 test, 실패·오류·skip 0
- Backend CI: Ubuntu `pwsh`에서 두 PowerShell fixture를 실행한 뒤 Java 21 Backend test 실행

## 8. 한계와 다음 검증

1. 로컬 부하 발생기·Backend·Docker DB가 같은 장비의 CPU·메모리·I/O를 공유한다.
2. 약 1초 polling은 짧은 connection·lock spike를 놓칠 수 있다.
3. DB CLI와 Compose healthcheck가 thread gauge에 미치는 영향이 있으며 connection 포화는 Hikari active·pending을 주 근거로 삼는다.
4. k6 setup·teardown의 보조 조회는 Hikari·DB 표본 시간창에 포함되지만 예약 전용 p95에는 포함되지 않는다.
5. 10초 run은 장기 안정성·열화·GC 영향을 증명하지 않는다.
6. 완료/설정초는 k6의 설정 부하 구간을 분모로 한 비교값으로, graceful stop을 포함한 wall-clock 처리량이 아니다.
7. Issue #55에서 좌석 잠금 SELECT에 대기가 집중됨을 확인했다. 다음 Issue는 `(concert_time_id, seat_number)` 복합 unique index 전후를 같은 고경합 조건으로 비교하며, 결과 전에는 Hikari pool 확대, Redis lock, 대기열, outbox, 메시지 브로커를 도입하지 않는다.

## 9. 연결

- [Backend Issue #53](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/53)
- [가상 좌석 2,000석 고경합 부하 측정 기반](high-contention-load-test-harness.md)
- [고경합 부하의 경량 Hikari·MariaDB 관측 경계](lightweight-contention-observability.md)
- [개선 근거 연결표](EVIDENCE_MAP.md)
- [학습·개선 여정](LEARNING_JOURNEY.md)
