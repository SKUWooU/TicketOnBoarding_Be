# Docker Compose 기반 로컬 Backend 실행 기준선

## 목적

기존 저장소는 추적된 `application.yml`이 비어 있어 Testcontainers 테스트는 실행할 수 있지만 실제 HTTP 서버를 `bootRun`으로 재현할 수 없었다. 이 기준선은 외부 연동 없이 host의 Spring Boot Backend와 Docker Compose MariaDB를 연결하고 실제 HTTP 요청까지 검증한다.

Backend image나 Frontend는 포함하지 않는다.

```text
HTTP client
    ↓ localhost:18080
Spring Boot Backend (host JVM)
    ↓ localhost:3307
MariaDB 10.11.8 (Docker Compose)
```

## 사전 조건

- Java 21
- Docker Desktop과 Docker Compose
- Backend 저장소 root

실제 KOPIS·CoolSMS·Naver OAuth·결제 credential은 필요하지 않다. local profile의 기본값은 외부 호출에 사용할 수 없는 값이다.

## 구성

### MariaDB

root의 `compose.yml`은 `mariadb:10.11.8` 한 service만 실행한다.

| 항목 | 기본값 |
| --- | --- |
| host port | `3307` |
| database | `onticket_local` |
| username | `onticket` |
| password | `onticket` |
| healthcheck | `mariadb-admin ping` |

named volume `ticketonboarding_be_onticket-mariadb-data`를 사용한다. `docker compose down`은 container와 network만 제거하고 volume은 보존한다.

### Backend local profile

`application-local.properties`는 다음 조건을 명시한다.

- server port `18080`
- Compose MariaDB 접속
- `spring.jpa.hibernate.ddl-auto=create`
- Spring Batch job 자동 실행 비활성화
- JWT issuer와 외부 연동 placeholder의 local 기본값

`ddl-auto=create`는 Backend를 시작할 때 application table을 다시 만든다. named volume이 남아 있어도 application data가 보존되는 환경이 아니며 운영 schema 정책으로 사용하지 않는다. Flyway 전환 조건은 [ADR-0001](adr/0001-schema-migration-ownership.md)을 따른다.

## 실행

저장소 root에서 MariaDB를 시작한다.

```powershell
docker compose -f compose.yml up -d --wait mariadb
docker compose -f compose.yml ps
```

`STATUS`가 `healthy`인지 확인한 뒤 `onticket` 디렉터리에서 Backend를 실행한다.

```powershell
.\gradlew.bat bootRun --args="--spring.profiles.active=local"
```

다음 로그가 나오면 HTTP 요청을 받을 수 있다.

```text
The following 1 profile is active: "local"
Tomcat initialized with port 18080 (http)
Started OnticketApplication
```

## HTTP smoke 검증

별도 terminal에서 실제 DB 조회가 포함된 `/main`을 호출한다.

```powershell
Invoke-WebRequest -UseBasicParsing -Uri "http://localhost:18080/main"
```

2026-08-23 로컬 실행 결과는 다음과 같다.

```text
HTTP status: 200
Content-Type: application/json
Body: {"MostPopularConcertList":[],"onTicketPickList":[]}
```

Backend 로그에서 `ConcertController#getMainPage()` 매핑과 `concert`, `concert_detail` SELECT를 확인했다. 같은 시점의 Compose DB는 `admin` 사용자 1건, 공연 0건이었다. 따라서 빈 목록 응답은 실제 Backend→MariaDB 경로의 데이터 상태와 일치한다.

## startup 동작과 외부 연동

- `KopisBatchConfig`는 `batch` profile 전용이며 local에서 활성화되지 않는다.
- KOPIS scheduler와 상태 갱신 Batch는 현재 주석 처리 상태다.
- CoolSMS와 Naver OAuth는 관련 endpoint를 호출하지 않았다.
- 결제 API 호출 경로는 현재 Backend에 없다.
- `DataInitializer`는 기존 동작대로 local DB에 기본 admin row 1건을 생성한다.

이번 검증 중 외부 API와 운영 DB 호출은 없었다.

## 환경변수 재정의

| 환경변수 | 용도 |
| --- | --- |
| `SERVER_PORT` | Backend HTTP port |
| `ONTICKET_DB_HOST` | MariaDB host |
| `ONTICKET_DB_PORT` | MariaDB published port |
| `ONTICKET_DB_NAME` | database name |
| `ONTICKET_DB_USERNAME` | application DB user |
| `ONTICKET_DB_PASSWORD` | application DB password |
| `ONTICKET_DB_ROOT_PASSWORD` | Compose root password |
| `JWT_ISSUER` | local JWT issuer |
| `KOPIS_API_URL`, `KOPIS_API_KEY` | KOPIS 설정 |
| `COOLSMS_API_KEY`, `COOLSMS_API_SECRET` | SMS 설정 |
| `NAVER_CLIENT_ID`, `NAVER_CLIENT_SECRET` | Naver OAuth 설정 |

실제 secret은 파일에 commit하지 않고 환경변수로만 전달한다.

## 종료와 초기화

Backend terminal에서 `Ctrl+C`로 서버를 먼저 종료한 뒤 root에서 DB를 종료한다.

```powershell
docker compose -f compose.yml down
```

volume까지 제거하는 다음 명령은 local DB를 복구할 수 없게 삭제하므로 명시적으로 초기화할 때만 사용한다. 이번 검증에서는 실행하지 않았다.

```powershell
docker compose -f compose.yml down -v
```

## 관찰된 기존 경고

실제 startup은 성공했지만 다음 기존 조건이 로그에서 확인됐다.

- Spring Data JDBC와 JPA starter가 함께 있어 repository store 판별 로그가 중복됨
- `spring.jpa.open-in-view` 기본 활성화 경고
- Security가 `/**`를 ignore한다는 경고
- 기존 `DataInitializer`가 고정된 admin을 생성함
- developmentOnly DevTools가 `bootRun`에 활성화됨

이 항목은 로컬 실행을 막지 않았으며 이번 Issue에서 수정하지 않는다. dependency 정리, Security·초기 관리자 정책과 관측 설정은 별도 Issue로 분리한다.

## 검증 범위와 한계

- 단일 host JVM과 단일 local MariaDB container
- 빈 공연 데이터의 `GET /main` smoke 요청 1회
- HTTPS·reverse proxy·Frontend·운영 network 미포함
- 처리량·p95·DB connection·lock wait 미측정

따라서 결과는 재현 가능한 로컬 실행 기준선이며 운영 가용성이나 성능 수치가 아니다.

## 연결

- [Issue #19](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/19)
- [Backend 아키텍처 학습 기준선](backend-architecture-learning-baseline.md)
- [ADR-0001](adr/0001-schema-migration-ownership.md)
