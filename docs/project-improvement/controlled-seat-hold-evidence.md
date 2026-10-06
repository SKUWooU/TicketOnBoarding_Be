# 인기 좌석 집중도별 Hold 경합 실험

## 범위와 질문

Issue #65는 단일 좌석·구역의 Hold 경합을, Issue #166은 인기 좌석 20·40·200석에 요청을 집중한 예약 확정 경로를 측정했다. 이번 Issue #170은 결제·예약 확정 **이전**의 좌석 임시 점유(`POST .../seat-holds`)에 같은 인기 좌석 분포를 적용했다. 질문은 인기 집합 크기가 달라질 때 Hold 성공·예상된 409·최종 점유 행이 일관되는지, 그리고 이 조건에서 서버 대기 신호가 관측되는지다.

로컬 단일 Backend(`local,loadtest`)와 전용 Compose 프로젝트 `ticketon-controlled170`의 MariaDB 10.11.8을 사용했다. 기존 `ticketon-controlled166` DB(3307)는 건드리지 않고 새 DB를 3308에 분리했다. KOPIS·실 PG·SMS·운영 데이터는 호출하지 않았다. `JWT_SECRET`은 해당 로컬 프로세스에서만 임의 생성했다.

## 방법

- 회차 하나에 가상 좌석 2,000개(50행×40석), k6 2.1.0 constant-arrival-rate 목표 50 RPS×10초, VU 200, 선택 seed 17, 인기 집합 20·40·200석, 목표 인기 선택 70%.
- 1차는 20→40→200석, 2차는 200→40→20석 순서다. 동일 fixture run ID를 사용하고 **매 run 직전 Hold를 reset**해 `activeHeldSeats=0`, `holdRows=0`, 재고 불변식을 확인한다. 첫 실행 전에는 전용 DB에 좌석 행이 0개인지 검사하고 기존 데이터를 삭제하지 않는다.
- 완료 iteration을 Hold 200·예상된 좌석 경합 409·예상 밖 비-2xx로 분류한다. k6 결과, 서버의 commit 후 Hold transition counter, 최종 snapshot을 교차 확인한다. 예약·Booking·Payment 생성은 0이어야 한다.
- 6개 run 모두 목표 도착 건수 대비 완료 99% 이상, dropped·예상 밖 비-2xx·deadlock 0을 비교 가능성의 최소 게이트로 삼았다. `hold170a` 결과의 manifest와 공개용 summary만 보존한다. stdout·stderr·CSV와 JWT는 공개 근거에서 제외한다.

## 관측 결과

| 인기 좌석 | 반복 | 완료/누락 | Hold 성공/예상 409 | 전체 Hold 요청 p95 | 최종 HELD | Hikari pending peak | DB 전역 row-lock wait 증가 |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 20 | 1 | 501/0 | 159/342 | 37.31ms | 159 | 0 | 2 |
| 40 | 1 | 501/0 | 182/319 | 23.68ms | 182 | 0 | 0 |
| 200 | 1 | 501/0 | 307/194 | 30.55ms | 307 | 0 | 0 |
| 200 | 2 | 500/0 | 307/193 | 23.28ms | 307 | 0 | 0 |
| 40 | 2 | 501/0 | 182/319 | 23.65ms | 182 | 0 | 0 |
| 20 | 2 | 500/0 | 159/341 | 24.94ms | 159 | 0 | 0 |

모든 run에서 인기 선택은 354~355건, 비인기 선택은 146건이었다. 최종 HELD 수는 서버의 신규 획득(`acquired`) 수와 일치했고, 소유자 재사용·만료 재획득은 0건이었다. 물리 좌석 2,000행·잔여 2,000·예약/Booking/Payment 0·부분 점유 0·점유 불변식 충족을 확인했다. 마지막 run 종료 후 독립 snapshot 조회에서도 HELD/hold row 각 159, 예약·결제 0, 불변식 `true`였다.

인기 집합이 커지면 같은 70% 선택 조건에서 더 많은 **서로 다른 좌석**을 점유할 수 있어 성공 159→307건·예상된 409 342→193/194건으로 바뀐다. 이는 50 RPS의 지속적인 신규 점유 처리량 향상이나 코드 성능 개선을 뜻하지 않는다. 한 번 HELD가 되면 후속 요청은 409로 빠르게 거부될 수 있으므로, 표의 p95는 성공과 충돌이 섞인 응답 시간이다. 20석의 첫 run p95 37.31ms와 재측정 24.94ms처럼 동일 조건의 변동도 있었다.

이번 조건에서는 Hikari pending·SQL deadlock·예상 밖 실패·dropped iteration이 관측되지 않았다. DB 전역 row-lock wait는 첫 run에서만 2회 늘었으며 이를 특정 SQL의 원인으로 귀속하지 않는다. DB buffer cache·호스트 부하는 완전히 통제하지 않았고, warm-up 없는 두 반복만으로 통계적 유의성·운영 SLA·실제 예매처 성능을 주장하지 않는다. 기존 #65의 더 높은 150·200 RPS 실험을 대체하지 않으며, 여기서 대기열·Redis lock·Kafka를 도입할 근거도 확인되지 않았다.

## 재현과 MCP 경계

전용 DB의 `seat` 테이블이 비어 있어야 하며 스크립트는 기존 fixture를 삭제하지 않는다. 다음 명령은 3308이 비어 있는 새 전용 Compose 프로젝트에서만 실행한다.

```powershell
$env:DOCKER_CONFIG = 'D:\project2'
$env:ONTICKET_DB_PORT = '3308'
$env:COMPOSE_PROJECT_NAME = 'ticketon-controlled170'
docker compose -p ticketon-controlled170 -f compose.yml up -d --pull never mariadb
# 별도 터미널: onticket에서 local,loadtest Backend를 3308 DB에 연결해 실행
powershell.exe -NoProfile -ExecutionPolicy Bypass -File load-test/scripts/Run-ControlledSeatHoldHotspot.ps1 -BatchId hold170a -Repeats 2 -Rate 50 -DurationSeconds 10
```

`hold170a` 배치를 같은 DB·출력 경로에 다시 실행하지 않는다. 추가 재현은 새 전용 Compose 프로젝트(예: `ticketon-controlled170-r2`), 다른 DB 포트·batch ID를 사용하고, runner의 `-ComposeProject`도 그 이름으로 지정한다. 기존 volume은 지우지 않는다. 현재 read-only MCP의 목록·요약·일반 좌석 수 불변식 조회는 이 summary를 읽을 수 있지만, `compare_controlled_hotspot_runs`는 #166의 **예약 확정** 배치 계약만 지원한다. 이 Hold 배치를 MCP가 직접 실행하거나 비교 판정했다고 서술하지 않는다. Hold 전용 비교 계약은 입력 필드·도메인 gate가 검증된 뒤 별도 Issue로 다룬다.
