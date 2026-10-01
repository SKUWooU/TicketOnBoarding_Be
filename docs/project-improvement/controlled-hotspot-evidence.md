# 초기 재고를 통제한 인기 좌석 경합 실험

## 문제와 통제 범위

Issue #164의 첫 실험은 run마다 새 회차와 가상 좌석 2,000행을 같은 DB에 추가했다. 물리 `seat` 행이 최대 30,000개로 늘어 인기 좌석 집합 크기 외에 테이블 크기·캐시 상태가 함께 변했다. 또한 `Innodb_row_lock_waits`는 DB 전역 증가량이라 특정 SQL의 대기라고 단정할 수 없다.

Issue #166은 로컬 전용 Compose 프로젝트 `ticketon-controlled166`와 Mock PG만 사용한다. KOPIS·실 PG·운영 데이터 호출은 없다. 기존 진단용 fixture 정리 기능을 이 프로젝트에만 연결하고, 정리 전 비-fixture 좌석·공연·예약·Booking·Payment 또는 Checkout·Review가 있으면 삭제 전에 거부한다. Booking과 Payment는 fixture 공연의 Reservation에 연결된 행만 정리 가능하도록 확인한다. 매 run마다 fixture 생성 후 물리 `seat` 2,000행, 잔여 2,000·예약/결제 0·재고 불변식을 확인한 다음 부하를 시작한다. 재고와 행 수는 통제하지만, DB buffer cache·호스트 부하·auto-increment 값은 초기화하지 않는다. 따라서 완전히 동일한 DB 물리 상태를 재현했다고 주장하지 않는다.

## 실행 및 검증 조건

- Windows 로컬 단일 Backend, Java 21·Spring Boot 3.2.5·MariaDB 10.11.8·k6 2.1.0. 호스트 i5-10210U 4코어/8스레드·약 15.8 GiB, Docker Desktop 25.0.2. `local,loadtest` 프로필과 Mock PG 사용.
- 가상 좌석 2,000개(50행×40석), 목표 50 RPS×10초, 인기 집합 20/40/200석, 집중 목표 70%, 선택 seed 17, k6 constant-arrival-rate. 1차 20→40→200, 2차 200→40→20으로 순서를 뒤집었다. 앞선 3조건 smoke는 본 측정에서 제외했다.
- `compose.yml`과 `compose.statement-diagnostics.yml`을 함께 적용해 이 격리 DB에서만 Performance Schema를 켰다. SQL digest는 `seat ... FOR UPDATE`와 `concert_time.seat_amount` 조건부 UPDATE의 실행 횟수·평균 시간을 분리한다. 요청이 좌석 잠금 SQL 전에 종료될 수 있어 좌석 SELECT 관측률은 완료 요청 대비 최소 95%로 검사한다. 잔여 수량 UPDATE는 예약 성공 건수와 일치해야 한다.
- `load-test/scripts/Run-ControlledHotspot.ps1`은 Compose 프로젝트 이름과 컨테이너 label을 확인한 뒤에만 fixture 정리를 수행한다. 결과는 `load-test/results/c166-repeat/`의 manifest와 6개 summary JSON에 남긴다. stdout·CSV는 공개 근거에서 제외한다.

## 반복 결과

| 인기 좌석 | 반복 | 완료/누락 | 성공/예상 좌석 충돌 | 성공 p95 | 충돌 p95 | 좌석 SELECT 평균 | 잔여 UPDATE 평균 | 전역 row-lock wait |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 20 | 1 | 501/0 | 159/342 | 76.16ms | 48.48ms | 0.409ms | 0.422ms | 4 |
| 40 | 1 | 501/0 | 182/319 | 72.35ms | 54.90ms | 0.467ms | 0.440ms | 3 |
| 200 | 1 | 500/0 | 307/193 | 80.02ms | 67.88ms | 0.502ms | 0.409ms | 5 |
| 200 | 2 | 501/0 | 307/194 | 118.66ms | 52.88ms | 0.454ms | 1.317ms | 18 |
| 40 | 2 | 500/0 | 182/318 | 70.83ms | 47.63ms | 0.452ms | 0.400ms | 1 |
| 20 | 2 | 501/0 | 159/342 | 77.69ms | 44.36ms | 0.436ms | 0.401ms | 2 |

