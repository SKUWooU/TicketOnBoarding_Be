# 좌석 임시 점유 API의 고경합 부하 기준선

## 1. 목적

Issue #63에서 구현한 DB 기반 좌석 임시 점유가 가상 좌석 고경합에서 상태 정합성을 유지하는지, 그리고 현재 로컬 환경에서 어느 부하 구간까지 connection·row lock 병목 없이 처리되는지 측정한다.

예약 확정 API에는 Payment·Booking·Reservation 저장과 회차 잔여 수량 갱신이 포함된다. 이번 측정은 그 경로와 분리해 `Seat` row의 `PESSIMISTIC_WRITE`, 소유권 확인, `HELD` 갱신 또는 409 반환 비용만 대상으로 한다. 결과는 Windows 로컬 단일 Backend·Docker MariaDB·명시적 2,000석 fixture의 개발 기준선이며 실제 예매처 처리량이나 운영 SLA가 아니다.

## 2. 측정 모델

### 같은 fixture를 reset해서 재사용

run마다 새로운 2,000석을 누적하면 table 크기·buffer cache·optimizer 조건이 달라진다. `loadtest` profile 전용 reset API가 기존 fixture의 `held_by`, `held_until`만 bulk clear하고, fixture 준비와 reset을 metric 표본 시작 전에 끝낸다. 따라서 모든 run은 같은 물리 좌석 2,000개와 `HELD=0`에서 시작한다.

종료 snapshot은 다음을 함께 검증한다.

- 실제 좌석 수와 잔여 좌석은 2,000 유지
- `reserved`, Reservation, Booking, Payment는 모두 0
- 소유자·만료 중 하나만 존재하는 partial hold는 0
- 저장된 hold row는 5분 TTL 안에서 모두 active
- distributed는 성공 응답 수와 `HELD` row 수 일치
- hot-section과 hot-seat은 각각 최종 `HELD` 40개·1개

### 정상 경합과 서버 오류 분리

- distributed: 서로 다른 좌석을 한 번씩 점유하므로 200만 정상
- hot-section: 40석에 여러 사용자가 집중하며 200과 409가 정상
- hot-seat: 한 좌석에 여러 사용자가 집중하며 200과 409가 정상
- 그 외 4xx·5xx·timeout은 예상 밖 실패

같은 소유자의 재요청은 서버 계약상 200이며 TTL을 연장하지 않는다. 따라서 hot 시나리오의 성공 응답 수는 신규 `HELD` row 수보다 클 수 있다. 상태 정합성은 응답 성공 수가 아니라 최종 40석·1석과 partial state 0으로 판정한다.

초기 smoke에서는 VU 번호로 사용자를 선택해 hot-section의 좌석 재방문 주기와 사용자 주기가 일치했고 61건 모두 같은 소유자의 재요청 200이 됐다. 이를 최종 모델로 사용하지 않고 hot 시나리오는 iteration별 500개 token을 순환하도록 수정했다. 수정 smoke는 61건 중 신규 점유 40건·타인 경합 409 21건·최종 `HELD` 40개를 확인했다.

## 3. 실행 조건

| 항목 | 값 |
| --- | --- |
| 실행일 | 2026-08-31 |
| 환경 | Windows 로컬 단일 Backend·Docker DB, 사용자 제공 기준 메모리 8GB |
| Runtime | Java 21, Spring Boot 3.2.5 |
| DB·pool | MariaDB 10.11.8, Hikari max 10 |
| 부하 도구 | k6 v2.1.0, `constant-arrival-rate` |
| fixture | 50행 × 40석 = 가상 좌석 2,000석, run 전 hold reset |
| 점유 TTL | 기본 5분, run당 10초 |
| 부하 발생기 | `preAllocatedVUs=250`, `maxVUs=250`, 사용자 token 500개 |
| 반복 | warmup 1회 제외, 단계별 3회 |
| 관측 | 약 1초 Hikari Prometheus·MariaDB global status |
| 외부 연동 | KOPIS·실제 PG·SMS·OAuth 호출 없음 |

측정 단계는 다음과 같다.

