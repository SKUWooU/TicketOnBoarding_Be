# 가상 좌석 2,000석 고경합 부하 측정 기반

## 1. 목적

이 문서는 예매 쓰기 경합을 반복 측정하기 위한 로컬 전용 실행 기반과 최초 smoke 결과를 기록한다. 기존 24석 MariaDB fixture는 동시성 오류를 결정적으로 재현하고 정합성을 검증하는 데 적합하지만, 요청률을 단계적으로 높이며 HTTP 지연·커넥션·DB 잠금을 함께 관찰하기에는 규모와 관측 지점이 부족했다.

이번 작업은 성능을 개선하거나 운영 처리량을 주장하는 작업이 아니다. 명시적인 2,000석 가상 공연장과 mock 결제 경계를 만들고, 이후 같은 조건에서 병목을 재현·비교할 수 있는 측정 기반을 구성하는 것이 목적이다.

## 2. 작업 전 확인된 공백

- 저장소에 k6 시나리오와 부하 측정 전용 실행 profile이 없었다.
- Actuator·Micrometer가 없어 HTTP 지연과 HikariCP 상태를 같은 실행에서 확인할 수 없었다.
- 통합 테스트의 24석 fixture는 정확성 회귀에 초점을 두며, 수백~수천 좌석에 요청을 분산하는 부하 모델은 없었다.
- 검증된 예약 API는 `PaymentVerificationPort` 구현 없이는 실행되지 않아 실제 PG를 호출하지 않는 측정 adapter가 필요했다.
- 고경합 실패 응답을 명시적인 HTTP 409 계약으로 분류하는 전역 예외 처리는 아직 확인되지 않았다. 따라서 좌석 충돌을 모두 정상 결과로 간주하면 실제 5xx를 가릴 수 있다.

## 3. fixture와 실행 격리

`loadtest` profile에서만 다음 구성요소가 활성화된다.

| 구성 | 값·역할 |
| --- | --- |
| 공연 | 실행마다 `LOAD-TEST-<runId>` 가상 공연 |
| 회차 | run별 2030-01-10 19:00 독립 회차 |
| 좌석 | 50행 × 40석 = 2,000석 |
| 좌석 번호 | `R001-S001` ~ `R050-S040` 고정 폭 |
| 단가 | 서버 가상 단가 30,000원 |
| 사용자 | `load-user-<runId>.001`부터 런타임 JWT 발급, 최대 500명 |
| 결제 검증 | `LT:<username>:<amount>:<nonce>` 형식의 로컬 mock 승인 |
| 안전 상한 | 설정으로 늘리더라도 최대 10,000석 |

기본 k6 실행은 시작 시각으로 고유한 run ID를 만들고 별도 공연·회차·좌석·사용자·멱등 key·결제 ID를 사용한다. 결제 ID는 이미 run별 username을 포함하므로 nonce에는 run ID를 중복하지 않는다. 최대 32자 run ID와 `idempotent-retry-vu-2000` 조합도 서버의 결제 ID 100자 제한 안에 있음을 테스트한다. 같은 DB에서 연속 실행해도 이전 좌석 점유나 최초 결과 재사용이 다음 측정에 섞이지 않는다. 명시적으로 같은 `RUN_ID`를 재사용하면 기존 run을 이어가므로 독립 측정에는 매번 다른 값을 사용해야 한다.

실행 전용 API는 `POST /loadtest/runs`, `/loadtest/fixture`, `/loadtest/tokens`, `/loadtest/snapshot`이며 모두 run ID를 요구한다. `loadtest` profile 밖에서는 Bean 자체가 등록되지 않는다. 애플리케이션과 관리 포트는 각각 `127.0.0.1:18080`, `127.0.0.1:18081`에만 bind한다. 로컬 profile의 DEBUG 설정이 JWT 응답을 기록하지 않도록 loadtest에서는 root·web 로그를 INFO로 제한하고, 예약마다 좌석 객체를 표준 출력하던 레거시 디버그 출력은 측정 노이즈로 제거했다.

로컬 실행에서는 `local,loadtest`만 활성화하고 batch profile을 켜지 않는다. KOPIS URL은 호출 불가능한 `example.invalid` 값과 placeholder 인증값을 사용하며, mock payment ID는 로컬 형식만 해석한다. 이번 검증에서 KOPIS·실제 PG·SMS·운영 DB는 호출하지 않았다.

## 4. 측정 흐름

```text
k6
 ├─ fixture 메타데이터/JWT 준비
 ├─ 검증된 예약 API 호출
 │   ├─ mock 결제 검증
 │   ├─ 멱등 key 소유권
 │   ├─ 좌석 잠금·점유
 │   └─ 잔여 좌석 원자 감소
 └─ 해당 run 종료 snapshot
     ├─ total = remaining + reserved
     ├─ reserved = reservations
     └─ bookings = payments

관측
 ├─ k6: 요청률, p95, 비-2xx, 성공 수
 ├─ Actuator: HTTP 요청, Hikari active/pending/max
 └─ MariaDB: deadlock, row lock wait, connection/thread 상태
```

