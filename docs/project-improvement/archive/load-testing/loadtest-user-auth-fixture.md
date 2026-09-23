# loadtest 사용자 fixture·인증 계약

연결: [Issue #144](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/144)

## 문제

기존 `GET /loadtest/tokens`는 `load-user-{runId}.{index}` 형식의 JWT만 만들었다. 그러나 `/auth/valid`는 JWT 서명 확인 뒤 `UserRepository`에서 같은 username의 `SiteUser`를 조회한다. 따라서 local Backend를 실제로 연결한 browser E2E에서는 토큰이 유효해도 사용자 조회가 실패했고, 인증 확인 route만 mock으로 대체해야 했다.

## 결정

`loadtest` profile의 토큰 발급은 사용자 fixture를 먼저 보장한다.

1. username은 기존 토큰 형식과 같은 `load-user-{runId}.{001..}`로 고정한다.
2. MariaDB upsert로 같은 runId·인덱스의 동시 insert 충돌을 재사용으로 처리하고, 다른 runId는 다른 username을 사용한다.
3. fixture 계정은 `@loadtest.invalid` 주소와 `loadtest-` 식별자를 사용한다. 운영 사용자 생성·OAuth 로그인 경로에는 연결하지 않는다.
4. token endpoint의 1–500 범위와 runId 검증은 유지한다.

## 검증

환경: `loadtest` profile, MariaDB 10.11.8 Testcontainers. 외부 OAuth·KOPIS·PG 호출 없음.

- `LoadTestControllerTest`는 token endpoint가 JWT 발급 전에 `ensureUsers(runId, count)`를 호출하는지 확인한다.
- `LoadTestFixtureIntegrationTest`는 같은 run 재시도와 동시 재시도의 사용자 단일화, 다른 run의 격리, 잘못된 runId의 사용자 미생성을 확인한다.
- 같은 통합 테스트에서 fixture JWT를 실제 `AuthController.validateToken`에 전달해 `UserRepository` 조회를 포함한 valid 응답을 확인한다.

실행 명령:

```powershell
cd onticket
.\gradlew.bat test --tests "com.onticket.loadtest.LoadTestControllerTest" --tests "com.onticket.loadtest.LoadTestFixtureIntegrationTest"
```

결과: Testcontainers 통합 12건과 controller 2건이 성공했다.

## 한계와 후속

- 이는 local fixture 인증 계약이다. 운영 계정 lifecycle, 비밀번호 정책, OAuth provider, 인증 처리량을 검증하거나 변경하지 않는다.
- Backend가 병합된 뒤 Frontend local browser E2E에서 `/api/auth/valid` route mock을 제거하는 작업은 별도 Issue로 분리한다.
