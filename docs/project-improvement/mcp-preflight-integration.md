# MCP 로컬 사전 점검 통합 검증

## 범위와 발견

Issue #178은 #176의 `preflight_controlled_experiment`가 실제 로컬 전용 MariaDB·Backend에서 쓰기 없이 준비 상태를 판정하는지 확인한다. k6, fixture POST, KOPIS·PG·SMS 호출은 하지 않았다. 이는 실험 **준비 상태** 검증이며 50/100 RPS 부하 성공이나 운영 성능 검증이 아니다.

첫 신규 전용 DB(`ticketon-controlled172-r3`)에서는 Backend 시작 후 `site_user=1`이 되어 `-CheckOnly`가 빈 DB 가드에서 거부됐다. 원인은 일반 실행용 `DataInitializer`가 `loadtest` 프로필에서도 관리자 계정을 자동 생성한 것이다. 기존 사용자 데이터를 삭제하거나 빈 DB 가드를 완화하지 않고 `@Profile("!loadtest")`를 적용했다. 일반 프로필의 관리자 초기화 동작은 유지한다. r3 컨테이너는 중지하고 볼륨은 보존했으며, 수정 후에는 별도 신규 r4 DB로 재검증했다.

## 환경과 절차

- Windows 로컬 단일 Backend, Java 21, Spring Boot `local,loadtest`, Gradle `--offline`, MariaDB 10.11.8 로컬 이미지.
- 전용 Compose 프로젝트 `ticketon-controlled172-r4`, DB 127.0.0.1:3309, Backend 127.0.0.1:18080, Actuator 127.0.0.1:18081. 신규 볼륨·Flyway schema 적용 후 애플리케이션 테이블은 0건.
- 배치 자동 시작 `--spring.batch.job.enabled=false`; KOPIS·SMS 자격 정보는 로컬 placeholder. 실제 외부 API·결제·부하 요청 없음.
- `BatchId=preflight178`, 고정 시나리오 `hold-churn-50`(50 RPS·10초·2회·100ms)과 결과 경로 충돌용 기존 `churn172a`를 사용했다. **RPS는 계획값일 뿐 실제 부하를 주지 않았다.**

전용 환경에서 `Run-ControlledSeatHoldChurn.ps1 -CheckOnly`를 호출하고, 설치된 SDK의 stdio MCP 클라이언트로 `preflight_controlled_experiment`를 호출했다. 전자는 `Status=READY`, 후자는 `status=READY`와 예상한 `20→40→200→200→40→20` 여섯 run ID를 반환했다. 인증 토큰·DB 비밀번호·fingerprint 원문은 기록하지 않았다.

## 거부 경로와 쓰기 부재

| 조건 | 결과 | 관찰 |
| --- | --- | --- |
| 새 r4 DB·일치하는 Backend·새 배치 ID | `READY` | runner와 실제 stdio MCP 모두 6-run 계획 반환 |
| 기존 결과 ID `churn172a` | `NOT_READY` | MCP는 `LOCAL_PREFLIGHT_FAILED`만 반환하고 기존 파일은 읽기·수정하지 않음 |
| 로컬 HTTP stub이 DB와 다른 64자리 fingerprint 반환 | runner exit 1 | `Backend datasource does not match...`로 첫 fixture POST 전에 거부 |
| Backend 중지 | `NOT_READY` | MCP는 내부 접속 오류를 노출하지 않고 `LOCAL_PREFLIGHT_FAILED` 반환 |

검증 전후 `site_user`, `seat`, `reservation_booking`은 모두 0건이었다. `load-test/results/preflight178`도 생성되지 않았다. 기존 결과 ID의 거부는 고정 결과 경로 충돌 검사이며, 잘못된 fingerprint는 **실제 두 DB 연결**이 아니라 로컬 stub으로 응답을 대체해 검사했다. 실 DB 연결 오배선을 포함한 운영 환경 보증으로 해석하지 않는다.

## 회귀와 다음 조건

`npm test` 29건은 카탈로그·인자 제한·stderr 비노출·stdio 계약을 확인했다. `Test-SeatHoldContention.ps1` 73 assertions는 일치/불일치 fingerprint의 순수 가드를 검증했다. `DataInitializerProfileTest` 2건은 `loadtest`에서 초기화 bean이 빠지고 `local`에서는 유지됨을 확인했다. Backend CI는 PR에서 확인한다. 실제 부하 실행 권한은 MCP에 없으며, 향후 도입 여부는 별도 Issue에서 준비 확인과 사용자 승인 경계를 정한 뒤 판단한다.
