# 회차 잔여 좌석 단일 행 병목 가설의 SQL별 진단

## 1. 출발점

Issue #53의 `distributed` 시나리오는 매 요청이 서로 다른 좌석을 예약하는데도 50→100 RPS 사이에서 예약 p95, Hikari pending, MariaDB row lock wait와 dropped iteration이 함께 증가했다. 모든 성공 transaction이 마지막에 동일한 `concert_time.seat_amount` 행을 감소시키므로, 처음에는 이 공유 행이 transaction을 직렬화한다는 가설을 세웠다.

이번 Issue의 목적은 해당 가설을 개선 코드로 바로 이어가는 것이 아니라, 개별 좌석 잠금 SQL과 회차 잔여 수량 갱신 SQL의 실행시간을 분리해 어느 statement에서 대기가 발생하는지 확인하는 것이다.

이 결과는 Windows 로컬 단일 Backend·Docker MariaDB·2,000석 가상 fixture에서 얻은 진단값이다. 실제 예매처, 운영 SLA, 다중 instance 또는 분산 환경 성능을 의미하지 않는다.

## 2. 현재 예약 transaction

검증 예약은 다음 순서로 처리된다.

1. Booking과 Payment를 생성하고 flush한다.
2. 요청 좌석 번호를 정렬·중복 제거한다.
3. 좌석마다 `(concert_time_id, seat_number)` 조건으로 `SELECT ... FOR UPDATE`를 실행한다.
4. 좌석을 점유 상태로 바꾸고 Reservation을 저장한다.
5. `concert_time.seat_amount`를 조건부 감소시킨다.
6. 하나의 transaction을 commit하고 DB connection과 잠금을 반환한다.

회차 갱신 repository는 `flushAutomatically=true`다. 따라서 Java에서 repository 메서드 전체만 재면 선행 좌석·Reservation 변경 flush와 실제 `UPDATE concert_time` 실행시간이 섞인다. 이번에는 application 메서드 타이머를 추가하지 않고 MariaDB가 관측한 statement별 시간을 사용했다.

## 3. 진단 방법 선택

### 선택하지 않은 방법

- P6Spy는 이미 의존성에 있지만 모든 SQL을 파일로 기록하면 고경합 실행에 추가 I/O를 만들고 값이 포함된 SQL 로그의 보관 범위도 커진다.
- 회차 counter 갱신을 생략하는 load-test 전용 분기는 재고 정합성 모델 자체를 바꾸므로 비교 대상이 달라진다.
- 2,000석을 여러 회차로 분할하는 fixture는 좌석 잠금과 회차 row 분산을 동시에 바꿔 어느 변화가 결과를 만들었는지 모호하다.

### 선택한 방법

기본 Compose는 그대로 두고 `compose.statement-diagnostics.yml`을 함께 지정한 경우에만 MariaDB를 `performance_schema=ON`으로 시작한다. Performance Schema는 runtime에 켤 수 없으므로 별도 시작 옵션이 필요하다. `events_statements_summary_by_digest`는 값이 제거된 normalized SQL digest별 실행 횟수와 누적·평균·최대 statement 시간을 제공한다.

