# 좌석 잠금 복합 unique index의 고경합 A/B 검증

## 1. 검증 질문

Issue #55는 서로 다른 가상 좌석을 예약하는 `distributed` 100 RPS에서 좌석 잠금 `SELECT ... FOR UPDATE`가 회차 잔여 좌석 `UPDATE`보다 평균 약 410.63배 오래 걸리고, 한 좌석을 찾기 위해 같은 회차의 약 2,000행을 검사한다는 사실을 확인했다. 그러나 실행계획 개선이 실제 HTTP 처리량·지연·connection 대기까지 개선하는지는 아직 검증하지 않았다.

이번 Issue의 질문은 하나다.

> `(concert_time_id, seat_number)` 복합 unique index가 가상 좌석 2,000개의 고경합 예약에서 넓은 잠금 조회를 제거하고, 같은 조건의 처리량·p95·DB lock wait·Hikari pending을 개선하는가?

애플리케이션 Entity나 운영 schema는 변경하지 않는다. 진단용 MariaDB에서 index를 A/B로 전환하고 측정 종료 시 기존 schema로 복원한다.

## 2. 비교 설계

| 항목 | 조건 |
| --- | --- |
| 환경 | 로컬 단일 Backend, MariaDB 10.11.8, Performance Schema 진단 override |
| 외부 연동 | `loadtest` profile의 가상 결제 adapter, KOPIS·실제 PG·SMS 호출 없음 |
| fixture | run별 공연 1·회차 1·서로 다른 가상 좌석 2,000개 |
| 부하 | k6 `constant-arrival-rate`, distributed 50·100 RPS, 각 10초 |
| VU | `preAllocatedVUs=maxVUs=200` |
| 반복 | variant별 warmup 1회 제외, 50·100 RPS 각각 3회 |
| 순서 효과 완화 | 반복 1·3은 current→composite, 반복 2는 composite→current |
| current | 복합 unique index 없음, 기존 FK 보조 index 유지 |
| composite | `UNIQUE (concert_time_id, seat_number)` 진단용 적용 |
| 집계 | 단일 최고값이 아니라 3회 중앙값과 최소~최대 범위 |

runner는 다음 조건 중 하나라도 어기면 유효 결과로 집계하지 않는다.

- index 적용 전 중복 좌석 조합이 0개가 아니다.
- variant와 실제 index·실행계획 증거가 일치하지 않는다.
- 성공 수와 좌석·예약·Booking·Payment·잔여 수량의 종료 snapshot이 일치하지 않는다.
- 예상 밖 비성공 응답 또는 SQL error가 발생한다.
- 좌석 잠금·회차 감소 statement digest 관측률이 성공 수의 95% 미만이다.
- `Performance_schema_digest_lost` 또는 NULL digest event가 발생한다.

고동시성에서 Performance Schema digest가 성공 statement의 일부를 누락 관측할 수 있어 A/B gate는 최소 95%로 두되, 실제 관측률과 instrumentation health를 함께 저장한다. 최종 batch의 최소 관측률은 99.5%였고 최대는 100%였다.

## 3. index lifecycle과 실행계획

복합 index 적용 전 중복 조합을 확인하고, 진단용 index를 만든다.

```sql
CREATE UNIQUE INDEX uk_seat_concert_time_number
ON seat (concert_time_id, seat_number);
```

current variant로 돌아갈 때 복합 index가 외래 키 보조 index 역할까지 대신하고 있다면 `concert_time_id` 단일 index를 먼저 만든 뒤 복합 index를 제거한다. `finally`에서도 current 전환을 수행하며 manifest의 `CurrentSchemaRestored=true`를 확인한다.

| variant | 복합 index | EXPLAIN key | 예상 검사 행 |
| --- | --- | --- | --- |
| current | 없음 | 기존 FK index 또는 full scan 경로 | 2,000 |
| composite | unique, column 순서 일치 | `uk_seat_concert_time_number` | 1 |

