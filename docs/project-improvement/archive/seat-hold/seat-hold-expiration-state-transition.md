# DB 기반 좌석 임시 점유·만료 상태 전이

## 목적

좌석을 선택한 시점부터 검증된 예약이 확정되기 전까지 다른 사용자가 같은 좌석을 선택하지 못하도록 서버 소유권을 만든다. Issue #61의 “점유 부재” 기준선을 바탕으로 단일 MariaDB에서 상태 전이와 동시성 정합성을 먼저 검증한다.

## 구현 범위

- `Seat.heldBy`, `Seat.heldUntil` nullable 필드
- 현재 시각에 따른 `AVAILABLE`, `HELD`, `RESERVED` 파생 상태
- `POST /main/detail/{concertId}/seat-holds`: 인증 사용자의 다좌석 점유
- `DELETE /main/detail/{concertId}/seat-holds`: 인증 사용자의 다좌석 해제
- 기본 TTL 5분과 주입 가능한 `Clock`
- 기존 비관적 좌석 잠금과 좌석 번호 canonical 정렬 재사용
- 예약 transaction의 활성 점유 소유권 확인과 예약 성공 시 점유 제거
- 좌석 조회의 `availability`, `holdExpiresAt` 추가

## 상태 전이

| 현재 상태 | 조건·명령 | 결과 |
| --- | --- | --- |
| `AVAILABLE` | 점유 요청 | `HELD`, 소유자와 만료 시각 저장 |
| `HELD` | 같은 사용자의 재요청 | 기존 만료 시각 반환, TTL 연장 없음 |
| `HELD` | 다른 사용자의 만료 전 요청 | 409 Conflict, 변경 없음 |
| `HELD` | `now >= heldUntil` 이후 요청 | expired 값을 잠금 안에서 지우고 새 소유자로 회수 |
| `HELD` | 소유자의 해제 | `AVAILABLE` |
| `HELD` | 다른 사용자의 해제 | 409 Conflict, 전체 요청 rollback |
| `HELD` | 소유자의 검증된 예약 | `RESERVED`, 점유 정보 제거 |
| `HELD` | 다른 사용자의 검증된 예약 | 409 Conflict, Payment·Booking·Reservation rollback |
| `RESERVED` | 점유 요청 | 409 Conflict |

점유가 없는 `AVAILABLE` 좌석의 기존 예약은 Frontend 전환 전 호환을 위해 허용한다. 이는 최종 계약이 아니라 단계적 전환 조건이며, FE가 점유 API를 사용한 뒤 legacy 예약 허용 제거 여부를 별도 검토한다.

## 동시성·원자성 원리

점유, 해제와 예약은 모두 좌석 번호를 중복 제거·정렬한 뒤 `PESSIMISTIC_WRITE`로 잠근다. 같은 좌석을 동시에 요청한 두 transaction은 하나가 먼저 소유권을 기록하고, 뒤 transaction은 commit된 활성 점유를 확인해 409로 종료한다.

복수 좌석 요청은 한 transaction이다. 예를 들어 `A1`을 임시 변경한 뒤 `A2`에서 타인 점유를 발견하면 예외가 transaction을 rollback하므로 `A1`도 다시 `AVAILABLE`이다. 해제도 같은 규칙을 사용해 일부 좌석만 풀리는 결과를 막는다.

만료 scheduler는 두지 않았다. 상태 조회는 현재 시각으로 expired 점유를 `AVAILABLE`로 해석하고, 쓰기 요청은 row 잠금 안에서 expired 필드를 정리한 뒤 회수한다. 이 구조는 scheduler 경쟁 없이 정합성을 보장하지만, 요청이 없는 expired row의 nullable 값은 물리적으로 남을 수 있다.

## API 호환 계약

기존 Frontend는 `reserved` boolean만 사용하므로 활성 점유도 `reserved=true`로 반환한다. 신규 연동은 다음 필드를 사용한다.

- `availability`: `AVAILABLE`, `HELD`, `RESERVED`
- `holdExpiresAt`: 활성 `HELD`일 때만 만료 시각

`heldBy`는 사용자 데이터이므로 조회 응답에 포함하지 않는다. 인증 실패는 401, 잘못된 회차·좌석 요청은 400, 예약 또는 타인 점유와 충돌하면 409이다.

## 검증 시나리오

- 같은 사용자가 60초 뒤 재요청해도 최초 5분 만료 시각 유지
- 만료 1ns 전 타인 요청 거부, 정확한 만료 시각에 회수 성공
- 서로 다른 사용자 2명이 같은 `A1`을 동시에 점유할 때 성공 1·conflict 1
- `A1/A2` 취득 중 `A2` 충돌 시 먼저 처리한 `A1`도 rollback
- 타인의 다좌석 해제 실패 시 모든 점유 유지, 소유자 해제 시 모두 해제
- 자기 점유 예약은 성공하고 점유 필드 제거
- 타인 점유 예약은 Payment·Booking·Reservation 전체 rollback
- 활성 점유 조회는 `reserved=true`, `availability=HELD`; expired 점유는 `AVAILABLE`
- 점유·해제 HTTP 인증, JSON 응답, 400·409 상태 계약

## 실행 명령

```powershell
.\gradlew.bat test --tests com.onticket.concert.service.SeatHoldIntegrationTest --tests com.onticket.concert.service.VerifiedReservationPaymentIntegrationTest --tests com.onticket.concert.controller.ReservationControllerTest --no-daemon
```

MariaDB Testcontainers와 mock 결제 검증만 사용한다. KOPIS, 실제 PG, SMS, OAuth 등 외부 연동은 호출하지 않는다.

## 해석과 한계

- 명시적 2석·24석 fixture의 기능 정합성 검증이며 실제 공연장 좌석 또는 운영 성능 측정이 아니다.
- TTL 5분은 정책 기본값이며 사용자 행동 데이터로 최적화한 수치가 아니다.
- 단일 DB가 정합성 원본이다. 다중 인스턴스에서도 같은 DB를 사용하면 row lock은 유효하지만, DB 병목과 장애 격리는 별도 부하 근거가 필요하다.
- Redis, 분산 lock, 대기열, 메시지 브로커와 scheduler는 현재 근거 없이 도입하지 않는다.
- 기존 DB에는 `held_by`, `held_until` migration이 필요하다. 이번 Entity 변경을 운영 migration 완료로 표현하지 않는다.

## 연결

- [Backend Issue #61](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/61)
- [Backend Issue #63](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/63)
