# 고경합 부하의 경량 Hikari·MariaDB 관측 경계

## 1. 목적

이 문서는 가상 좌석 부하와 같은 시간 구간의 Hikari connection pool·MariaDB row lock 상태를 로컬에서 반복 수집하는 경량 측정 경계를 기록한다. Issue #47의 Actuator endpoint와 k6 시나리오는 종료 후 순간값과 여러 실행이 누적된 DB counter만 남겨, 지연이 증가한 시점의 connection·lock 상태나 해당 run의 증가분을 분리하지 못했다.

이번 작업은 Prometheus·Grafana 전체 stack이나 성능 개선이 아니다. Windows 로컬 단일 Backend·Docker MariaDB 환경에서 k6 실행과 약 1초 cadence 표본을 같은 run ID로 묶고, 후속 단계별 기준선이 병목 후보를 근거로 판단할 수 있게 하는 측정 기반이다.

## 2. 확인한 지표와 해석

### Hikari Prometheus gauge

`local,loadtest` profile의 관리 포트 `127.0.0.1:18081`은 다음 gauge를 실제로 노출한다.

| 지표 | 이번 요약 방식 | 해석 |
| --- | --- | --- |
| `hikaricp_connections_active` | run 중 peak | DB 작업에 사용 중인 application connection |
| `hikaricp_connections_pending` | run 중 peak | pool connection을 기다리는 thread |
| `hikaricp_connections_idle` | run 중 peak와 CSV 원본 | pool 내 유휴 connection |
| `hikaricp_connections_max` | run 중 peak | 구성된 pool 상한 |

한 번의 `/actuator/prometheus` 요청에서 해당 metric을 읽으며 pool tag가 여러 개면 합산한다. 필수 지표가 하나라도 없으면 표본과 전체 측정을 실패 처리한다.

### MariaDB global status

| 지표 | 종류 | 이번 요약 방식 |
| --- | --- | --- |
| `Innodb_row_lock_current_waits` | 현재 gauge | run 중 peak |
| `Innodb_row_lock_waits` | global 누적 counter | 마지막 값 - 첫 값 |
| `Innodb_row_lock_time` | global 누적 ms counter | 마지막 값 - 첫 값 |
| `Innodb_deadlocks` | global 누적 counter | 마지막 값 - 첫 값 |
| `Threads_connected` | 현재 gauge | peak, 관측자 connection 포함 표시 |
| `Threads_running` | 현재 gauge | peak, 관측 SQL thread 포함 표시 |

MariaDB 문서상 `Innodb_row_lock_waits`는 row lock을 기다린 횟수이고 `Innodb_row_lock_time`은 row lock 획득에 사용한 누적 millisecond다. 따라서 절대값을 특정 run의 결과로 사용하지 않고 실행 전후 delta로만 기록한다.

반복 조사에서 새 MariaDB CLI 연결을 열 때 `Connections`가 `38 → 39 → 41`로 증가했고, Compose healthcheck도 별도 연결을 열 수 있었다. `Connections`와 `Max_used_connections`를 application 부하 근거로 사용하지 않으며 결과 JSON에 이 제외 정책을 명시한다. `Threads_connected/running`에는 표본을 읽는 관측자 1개가 포함될 수 있어 보조 지표로만 사용한다. application connection 포화의 주 근거는 Hikari active·pending·max다.

## 3. 측정 흐름

```text
Measure-Contention.ps1
 ├─ Backend health·k6·Docker·필수 metric 사전 확인
 ├─ 고유 run ID·결과 경로·기존 파일 충돌 검증
 ├─ 첫 Hikari·MariaDB 표본
 ├─ k6 child process 시작
 │   ├─ stdout·stderr 비동기 drain
 │   └─ 고정 cadence로 Hikari·DB 표본 반복
 ├─ 마지막 표본과 counter delta·peak 계산
 ├─ k6 exit code + LOADTEST_FINAL_SNAPSHOT 불변식 gate
 └─ ignored CSV·summary JSON·k6 로그 저장
```

