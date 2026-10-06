# 인기 좌석 Hold→Release 지속 경합 기준선

## 왜 다시 측정했는가

Issue #170의 Hold-only 경로는 좌석이 HELD가 된 후 같은 좌석의 후속 요청이 대부분 정상 409로 끝났다. 이는 인기 좌석 선택 분포와 최종 점유 정합성의 근거지만, 같은 좌석에 지속적으로 쓰기가 들어오는 상황은 아니다. 이번 #172는 기존 `hot-seat-churn`의 소유자 해제 경로와 weighted 20·40·200석 선택을 결합한다. 성공한 사용자는 점유를 100ms 유지한 뒤 해제하므로 같은 좌석이 다시 경쟁 대상이 된다.

## 환경과 방법

- Windows 로컬 단일 Backend(`local,loadtest`), MariaDB 10.11.8, k6 2.1.0, 가상 2,000석(50행×40석). 실제 KOPIS·PG·SMS·운영 데이터 호출 없음.
- 고정 seed 17, 인기 집합 20·40·200석, 인기 선택 목표 70%, 사용자별 loadtest 토큰. 각 RPS에서 20→40→200→200→40→20석의 교차 순서로 10초씩 측정했다.
- 50 RPS는 새 전용 Compose `ticketon-controlled172`, 100 RPS는 50 RPS 게이트 통과 뒤 또 다른 빈 DB `ticketon-controlled172-r100`에서 실행했다. 기존 컨테이너와 볼륨은 삭제하지 않았다.
- 매 run 직전 Hold를 reset해 초기 활성 점유·hold row 0을 확인한다. Hold 200 이후 100ms 유지, 동일 사용자 DELETE 204가 완료되어야 성공 cycle이다. 예상된 Hold 409와 예상 밖 응답을 분리한다.
- k6의 성능 threshold는 진단을 위해 비활성화했다. 아래의 측정 유효성 게이트 통과를 p95 목표 달성으로 바꿔 해석하지 않는다.
- k6의 성공·409별 Hold latency, 서버 commit 후 Hold/Release 전이 Counter, 최종 snapshot을 교차 검증한다. 종료 시 물리·잔여 좌석 2,000, HELD/hold row·예약·Booking·Payment·부분 점유 0이어야 한다.
- 모든 run은 목표 도착 대비 완료 99% 이상, dropped·예상 밖 Hold/Release·deadlock 0을 비교 가능한 로컬 측정의 최소 게이트로 삼았다. 이는 운영 SLA가 아니다.

## 결과

| 목표 RPS | 인기 좌석 | 반복 | 완료 | Hold=Release / 예상 409 | 성공 Hold p95 | 409 Hold p95 | Hikari pending peak | DB 전역 row-lock wait 증가 |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 50 | 20 | 1 | 501 | 440 / 61 | 49.35ms | 42.88ms | 0 | 15 |
| 50 | 40 | 1 | 501 | 463 / 38 | 30.32ms | 26.16ms | 0 | 5 |
| 50 | 200 | 1 | 501 | 486 / 15 | 44.67ms | 37.21ms | 0 | 1 |
| 50 | 200 | 2 | 500 | 484 / 16 | 48.76ms | 31.61ms | 0 | 0 |
| 50 | 40 | 2 | 501 | 462 / 39 | 28.62ms | 27.79ms | 0 | 6 |
| 50 | 20 | 2 | 501 | 441 / 60 | 29.11ms | 27.30ms | 0 | 10 |
| 100 | 20 | 1 | 1,001 | 763 / 238 | 112.04ms | 96.80ms | 0 | 36 |
| 100 | 40 | 1 | 1,001 | 871 / 130 | 70.25ms | 49.23ms | 0 | 20 |
| 100 | 200 | 1 | 1,000 | 959 / 41 | 55.25ms | 36.85ms | 0 | 1 |
| 100 | 200 | 2 | 1,000 | 951 / 49 | 139.00ms | 104.09ms | 0 | 6 |
| 100 | 40 | 2 | 1,000 | 850 / 150 | 149.77ms | 112.52ms | 0 | 31 |
| 100 | 20 | 2 | 1,001 | 776 / 225 | 68.57ms | 52.25ms | 0 | 30 |