SQL 원문과 parameter는 결과 JSON에 저장하지 않고 index metadata·access type·key·예상 행만 남긴다.

## 4. 최종 측정 결과

최종 공개 근거는 `issue57-ab-02` batch 하나다. warmup 2회를 포함해 14회가 완료됐고, 본 측정 12회가 모두 `ValidMeasurement=true`와 종료 재고 불변식을 충족했다.

| variant | 목표 RPS | 완료/설정초 중앙값 (범위) | 도달률 중앙값 (범위) | dropped 중앙값 (범위) | 예약 p95 ms 중앙값 (범위) |
| --- | ---: | ---: | ---: | ---: | ---: |
| current | 50 | 50.1 (50.1~50.1) | 100.00% | 0 | 65.86 (59.87~744.74) |
| composite | 50 | 50.0 (50.0~50.0) | 100.00% | 0 | 55.36 (52.24~87.08) |
| current | 100 | 81.6 (71.1~85.1) | 81.52% (71.03~85.10) | 185 (149~290) | 3,252.76 (2,920.66~4,476.85) |
| composite | 100 | 100.0 (100.0~100.0) | 100.00% | 0 | 91.33 (64.66~144.16) |

| variant | 목표 RPS | Hikari pending peak 중앙값 | DB lock wait 횟수 중앙값 | DB lock time ms 중앙값 | 좌석 잠금 SELECT 평균 ms 중앙값 | 회차 감소 UPDATE 평균 ms 중앙값 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| current | 50 | 0 | 103 | 704 | 6.578 | 0.329 |
| composite | 50 | 0 | 4 | 21 | 0.407 | 0.377 |
| current | 100 | 189 | 815 | 97,970 | 125.888 | 0.305 |
| composite | 100 | 0 | 97 | 1,264 | 0.377 | 1.679 |

100 RPS 중앙값 비교에서 복합 index는 다음 변화를 보였다.

- 완료/설정초: 81.6 → 100.0, 22.55% 증가
- 목표 요청 도달률: 81.52% → 100%, dropped 185 → 0
- 예약 p95: 3,252.76ms → 91.33ms, 97.19% 감소
- Hikari pending peak: 189 → 0
- DB row lock time: 97,970ms → 1,264ms, 98.71% 감소
- 좌석 잠금 SELECT 평균: 125.888ms → 0.377ms, 99.70% 감소

본 측정 12회 모두 예상 밖 비성공 응답 0건, deadlock 0건, 좌석·예약·Booking·Payment·잔여 수량 불변식 충족이었다. 완료/설정초는 설정한 10초 구간의 완료 iteration을 나눈 값이며 graceful stop까지 포함한 wall-clock TPS가 아니다.

## 5. 해석: 원인 확인과 다음 병목

실행계획이 약 2,000행 탐색에서 1행 식별로 바뀐 뒤 좌석 잠금 SQL 시간, DB lock time, Hikari pending, HTTP p95가 같은 방향으로 개선됐다. 따라서 이 fixture의 100 RPS 변곡은 단순히 connection pool이 작아서가 아니라 복합 index가 없는 넓은 좌석 잠금 조회가 주원인이었다고 판단할 수 있다. Hikari pool 확대나 Redis 분산 lock 없이 DB 접근 경로를 먼저 고친 이유도 수치로 설명된다.

동시에 다음 병목 후보도 드러났다. 100 RPS에서 회차 감소 UPDATE 평균은 current 0.305ms에서 composite 1.679ms로 늘었다. 좌석 조회가 빨라지면서 더 많은 transaction이 같은 회차 잔여 수량 행에 도달해 공유 counter 경합 비중이 커진 것으로 해석할 수 있다. 다만 composite 조건에서도 목표 100 RPS·pending 0·p95 91.33ms를 유지했으므로, 현재 근거만으로 counter 분리·비동기 재고·대기열을 도입하는 것은 과하다. 더 높은 단계 또는 burst에서 다시 변곡이 관측될 때 별도 Issue로 검증한다.

