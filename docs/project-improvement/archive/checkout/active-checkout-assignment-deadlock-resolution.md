# 활성 Checkout assignment deadlock 해소

## 문제와 판단

#126은 Checkout 준비의 active assignment `PESSIMISTIC_WRITE` range 조회 뒤 서로 다른 assignment를 insert할 때 MariaDB `1213/40001` deadlock이 발생함을 재현했다. 처음에는 `(seat_id, active_until)`을 nullable `active_seat_id` unique key로만 대체했지만, 같은 barrier에서 deadlock이 남았다. 즉 unique key의 형태만 바꾸는 것은 충분하지 않았다.

각 Checkout 준비는 이미 대상 `Seat`를 canonical 순서로 `PESSIMISTIC_WRITE` 잠근다. 따라서 서로 다른 Seat 요청의 active assignment 존재 확인에는 range lock이 필요하지 않고, 같은 Seat의 준비 경쟁은 Seat lock과 active key가 막는다.

## 변경

- history용 `seat_id`와 활성 단일성용 nullable `active_seat_id`를 분리하고, 후자에 unique constraint를 둔다.
- 준비 경로는 Seat lock 뒤 `active_seat_id`의 non-locking 조회를 사용한다.
- 취소는 기존 release와 함께 active key를 해제하고, 예약 확정은 history를 남긴 채 active key만 해제한다.
- READY 만료와 UNKNOWN verification deadline 경과 assignment는 다음 준비 요청이 해당 Seat를 잠근 상태에서 active key를 해제한다.
- 일반 hold release는 Checkout 준비와 경쟁하므로 기존 active assignment locking 조회를 유지한다.

## 검증

MariaDB Testcontainers에서 서로 다른 `A1`·`A2` Checkout 준비가 active assignment 조회 직후 함께 진행되도록 barrier를 구성했다.

| 조건 | #126 기준선 | #128 변경 후 |
| --- | --- | --- |
| 반복 | 3회 | 3회 |
| 결과 | 매회 한 요청 `1213/40001`, 한 Checkout만 commit | 매회 두 Checkout 모두 READY commit |
| inventory | Booking·Payment·Reservation 0 | Booking·Payment·Reservation 0 |
| assignment | 한 row만 유지 | 서로 다른 두 Seat assignment 유지 |

로컬 단일 Backend·Docker Compose MariaDB 10.11.8·mock PG·run별 2,000석 fixture에서 `distributed` 100 RPS·10초를 실행했다. threshold는 관찰을 위해 비활성화했다.

- completed 508, dropped 493, confirmed 508, unexpected 0
- MariaDB deadlock delta 0, final `reservedSeats`·`reservations`·`bookings`·`payments`와 commit transition delta 모두 508
- Hikari pending peak 75, verified path p95 2,738.65ms

이는 같은 local mock fixture의 deadlock·도메인 수렴 관찰이며, 실행마다 host CPU·sampling interval·dropped iteration이 달라 p95·처리량의 운영 성능 비교나 SLA로 사용하지 않는다.

## 제외

- 기존 DB migration, 운영 데이터 변환, 실제 PG·KOPIS 호출
- Hikari pool 재조정, deadlock 재시도, queue·broker
- 실제 운영 환경 성능 주장