- warmup: distributed 20 RPS 1회, 집계 제외
- distributed: 50·100·150 RPS
- hot-section: 100·150·200 RPS
- hot-seat: 100·150·200 RPS

각 단계는 10초이며 p95 2초 이상, dropped 1% 이상, 예상 밖 실패 5% 이상이 3회 중 2회 반복되면 해당 시나리오의 상위 단계를 중단하도록 했다. 최종 `issue65-base-01`은 중단 없이 warmup 포함 28회가 모두 유효했다.

## 4. 측정 결과

아래 값은 단계별 3회 중앙값이며 p95 괄호는 최소~최대다. `완료/설정초`는 완료 iteration을 설정한 10초로 나눈 비교 지표이고 graceful stop을 포함한 wall-clock TPS는 아니다.

| 시나리오 | 목표 RPS | 완료/설정초 | 도달률 | 점유 p95 ms | 200 / 정상 409 | 최종 HELD | Hikari active / pending | DB lock waits / time ms |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| distributed | 50 | 50.1 | 100% | 18.10 (15.52~64.50) | 501 / 0 | 501 | 1 / 0 | 0 / 0 |
| distributed | 100 | 100.1 | 100% | 28.22 (26.36~29.07) | 1,001 / 0 | 1,001 | 2 / 0 | 0 / 0 |
| distributed | 150 | 150.1 | 100% | 33.68 (28.40~61.99) | 1,501 / 0 | 1,501 | 3 / 0 | 0 / 0 |
| hot-section | 100 | 100.0 | 100% | 20.48 (20.28~21.01) | 40 / 960 | 40 | 1 / 0 | 0 / 0 |
| hot-section | 150 | 150.0 | 100% | 25.44 (23.78~28.96) | 80 / 1,420 | 40 | 2 / 0 | 0 / 0 |
| hot-section | 200 | 200.0 | 100% | 38.07 (37.02~62.57) | 80 / 1,920 | 40 | 5 / 0 | 0 / 0 |
| hot-seat | 100 | 100.1 | 100% | 24.10 (23.39~128.22) | 3 / 998 | 1 | 2 / 0 | 6 / 25 |
| hot-seat | 150 | 150.1 | 100% | 32.03 (31.64~43.58) | 4 / 1,497 | 1 | 5 / 0 | 69 / 516 |
| hot-seat | 200 | 200.1 | 100% | 29.37 (29.07~43.30) | 4 / 1,997 | 1 | 3 / 0 | 142 / 722 |

27개 유효 측정 전체에서 dropped iteration, 예상 밖 비성공 응답, deadlock은 0이었다. 모든 종료 snapshot은 좌석·점유·예약·결제 불변식을 충족했다.

## 5. 해석

### 서로 다른 좌석 점유

distributed는 50→100→150 RPS에서 p95 중앙값이 18.10→28.22→33.68ms로 증가했지만 목표 도달률 100%, Hikari pending 0, DB row lock wait 0을 유지했다. 이번 범위에서는 서로 다른 좌석의 `HELD` 갱신이 connection 또는 row lock 병목으로 전환되는 지점을 찾지 못했다. 이는 “150 RPS까지 검증한 구간이 안정적”이라는 뜻이며 최대 처리량이나 150 RPS 초과 안정성을 증명하지 않는다.

### 한 구역과 한 좌석 경합

hot-section은 200 RPS에서도 p95 중앙값 38.07ms·pending 0이었고 최종 40석만 점유됐다. hot-seat은 부하가 증가하며 row lock wait 중앙값이 6→69→142회, 누적 lock time이 25→516→722ms로 늘었지만 p95 2초·connection pending·dropped·deadlock으로 이어지지 않았다. 한 좌석 직렬화 비용은 관측됐지만 현재 단계에서는 시스템 포화 병목이 아니다.

### 예약 확정 경로와의 차이

Issue #57의 복합 index 적용 후 검증된 예약 distributed 100 RPS는 p95 143.41ms·DB row lock time 4,565ms였다. 이번 점유 distributed 100 RPS는 p95 28.22ms·lock time 0이었다. 같은 로컬 2,000석·10초·constant-arrival-rate 계열이지만 실행일, VU 수, endpoint와 transaction 작업량이 다르므로 이를 직접적인 전후 성능 개선률로 표현하지 않는다.

