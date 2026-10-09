# TicketOnBoarding 탑다운 학습 가이드

이 문서는 **현재 코드가 어떤 예매 문제를 다루는지** 먼저 이해하기 위한 진입점이다. 최초 조사 당시의 구조와 지금의 구현을 섞지 않는다. 성능 수치·실행 조건은 [실험 요약](EXPERIMENTS.md), 문제→검증 연결은 [근거 지도](EVIDENCE_MAP.md)에 둔다.

## 1. 한 문장과 전체 흐름

KOPIS 공연 정보를 가져와 공연·회차를 구성하고, **실제 예매처 좌석도가 아닌 자체 가상 좌석**에 대해 조회 → 임시 점유 → Checkout 준비 → 결제 결과 검증 → 예약 확정·취소를 처리하는 서비스다.

```text
KOPIS 목록·상세·시설 ──> Concert · ConcertDetail · Place
                                  └─ 일정 파싱 → ConcertTime → 가상 Seat
React FE ──조회──> 공연·회차·좌석 상태
         └─선택──> Seat Hold(소유자·만료) → Checkout(서버 주문)
                                      → 결제 검증 claim → PG 검증 경계 → Booking·Reservation·Payment
                                      └─ 취소·만료·검증 불명확 상태의 재고 처리
```

KOPIS는 공연 정보를 제공하지만 실제 공연장의 좌석 재고와 결제 승인 결과를 제공하지 않는다. `batch` profile의 [KOPIS 설정](../../onticket/src/main/java/com/onticket/concert/batch/config/KopisBatchConfig.java)과 [수집 서비스](../../onticket/src/main/java/com/onticket/concert/batch/service/KopisService.java)는 카탈로그 적재 경로다. 로컬 부하 실험은 별도의 `loadtest` fixture를 쓰며 KOPIS·실제 PG·SMS를 호출하지 않는다.

## 2. 도메인에서 먼저 구분할 것

| 질문 | 핵심 객체·코드 | 기억할 불변식 |
| --- | --- | --- |
| 무엇을 예매하나? | `Concert` → `ConcertTime` → `Seat`, [공연 조회 Controller](../../onticket/src/main/java/com/onticket/concert/controller/ConcertController.java) | 좌석 번호는 공연 전체가 아니라 **회차 안에서** 식별된다. |
| 결제 전에 누가 좌석을 쓸 수 있나? | [SeatHoldService](../../onticket/src/main/java/com/onticket/concert/service/SeatHoldService.java), `AVAILABLE·HELD·RESERVED` | HELD에는 소유자와 만료가 있고, 최종 예약과 다르다. |
| 여러 좌석을 함께 예매하면? | [SeatReservationService](../../onticket/src/main/java/com/onticket/concert/service/SeatReservationService.java), [SeatRepository](../../onticket/src/main/java/com/onticket/concert/repository/SeatRepository.java), [ConcertTimeRepository](../../onticket/src/main/java/com/onticket/concert/repository/ConcertTimeRepository.java) | 한 요청의 좌석·예약·잔여 수량은 함께 commit/rollback; 재고는 조건부 원자 UPDATE. |
| 중복 요청은 어떻게 구분하나? | [CheckoutService](../../onticket/src/main/java/com/onticket/concert/service/CheckoutService.java), `Idempotency-Key`·fingerprint·DB unique | 같은 key·같은 요청은 결과 재사용, 같은 key·다른 요청은 충돌. |
| 결제 결과는 언제 확정하나? | [CheckoutStatus](../../onticket/src/main/java/com/onticket/concert/domain/CheckoutStatus.java), [CheckoutVerifiedReservationService](../../onticket/src/main/java/com/onticket/concert/service/CheckoutVerifiedReservationService.java) | `READY → PAYMENT_VERIFYING → RESERVATION_CONFIRMED`; 불명확하면 `PAYMENT_VERIFICATION_UNKNOWN`, 취소·만료는 별도 상태. |

## 3. 요청 한 건을 따라 읽기