예약·결제 application 코드는 변경하지 않는다. wrapper는 기존 `reservation-contention.js`에 scenario·rate·duration·run ID를 전달한다. 표본 요청이 정해진 cadence보다 오래 걸리면 밀린 횟수만큼 연속 조회하지 않고 다음 시간 슬롯으로 이동하며, summary에 실제 표본 간격의 min·avg·max를 기록한다.

## 4. 실패와 결과 보존 정책

- run ID는 영문·숫자·하이픈 1~32자로 제한한다.
- 결과 경로는 저장소의 ignored `load-test/results/` 아래로 제한한다.
- 같은 run ID 결과 파일이 하나라도 있으면 덮어쓰지 않는다.
- 필수 Hikari metric·MariaDB status 누락, DB counter 감소, 시간 역행을 실패 처리한다.
- 지표 수집 실패 시 실행 중인 k6를 종료하고 `ValidMeasurement=false` 실패 JSON을 남긴다.
- k6 exit code가 0이 아니거나 최종 재고 불변식이 true가 아니면 정상 summary를 만들지 않는다.
- CSV·summary에는 JWT·cookie·HTTP request body·DB row를 저장하지 않는다.
- k6 stdout·stderr에는 현재 script가 출력하는 집계와 종료 snapshot만 남으며 전체 결과 디렉터리는 Git에서 제외된다.
- DB password는 로컬 MariaDB CLI 호출에만 사용하고 결과 파일에 기록하지 않는다.

개발 중 제한된 하위 PowerShell의 Docker config 접근 실패는 `ValidMeasurement=false`로 종료됐고, Windows PowerShell 5.1의 `Start-Process` 종료 코드 미갱신과 k6 stderr의 escape된 snapshot도 처음에는 false negative로 거절됐다. 최종 구현은 .NET `Process`의 비동기 stdout·stderr drain과 실제 exit code, 두 채널을 합친 snapshot 정규화를 사용한다. 실패를 성공으로 완화하지 않고 parser를 수정한 뒤 매번 새 run으로 재검증했다.

## 5. 실행 방법

MariaDB와 Backend를 먼저 실행한다.

```powershell
docker compose up -d mariadb
cd onticket
./gradlew.bat bootRun --args="--spring.profiles.active=local,loadtest --spring.batch.job.enabled=false"
```

저장소 root의 다른 terminal에서 wrapper를 실행한다. 로컬 실행 정책이 서명되지 않은 script를 막는 환경은 해당 프로세스에만 bypass를 적용한다.

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File `
  .\load-test\scripts\Measure-Contention.ps1 `
  -Scenario hot-seat `
  -Rate 100 `
  -DurationSeconds 10 `
  -RunId example-hot-01
```

parser·summary fixture만 검증할 수 있다.

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File `
  .\load-test\scripts\Test-ContentionMetrics.ps1
```

## 6. 자동 검증

`Test-ContentionMetrics.ps1`의 22개 assertion은 다음을 외부 서비스 없이 검증한다.

- 복수 Hikari pool의 active·pending·idle·max 합산
- 필수 Prometheus metric 누락 거부
- MariaDB tab·공백 status parsing과 필수 값 누락 거부
- Hikari·DB gauge peak와 global counter delta
- 실제 표본 간격 min·avg·max
- DB counter 감소와 잘못된 run ID 거부
- DB CLI·Compose healthcheck의 관측자 효과와 `Connections` 제외 표시

Backend CI는 Ubuntu `pwsh`에서 이 fixture 검사를 먼저 실행한 뒤 기존 Java 21 Backend 테스트를 수행한다. 로컬에서는 PowerShell 5.1로 22개 assertion, 전체 Backend 102개 test 강제 재실행, `k6 inspect`가 통과했다. 로컬에 `actionlint`는 설치되어 있지 않아 workflow step의 Ubuntu 실행 결과는 PR CI에서 최종 확인한다.