- [MariaDB Performance Schema 설정](https://mariadb.com/docs/server/reference/system-tables/performance-schema/performance-schema-system-variables)
- [MariaDB statement digest summary](https://mariadb.com/docs/server/reference/sql-statements/administrative-sql-statements/system-tables/performance-schema/performance-schema-tables/performance-schema-events_statements_summary_by_digest-table)

fixture 2,000석 생성을 마친 뒤 digest table을 초기화하고 k6 부하를 실행한다. 수집기는 다음 두 normalized statement만 분류한다.

- `seat-lock-select`: `seat`의 `SELECT ... FOR UPDATE`
- `concert-time-decrement`: `concert_time.seat_amount`의 조건부 `UPDATE`

각 statement의 횟수는 k6 예약 성공 수와 정확히 같아야 한다. digest 누락, 횟수 불일치, SQL error, 예상 밖 HTTP 실패 또는 재고 불변식 위반이 있으면 해당 run을 유효한 진단으로 저장하지 않는다. summary에는 SQL 원문·파라미터·JWT·cookie·DB 비밀번호를 넣지 않고 operation 이름과 집계값만 저장한다.

## 4. 실행 조건

| 항목 | 값 |
| --- | --- |
| 실행일 | 2026-08-29 |
| 환경 | Windows 로컬 단일 Backend·Docker DB, 사용자 제공 기준 메모리 8GB |
| Runtime | Java 21, Spring Boot 3.2.5 |
| DB·pool | MariaDB 10.11.8, Performance Schema ON, Hikari max 10 |
| fixture | run별 단일 회차, 50행 × 40석 = 가상 좌석 2,000석 |
| 시나리오 | 서로 다른 좌석을 선택하는 `distributed` |
| 단계 | warmup 20 RPS 1회 제외, 50·100 RPS 각 3회 |
| 부하 설정 | run당 10초, `preAllocatedVUs=maxVUs=200` |
| 결제 | `loadtest` profile의 로컬 mock 검증 |
| 외부 연동 | KOPIS·실제 PG·SMS·운영 DB 호출 없음 |

Performance Schema 자체에도 관측 비용이 있으므로 Issue #53의 OFF 환경과 절대 p95를 직접 비교하지 않는다. 이번 결론은 동일 진단 batch 내부의 50/100 RPS와 두 SQL 사이의 차이에서만 도출한다.

## 5. 반복 측정 결과

아래 값은 각 단계 3회의 중앙값이며 괄호는 최소~최대다. `완료/설정초`는 완료 iterations를 설정한 10초로 나눈 비교값이며 wall-clock TPS가 아니다.

### 요청·connection·DB lock

| 목표 RPS | 완료/설정초 | 도달률 | 예약 p95 ms | Hikari pending peak | DB row lock waits | DB row lock time ms |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 50 | 50.0 (50.0~50.1) | 100% | 75.05 (56.65~136.32) | 0 | 97 (44~210) | 1,211 (178~6,165) |
| 100 | 71.9 (70.6~75.4) | 71.83% (70.53~75.40%) | 4,096.96 (3,456.42~4,293.16) | 189 (189~190) | 718 (705~753) | 100,479 (99,387~106,766) |

100 RPS에서는 Hikari active가 3회 모두 10/10에 도달했다. 모든 유효 run의 예상 밖 실패와 deadlock delta는 0이었고 종료 재고 불변식도 충족됐다.

### SQL statement 시간

| 목표 RPS | 좌석 lock 평균 ms | 좌석 lock 최대 ms | 좌석 lock 누적 ms | 회차 UPDATE 평균 ms | 회차 UPDATE 최대 ms | 회차 UPDATE 누적 ms | 좌석/회차 평균 비율 |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 50 | 7.63 (4.27~18.87) | 49.89 (27.15~113.68) | 3,816.62 (2,134.06~9,454.61) | 0.338 (0.323~0.338) | 1.249 (0.730~1.688) | 169.03 (161.71~169.55) | 22.58배 (13.20~55.76배) |
| 100 | 146.44 (138.24~158.46) | 271.40 (224.37~282.40) | 105,292.15 (104,230.90~111,876.16) | 0.354 (0.337~0.390) | 1.889 (1.145~2.941) | 254.56 (253.83~275.33) | 410.63배 (406.33~413.62배) |

100 RPS의 좌석 lock SELECT 누적시간 약 105.29초는 MariaDB row lock time 증가 약 100.48초와 같은 규모로 증가했다. 두 값은 집계 범위가 달라 일치해야 하는 지표는 아니지만, 회차 UPDATE 누적 약 0.25초보다 좌석 잠금 statement가 DB lock 증가를 훨씬 잘 설명한다.

## 6. 가설 판정

### 반증된 가설

`concert_time.seat_amount` 단일 행 갱신이 현재 변곡점의 주된 대기 statement라는 가설은 이번 조건에서 지지되지 않았다.

- 50→100 RPS에서 회차 UPDATE 평균은 약 0.338ms→0.354ms로 거의 유지됐다.
- 100 RPS의 회차 UPDATE 최대도 중앙값 약 1.89ms였다.
- 반면 좌석 잠금 SELECT 평균은 약 7.63ms→146.44ms, 누적은 약 3.82초→105.29초로 증가했다.

공유 회차 행이 존재한다는 코드 사실과, 그 행이 현재 병목이라는 성능 결론은 구분해야 한다.

### 새 원인 후보: 좌석 잠금 조회의 넓은 탐색 범위

최종 fixture schema의 `seat` index는 다음 두 개뿐이었다.

- 기본 키 `id`
- 외래 키 지원 index `concert_time_id`

`seat_number`를 포함한 `(concert_time_id, seat_number)` 복합 index는 없다. 실제 실행계획은 외래 키 index를 사용하지만 한 좌석을 찾기 위해 같은 회차의 약 2,000행을 검사했다.

```text
type=ref
key=FK...concert_time_id
rows=2000
Extra=Using where
```

Issue #11의 24석 test fixture에서는 복합 unique index를 임시 적용했을 때 잠금 조회가 `rows=1`로 바뀌는 것을 확인했지만, 운영 schema에는 적용하지 않았다. 이번 2,000석 고경합 결과는 해당 미적용 상태가 처리량 병목 후보임을 처음 수치로 연결한다.

현재 확인된 사실은 “대기시간이 좌석 잠금 SELECT에 집중된다”는 것이다. “복합 index 하나로 고경합 병목이 해결된다”는 결론은 아직 아니다. 다음 Issue에서 같은 Performance Schema 조건으로 index 적용 전후 query plan·SQL 시간·k6 p95·Hikari pending·정합성을 A/B 비교해야 한다.

## 7. 재현 방법

진단용 override를 포함해 MariaDB와 Backend를 실행한다.

```powershell
docker compose -f compose.yml -f compose.statement-diagnostics.yml up -d mariadb
cd onticket
./gradlew.bat bootRun --args="--spring.profiles.active=local,loadtest --spring.batch.job.enabled=false"
```

저장소 root에서 실행한다.

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File `
  .\load-test\scripts\Run-ContentionBottleneckDiagnosis.ps1 `
  -BatchId example-diag-01
```

원본 summary·manifest·aggregate는 `load-test/results/<batchId>/`에 저장되며 Git에 포함하지 않는다. 같은 batch ID는 재사용하지 않는다.

## 8. 자동 검증

- `Test-StatementDigestDiagnostics.ps1`: digest decoding·SQL 분류·단위 변환·복수 digest 집계·필수 statement와 성공 수 일치·민감 SQL 원문 제외 16개 assertion
- `Test-ContentionBottleneckDiagnosis.ps1`: 7-run plan·fixture 상한·중앙값/범위·SQL별 비율·digest 필수 조건 15개 assertion
- 기존 수집기 42개, baseline runner 19개 assertion
- k6 inspect
- Backend Testcontainers 포함 전체 102개 test(실패·오류·skip 0)
- GitHub Actions Ubuntu `pwsh`와 Java 21

## 9. 한계와 다음 단계

1. Performance Schema는 관측 비용을 추가하므로 비활성화된 기존 기준선과 절대 성능을 직접 비교하지 않는다.
2. digest는 같은 normalized SQL의 집계값이므로 개별 요청의 p95나 transaction 전체 시간을 제공하지 않는다.
3. statement 누적시간은 여러 connection에서 겹쳐 실행된 시간을 합한 값이므로 wall-clock 시간과 같지 않다.
4. 10초 로컬 run은 장기 안정성·GC·운영 환경을 증명하지 않는다.
5. 현재 local profile은 Hibernate `ddl-auto=create`이며 Flyway 운영 baseline이 없다. 다음 index 적용은 Entity 생성 schema와 향후 운영 migration 경계를 분리해 설계해야 한다.
6. 다음 Issue는 `(concert_time_id, seat_number)` 복합 unique index의 고경합 A/B다. 결과를 확인하기 전에는 Hikari 확대, Redis lock, 대기열, outbox 또는 메시지 브로커를 도입하지 않는다.

## 10. 연결

- [Backend Issue #55](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/55)
- [가상 좌석 고경합의 단계별 성능 기준선](staged-contention-performance-baseline.md)
- [좌석 복합 인덱스와 deadlock 비교 기준선](seat-composite-index-deadlock-comparison.md)
- [좌석 복합 unique index migration 안전성 기준선](seat-unique-index-migration-baseline.md)
- [개선 근거 연결표](EVIDENCE_MAP.md)
- [학습·개선 여정](LEARNING_JOURNEY.md)
