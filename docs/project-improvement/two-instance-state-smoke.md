# 2-instance 좌석 hold·Checkout 정합성 smoke

## 목적

단일 Backend에서 검증한 JWT 인증과 `AVAILABLE → HELD → RESERVED` 상태 전이가, 같은 MariaDB를 공유하는 두 Spring Boot 인스턴스와 loopback proxy 경계에서도 수렴하는지 확인한다.

이 문서는 local fixture 정합성 smoke의 근거다. 운영 load balancer, 실제 예매 성능, scale-out 배율이나 SLA를 주장하지 않는다.

## 재현한 결함: 인스턴스별 임의 JWT key

기존 `JwtUtil`은 시작마다 `Keys.secretKeyFor(...)`를 호출했다. 따라서 instance-a에서 발급한 loadtest JWT는 instance-b에서 검증할 수 없었다. round-robin proxy를 통과한 hold 뒤 release가 다른 인스턴스로 가면 인증 실패가 발생할 수 있는 구조였다.

`jwt.secret`을 Base64 환경 설정으로 받고 `Keys.hmacShaKeyFor`로 초기화하도록 변경했다.

- local을 포함한 모든 실행에서 Base64 `JWT_SECRET`을 명시해야 한다.
- 짧거나 잘못된 Base64 key는 application 시작 시 `IllegalArgumentException`으로 거부한다.
- 두 `JwtUtil` 인스턴스가 같은 key로 서로의 token을 검증하는 단위 테스트를 둔다.

## 실행 경계

```text
k6 → 127.0.0.1:18090 loopback proxy
         ├─ instance-a : 18080 / management 18081 / Hikari 12
         └─ instance-b : 18082 / management 18083 / Hikari 12
                              ↓
                    Docker MariaDB (shared fixture DB)
```

- Host physical memory: 약 16GB
- Docker Desktop allocation: 약 8GB / 8 CPU
- 두 JVM의 Hikari max는 각각 12, 합계 24로 고정한다.
- proxy target과 bind address는 `127.0.0.1`·`localhost`·`::1`의 HTTP만 허용한다.
- proxy는 test helper이며 Nginx·Kubernetes·운영 LB가 아니다.
- KOPIS·PG·OAuth 호출은 하지 않는다. Checkout은 `loadtest` Mock payment adapter를 사용한다.

## 실행 순서

MariaDB를 먼저 준비한다.

```powershell
cd D:\project2\TicketOnBoarding_Be
docker compose up -d
```

instance-a는 disposable local schema를 생성하고, instance-b는 이를 `validate`만 한다. 두 프로세스는 별도 PowerShell에서 시작한다.

```powershell
cd D:\project2\TicketOnBoarding_Be\onticket
$env:SERVER_PORT='18080'; $env:MANAGEMENT_SERVER_PORT='18081'
$env:ONTICKET_INSTANCE_ID='instance-a'; $env:ONTICKET_HIKARI_MAXIMUM_POOL_SIZE='12'
$jwtBytes = New-Object byte[] 32; [Security.Cryptography.RandomNumberGenerator]::Fill($jwtBytes)
$env:JWT_SECRET = [Convert]::ToBase64String($jwtBytes) # 다음 terminal에도 같은 값을 설정
.\gradlew.bat bootRun --args="--spring.profiles.active=local,loadtest"
```

```powershell
cd D:\project2\TicketOnBoarding_Be\onticket
$env:SERVER_PORT='18082'; $env:MANAGEMENT_SERVER_PORT='18083'
$env:ONTICKET_INSTANCE_ID='instance-b'; $env:ONTICKET_HIKARI_MAXIMUM_POOL_SIZE='12'
$env:SPRING_JPA_HIBERNATE_DDL_AUTO='validate'
$env:JWT_SECRET='<instance-a와 동일한 Base64 HMAC key>'
.\gradlew.bat bootRun --args="--spring.profiles.active=local,loadtest"
```

proxy는 별도 terminal에서 실행한다.

```powershell
cd D:\project2\TicketOnBoarding_Be
node .\load-test\two-instance\loopback-proxy.mjs
```

기존 k6 script의 `BASE_URL`에는 `http://127.0.0.1:18090`을 사용한다. 다중 instance 결과는 두 management endpoint를 별도로 읽고 최종 fixture snapshot과 교차한다.

## local smoke 결과

| 흐름 | 조건 | 결과 | 최종 상태 |
| --- | --- | --- | --- |
| hold/release churn | 2,000석, 10 RPS, 10초 | 101 hold / 101 release, unexpected 0 | active hold 0, hold row 0, 재고 2,000 |
| Checkout | 2,000석, 2 RPS, 5초 | 11 Checkout confirmed, unexpected 0 | remaining 1,989, reserved·Reservation·Booking·Payment 각 11 |

Checkout commit transition Counter는 instance-a 5와 instance-b 6으로 합계 11이었다. 이 값은 proxy를 거친 동일 fixture run의 완료 수와 일치한다.

## 한계와 다음 판단

- Docker DB·두 JVM·k6가 같은 host 자원을 공유한다. 이 수치로 2-instance 성능 우위나 운영 최대 처리량을 주장하지 않는다.
- HTTP round-robin은 요청 단위의 간단한 test helper일 뿐 endpoint별 균등 분배나 장애 조치를 제공하지 않는다.
- Kafka, outbox, Redis 분산 lock, 실제 reverse proxy/LB 도입은 이 smoke만으로 정당화되지 않는다. 비동기 후속 처리 유실·독립 consumer·재시도 또는 다중 instance에서 DB 제약으로 해결할 수 없는 경합이 재현될 때 판단한다.