모든 run에서 실제 물리 좌석 2,000행, 초기 재고 2,000, 예상 밖 응답·SQL deadlock·dropped iteration 0, 최종 재고 불변식 정상이다. 좌석 SELECT digest 관측률은 99.6~100%, 잔여 UPDATE는 성공 건수 대비 100%다. 인기 좌석 집합이 200석일 때 성공 건수가 늘고 좌석 충돌이 줄어드는 결과는 두 순서에서 반복됐다. 하지만 200석의 두 번째 run은 첫 번째보다 성공 p95와 전역 row-lock wait가 높다. 같은 성공 건수에서도 대기·호스트 상태가 흔들린다는 뜻이며, 이 두 번만으로 p95 개선 또는 안정 처리량을 주장하지 않는다.

Reviewer 지적 후 정리 사전 검사를 예약·Booking·Payment 관계까지 확장하고 3조건 smoke를 재실행해 통과했다. 별도로 전용 DB에 fixture와 연결되지 않은 `lt-manual-guard166` Booking 한 행을 주입하자 `bookings=308/307`에서 정리 전에 실행이 거부됐고, 그 행이 그대로 남은 것을 확인한 뒤 해당 검증 행만 제거했다. 이 거부 실험은 성능 결과에서 제외한다.

SQL별 digest 평균 시간은 `seat` 잠금 SELECT와 `concert_time` UPDATE를 각각 관측한 값이지 InnoDB record-lock 대기의 직접 귀속값이 아니다. MariaDB의 digest 표는 정규화된 SQL별 실행·잠금 통계를 제공하지만, 이 실험의 전역 `Innodb_row_lock_waits`를 어느 statement가 유발했는지 연결하지 않는다. 특히 200석 두 번째 run에서 UPDATE 평균이 1.317ms로 증가했다는 사실은 추가 조사 후보일 뿐 root cause 증명은 아니다. [MariaDB statement digest 문서](https://mariadb.com/docs/server/reference/system-tables/performance-schema/performance-schema-tables/performance-schema-events_statements_summary_by_digest-table), [InnoDB lock wait 관계 문서](https://mariadb.com/docs/server/reference/system-tables/information-schema/information-schema-tables/information-schema-innodb-tables/information-schema-innodb_lock_waits-table)를 기준으로, 후속에는 실제 대기 중인 트랜잭션/SQL 표본을 수집해 귀속 가능성을 점검한다.

## 재현 명령과 다음 판단

전용 Compose 프로젝트에만 적용한다. 시작 전 다른 로컬 서비스의 3307·18080·18081 포트 사용 여부를 확인해야 한다. 기존 DB 프로젝트에 같은 정리 스크립트를 사용하지 않는다.

```powershell
$env:DOCKER_CONFIG = 'D:\project2'
$env:COMPOSE_PROJECT_NAME = 'ticketon-controlled166'
docker compose -p ticketon-controlled166 -f compose.yml -f compose.statement-diagnostics.yml up -d mariadb
# 별도 터미널: onticket 에서 local,loadtest Backend 구동
powershell.exe -NoProfile -ExecutionPolicy Bypass -File load-test/scripts/Run-ControlledHotspot.ps1 -BatchId mybatch -Repeats 2 -Rate 50 -DurationSeconds 10
```

다음 실험은 DB 전역 counter 외에 `INNODB_LOCK_WAITS`·대기 트랜잭션 SQL의 시점별 표본을 결합하고, 성공/409 비중이 다른 상황의 p95를 분리해 판단한다. 샘플링 누락 가능성과 관측 오버헤드를 먼저 평가해야 한다. 현재 근거로 대기열·Kafka·캐시·connection pool 조정을 도입하지 않는다. 실제 공연장 예매 성능이 아니라 가상 좌석 재고의 로컬 고경합 시나리오다.
