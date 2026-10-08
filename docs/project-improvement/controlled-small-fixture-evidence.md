# 소규모 가상 좌석 fixture의 통제된 첫 쓰기

## 목적과 경계

Issue #180은 #176·#178의 읽기 전용 사전 점검 뒤 **첫 fixture POST 한 번**이 어떤 데이터를 생성하는지 검증한다. 이는 가상 공연 회차의 20석 데이터 준비이며, k6 부하·동시 사용자·실제 KOPIS 공연장 좌석 성능을 측정하지 않는다. MCP에는 쓰기 도구를 추가하지 않았다.

`Run-ControlledSmallFixture.ps1`은 기존 `-CheckOnly`가 전용 Compose label, 빈 애플리케이션 테이블, Backend·DB fingerprint, loopback, 결과 경로 충돌을 검사한 뒤에만 계속한다. 새 `GET /loadtest/fixture-config`의 `rows=2`, `seatsPerRow=10`, `totalSeats=20`을 먼저 확인하고, 고정된 127.0.0.1 Backend에 `/loadtest/runs` POST를 정확히 한 번 호출한다. 이어 API snapshot·Hold snapshot과 전용 DB의 공연·회차·좌석·사용자·예약·Checkout·결제 건수를 대조한다. 실패 시 데이터를 자동 삭제하지 않고 원인을 확인할 수 있게 보존한다.

## 로컬 검증 조건과 결과

- Windows 로컬, Java 21, Spring Boot `local,loadtest`, MariaDB 10.11.8. 전용 Compose 프로젝트 `ticketon-controlled172-r4`, DB 127.0.0.1:3309, Backend 127.0.0.1:18080. 기존 r4 볼륨의 애플리케이션 테이블 0건을 확인하고 시작했다.
- Backend는 `ONTICKET_LOADTEST_ROWS=2`, `ONTICKET_LOADTEST_SEATS_PER_ROW=10`, 배치 자동 실행 비활성으로 구동했다. 실 KOPIS·PG·SMS 자격 정보나 외부 호출은 사용하지 않았다.
- `RunId=smallfixture180`. 첫 실행은 `FIXTURE_READY`, `ConcertId=LOAD-TEST-smallfixture180`, 공연·회차 각 1건, 좌석 20건, 잔여 20건, 사용자·예약·Booking·Checkout·결제·활성 Hold 0건으로 완료했다. API `invariantSatisfied=true`이며 독립 DB COUNT와 일치했다.
- 같은 runner·RunId 재실행은 빈 DB 사전 점검에서 exit 1로 **POST 전에 거부**됐다. 재조회한 좌석 20건·예약 0건은 변하지 않았다.
- DB 포트를 3308로 바꾼 요청도 전용 3309 계약에서 POST 전에 거부됐다. 새 결과 경로는 생성되지 않았다.

전용 r4 DB는 검증 후 컨테이너만 중지하고 볼륨을 보존했다. **현재 r4 DB는 더 이상 빈 DB가 아니므로 후속 부하 실험의 출발점으로 재사용하지 않는다.** 성능 실험은 새 전용 프로젝트·빈 볼륨에서 다시 사전 점검해야 한다.

## 검증과 한계

`Test-ControlledSmallFixture.ps1` 10 assertions와 `LoadTestControllerTest`의 fixture-config 계약이 통과했다. Backend CI와 별도 Reviewer 결과는 PR에서 확인한다. 안전 검사는 로컬·단일 실행자를 전제로 한다. 사전 점검과 POST 사이의 외부 쓰기 경쟁을 분산 환경까지 원자적으로 막는 설계는 아니므로, MCP에 자동 실행 권한을 주거나 2,000석 고경합으로 확대하기 전에 별도 승인·중단·격리 조건을 정의해야 한다.