## 6. 폐기한 측정과 재현성

초기 batch `issue57-ab-01`은 composite 100 RPS에서 Performance Schema 좌석 digest 관측 수가 실제 성공·DB row 수보다 적어 중단됐다. DB 종료 상태와 회차 UPDATE digest는 성공 수와 일치했고 digest table 포화 지표도 0이어서 애플리케이션 정합성 실패가 아니라 고동시성 관측 coverage 문제로 분리했다.

이를 숨기거나 성공 수 일치 검증을 제거하지 않고 다음을 추가했다.

- statement별 관측률과 최소 관측률 저장
- A/B 최소 coverage 95% gate
- digest count가 성공 수를 초과하면 실패
- SQL error 0 확인
- `Performance_schema_digest_lost=0`, NULL digest event 0 확인

`issue57-ab-01`과 단일 smoke 수치는 최종 비교값에 포함하지 않는다. Git에서 제외되는 로컬 원본은 `load-test/results/issue57-ab-02/`에 있으며, 공개 문서에는 재현 조건과 중앙값·범위만 남긴다.

실행 명령은 다음과 같다.

```powershell
docker compose -f compose.yml -f compose.statement-diagnostics.yml up -d mariadb
cd onticket
./gradlew.bat bootRun --args="--spring.profiles.active=local,loadtest --spring.batch.job.enabled=false"
```

별도 PowerShell에서 저장소 root 기준으로 실행한다.

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File `
  .\load-test\scripts\Run-SeatIndexContentionComparison.ps1 `
  -BatchId example-ab-01
```

## 7. 적용 범위와 다음 결정

이번 결과는 로컬 8GB 개발 장비, 단일 Backend·단일 MariaDB, 가상 좌석 2,000개, mock 결제, 10초 부하의 비교 결과다. 실제 공연장 좌석, 운영 예매처 처리량, 다중 인스턴스, 네트워크, 실제 PG, 운영 SLA를 의미하지 않는다.

복합 unique index는 검증된 개선 후보지만 아직 제품 schema에 적용된 상태가 아니다. 후속 Issue에서는 다음 경계를 지켜 최소 적용한다.

1. Entity 생성 schema에 unique 제약을 표현한다.
2. 기존 DB는 Issue #17의 중복 사전 점검과 schema ownership ADR을 따른다.
3. canonical 복수 좌석 잠금 순서와 deadlock 회귀를 유지한다.
4. 같은 50·100 RPS A/B 또는 적용 후 회귀로 성능·정합성을 재확인한다.
5. 전체 Flyway baseline 전환은 복합 index 한 건과 분리한다.

## 8. 자동 검증

- 기존 수집기 42개·baseline runner 19개 assertion
- statement digest 20개·SQL별 진단 runner 15개 assertion
- 신규 index lifecycle 21개·A/B runner 20개 assertion
- PowerShell 합계 137개 assertion
- `k6 inspect load-test/k6/reservation-contention.js`
- `docker compose -f compose.yml -f compose.statement-diagnostics.yml config --quiet`
- MariaDB Testcontainers 포함 Backend 전체 102개 test 강제 재실행, 실패·오류·skip 0
- `git diff --check`

## 9. 연결

- [Backend Issue #57](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/57)
- [회차 잔여 좌석 단일 행 병목 가설의 SQL별 진단](concert-time-row-bottleneck-diagnosis.md)
- [좌석 복합 index와 deadlock 비교 기준선](seat-composite-index-deadlock-comparison.md)
- [좌석 복합 unique index migration 안전성 기준선](seat-unique-index-migration-baseline.md)
- [가상 좌석 고경합의 단계별 성능 기준선](staged-contention-performance-baseline.md)
- [ADR-0001: schema migration ownership](adr/0001-schema-migration-ownership.md)
