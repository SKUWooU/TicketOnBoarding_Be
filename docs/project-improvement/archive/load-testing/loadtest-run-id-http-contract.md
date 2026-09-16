# loadtest runId 입력 오류 HTTP 계약

## 문제

`loadtest` profile의 fixture API는 `runId`를 영문·숫자·하이픈 1~32자로 제한한다. 검증 실패는 Service의 일반 `IllegalArgumentException`으로만 표현되어 HTTP 호출자가 입력 오류와 서버 오류를 구분할 안정적인 계약이 없었다.

## 변경

- 잘못된 `runId`를 나타내는 `InvalidLoadTestRunIdException`을 분리했다.
- `LoadTestController` 범위에서만 해당 예외를 `400 Bad Request` 및 아래 JSON으로 변환했다.

```json
{
  "code": "INVALID_LOADTEST_RUN_ID",
  "message": "loadtest runId는 영문·숫자·하이픈 1~32자여야 합니다."
}
```

운영 예약·결제 API의 예외 처리 정책은 변경하지 않았다. token `count` 검증도 기존 동작을 유지한다.

## 검증

- `LoadTestControllerTest`: `POST /loadtest/runs?runId=invalid_run_id`가 400과 오류 JSON을 반환하는지 확인했다.
- `LoadTestFixtureIntegrationTest`: 잘못된 runId의 초기화 시도 뒤 concert·concert time·seat·place row 수가 모두 0인지 MariaDB Testcontainers로 확인한다.

로컬에서는 Docker daemon이 꺼져 있어 후자의 Testcontainers 실행은 불가했다. 이는 테스트 실패가 아니라 실행 환경 제약이며, CI의 Docker 환경에서 통합 회귀로 확인한다. 외부 KOPIS·PG·SMS·OAuth 호출은 수행하지 않는다.

## 한계

이 변경은 local loadtest 보조 API의 입력 계약만 다룬다. 운영 API 오류 포맷 통일, 실제 외부 연동, 운영 성능 수치 주장은 범위 밖이다.
