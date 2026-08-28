# 좌석 경합 실패의 HTTP 409 계약

## 1. 목적

이 문서는 가상 좌석 고경합 측정에서 정상적인 재고 경쟁과 서버 장애를 구분하기 위해 추가한 HTTP 응답 계약과 검증 결과를 기록한다. 성능을 개선하거나 운영 처리량을 주장하는 작업이 아니라, 후속 부하 측정의 오류율을 해석할 수 있게 만드는 선행 작업이다.

## 2. 변경 전 문제

`SeatReservationService`는 비관적 잠금 이후 이미 점유된 좌석을 확인하거나 회차 잔여 수량의 조건부 감소가 실패하면 checked `Exception`을 던졌다.

```text
후발 요청
  → 좌석 PESSIMISTIC_WRITE 대기
  → 선행 transaction commit
  → reserved=true 확인
  → Exception("이미 예약된 좌석입니다.")
  → HTTP 500
```

DB 결과는 한 요청만 성공하도록 정합성을 유지했지만, HTTP에서는 예상 가능한 재고 경합이 내부 서버 오류와 같아졌다. 기존 k6도 모든 비-2xx를 한 Counter에 기록했기 때문에 hot-seat·hot-section 결과에서 정상 충돌과 실제 5xx를 분리할 수 없었다.

## 3. 변경한 계약

`SeatReservationConflictException`을 HTTP 409로 선언하고 다음 두 재고 경쟁에만 적용했다.

| 조건 | 메시지 | HTTP |
| --- | --- | --- |
| 잠금 이후 좌석이 이미 점유됨 | `이미 예약된 좌석입니다.` | 409 Conflict |
| 요청 좌석 수만큼 잔여 수량을 원자 감소하지 못함 | `잔여 좌석이 부족합니다.` | 409 Conflict |

성공 예약의 HTTP 200, 인증 401, 잘못된 멱등 key 400, 멱등 payload 충돌 409, 결제 검증 실패 422·503 계약은 변경하지 않았다. 존재하지 않는 공연·회차·좌석 등 레거시 일반 예외를 일괄 변환하지도 않았다. `rollbackFor = Exception.class` transaction 경계도 유지해 복수 좌석 중간 실패와 재고 감소 실패의 전체 rollback을 보존했다.

## 4. k6 오류 분류

모든 예약 요청은 다음 지표에 기록된다.

| 지표 | 의미 |
| --- | --- |
| `reservation_success` | HTTP 200 예약 결과 |
| `reservation_non_2xx` | 전체 비-2xx 응답 |
| `reservation_expected_contention` | hot-seat·hot-section에서 발생한 HTTP 409 |
| `reservation_unexpected_non_2xx` | 시나리오상 예상하지 않은 비-2xx |
| `reservation_unexpected_failure` | 요청별 예상 밖 실패 비율 |

hot-seat·hot-section에서만 409를 예상 경합으로 처리한다. distributed·idempotent-retry의 409는 정상 결과로 숨기지 않고 예상 밖 실패로 남는다. k6 기본 `http_req_failed`도 hot 시나리오에서는 200·409를 expected status로 설정해 커스텀 의미와 일치시켰다. 모든 시나리오에 `reservation_unexpected_failure < 5%` sanity threshold를 적용했지만 이는 운영 SLA가 아니라 실행 이상을 발견하기 위한 guardrail이다.

## 5. 테스트 검증

- `ReservationControllerTest`: 일반 예약과 검증 예약에서 좌석 경합 예외가 HTTP 409인지 확인한다.
- `SeatReservationConcurrencyIntegrationTest`: 동일 좌석 8개 동시 요청에서 성공 1건·전용 경합 예외 7건과 좌석·예약·잔여 수량 불변식을 확인한다.
- 복수 좌석 반대 순서와 indexed canonical order fixture에서도 패자가 같은 경합 예외로 수렴하며 SQL deadlock 예외가 아닌지 확인한다.
- 잔여 수량 부족 fixture는 좌석·예약 변경을 rollback하고 잔여 수량을 보존한다.
- `VerifiedReservationPaymentIntegrationTest`: 예약 후반 재고 감소 실패가 Payment 소비·Booking·좌석·예약을 함께 rollback하는 기존 계약을 새 예외 타입으로 확인한다.
- 전체 Backend 102개 test가 실패·오류·skip 없이 통과했다.
- `k6 inspect`가 변경된 시나리오와 threshold를 정상 해석했다.

## 6. 로컬 hot-seat smoke

### 조건

| 항목 | 값 |
| --- | --- |
| 실행일 | 2026-08-28 |
| 환경 | Windows 로컬 단일 Backend, 사용자 제공 기준 메모리 8GB |
| Runtime | Java 21, Spring Boot 3.2.5 |
| DB | Docker MariaDB 10.11.8, Hikari 최대 10 connection |
| 시나리오 | `hot-seat`, 20 RPS, 10초 |
| fixture | 가상 공연 1·회차 1·좌석 2,000, 모든 요청 `R001-S001` |
| 외부 연동 | KOPIS·PG·SMS·운영 DB 호출 없음, loadtest mock 결제 |

### 결과

| 지표 | 관찰값 |
| --- | --- |
| 예약 요청 | 201회 |
| 성공 | 1회 |
| 예상 409 경합 | 200회 |
| 예상 밖 비-2xx·5xx | 0회 |
| `http_req_failed` | 0% (`200`, hot 시나리오의 `409`를 expected로 분류) |
| 예약 응답 평균 | 23.62ms |
| 예약 응답 p95 | 36.69ms |
| 예약 응답 최대 | 63.53ms |
| 종료 재고 | 잔여 1,999·점유 1·예약 1 |
| 결과 행 | Booking 1·Payment 1 |
| 종료 불변식 | 충족 |

첫 smoke에서도 성공 1·409 경합 200·예상 밖 오류 0과 종료 불변식은 같았지만, k6 기본 `http_req_failed`가 409를 실패로 세어 98.03%로 표시됐다. expected status를 시나리오별로 정렬한 뒤 독립 run으로 재측정한 값만 위 표에 기록했다. 두 실행의 지연 차이를 성능 개선으로 비교하지 않는다.

## 7. 해석 범위와 다음 단계

이 결과는 한 좌석의 최고 경합에서 DB 잠금 이후 후발 요청이 409로 수렴하고 transaction 정합성을 유지한다는 로컬 계약 검증이다. 실제 공연장, 실제 예매처, 다중 서버, 인터넷 구간, 운영 PG의 처리량이나 SLA를 나타내지 않는다. 20 RPS·10초 수치도 병목 기준선이나 안정 처리량이 아니다.

다음 Issue에서는 부하 중 Hikari active·pending과 MariaDB row lock counter를 시간축 또는 실행 전후 차분으로 수집한 뒤 distributed·hot-section·hot-seat의 도착률을 단계적으로 높인다. 관찰된 최초 병목을 근거로 최소 개선을 선택하고 같은 조건에서 다시 측정한다.

## 8. 연결

- [Backend Issue #49](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/49)
- [가상 좌석 2,000석 고경합 부하 측정 기반](high-contention-load-test-harness.md)
- [개선 근거 연결표](EVIDENCE_MAP.md)
- [학습·개선 여정](LEARNING_JOURNEY.md)