차이는 임시 점유가 Seat 한 row만 갱신하는 반면 예약 확정은 Payment·Booking·Reservation 저장과 공통 회차 잔여 수량 갱신까지 포함한다는 코드 경계와 일치한다. 이 비교는 “선택 단계와 확정 단계의 비용을 분리해 측정해야 한다”는 근거이며, 점유 API가 예약 API보다 몇 배 빠르다는 운영 일반화가 아니다.

## 6. 기술 도입 판단

측정 범위에서 정상 경합을 409로 처리하면서 목표 도달률과 상태 불변식을 유지했고 Hikari pending·deadlock이 없었다. 따라서 지금 Redis 분산 lock, 대기열, 메시지 브로커, scheduler 또는 pool 확대를 도입하는 것은 과하다.

다음 조건 중 하나가 별도 반복 측정에서 확인될 때 재검토한다.

- distributed 150 RPS 초과 또는 burst에서 dropped·pending·p95 변곡 반복
- 다중 Backend instance가 같은 DB 외의 점유 원본을 요구
- DB 장애 시 점유 기능과 조회 기능을 격리해야 하는 요구
- 공정한 진입 순서나 순간 유입 흡수가 제품 요구사항으로 확정
- TTL 만료 row의 물리 정리가 실제 조회·저장 병목으로 관측

현재 다음 작업은 분산 기술이 아니라 Frontend가 점유·해제 API와 만료 UI를 사용하도록 연결하고, 점유 없는 legacy 예약 허용을 제거할 조건을 검증하는 것이다.

## 7. 재현 방법

```powershell
docker compose up -d mariadb
cd onticket
.\gradlew.bat bootRun --args="--spring.profiles.active=local,loadtest --spring.batch.job.enabled=false"
```

별도 PowerShell에서 저장소 root 기준으로 실행한다.

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File `
  .\load-test\scripts\Run-SeatHoldContentionBaseline.ps1 `
  -BatchId example-hold-01
```

원본 CSV·summary·manifest·aggregate는 Git에서 제외된 `load-test/results/<batchId>/`에 저장한다. 같은 batch ID는 덮어쓰지 않는다.

## 8. 자동 검증

- Backend 전체 테스트: 120개 통과, 실패·오류·skip 0
- PowerShell 측정 도구 테스트: 기존 도구와 신규 좌석 점유 도구를 합해 192개 assertion 통과
- k6 스크립트 정적 검사: 기존 예약 확정 및 신규 좌석 점유 시나리오 모두 통과
- Docker Compose 설정 검사와 `git diff --check` 통과
- 최종 batch: warmup 1회와 본 측정 27회가 모두 유효했으며 상태 invariant 위반 0건

원본 CSV·summary·manifest·aggregate는 로컬 측정 산출물이므로 Git에 포함하지 않는다. 재현 명령과 집계 수치, 측정 한계만 이 문서에 보존한다.

## 9. 한계

1. 부하 발생기·Backend·Docker DB가 같은 로컬 장비의 CPU·메모리·I/O를 공유한다.
2. 10초 run과 약 1초 polling은 장기 열화·GC와 짧은 spike를 증명하지 못한다.
3. 5분 TTL 안에서 snapshot을 수집했으므로 장시간 만료 청소 비용 측정이 아니다.
4. hot 시나리오의 200은 신규 점유와 동일 소유자 재요청을 포함하므로 `HELD` 수와 같지 않다.
5. 단일 Backend와 단일 MariaDB 결과이며 다중 instance·network latency·운영 데이터 분포를 포함하지 않는다.
6. 실제 공연장 좌석이나 실제 예매처 트래픽이 아닌 명시적 가상 좌석 fixture다.

## 10. 연결

- [Backend Issue #63](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/63)
- [Backend Issue #65](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/65)
- [DB 기반 좌석 임시 점유·만료 상태 전이](seat-hold-expiration-state-transition.md)
- [좌석 잠금 복합 unique index의 고경합 A/B](seat-composite-index-high-contention-ab.md)
- [개선 근거 연결표](EVIDENCE_MAP.md)
