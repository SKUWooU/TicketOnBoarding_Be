# 예매 트랜잭션·경합 기준선

## 목적

운영 코드를 바꾸기 전에 현재 `SeatReservationService.reserveSeat()`가 MariaDB 경합과 예외 상황에서 만드는 최종 DB 상태를 고정한다. 이 문서의 결과는 로컬 단일 JVM과 가상 좌석 fixture에서 얻은 정합성 기준선이며 처리량이나 실제 예매처 성능을 뜻하지 않는다.

## 실행 조건

- 측정일: 2026-08-19 (Asia/Seoul)
- Java: Eclipse Temurin 21.0.12
- Spring Boot: 3.2.5
- Gradle: wrapper 8.7
- DB: Testcontainers MariaDB 10.11.8
- 애플리케이션: 로컬 단일 JVM, service 직접 호출
- fixture: 공연 1개, 회차 1개, `A1`부터 `C8`까지 가상 좌석 24개
- 동시 요청: 고정 크기 thread pool과 start latch, 시나리오당 8개 요청
- 결정적 경합 지점: 각 요청이 회차 집계를 읽은 뒤 실제 좌석 잠금 repository 호출 직전 테스트 전용 barrier에서 대기하고, 8개가 모두 도착하면 원래 Spring Data repository bean으로 위임
- 외부 연동: KOPIS, CoolSMS, OAuth, 결제와 운영 DB 미사용

실행 명령:

```powershell
$env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-21.0.12.8-hotspot'
.\gradlew.bat test --tests "com.onticket.concert.service.SeatReservationConcurrencyBaselineTest" --rerun-tasks
```

Docker Desktop이 실행 중이어야 한다. 테스트가 disposable MariaDB container와 schema를 만들며 테스트별로 예약·좌석·회차·공연 상세·공연 순서로 fixture를 정리한다.

## 관찰 결과

| 시나리오 | 요청 결과 | 최종 `Seat.reserved` | 예약 row | 최종 `seatAmount` | `24 = reserved + remaining` |
| --- | --- | ---: | ---: | ---: | --- |
| 단일 좌석 `A1` | 성공 1 | 1 | 1 | 23 | 충족 |
| 동일 좌석 `A1` 8개 동시 요청 | 성공 1, 실패 7 | 1 | 1 | 23 | 충족 |
| 서로 다른 `A1`~`A8` 8개 동시 요청 | 성공 8 | 8 | 8 | 23 | **불충족** |
| `[A1, NOT-EXISTING]` 한 트랜잭션 | checked `Exception` | 1 | 1 | 24 | **불충족** |

서로 다른 좌석 시나리오는 한 테스트 실행 안에서 3회 반복하며, 결정적 barrier 적용 후 세 번 모두 `remaining=23, reserved=8, reservations=8`로 같았다. checked exception 결과는 `remaining=24, reserved=1, reservations=1`이었다.

전체 `gradlew test`에서도 기존 context smoke test 1개와 신규 기준선 테스트 invocation 7개, 총 8개가 통과했다. 기존 smoke test에는 같은 disposable MariaDB와 사용되지 않는 외부 연동 placeholder를 제공하고 batch job을 비활성화했다.

### 동일 좌석

`PESSIMISTIC_WRITE` 조회는 실제 SQL의 `FOR UPDATE`로 실행됐다. 첫 요청만 성공하고 대기한 7개 요청은 commit된 `reserved=true`를 읽어 `이미 예약된 좌석입니다.`로 실패했다. 이 fixture에서는 동일 좌석 초과 예약이 재현되지 않았다.

이는 현재 단일 DB 경계와 해당 쿼리 조건에서 관찰한 결과다. 좌석 식별 유일 제약이 없다는 별도 사실이나 다중 인스턴스 전체의 안전성을 증명하지 않는다.

### 서로 다른 좌석과 회차 집계

8개 좌석 row와 8개 예약 row는 모두 정상 반영됐지만, 각 트랜잭션이 좌석 잠금 전에 같은 `ConcertTime.seatAmount=24`를 읽고 `23`을 저장했다. 마지막 update가 앞선 update를 덮어써 8회 감소 중 7회가 유실됐다.

좌석별 `PESSIMISTIC_WRITE`만으로는 별도 row인 회차 집계를 보호하지 못한다. 후속 Issue에서는 회차 row 잠금, 원자적 감소 쿼리, 집계 제거·파생 등 대안을 같은 fixture로 비교해야 한다.

### checked exception과 rollback

첫 좌석을 변경하고 예약 row를 만든 뒤 존재하지 않는 두 번째 좌석에서 checked `Exception`이 발생했다. Spring의 기본 rollback 규칙은 checked exception을 자동 rollback 대상으로 삼지 않으므로 첫 좌석과 예약 row가 commit됐고, 메서드 끝의 회차 집계 감소는 실행되지 않았다.

후속 Issue에서는 예외 타입과 transaction 경계를 명시하고 복수 좌석 예약의 원자성을 검증해야 한다.

### 잠금 조회 인덱스

생성된 `seat` table의 index column은 기본 키 `id`와 외래 키 `concert_time_id`뿐이며 `seat_number`는 포함되지 않았다. 다음 잠금 조회의 `EXPLAIN`은 fixture 24행에서 `type=ALL`, `key=null`, `rows=24`, `Extra=Using where`였다.

```sql
SELECT *
FROM seat
WHERE concert_time_id = ? AND seat_number = ?
FOR UPDATE;
```

이는 복합·유일 인덱스 후보를 조사할 근거지만 24행 실행 계획만으로 운영 규모의 비용이나 실제 잠금 대기 범위를 단정하지 않는다. schema 제약과 실행 계획 개선은 별도 Issue에서 비교한다.

## 검증 경계와 다음 질문

- HTTP, 인증 filter, network, connection pool 포화와 k6 부하는 포함하지 않았다.
- deadlock을 유도하는 반대 순서 복수 좌석 요청은 포함하지 않았다.
- 취소·결제·만료·중복 요청과 상태 전이는 포함하지 않았다.
- 테스트는 현재 결함을 의도적으로 assertion한다. 후속 수정 PR에서는 같은 조건의 기대값을 정합한 결과로 바꿔 회귀 테스트로 전환한다.
- 대기열, Redis·분산 락, outbox와 메시지 브로커를 도입할 근거는 아직 없다.

## 연결

- [Issue #3](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/3)
- [Spring transaction rollback 규칙](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/rolling-back.html)
- [Testcontainers MariaDB module](https://java.testcontainers.org/modules/databases/mariadb/)