1. FE가 공연·회차와 좌석 상태를 조회한다. 좌석 조회는 `AVAILABLE`, 다른 사용자의 `HELD`, 확정 `RESERVED`를 구분한다.
2. 좌석 선택은 서버의 Hold를 요청한다. UI에서 버튼을 비활성화하는 것만으로 동시 사용자를 막을 수 없어, DB의 소유권·만료·잠금이 최종 판단이다.
3. Checkout 준비는 선택 좌석·사용자·서버 계산 금액을 결제 시도에 연결한다. 재시도 key와 정규화된 요청 fingerprint를 확인한다.
4. 결제 검증은 짧은 DB 트랜잭션에서 Checkout을 선점한 뒤 commit한다. [외부 검증 포트](../../onticket/src/main/java/com/onticket/concert/service/PaymentVerificationPort.java)의 I/O는 DB 트랜잭션 밖에서 호출한다. 이후 별도 트랜잭션이 현재 상태와 선점 정보를 재검사해 확정한다. `loadtest`의 [Mock adapter](../../onticket/src/main/java/com/onticket/loadtest/LoadTestPaymentVerificationAdapter.java)는 실제 PG가 아니다.
5. 실패·취소·만료는 Hold와 예약 상태가 어긋나지 않게 수렴시킨다. 검증 결과가 불명확하면 성공으로 추정하지 않고 UNKNOWN 재조정 경계를 사용한다.

## 4. 공부할 문제를 네 묶음으로 보기

| 순서 | 질문 | 핵심 개념 | 대표 검증 |
| --- | --- | --- | --- |
| ① 재고 | 다른 좌석을 동시에 예약하면 잔여 수량이 왜 틀어지는가? | transaction rollback, lost update, 조건부 단일 UPDATE, DB 불변식 | [동시 예약 통합 테스트](../../onticket/src/test/java/com/onticket/concert/service/SeatReservationConcurrencyIntegrationTest.java) |
| ② 잠금 | 여러 좌석의 잠금 순서가 왜 deadlock을 만드는가? | `PESSIMISTIC_WRITE`, canonical lock ordering, `(concert_time_id, seat_number)` unique index | 역순·부분 중첩 다좌석 fixture |
| ③ 상태 | 재시도와 느린 PG 응답 사이에 예약이 언제 확정되는가? | idempotency, Checkout claim, transaction/I/O 경계, UNKNOWN·취소 경합 | [Checkout 통합 테스트](../../onticket/src/test/java/com/onticket/concert/service/CheckoutVerifiedReservationIntegrationTest.java) |
| ④ 부하 | 정상 좌석 충돌 409와 DB·pool 포화를 어떻게 구분하나? | k6 도착률/완료율, 성공·409 별 p95, Hikari pending, DB wait, 최종 재고 | [시나리오별 실험 요약](EXPERIMENTS.md) |

GlobalTimes의 검색·캐시·외부 AI 읽기 경로와 달리, 이 프로젝트의 중심은 **좌석 재고를 바꾸는 쓰기 경합과 상태 수렴**이다. 기술 도입보다 재현·불변식·측정 조건의 순서가 먼저다.

## 5. 검증 근거를 읽는 순서

1. [근거 지도](EVIDENCE_MAP.md)에서 문제와 대표 결과를 고른다.
2. [실험 요약](EXPERIMENTS.md)에서 fixture·부하 조건과 측정 한계를 확인한다.
3. 위 코드와 테스트의 실제 분기를 읽는다. `load-test/results/`의 추적된 JSON은 수치 재검산용이며 stdout·JWT는 공개 근거가 아니다.
4. 장기 설계 판단은 [ADR](adr/README.md), 과거 세부 실험은 [기존 아카이브](archive/) 또는 [정리 전 문서 스냅샷](https://github.com/SKUWooU/TicketOnBoarding_Be/tree/53e9b7a7425a7ee961f332ef716e8b05e08484e5/docs/project-improvement)에서 찾는다.

현재 미확정: 30 RPS는 단일 순차 탐색일 뿐 안정 구간이 아니다. 실 PG 정산·환불, 운영 데이터 migration, 대기열/Kafka 도입과 다중 서버 처리량은 여기서 검증하지 않았다. 다음 후보는 같은 조건의 반복·교차 측정과 CPU/메모리 관측을 먼저 보강하는 것이다.