최종 snapshot의 좌석·예약은 run별 회차 ID, Booking은 run별 멱등 key, Payment는 run별 provider payment ID로 집계한다. run ID에는 사용할 수 없는 `.`을 경계 문자로 두어 `run-a`와 `run-a-extra` 같은 접두 관계도 섞이지 않는다. `invariantSatisfied`가 false이거나 snapshot 조회가 실패하면 k6 teardown이 예외를 발생시켜 실행 자체를 실패 처리한다.

## 5. k6 시나리오

| `TEST_SCENARIO` | 좌석 선택 | 확인 목적 | 결과 해석 |
| --- | --- | --- | --- |
| `hot-seat` | 모든 요청이 한 좌석 | 동일 좌석 최고 경합 | 비-2xx를 기록하되 현재는 정상 충돌과 5xx를 구분해 성공률로 단정하지 않음 |
| `hot-section` | 첫 행 40석 순환 | 좁은 구간 lock 경합 | 비-2xx와 지연을 기록하고 DB lock 지표와 함께 해석 |
| `distributed` | 2,000석 순환 | 경합이 낮은 쓰기 기준선 | 비-2xx 5% 미만 sanity threshold 적용 |
| `idempotent-retry` | 2,000석 범위의 VU별 고정 좌석·key·payment ID | 좌석 간 충돌 없이 동일 요청 결과 재사용 | 비-2xx 5% 미만 sanity threshold와 DB 행 수 확인 |

`constant-arrival-rate`를 사용하므로 애플리케이션 응답이 느려져도 목표 도착률을 유지하려고 시도한다. fixture 생성·token·snapshot HTTP 시간과 예약 API 지연을 섞지 않도록 예약 응답만 `reservation_duration` Trend에 기록한다. 기본값은 5 RPS·10초이며, 이는 실행 확인용 smoke일 뿐 부하 기준선이 아니다. 예약 p95 2초 threshold도 환경 오류를 빠르게 찾는 guardrail이지 목표 SLA가 아니다.

고경합 시나리오는 현재 모든 비-2xx를 `reservation_non_2xx`에 기록한다. 좌석 충돌의 명시적 409 응답 계약을 마련하기 전에는 이를 예상 성공으로 재분류하지 않는다.

## 6. 로컬 실행 방법

MariaDB를 준비한 뒤 별도 터미널에서 Backend를 실행한다.

```powershell
docker compose up -d mariadb
$env:SPRING_PROFILES_ACTIVE='local,loadtest'
$env:SPRING_BATCH_JOB_ENABLED='false'
./gradlew.bat bootRun --args="--debug=false --logging.level.root=INFO --logging.level.org.springframework.web=INFO"
```

fixture와 관측 endpoint를 확인한다.

```powershell
$runId = 'manual-001'
Invoke-RestMethod -Method Post "http://127.0.0.1:18080/loadtest/runs?runId=$runId"
Invoke-RestMethod "http://127.0.0.1:18080/loadtest/fixture?runId=$runId"
Invoke-RestMethod "http://127.0.0.1:18080/loadtest/snapshot?runId=$runId"
Invoke-RestMethod http://127.0.0.1:18081/actuator/health
Invoke-RestMethod http://127.0.0.1:18081/actuator/metrics/hikaricp.connections.active
```

다른 터미널에서 smoke 또는 단계별 부하를 실행한다.

```powershell
k6 run load-test/k6/reservation-contention.js

$env:TEST_SCENARIO='hot-section'
$env:RATE='20'
$env:DURATION='30s'
k6 run load-test/k6/reservation-contention.js
```

기본 실행은 고유 run ID를 자동 생성한다. 재현을 위해 `RUN_ID`를 직접 지정할 수 있지만, 같은 ID를 다시 쓰면 신규 기준선이 아니라 기존 실행을 이어간다.

`--summary-export`의 원본 JSON에는 k6 `setup_data`의 임시 JWT가 포함될 수 있으므로 공개 산출물로 저장하지 않는다. 원본 결과가 필요하면 ignored `load-test/results/`에만 보관하고 토큰을 제거한 집계 수치만 문서에 옮긴다.

## 7. run 격리 보완 후 distributed smoke

### 조건

| 항목 | 값 |
| --- | --- |
| 실행일 | 2026-08-28 |
| 환경 | Windows 로컬 단일 인스턴스, 사용자 제공 기준 메모리 8GB |
| Runtime | Java 21, Spring Boot 3.2.5 |
| DB | Docker MariaDB 10.11.8, Hikari 최대 10 connection |
| 부하 도구 | k6 2.1.0 |
| 시나리오 | `distributed`, 5 RPS, 10초 |
| fixture | 가상 공연 1·회차 1·좌석 2,000 |

### 결과