## 7. 최종 로컬 smoke

### 공통 조건

| 항목 | 값 |
| --- | --- |
| 실행일 | 2026-08-28 |
| 환경 | Windows 로컬 단일 Backend, 사용자 제공 기준 메모리 8GB |
| Runtime | Java 21, Spring Boot 3.2.5 |
| DB | Docker MariaDB 10.11.8, Hikari max 10 |
| fixture | run별 가상 좌석 2,000석, loadtest mock 결제 |
| 목표 표본 cadence | 1,000ms |
| 외부 연동 | KOPIS·실제 PG·SMS·운영 DB 호출 없음 |

### 낮은 경합 확인 — distributed 5 RPS·5초

| 지표 | 관찰값 |
| --- | --- |
| 예약 | 26회 성공, 예상 밖 오류 0 |
| 예약 응답 p95 | 38.90ms |
| 표본 | 10개, 간격 avg 1,010ms·max 1,650ms |
| Hikari | active peak 1·pending peak 0·max 10 |
| DB lock | current waits peak 0·waits delta 0·time delta 0ms·deadlock delta 0 |
| DB threads | connected peak 11·running peak 1, 관측자 포함 |
| 종료 | 잔여 1,974·점유/예약/Booking/Payment 26·불변식 충족 |

### 최고 좌석 경합 확인 — hot-seat 100 RPS·10초

| 지표 | 관찰값 |
| --- | --- |
| 예약 요청 | 1,001회 |
| 결과 | 성공 1·예상 409 경합 1,000·예상 밖 오류 0 |
| 예약 응답 p95 | 39.68ms |
| 표본 | 15개, 간격 min 516ms·avg 1,009.36ms·max 1,692ms |
| Hikari | active peak 2·pending peak 0·max 10 |
| DB lock | current waits peak 1·waits delta 77·time delta 142ms·deadlock delta 0 |
| DB threads | connected peak 11·running peak 4, 관측자 포함 |
| 종료 | 잔여 1,999·점유/예약/Booking/Payment 1·불변식 충족 |

두 결과는 collector가 낮은 경합의 0 delta와 hot-seat의 lock wait 증가를 모두 포착하고 k6·재고 결과와 한 run에 연결한다는 기능 확인값이다. 서로 scenario·rate·duration이 다르므로 성능 개선 전후 비교가 아니며 안정 TPS나 운영 SLA로 사용하지 않는다. 같은 100 RPS 개발 smoke도 warmup·sampling 구현 조건에 따라 lock delta가 달랐으므로 후속 기준선은 동일 버전·warmup·반복 횟수를 고정하고 분산을 함께 기록해야 한다.

## 8. 남은 한계와 다음 단계

1. 약 1초 polling은 매우 짧은 connection·lock spike를 놓칠 수 있다. 실제 간격 max도 summary와 함께 해석한다.
2. MariaDB CLI와 Compose healthcheck가 DB thread gauge에 영향을 준다. 이를 숨기지 않고 결과에 표시하며 connection 포화는 Hikari를 주 근거로 본다.
3. 로컬 결과는 process·OS·Docker가 자원을 공유하므로 실제 예매처나 다중 instance 성능을 나타내지 않는다.
4. 다음 Issue는 동일 collector 버전에서 warmup 후 distributed·hot-section·hot-seat를 단계별 RPS와 반복 실행해 최초 p95·pending·lock wait·오류율 변곡점을 찾는다.
5. 병목이 확인되기 전에는 Hikari 크기 조정, 대기열, Redis 분산 lock, outbox, 메시지 브로커를 도입하지 않는다.

## 9. 연결

- [Backend Issue #51](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/51)
- [가상 좌석 2,000석 고경합 부하 측정 기반](high-contention-load-test-harness.md)
- [좌석 경합 실패의 HTTP 409 계약](seat-contention-http-contract.md)
- [개선 근거 연결표](EVIDENCE_MAP.md)
- [학습·개선 여정](LEARNING_JOURNEY.md)
