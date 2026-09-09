# 좌석 점유 도메인 메트릭

## 1. 문제

기존 좌석 점유 부하 측정은 다음 두 관점을 이미 제공한다.

- k6: HTTP 200, 예상된 409 경합, 예상 밖 비정상 응답, 처리 시간
- Actuator·MariaDB: Hikari 사용량, row lock wait/time, deadlock, DB thread

하지만 HTTP 200만으로는 서버가 좌석을 처음 획득했는지, 같은 소유자의 재시도를 재사용했는지, 만료된 점유를 회수했는지 구분할 수 없다. 또한 클라이언트가 센 성공·충돌 수가 서버의 transaction 완료 결과와 실제로 일치하는지 확인할 근거도 없었다.

Issue #91은 기존 계측을 교체하지 않고 이 사이의 도메인 판정만 보완한다. 새로운 캐시·대기열·브로커·대시보드는 추가하지 않는다.

## 2. 메트릭 계약

### 요청 Timer

`onticket.seat.hold.request`

| tag | 값 | 단위 |
| --- | --- | --- |
| `operation` | `hold`, `release` | Service 요청 |
| `outcome` | `success`, `conflict`, `invalid`, `error` | transaction 완료 결과 |

Prometheus에서는 `_seconds_count`, `_seconds_sum`, `_seconds_max`와 5·10·25·50·100·250·500·1,000ms 고정 bucket으로 노출된다. 고정 bucket은 분포 변화와 근사 분위수를 확인하기 위한 것이며, 이 문서의 단일 실행 p95는 k6가 기록한 client 관점의 값이다.

### 좌석 transition Counter

`onticket.seat.hold.transitions`

| operation | transition | 의미 |
| --- | --- | --- |
| `hold` | `acquired` | 비점유 좌석을 새로 점유 |
| `hold` | `reused` | 같은 소유자의 활성 점유와 만료 시각을 그대로 재사용 |
| `hold` | `reclaimed` | 만료된 점유를 지운 뒤 새 소유권 획득 |
| `release` | `released` | 활성 점유를 소유자가 해제 |
| `release` | `expired_cleared` | release 시 발견한 만료 데이터를 정리 |

요청 Timer의 count는 요청 단위이고 transition Counter는 좌석 단위다. 복수 좌석 요청에서는 두 수가 같다고 가정하지 않는다. 현재 k6 좌석 점유 시나리오는 요청당 한 좌석이므로 그 실행에서만 성공 요청 수와 `acquired + reused + reclaimed`를 동일성 조건으로 사용한다.

## 3. transaction 완료 뒤 기록하는 이유

복수 좌석 `[A1, A2]` 처리에서 A1을 변경한 뒤 A2 충돌이 발생할 수 있다. 메서드 중간에 Counter를 바로 증가시키면 DB에서는 A1이 rollback되어도 메트릭에는 획득으로 남는다.

따라서 Service 진입 시 transaction synchronization을 등록하고 다음 순서로 확정한다.

1. 처리 중에는 예상 outcome과 좌석 transition을 메모리에 누적한다.
2. transaction이 commit된 성공 요청만 transition을 Counter에 반영한다.
3. rollback이면 누적 transition을 버리고 `conflict`, `invalid`, `error` 요청 결과만 기록한다.
4. 외부 transaction이 Service 성공 뒤 rollback되는 경우도 `success`가 아니라 `error`로 기록한다.

이 구조는 DB 상태와 계측 상태의 의미를 맞추지만, 메트릭 자체를 DB transaction에 저장한다는 뜻은 아니다. 프로세스가 commit 직후 종료되면 in-memory registry 수집 전 표본이 유실될 수 있다.

## 4. 카디널리티 경계

애플리케이션이 직접 추가하는 태그는 `operation`, `outcome`, `transition`뿐이다. 다음 값은 태그로 사용하지 않는다.

- username
- concertId·concertTimeId
- seatNumber
- loadtest runId
- idempotency key·payment ID

식별자를 태그로 쓰면 공연·사용자·좌석 수에 비례해 시계열이 늘어나므로 로그나 DB 추적 대상으로 남긴다. Timer bucket의 `le`와 공통 `application`은 Micrometer·registry가 만드는 제한된 기술 태그다.

## 5. 자동 검증

### MariaDB Testcontainers

`SeatHoldIntegrationTest`가 다음을 검증한다.

