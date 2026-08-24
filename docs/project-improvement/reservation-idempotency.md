# 예약 요청 멱등성과 최초 결과 재사용

## 문제

Issue #37에서 첫 예약 transaction이 commit된 뒤 같은 사용자·회차·좌석 요청을 다시 보내면 재고는 중복 차감되지 않지만 `이미 예약된 좌석입니다.`로 실패했다. 현재 `Reservation`은 좌석마다 한 행을 만들기 때문에 복수 좌석을 하나의 요청 결과로 식별할 기준도 없었다.

메모리나 Redis TTL만으로 키를 보관하면 애플리케이션 재시작과 DB transaction rollback 경계를 함께 보장할 수 없다. 따라서 이번 변경은 예약 결과와 같은 MariaDB transaction에 영속 Booking을 저장한다. 이는 결제 승인 증빙이나 Payment 상태를 뜻하지 않으며, 한 예약 요청의 멱등성 소유권과 최초 성공 시각만 표현한다.

## API 계약

`POST /main/detail/{concertId}/reservation`은 선택적 `Idempotency-Key` header를 받는다.

- 키가 없으면 현재 Frontend와 같은 기존 예약 경로를 유지한다.
- 키는 공백일 수 없고 100자를 초과할 수 없다. 위반 시 HTTP 400이다.
- 같은 사용자·같은 키·같은 유효 payload는 최초 성공의 `LocalDateTime` 응답을 그대로 반환한다.
- 같은 사용자·같은 키에 다른 유효 payload를 보내면 HTTP 409로 거부한다.
- 사용자 단위로 키가 분리되며, 키 자체를 인증·결제 식별자로 사용하지 않는다.

payload fingerprint는 실제 예약 처리에 사용하는 `concertId`, `concertTimeId`, 정렬된 좌석 번호 목록으로 SHA-256을 계산한다. client의 `concertDate`와 `concertTime`은 현재 Service가 신뢰하거나 처리에 사용하지 않으므로 fingerprint에서도 제외했다. 좌석 입력 순서만 다른 요청은 같은 의미로 취급하지만 좌석 집합이나 회차·공연이 다르면 충돌한다.

## transaction과 동시 요청 처리

`reservation_booking`은 `(username, idempotency_key)` unique constraint를 가진다. 새 요청은 다음 순서로 실행된다.

1. 기존 Booking을 조회해 있으면 fingerprint를 비교하고 최초 생성 시각을 반환한다.
2. 없으면 별도 transaction Service가 Booking을 `saveAndFlush`해 키 소유권을 먼저 확정한다.
3. 같은 transaction에서 기존 좌석 `PESSIMISTIC_WRITE`, Reservation 생성과 회차 잔여 원자 감소를 수행한다.
4. 좌석·잔여 수량 처리에 실패하면 Booking까지 rollback한다.
5. 두 요청이 동시에 기존 Booking 없음으로 판단하면 unique constraint의 승자만 예약을 실행한다. 패자는 첫 transaction의 commit 이후 unique 위반을 받고, rollback된 transaction 밖에서 승자의 Booking을 다시 읽어 같은 fingerprint이면 최초 결과를 반환한다.

각 Reservation은 nullable `booking_id`로 Booking에 연결된다. 키 없는 기존 호출과 기존 데이터는 이 관계가 없어도 동작한다.

## 검증 결과

MariaDB 10.11.8 Testcontainers의 공연 1개·회차 1개·가상 좌석 24개 fixture에서 다음을 검증했다.

- `[A2,A1]` 성공 뒤 같은 키로 `[A1,A2]` 재시도: 같은 생성 시각, Booking 1·Reservation 2·점유 2·잔여 22
- 두 thread가 모두 기존 키 없음 결과를 얻은 뒤 같은 키·`A1`으로 경쟁: 3회 모두 두 응답 성공·같은 생성 시각, unique 충돌 후 재조회 경로 포함, Booking 1·Reservation 1·점유 1·잔여 23
- 같은 키로 `A1` 성공 뒤 `A2` 요청: HTTP 계약상 409 예외, Booking·Reservation·점유·잔여 상태 무변경
- 존재하지 않는 `Z9`로 실패 뒤 같은 키로 `A1` 재시도: 첫 Booking rollback 후 정상 성공
- 키 없는 호출: Booking 없이 기존 예약·재고 변경 유지
- 공백·101자 키: 상태 변경 전 거부

Service 통합 테스트는 반복 invocation을 포함해 29개, Controller는 3개, 전체 Backend는 59개 invocation이 통과했다. 이 수치는 로컬 단일 JVM과 가상 좌석 fixture의 정합성 검증이며 TPS·p95나 운영 성능을 뜻하지 않는다.

## 적용 한계와 후속 조건

- 현재 Frontend는 `Idempotency-Key`를 보내지 않으므로 실제 화면 호출은 아직 기존 호환 경로를 사용한다. Frontend 연동은 별도 저장소 Issue가 필요하다.
- `reservation_booking`과 `booking_id`는 local/Testcontainers의 Hibernate `ddl-auto=create` schema에서 검증했다. 운영 schema migration 이력은 [ADR-0001](adr/0001-schema-migration-ownership.md)에 따라 아직 보류 상태이므로 배포 가능한 migration을 완료한 것으로 주장하지 않는다.
- Booking은 PG 승인·금액·환불 상태를 저장하지 않는다. 실제 결제 검증과 Payment/Order 상태 머신은 mock 경계부터 별도 Issue로 진행한다.
- Redis, 대기열, outbox와 메시지 브로커는 이번 단일 DB transaction 문제에 필요하지 않아 도입하지 않았다.

## 관련 Issue

- [Backend Issue #37](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/37)
- [Backend Issue #39](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/39)