12회 모두 k6 dropped·예상 밖 Hold/Release·SQL deadlock 0, 최종 HELD/hold row 0, 좌석·예약·결제 불변식 정상이다. 서버의 `acquired`와 `released` 전이 수는 각 run의 Hold/Release 성공 수와 일치했다. 마지막 100 RPS run 종료 후 독립 snapshot 조회에서도 활성 점유·hold row 0, 재고 불변식 `true`였다.

같은 100ms 점유 조건에서 인기 집합이 20석일 때는 200석보다 예상 409와 DB 전역 row-lock wait가 두 반복 모두 많았다. 이는 Hold-only #170과 달리 수백 건의 Hold→Release 쓰기가 계속 반복되는 조건에서 얻은 관찰이다. 그러나 DB wait는 **전역 counter**라 특정 SQL·잠금 원인으로 귀속하지 않는다. 100 RPS·200석 성공 p95가 첫 실행 55.25ms, 두 번째 139.00ms인 것처럼 동일 조건 변동도 크다. 별도 DB의 cache 상태, 공유 호스트 부하, warm-up 미실시, 각 조건 2회라는 한계가 있어 RPS 간 p95 차이를 인과적 성능 저하나 개선으로 해석하지 않는다. 실제 공연장 예매 성능·운영 처리량 주장도 제외한다.

## 재현과 공개 근거

`load-test/results/churn172a`와 `churn172b`에 공개용 manifest·summary만 추적한다. stdout·stderr·CSV와 JWT는 공개 근거에서 제외한다. 새 배치를 실행하려면 빈 전용 DB·새 Compose 프로젝트·새 batch ID가 필요하며 기존 결과를 덮어쓰거나 볼륨을 삭제하지 않는다. runner는 loopback, 프로젝트 label, Flyway 이외 애플리케이션 테이블의 빈 상태, RPS≤100·10초 이하를 확인한다.

```powershell
# 별도 전용 DB를 3309에 시작하고, Backend를 local,loadtest로 그 DB에 연결한 뒤
$env:DOCKER_CONFIG = 'D:\project2'
$env:COMPOSE_PROJECT_NAME = 'ticketon-controlled172-r2'
$env:ONTICKET_DB_PORT = '3309'
docker compose -p ticketon-controlled172-r2 -f compose.yml up -d --pull never mariadb
# 별도 PowerShell 터미널에서 onticket 디렉터리로 이동한 뒤 같은 DB 포트 사용
$env:ONTICKET_DB_PORT = '3309'
$secretBytes = New-Object byte[] 48
[System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($secretBytes)
$env:JWT_SECRET = [Convert]::ToBase64String($secretBytes)
.\gradlew.bat --offline bootRun '--args=--spring.profiles.active=local,loadtest --spring.batch.job.enabled=false'
# Backend health UP을 확인한 뒤 원래 터미널의 저장소 루트에서 실행
powershell.exe -NoProfile -ExecutionPolicy Bypass -File load-test/scripts/Run-ControlledSeatHoldChurn.ps1 `
  -BatchId churn172c -ComposeProject ticketon-controlled172-r2 -Rate 50 -Repeats 2 -DurationSeconds 10
```

현재 MCP는 이 summary를 단일 조회·기본 불변식 검증할 수 있지만, Hold 전용 배치의 manifest 멤버십·점유 해제·결과별 latency 비교 판정은 아직 제공하지 않는다. 다음 Issue의 MCP 검증 계약은 이 12개 공개 summary를 입력으로 삼되, k6 실행 권한은 별도 판단 전까지 추가하지 않는다.