- 최초 점유 1회와 동일 소유자 재사용 1회
- 다른 사용자 충돌 뒤 정확한 만료 경계에서 재획득
- 타인 release 충돌과 소유자 복수 좌석 release
- A1 변경 뒤 A2 충돌 시 전체 rollback 및 A1 transition 미집계
- 잘못된 요청의 `invalid` 분류
- 애플리케이션 태그가 제한된 집합인지 확인

전체 Backend 189 tests가 통과했다.

### PowerShell 측정 gate

`Measure-SeatHoldContention.ps1`은 부하 전후 `/actuator/prometheus`를 읽고 다음 조건이 하나라도 다르면 측정을 실패 처리한다.

- server `hold/success` delta = k6 `holdSuccess`
- server `hold/conflict` delta = k6 `expectedContention`
- server `invalid/error` delta = 0
- commit된 hold transition 합 = 성공한 1좌석 요청 수
- 누적 Counter가 실행 도중 감소하지 않음

파서와 gate는 `Test-SeatHoldContention.ps1` 44 assertions로 검증했다.

## 6. 로컬 2,000석 교차 측정

### 조건

| 항목 | 값 |
| --- | --- |
| 실행 시각 | 2026-09-09 KST |
| 환경 | Windows 로컬 단일 Backend, 제한된 8GB PC |
| Runtime | Java 21, Spring Boot 3.2.5 |
| DB | Docker MariaDB 10.11.8 |
| fixture | 공연 1·회차 1·가상 좌석 2,000 |
| 시나리오 | 동일 좌석 `R001-S001`, 500개 가상 인증 사용자 순환 |
| 부하 | k6 constant-arrival-rate, 100 RPS, 10초 |
| 외부 연동 | KOPIS·PG·SMS 호출 없음 |

명령:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\load-test\scripts\Measure-SeatHoldContention.ps1 `
  -Scenario hot-seat -Rate 100 -DurationSeconds 10 `
  -SampleIntervalMilliseconds 1000 -PreAllocatedVus 20 -MaxVus 100 `
  -RunId issue91-hot-100-a -FixtureRunId issue91-hot-fixture
```

### 결과

| 관점 | 결과 |
| --- | --- |
| k6 | iterations 1,000, success 2, expected 409 conflict 998 |
| 서버 요청 메트릭 | success 2, conflict 998, invalid 0, error 0 |
| commit transition | acquired 1, reused 1, reclaimed 0 |
| 최종 DB | active HELD 1, 불변식 충족 |
| 지연·도달 | p95 20.39ms, dropped iteration 0 |
| 자원·잠금 | Hikari pending peak 0, row lock waits +12, lock time +205ms, deadlock 0 |

최초 성공이 좌석을 획득했고, 500명 token 순환 중 같은 token이 다시 도달한 한 요청이 기존 만료 시각을 재사용했다. 나머지 사용자는 정상 409 경합으로 분류되었다. k6 결과와 서버 transaction 완료 메트릭이 요청 1,000건 모두에서 일치했다.

## 7. 해석과 한계

- 이번 수치는 계측 정확성을 확인한 단일 smoke다. Issue #65의 반복 성능 기준선을 대체하거나 계측 전후 성능 개선을 주장하지 않는다.
- 가상 2,000석과 단일 로컬 인스턴스 결과이며 실제 공연장·예매처 처리량이나 SLA가 아니다.
- `reclaimed`, `release`, rollback은 결정적 Testcontainers fixture로 검증했고 이번 hot-seat smoke에서 모두 발생시키지는 않았다.
- Grafana·alert·장기 보존은 현재 필요 근거가 없으므로 보류한다. 여러 run의 추세를 사람이 반복해서 비교해야 하는 시점에 대시보드 후보로 다시 판단한다.
- 다중 인스턴스의 registry 합산·Prometheus scrape 유실·재시작에 따른 누적값 초기화는 검증 범위가 아니다.

## 8. 관련 파일

- `onticket/src/main/java/com/onticket/concert/service/SeatHoldMetrics.java`
- `onticket/src/main/java/com/onticket/concert/service/SeatHoldService.java`
- `onticket/src/test/java/com/onticket/concert/service/SeatHoldIntegrationTest.java`
- `load-test/scripts/Measure-SeatHoldContention.ps1`
- `load-test/scripts/SeatHoldContention.psm1`
- `load-test/scripts/Test-SeatHoldContention.ps1`