| 지표 | 관찰값 |
| --- | --- |
| 예약 성공 | 51건 |
| 전체 HTTP | 54건(준비·종료 조회 포함) |
| 예약 비-2xx | 0건 |
| HTTP 실패율 | 0% |
| 예약 응답 평균 | 52.87ms |
| 예약 응답 p95 | 68.21ms |
| 예약 응답 최대 | 177.80ms |
| 종료 재고 | 잔여 1,949·점유 51·예약 51 |
| 결과 행 | Booking 51·Payment 51 |
| 불변식 | 충족 |

고유 run ID를 자동 생성해 연속 실행한 smoke는 각각 별도 공연·회차에 저장되어 50건과 51건 모두 종료 불변식을 충족했다. run 경계 문자와 로그 조건까지 최종 보완한 뒤 다시 실행한 smoke도 예약 51건·비-2xx 0건·불변식 충족으로 종료했다. 위 지연값은 이 최종 실행의 예약 API 전용 Trend다. 서로 다른 로그 조건의 앞선 실행과 성능 개선 수치로 직접 비교하지 않는다.

최대 길이 경계는 32자 `RUN_ID`와 `idempotent-retry`로 별도 실행했다. 51회 API 호출이 VU별 최초 결과 20건으로 재사용되어 좌석·예약·Booking·Payment가 각각 20건, 잔여 1,980석으로 수렴했고 비-2xx 0·p95 58.81ms·불변식 충족으로 종료했다. 이 결과는 결제 ID 100자 계약과 멱등 재사용 실행 여부를 확인한 smoke이며 처리량 개선 수치가 아니다.

종료 후 순간값은 Hikari active 0·pending 0·max 10이었다. 같은 로컬 MariaDB의 누적 상태는 `Innodb_deadlocks=0`, `Innodb_row_lock_waits=4`, `Innodb_row_lock_time=683ms`, `Threads_connected=11`, `Threads_running=1`이었다. 이 값들은 여러 로컬 실행이 누적된 DB의 종료 뒤 값으로 이번 smoke의 peak connection·lock wait나 단독 원인을 의미하지 않는다. 이후 Prometheus 시계열 수집과 실행 전후 DB counter 차분을 추가해야 부하 중 최고점과 시간 상관관계를 비교할 수 있다.

## 8. 검증과 해석 범위

- `LoadTestFixtureIntegrationTest`: MariaDB 10.11.8에서 run별 정확히 2,000석 생성, 좌석 이름 경계, 같은 run 중복 초기화 방지, 서로 다른 run의 회차·재고 분리, 다른 loadtest run의 Booking·Payment 배제를 검증한다.
- `LoadTestPaymentVerificationAdapterTest`: 정상 로컬 결제와 잘못된 형식·금액을 외부 호출 없이 검증하고, 일반 context에는 adapter가 없으며 `loadtest` profile에서만 등록되는지와 최대 run ID 조합이 결제 ID 100자 이내인지 확인한다.
- 전체 Backend 99개 test는 기존 24석 동시성·예약·취소·결제 회귀와 신규 fixture가 충돌하지 않는지 확인했으며 실패·오류·skip은 모두 0이었다.
- `k6 inspect`는 스크립트 문법과 시나리오 구성을 확인한다.

이 수치는 실제 공연장 좌석 데이터, 실제 예매처, 다중 서버, 인터넷 구간, 운영 PG 성능을 나타내지 않는다. 단일 로컬 환경에서 가상 좌석 재고 쓰기 경로가 실행되고 종료 정합성을 유지하는지 확인한 smoke 결과다. 개선 전후 성능 비교도 아니므로 “성능 향상” 수치로 사용하지 않는다.

## 9. 확인된 한계와 후속 조건

1. 기존 `SecurityConfig`가 `/**`를 security ignore하는 경고가 있어 loadtest endpoint 격리는 profile·loopback bind에 의존한다. 인증 정책 정비는 Frontend 계약 영향이 있어 별도 Issue로 다룬다.
2. 좌석 경합 오류의 명시적 HTTP 409 계약이 없어 hot 시나리오의 비-2xx를 정상 충돌과 서버 오류로 분리할 수 없다.
3. 종료 후 단일 조회만으로는 부하 중 Hikari·lock wait peak를 알 수 없다. Prometheus/Grafana 또는 동등한 시계열 수집이 필요하다.
4. 2,000석은 병목을 재현하기 위한 명시적 가상 fixture 규모이며, 좌석 수를 크게 만드는 것 자체가 대규모 트래픽 처리 능력의 증거는 아니다.
5. 대기열·Redis 분산 lock·outbox·메시지 브로커는 아직 도입하지 않는다. 단계별 RPS에서 확인된 병목과 실패 양상이 해당 기술의 필요 조건을 충족할 때 별도 ADR로 판단한다.

권장 다음 순서는 `경합 오류 HTTP 계약 → 시계열 관측 → distributed/hot-section/hot-seat 단계별 측정 → 병목별 최소 개선 → 같은 조건 재측정`이다.

## 10. 연결

- [Backend Issue #47](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/47)
- [개선 근거 연결표](EVIDENCE_MAP.md)
- [학습·개선 여정](LEARNING_JOURNEY.md)
