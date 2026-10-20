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
| 중복 요청은 어떻게 구분하나? | [ReservationIdempotencyService](../../onticket/src/main/java/com/onticket/concert/service/ReservationIdempotencyService.java), [CheckoutService](../../onticket/src/main/java/com/onticket/concert/service/CheckoutService.java), `Idempotency-Key`·fingerprint·DB unique | 같은 사용자·같은 key·같은 요청은 결과 재사용, 같은 key·다른 요청은 충돌. |
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

현재 미확정: 20/30 iterations/s 반복·교차 관측은 로컬 단기 fixture의 결과이지 안정적인 처리 용량 보증이 아니다. 실 PG 정산·환불, 운영 데이터 migration, 대기열/Kafka 도입과 다중 서버 처리량은 여기서 검증하지 않았다.

## 6. MCP 단계에서 배운 것과 종료 기준

**측정 → 근거 조회 → 가설 → 제한된 실험 → 판단**의 순서로 읽는다. MCP는 결과를 AI가 읽는 인터페이스이고, 숫자 검증은 코드가 수행한다. 가설을 제안했다고 원인이 확정되거나 실행 권한이 생기는 것은 아니다.

#208의 SQL 횟수 불일치는 예매 실패와 다르다. #210에서는 독립 SQL의 commit 표시·DB 누적 값과 digest 통계를 대조했다. 직렬은 일치했지만 병렬에서는 실제 DB 반영이 정상이어도 집계 수가 부족했다. 따라서 digest 실행 수를 정확한 업무 완료 횟수로 사용하지 않으며, 이것만으로 JDBC 경로의 내부 원인을 확정하지 않는다.

기존 예매 경합으로 한 번 복귀해 결과를 MCP로 조회하는 데서 이 단계를 마무리한다. 수집 방식·분류·실측 한계는 [실험 요약 8절](EXPERIMENTS.md#8-제한된-sql-대조와-예매-경합-복귀-210)을 참고한다. 더 많은 계측·AI 정확도 비교·RAG·증량은 필수 후속 작업이 아니다. 학습은 다시 좌석 잠금 → Hold → Checkout → 결제 검증의 서비스 경로로 돌아간다.

## 7. 다섯 개선 주제의 전후 흐름

아래 순서는 성능 근거 → DB 정합성 → 요청 재시도 → 결제 경계 → 분석 도구다. **과거 기준선, 현재 코드, 측정한 결과**를 구분해 읽는다. 과거 재현 명령을 현재 코드에서 실행한다고 과거 결함이 다시 발생하는 것은 아니다.

### ① 좌석 잠금 조회·커넥션 대기 병목

- **개선 전:** 회차 ID와 좌석 번호로 한 좌석을 찾지만, 적절한 복합 index가 없어 회차의 약 2,000행을 탐색·필터링했다. 기존 FK index는 회차를 좁혀도 좌석 번호까지 바로 식별하지 못한다. 모든 조건을 무조건 full scan이었다고 표현하지 않는다.
- **원인:** 넓은 `SELECT ... FOR UPDATE` 조회가 느려지면서 transaction의 커넥션 반환도 지연됐다. Hikari pending은 **커넥션을 빌리려고 기다리는 요청 스레드 수**이지 DB lock 수가 아니다. pool 확대보다 DB 접근 경로를 먼저 고쳤다.
- **개선 후:** `UNIQUE(concert_time_id, seat_number)`로 회차 안의 좌석 식별과 중복 방지를 결합했다. [SeatRepository](../../onticket/src/main/java/com/onticket/concert/repository/SeatRepository.java)의 잠금 조회와 [Seat](../../onticket/src/main/java/com/onticket/concert/domain/Seat.java)의 제약을 함께 읽는다.
- **검증:** [A/B 기록](archive/load-testing/seat-composite-index-high-contention-ab.md)의 같은 가상 2,000석·distributed 100 RPS·10초·조건별 3회 중앙값에서 p95 **3,064.39→143.41ms**, Hikari pending peak **189→0**, 예상 검사 행 **2,000→1**. warm-up 제외·교차 순서·종료 재고 검증을 사용했다.
- **한계:** 서로 다른 좌석에 분산된 예약 부하다. 인기 한 좌석을 반복 점유하는 Hold 실험과 다르며, 2,000명이 동시에 예약했거나 2,000석을 동시에 처리했다는 뜻이 아니다. 회차 잔여 수량 행의 경합은 남았고 운영 처리 용량·다중 서버 성능은 검증하지 않았다.

### ② 다좌석 원자성·재고 정합성·잠금 순서

- **개선 전:** checked exception 이후 첫 좌석·예약만 commit되는 부분 반영과, 다른 좌석의 동시 예약이 이전 잔여 수량을 덮어쓰는 갱신 유실을 [초기 기록](archive/foundations/reservation-atomicity-inventory-consistency.md)에서 재현했다. 둘은 각각 rollback 경계와 공유 수량 갱신 방식의 문제다.
- **원인과 잠금 조건:** 좌석 잠금만으로 **다른 좌석 요청이 공유하는 회차 수량**까지 보호하지는 못한다. 또 [indexed 비교](archive/foundations/seat-composite-index-deadlock-comparison.md)에서 역순 요청이 서로 다른 첫 좌석을 잠근 뒤 두 번째 좌석을 기다리는 deadlock을 재현했다. 인덱스가 없던 좁은 fixture에서는 첫 잠금부터 직렬화돼 deadlock이 관측되지 않았다는 차이도 보존한다.
- **개선 후:** [SeatReservationService](../../onticket/src/main/java/com/onticket/concert/service/SeatReservationService.java)의 `rollbackFor = Exception.class`, [정규화 코드](../../onticket/src/main/java/com/onticket/concert/service/ReservationRequestCanonicalizer.java)의 좌석 문자열 정렬, [조건부 감소 쿼리](../../onticket/src/main/java/com/onticket/concert/repository/ConcertTimeRepository.java)를 함께 읽는다. `seat_amount >= 요청 수량`일 때만 `seat_amount = seat_amount - 요청 수량`을 수행하고 영향 행이 1이 아니면 전체 rollback한다. 현재 순서는 숫자 자연 정렬이 아니라 문자열의 일관된 정렬이다.
- **검증:** [부분 중첩 반복 기록](archive/foundations/overlapping-multi-seat-write-contention.md): JUnit·MariaDB Testcontainers에서 회차당 8개 요청·요청당 4좌석, 50회 fixture 초기화 후 **400회 시도 = 성공 100 + 정상 좌석 충돌 300**. SQL deadlock·예상 밖 오류·재고 불변식 위반은 각각 **0건**. 각 회차 좌석·예약·잔여 수량도 대조했다.
- **한계:** 이는 반복 쓰기 경합의 정합성 결과이지 TPS·p95 개선이 아니다. canonical ordering은 이 좌석 획득 경로의 순환 대기를 막는 설계이며, 다른 테이블·다른 잠금 경로까지 모든 deadlock이 없어졌다는 증명은 아니다. FE의 선택 불가 표시는 오래될 수 있어 서버가 최종 판정한다.

### ③ 네트워크 재시도와 예약 멱등성

- **개선 전:** 요청이 반영됐지만 응답을 받지 못해 재시도하면 기존 성공 결과를 돌려주기 어렵고, 사전 조회만으로는 두 요청이 동시에 “없음”을 확인하는 경합을 방어할 수 없다. 모든 재시도가 실제 중복 Booking을 만들었다고 일반화하지 않는다.
- **구분:** `Idempotency-Key`는 **같은 논리 요청의 재시도에 재사용하는 키**다. 사용자별 key로 범위를 나누며, fingerprint는 공연·회차·정렬된 좌석 payload의 SHA-256이다. 사용자 범위는 조회/제약으로 분리하고 이 예약 fingerprint 자체에 username을 넣지는 않는다. 같은 payload는 다른 key에서도 같은 fingerprint가 될 수 있다.
- **개선 후:** [ReservationIdempotencyService](../../onticket/src/main/java/com/onticket/concert/service/ReservationIdempotencyService.java)에서 같은 key·같은 fingerprint는 기존 결과를 반환하고 다른 fingerprint는 충돌로 거절한다. [BookingRepository](../../onticket/src/main/java/com/onticket/concert/repository/BookingRepository.java)·[Booking](../../onticket/src/main/java/com/onticket/concert/domain/Booking.java)의 사용자/key unique 제약은 동시 insert의 최종 방어다. 제약 위반 뒤에는 실패한 transaction 밖에서 재조회해 결과 재사용 또는 충돌로 수렴한다.
- **검증:** [반복 기록](archive/foundations/repeated-idempotency-contention.md): JUnit·MariaDB Testcontainers에서 같은 사용자·key·payload의 요청 8개를 50회 경합시켰다. **400회 시도 → Booking 50건·기존 결과 재사용 350회·중복 Booking 0건·예상 밖 오류 0건**. 다른 payload 거절은 별도 테스트다.
- **한계:** 동일 key 계약의 검증이다. 다른 key로 재전송하면 같은 논리 요청인지 이 계약만으로 알 수 없다. 좌석 소유권/충돌 방어와 멱등성은 별개이며 400회는 처리 용량 수치가 아니다. 결제 검증 fingerprint는 paymentId·merchantUid까지 포함하는 별도 경로다.

### ④ 지연된 PG 검증과 예약 확정 경계

- **개선 전·위험:** 초기 예약 경로는 PG 근거 없이 결제 완료 상태를 만들 수 있었다. 현재는 임시 점유와 서버 주문 Checkout을 연결한다. 외부 I/O를 transaction 안에서 기다리면 lock·커넥션을 오래 점유할 수 있다는 것은 설계 위험이며, 이 항목에서 과거 PG 지연 성능을 실측한 것은 아니다.
- **개선 후:** [CheckoutVerifiedReservationService](../../onticket/src/main/java/com/onticket/concert/service/CheckoutVerifiedReservationService.java)를 따라 **짧은 선점 transaction → commit → PG 조회 → 별도 확정 transaction**을 읽는다. [선점 서비스](../../onticket/src/main/java/com/onticket/concert/service/CheckoutPaymentVerificationTransactionService.java)가 `READY → PAYMENT_VERIFYING`과 검증 정보를 저장한다. DB lock은 풀려도 저장된 상태·선점 정보가 다른 검증 요청을 제어한다.
- **확정 조건:** PG 응답 뒤 현재 상태·선점 정보·승인 내용을 확인해 Booking·Reservation·Payment를 확정한다. 진행 중 중복은 충돌, 완료 후 동일 요청은 결과 재사용이다. 명확한 거절과 timeout/조회 불가를 구분하고, 결과가 불명확하면 `PAYMENT_VERIFICATION_UNKNOWN`으로 보류한다. [CheckoutStatus](../../onticket/src/main/java/com/onticket/concert/domain/CheckoutStatus.java)·[통합 테스트](../../onticket/src/test/java/com/onticket/concert/service/CheckoutVerifiedReservationIntegrationTest.java)의 분기가 기준이다.
- **검증:** [I/O 경계 기록](archive/foundations/payment-verification-io-boundary.md)의 `delayedProviderVerificationDoesNotKeepCheckoutTransactionLocked`: Mock PG 응답을 CountDownLatch로 대기시킨 동안 중복 요청이 **1초 제한 내 충돌**, 외부 검증 **1회**, 재개 후 Booking·Reservation 각각 **1건**. 1회/1건은 적은 부하를 강조하는 숫자가 아니라 동일 결제의 중복 처리 방지 기대값이다.
- **한계:** 1초는 테스트의 완료 대기 제한이지 운영 응답시간 SLA나 p95가 아니다. Mock PG는 외부 결과를 통제하는 fixture이고 실제 PG 결제·정산·환불 검증이 아니다. 중복 요청의 반환으로 해당 Checkout lock이 외부 대기 내내 유지되지 않음을 확인한 것이지 모든 DB 자원의 보유 시간을 계측한 것은 아니다.

### ⑤ MCP를 통한 제한된 근거 조회와 실험 연결

- **개선 전:** 사람이 summary·manifest·문서를 찾아 조건과 수치를 대조할 수는 있지만, AI에 임의 파일을 읽게 하면 입력 범위와 비교 조건이 제각각이 될 수 있다. 기존 방식이 불가능했다거나 시간 절감 효과를 실측했다는 뜻은 아니다.
- **개선 후:** [MCP 도구 목록](../../tools/ticketon-evidence-mcp/README.md)과 [근거 조회 코드](../../tools/ticketon-evidence-mcp/src/evidence.ts)를 먼저 읽는다. 고정된 결과 경로·허용 artifact·민감 필드 제한을 적용하고 완료·조건·재고 검증이 부족하면 수치를 차단한다. 수치 검증/판정은 코드, 자연어 해석/가설은 AI, 실행 판단은 사용자·Implementer 역할이다.
- **실제 흐름:** 저장된 결과 → MCP 조회 → 관측 사실/가설/근거 ID를 구분한 제안 → [offline 계약](../../tools/ticketon-evidence-mcp/src/experimentProposal.ts) → 승인된 제한 실험 → 결과 재조회. `preflight`는 CheckOnly 준비 점검이고, MCP가 k6 실행·fixture 적재·RPS 증량·DB 변경을 수행하지 않는다. 이 단계에 RAG나 별도 모델 API를 도입하지 않았다.
- **검증:** [#208~#210 실험](EXPERIMENTS.md#7-mcp-근거에서-선택한-sql-진단-208)에서 SQL 진단 중단과 독립 DB 대조를 기록했다. SQL **600 transactions**의 실제 반영은 정상이나 병렬 digest 횟수가 부족해 업무 누락으로 해석하지 않았다. 기존 Hold 경합 복귀의 **1,804 iterations = 성공·해제 647 + 정상 충돌 1,157**을 실제 MCP로 조회했고 `RESOURCE_REVIEW_REQUIRED / MANUAL_REVIEW_ONLY`를 유지했다.
- **한계·마무리:** 이점은 임의 로그 대신 검증 계약이 있는 입력을 반복 제공하고 비교 불가 조건을 차단하는 인터페이스다. AI 정확도·분석 시간 절감·원인 확정은 미검증이다. 정상 409는 시스템 오류가 아니며 iteration에는 성공 시 Hold POST와 Release DELETE가 있어 HTTP 요청 수와 같지 않다. 호스트 CPU의 원인 단정·자동 증량 없이 이 단계를 종료했다.

### 학습 후 스스로 설명할 질문

1. 좌석별 잠금이 있어도 회차의 잔여 수량 UPDATE가 필요한 이유는 무엇인가?
2. 인덱스로 탐색 범위를 줄였을 때 왜 잠금 순서 문제를 함께 검토해야 하는가?
3. 같은 key·같은 payload, 같은 key·다른 payload, 다른 key의 차이는 무엇인가?
4. DB lock을 풀어도 `PAYMENT_VERIFYING` 상태로 중복 검증을 제어할 수 있는 이유는 무엇인가?
5. MCP 판정에서 정상 충돌·재고 위반·근거 부족·호스트 자원 신호를 왜 구분하는가?

답은 코드 → 대표 테스트 → 해당 측정 조건으로 확인한다. 기술 이름이나 0건 수치만 외우지 않고, 보장하는 경로와 검증하지 않은 범위를 함께 설명한다.

## 8. 계정 복구의 임시 차단 경계 (#214)

기존 `/users/changepwd`는 전화번호와 새 비밀번호만으로 변경했고, `/users/issmscode`는 코드 일치 **또는** 계정 존재를 성공으로 처리했다. 회원가입용 SMS 응답은 코드 자체를 반환하고 singleton 필드에 마지막 번호·코드를 보관한다. 이 상태에서 코드 검사만 추가하면 안전한 복구 증명이 되지 않는다.

사용자 승인에 따라 두 복구 POST 경로를 **403 / `PASSWORD_RECOVERY_DISABLED`**로 일시 차단했다. 요청 본문을 인증 근거로 해석하지 않고 계정 조회·SMS·비밀번호 저장을 수행하지 않는다. 전화번호만 받아 저장하던 `UserService.ChangePassword`도 제거했다. 기존 FE 비밀번호 찾기는 실패 응답을 받으며 성공 화면으로 진행할 수 없다. 로그인 후 `/users/change`와 회원가입 SMS 계약은 이번 범위에서 유지했다.

수정 전 독립 MockMvc 직접 재설정 테스트는 `403` 기대에 **200 응답**으로 실패했다. 수정 후 검증은 [PasswordResetDisabledTest](../../onticket/src/test/java/com/onticket/user/controller/PasswordResetDisabledTest.java)의 직접 요청·임의 코드/증명·빈/잘못된 본문·반복·동시 요청에서 고정 차단 응답과 관련 mock의 **호출 없음**을 확인한다. 실제 DB·SMS를 사용한 OTP 만료·소비·원자성 테스트가 아니며 안전한 복구 기능을 완성한 것도 아니다.

재개 조건은 번호별 인증 challenge, 만료·시도 제한, 목적이 분리된 일회용 복구 권한, 권한 소비와 저장의 원자성, 코드 미노출, FE 새 계약의 검증이다. 기존 공유 SMS 상태·회원가입의 보안 문제와 전역 Security 필터 제외는 별도 미해결 영역으로 남긴다. 현재 차단을 전체 인증 안전성 확보로 확대 해석하지 않는다.

## 9. 리뷰 변경의 소유권 경계 (#216)

기존 Controller는 요청의 `author`와 로그인 이름을 비교했지만, 서비스는 `reviewId`의 실제 작성자·공연 소속을 검증하지 않았다. 본문에 로그인 이름을 넣은 타인 리뷰 삭제 요청은 수정 전 MockMvc에서 `403` 기대에 **200 응답**으로 실패했다. 실제 DB를 삭제한 실험은 아니다.

현재 [ReviewController](../../onticket/src/main/java/com/onticket/concert/controller/ReviewController.java)는 JWT의 사용자만 전달하고, 요청의 `author`·역할은 무시한다. [ReviewService](../../onticket/src/main/java/com/onticket/concert/service/ReviewService.java)는 DB 리뷰의 공연 소속과 작성자, DB 사용자 존재·관리자 역할을 확인한 뒤 변경한다. 작성자는 수정·삭제 가능, 관리자 권한은 타인 리뷰 삭제에만 적용한다. 없는 리뷰·다른 공연은 404, 권한 부족은 403, 유효한 요청에서 인증 없음은 401이다. 삭제·수정과 해당 공연 평점 재계산은 각각 서비스 transaction 안에서 수행한다.

[ReviewAuthorizationTest](../../onticket/src/test/java/com/onticket/concert/controller/ReviewAuthorizationTest.java)는 실제 서비스와 mock Repository를 연결한 MockMvc 테스트다. 작성자·역할 위조, 다른 공연(관리자 포함), 계정 없음, 리뷰 없음과 정상 작성자/관리자 정책을 검증하고 거절 요청의 리뷰·평점 저장 미호출과 fixture 값 불변을 확인한다. 기존 FE URL과 `reviewId`·`content`·`starCount` 본문을 유지하며, 추가 author 필드는 호환을 위해 무시한다.

이 검증은 실제 DB rollback·리뷰 동시 평점 갱신·입력 평점 범위·전역 Security/CSRF를 검증한 것이 아니다. 읽기/생성 API는 이번 범위에서 바꾸지 않았으며 평점 집계 성능과 경합은 별도 후속 후보다.

## 10. 결제 검증 없는 예약 진입점 폐쇄 (#218)

기존 POST `/main/detail/{concertId}/reservation`은 JWT 인증 후 `ReservationIdempotencyService.reserve`로 바로 연결됐다. 멱등성·재고 원자성은 중복과 경합을 제어하지만 **결제 승인 증명은 아니다**. 기존 MockMvc 테스트도 이 경로의 200 응답을 정상 계약으로 검증하고 있었다.

이번 수정은 내부 예약 로직을 삭제하지 않고 **HTTP 진입점만 폐쇄**한다. 유효한 토큰에는 `410 / LEGACY_RESERVATION_DISABLED`, 토큰 없음·유효하지 않은 토큰에는 기존 401을 반환한다. 본문과 멱등 키를 파싱하지 않고 Controller에서 미검증 예약 서비스 의존성을 제거해, 빈/잘못된 본문·재시도에도 예약 생성 경로로 연결되지 않는다.

현재 FE는 Hold → Checkout 준비 → `/checkouts/{merchantUid}/verified-reservation` 경로를 사용한다. 별도 `/verified-reservation`은 실제로 `PaymentVerificationPort` 승인·금액·사용자 검증을 수행하고 기존 k6 fixture도 사용하므로 유지했다. 두 검증 경로가 같은 Checkout 보장을 제공한다는 뜻은 아니며, Checkout 없는 PG 검증 경로의 통합은 이번 범위 밖이다.

[LegacyReservationDisabledTest](../../onticket/src/test/java/com/onticket/concert/controller/LegacyReservationDisabledTest.java)는 수정 전 정상 본문 인증 요청의 200 응답을 재현하고, 수정 후 반복/빈/잘못된 본문·미인증 응답과 연결된 서비스 미호출을 확인한다. [ReservationControllerTest](../../onticket/src/test/java/com/onticket/concert/controller/ReservationControllerTest.java)는 유지된 PG·Checkout·Hold 계약을 회귀 검증한다. MockMvc/mock 서비스 검증이며 실제 PG·DB·부하 시험이 아니다. 기존 내부 정합성 테스트와 과거 측정은 당시 실행한 경로의 근거로 보존한다.

## 11. 다좌석 잠금 대기와 Hold 만료 경계 (#220)

**문제:** `SeatHoldService.hold`와 `CheckoutPreparationTransactionService.create`는 잠금 전에 읽은 `now`를 좌석별 검증에 재사용했다. A1을 얻고 A2를 기다리는 동안 A1·A2의 Hold가 만료될 수 있어, 단순히 각 좌석 잠금 직후에만 시각을 읽어도 앞 좌석의 판정은 오래될 수 있다. 새 Hold TTL도 대기 전 시각으로 계산됐다.

**전후:** 정렬된 좌석을 같은 순서로 전부 잠근 뒤 현재 시각을 읽고 전체 좌석의 만료·소유권을 판정한다. 만료 Hold로 신규 Checkout을 만들지 않고, 새 Hold TTL은 이 판정 시각부터 시작한다. 동일 소유자의 아직 유효한 Hold 재시도는 기존 만료 시각을 유지한다. transaction·canonical lock ordering·기존 오류 계약은 유지하며, commit 시점까지의 절대 유효성을 새로 보장하는 것은 아니다.

**재현:** [CheckoutIntegrationTest](../../onticket/src/test/java/com/onticket/concert/service/CheckoutIntegrationTest.java)의 별도 MariaDB transaction이 A2를 `PESSIMISTIC_WRITE`로 잠근다. 다좌석 요청이 A2 잠금 호출에 도달했고 아직 완료되지 않음을 latch와 bounded Future 대기로 확인한 뒤 MutableClock을 전진시키고 blocker를 해제한다. 5분 TTL에서 시계 기준 299초(유효)·300초(정각 만료)·301초(직후)를 검증한다. 실제 5분 lock wait·TPS 측정이 아니라 실제 DB lock과 제어 시계를 조합한 경계 테스트다.

**수정 전 근거:** 정각·직후 요청은 바깥 `CheckoutService`에서 만료 오류를 반환했지만, 그 전에 Checkout 1건·요청 키 1건·좌석 귀속 2건이 저장됐다. 결제·예약 확정이 발생한 증거는 아니다. 새 다좌석 Hold는 시계 301초 전진 후에도 `12:05`를 반환해 이미 만료됐고, 기대한 잠금 후 TTL은 `12:10:01`이었다.

**검증·한계:** 수정 후 정각·직후 신규 Checkout/키/귀속은 모두 0건, 기존 Hold 데이터·잔여 재고는 유지되고 만료 좌석은 다음 사용자가 재점유할 수 있어야 한다. 만료 직전 준비와 새 Hold의 동일 TTL도 확인한다. 추가로 A1만 만료되고 A2는 유효한 사례에서, 마지막 좌석만 재검증하는 불완전한 수정도 차단한다. 결제 claim/finalize·release의 후속 조사는 12절로 연결한다. 요청 좌석 수 상한은 현재 없지만 이 작은 경계 테스트는 적정 상한의 성능 근거가 아니다. 임의 제한·lock timeout·대기열 대신 큰/작은 요청 혼합 시나리오를 먼저 설계한다.

## 12. 결제 검증·확정·해제의 추가 잠금 후 기한 (#222)

**왜 추가 판정인가:** Checkout 행을 잠갔다고 좌석·귀속 행까지 잠긴 것은 아니다. 검증 선점·최종 확정·취소에서 첫 시각을 읽은 뒤 추가 잠금을 기다리면 Hold 만료나 verification deadline을 넘을 수 있다. Hold 해제의 귀속 조회도 SQL에 전달한 시각 이후에 기다릴 수 있으므로 조회 결과의 현재 활성 여부를 다시 판단해야 한다.

**수정 전:** [CheckoutVerifiedReservationIntegrationTest](../../onticket/src/test/java/com/onticket/concert/service/CheckoutVerifiedReservationIntegrationTest.java)의 새 9개 사례 중 8개가 실패했다. 좌석(A2) 또는 귀속 잠금 대기 중 시계를 경계로 전진시켰을 때 만료 후 PG mock 호출, deadline 후 Booking 생성, 만료 후 취소 성공, 만료 귀속 때문에 Hold 해제 거절이 각각 두 조건에서 재현됐다. 명확한 거절의 `releaseKnownFailure`는 원래 추가 잠금 후 시각을 사용해 통과했다. 실제 PG 승인·운영 결제가 발생한 근거는 아니다.

**수정 원리:** claim은 Checkout → 정렬된 좌석 → 귀속 잠금 이후 시각으로 만료를 재판정하고, 늦은 선점은 `EXPIRED`로 종료해 PG를 호출하지 않는다. finalize도 같은 잠금 순서로 **Booking/Payment 저장 전에** deadline을 재판정해 `PAYMENT_VERIFICATION_UNKNOWN`으로 보류한다. `UNKNOWN` 예외는 상태를 저장하기 위해 `noRollbackFor`를 사용하므로, 업무 데이터 쓰기를 먼저 수행한 뒤 이 예외를 던지면 부분 저장을 남길 수 있다. 시간 검증은 반드시 쓰기 앞에 둔다.

**취소·해제:** Checkout 취소는 추가 잠금 후 `EXPIRED`를 저장하고 기존 410을 반환하며, `CANCELED`·해제 시각·Hold 변경을 남기지 않는다. Hold 해제는 전체 좌석 잠금 후 만료·소유권을 확인하고, 귀속 잠금 후 현재도 활성인 귀속만 충돌로 처리한다. 대기 중 만료된 Hold의 정리는 `EXPIRED_CLEARED`로 기록한다. 명확한 PG 거절의 기존 Hold 만료 복원 로직은 수정하지 않고 회귀 검증한다.

**테스트 해석:** 별도 MariaDB transaction이 좌석 또는 귀속을 잠근 상태에서 대상 요청의 잠금 호출 도달과 미완료를 확인한다. MutableClock을 Hold 만료/검증 deadline 정각으로 전진시킨 뒤 blocker를 풀고 상태·업무 데이터·PG 호출 여부를 확인한다. 실제 대규모 트래픽·5분 대기·SLA 측정이 아니다. 최종 판정 이후 SQL 실행·commit까지 기한이 절대 지나지 않는다는 보장도 아니며, 단일 인스턴스의 제어된 잠금 대기 경계 검증이다. finalized 예약 서비스의 좌석 조회는 이미 같은 transaction에서 획득한 잠금을 다시 사용하며, 추가 조회 비용의 성능 영향은 별도 측정 대상이다.

## 13. 요청 크기와 좌석 경합을 나누는 대조 (#224)

작은 요청을 고정하고 큰 요청을 추가하면 작업량도 늘어난다. 따라서 작은 요청 단독은 기준선이고, 같은 크기·유입량의 큰 묶음이 **분리된 범위/겹치는 범위**를 쓰는 비교가 좌석 중첩의 영향을 보는 대조다. 성공 지연과 정상 409 지연을 섞으면 빠른 거절이 전체 p95를 낮춰 성능 개선처럼 보일 수 있다.

[mixed-seat-plan.mjs](../../load-test/k6/mixed-seat-plan.mjs)는 고정 좌석 묶음을 만들고 [mixed-seat-hold.js](../../load-test/k6/mixed-seat-hold.js)는 1석과 다좌석 클래스별 점유 성공·409·해제·지연을 기록한다. Hold→50ms 대기→해제는 반복 가능한 재고 쓰기이며 PG·예약 확정·운영 사용자의 행동을 재현한 것은 아니다. 큰 요청끼리는 같은 묶음을 경쟁하므로 분리 조건에서도 그들끼리 잠금 대기가 생긴다.

[비교 runner](../../load-test/scripts/Run-MixedSeatHoldComparison.ps1)는 warm-up과 측정을 구분해 정순/역순 반복하고 CPU peak 90%에서 중단한다. 빈 묶음 크기는 거절하며 필수 조건의 3회 측정 완료를 검사한다. [계측 runner](../../load-test/scripts/Measure-MixedSeatHold.ps1)는 Windows 가용 메모리 2GiB preflight, 준비된 fixture의 GET·전용 DB SQL 대조, 결과 합계·최종 Hold 불변식을 검증한다. 자동 fixture 생성은 제거해 대상 확인 전에 DB에 쓰지 않도록 했다. 첫 두 배치는 이전 도구의 warm-up 자원 gate에서 끝났으므로 측정 0단계이며 상한값이나 p95 개선을 결론 내리지 않는다. 원 수치·환경·표본 간격·재개 조건은 [실험 9절](EXPERIMENTS.md#9-큰작은-다좌석-hold-혼합-224)에 통합했다.

## 14. 공연 오픈 대기실의 설계 계약 (#228)

**현재 구현과 구분:** 아래는 신규 API/gate·FE의 설계 초안이다. #230의 비노출 Redis 코어 구현은 15절로 구분하며 HTTP 기능은 아직 구현하지 않았다. 선택 이유와 대안은 [ADR-0006](adr/0006-performance-opening-admission.md)에 둔다. 기존 로그인 cookie와 회차/공연 소속 검증을 재사용하되 현재 `SecurityConfig`는 `/**`를 ignore하므로 새 API가 자동으로 인증된다고 가정하지 않는다. 각 진입점에서 인증된 사용자 이름을 추출하고 본문의 username/순번을 신뢰하지 않는다.

### 요구와 최소 정책

- 공연 상세 → 기존 예약 화면의 날짜·회차 선택 → 대기 패널 → 입장 허용 후 좌석표 → 기존 Hold·Checkout·결제 흐름.
- 정책은 회차별 `enabled`, `opensAt`, `maxActiveAdmissions`, `maxWaitingEntries`, `waitingTtl`, `admissionTtl`, `generation`을 서버에서 지정한다. 최초에는 명시적 fixture만 활성화하고 기존 회차는 비활성으로 유지한다. 정책 조회 실패를 비활성으로 대체하지 않는다.
- 테스트 예산은 입장 한도 2·사용자 5명 같은 작은 구성이며 서비스 권장 용량이 아니다. TTL·오픈 시각은 configurable로 구현하고 경계 테스트에는 제어 시계를 사용한다. 시간값은 실제 운영 정책으로 확정한 것이 아니다.
- 오픈 전에 대기를 허용하지만 `opensAt` 이전 입장은 금지한다. 순서는 Redis가 수락한 등록 sequence 기준이며 브라우저 click 시각이나 네트워크 요청 발송 순서를 보장하지 않는다.
- 같은 사용자/회차/generation 재등록·재접속은 기존 대기/입장권 ID·순서·만료를 유지한다. 상태 조회로 TTL을 무한 연장하지 않는다. 이탈/만료 후 새 등록은 맨 뒤로 간다.
- 정해진 만료 시각에서 `now >= expiresAt`이면 유효하지 않다. Redis 원자 처리의 기준 시각과 앱 응답 `serverNow`의 차이를 고려하며, 여러 앱의 자체 시계 차이로 입장권을 연장하지 않는다.

### 상태와 API 후보

공개 사용자 상태는 `WAITING → ADMITTED → EXPIRED / LEFT`다. WAITING도 만료·이탈할 수 있다. `ADMITTED`는 좌석을 고를 권한이며 `HELD`나 `RESERVATION_CONFIRMED`와 다른 개념이다. 비활성 회차는 정책 응답에서 `enabled:false`, 신규 등록 결과는 `409 ADMISSION_NOT_ENABLED`로 구분한다. 서버 장애는 사용자 상태가 아니라 503 오류다.

공통 경로 후보: `/main/detail/{concertId}/calendar/{concertTimeId}/admission`.

| 요청 | 계약 초안 |
| --- | --- |
| `GET /policy` | 해당 공연/회차 소속 검증, 공개 운영 정책·serverNow 조회. 내부 key·다른 사용자 정보 미노출 |
| `POST` | 로그인 사용자 등록/기존 상태 재사용. `queueEntryId`, 상태, 자신의 position, waitingExpiresAt, serverNow 반환. 허용 시 admissionId·token·expiresAt 포함 |
| `GET` | 로그인 사용자의 현재 상태만 조회. Redis mutation/배정은 bounded worker에서 수행하며 HTTP GET에 등록·기한 연장을 넣지 않음 |
| `DELETE` | 자신의 `queueEntryId`·generation을 제시해 이탈. 반복/오래된 ID의 이탈은 204이며 새 등록/입장권을 삭제하지 않음. 좌석 Hold 해제·Checkout 취소를 대신하지 않음 |

token은 서버 발급의 예측 불가능한 구매 권한이며 로그인 JWT와 구분한다. `X-Admission-Token` 후보 헤더로 전달하고 서버에서 사용자·회차·generation·입장 ID·만료를 대조한다. token/username/queueEntryId는 metric label에 넣거나 로그에 남기지 않는다. waiting 순번은 변하는 조회 값이며 확정 입장 시각을 약속하지 않는다. FE polling 기본 후보 5초+jitter, 재시도 backoff, 페이지 숨김/언마운트 시 정지. polling 주기는 전체 용량의 성능 근거가 아니다.

HTTP 오류 후보는 미인증 401, 위조/타 사용자/다른 회차/만료 권한 403(별도 admission code), 신규 등록 시 대기 인원 초과 429·Retry-After, Redis/정책 상태 불명확 503이다. 알려진 정상 회차 상태에서 아직 등록하지 않은 사용자 조회는 404 `ADMISSION_NOT_JOINED`로 구분한다. 대기 중 GET는 200 WAITING이다. 기존 좌석 충돌 409를 admission 오류로 재사용하지 않는다. 최종 API 코드는 후속 구현/FE 계약 테스트에서 고정한다.

### Redis 원자 코어의 구현 조건

회차/generation별 sequence, waiting Sorted Set, 사용자 상태, active admission 만료 인덱스를 공유한다. 동일 timestamp tie 대신 등록 sequence를 사용하고 score 정밀도 범위를 검증한다. 짧은 원자 작업으로 중복 확인·등록·만료 정리·남은 슬롯의 FIFO 배정을 수행한다. waiting 최대 인원, 처리 batch 크기, 유지 데이터 TTL을 제한하며 전체 큐를 한 script에서 순회하지 않는다. 한 사용자가 슬롯을 두 개 소비하거나 유효 입장권 수가 한도를 넘으면 실패다. 여러 key는 같은 hash tag를 사용하는 설계 후보이며 첫 배포는 cluster가 아니다.

첫 배정 worker는 bounded tick 작업이다. 앱 두 개가 배정해도 Redis의 원자 작업이 상태를 직렬화하며 새 요청이 기존 대기자를 건너뛰지 않는다. 오류 때 자동으로 process-local 큐로 fallback하지 않는다. Redis 자료 유실 시 이전 입장권은 유효 처리하지 않고 수동 재개·generation 변경이 필요하다. 이를 검증하기 위한 정책 원본은 Redis 외부의 서버 구성에 둔다.

활성 회차/generation의 Redis 준비 marker도 관리자가 확인한 bootstrap 단계에서 준비한다. marker가 없으면 신규 등록/구매를 503으로 닫고 빈 큐/비활성으로 자동 초기화하지 않는다. 복구·새 generation 배포는 앱 간 설정을 일치시키는 별도 제어 단계이며 일반 사용자의 join으로 수행하지 않는다. AOF clean restart와 강제 자료 유실 후 수동 복구를 별도 테스트한다.

### API 우회와 결제 연속성

현재 [ReservationController](../../onticket/src/main/java/com/onticket/concert/controller/ReservationController.java)는 수동 cookie 검증 후 서비스를 호출한다. 새로운 gate도 화면 숨김에만 의존하지 않고 신규 구매 쓰기의 좌석 잠금/업무 저장 앞에 둔다.

명시적 Controller 외에 `spring-boot-starter-data-rest`의 Repository 자동 export 후보와 `/loadtest/runs`·관리 reset 등 fixture 쓰기 경로도 조사한다. 현재 소스 조사만으로 실제 HTTP 노출/악용을 확인한 것은 아니다. 활성 회차의 보호를 우회하는 쓰기는 차단하거나 별도 테스트 전용 경계로 격리한 뒤 기능 완료를 판단한다. FE 연동에서는 새 token 헤더의 CORS 허용 범위와 이전 회차 polling 응답의 무시도 검증한다.

| 기존 경로 | 활성 회차의 후속 gate 요구 |
| --- | --- |
| `POST .../seat-holds` | 신규 점유·추가 선택·재점유에 유효한 자신의 입장권 필요. 입장권이 좌석 conflict를 성공으로 바꾸지 않음 |
| `POST .../checkouts` | 신규 Checkout 준비 전에 입장권 검증, 재시도에서 기존 Checkout 결과 재사용과 신규 생성은 구분 |
| `POST .../verified-reservation` | 유지된 Checkout 없는 PG 검증 경로도 우회 후보. 활성 회차에서는 신규 생성 gate를 적용하거나 명시적으로 차단. 기존 k6 비활성 fixture 계약은 유지 |
| `POST .../reservation` | 이미 종료된 경로의 410 유지; 입장권이 있어도 재개하지 않음 |
| Hold DELETE / Checkout DELETE | 기존 DB 소유권·상태 검증으로 cleanup 계속, 대기실 장애/입장 만료를 이유로 해제·취소를 막지 않음 |
| `POST .../checkouts/{merchantUid}/verified-reservation` | 이미 준비된 Checkout의 소유권·검증 선점·Hold lease로 계속 처리. 입장권 만료/Redis 장애 때문에 유효 결제를 버리지 않음 |

Redis 검증과 MariaDB transaction은 하나의 원자 transaction이 아니다. gate를 통과한 뒤 입장권이 만료돼도 이미 시작한 DB 작업을 자동 취소하거나 승인 결제를 되돌리지 않는다. 만료/이탈은 다음 신규 구매 접근을 차단하고, 기존 Hold/Checkout은 원래 정책으로 정리한다. 입장권 개수·유효 구매 세션 수·DB 요청 동시 수는 각각 다르다. worker나 클라이언트가 임의로 TTL을 재발급하는 방식으로 이 경계를 감추지 않는다.

### 후속 구현 순서와 완료 조건

1. **BE 원자 코어:** Redis 추가·실행 경계 확인 후 등록·중복·순서·bounded 배정·만료·이탈. Redis Testcontainers로 동시 등록/배정에서 한도·순서·단일 사용자 상태 검증. 이탈→재등록 후 오래된 이탈/만료 작업의 새 상태 미삭제, 자료형/입력 오류와 script 부분 변경 검사 포함.
2. **BE API/gate:** 인증·타 사용자/다른 회차/위조·만료 token·legacy 우회·Redis unavailable을 검증. 거절 시 MariaDB 재고·Checkout·PG 호출이 시작되지 않아야 한다. 기존 Checkout의 종료·복구 경로 회귀.
3. **FE 별도 저장소 Issue/PR:** 회차 선택 뒤 WAITING/ADMITTED 표시, 재접속·만료·이탈·API 오류 처리. 기존 소규모 좌석·2,000석 fixture UI 유지, 화면과 실제 gate가 일치하는지 확인.
4. **제한된 통합 검증:** 앱 두 개의 공유 순서/권한·앱 재시작·Redis clean restart/자료 유실을 구분해 검증. 최초는 작은 fixture·Mock PG·명시적 기능 검증이며 새 k6 성능 증량은 요구하지 않는다.

처음부터 운영 고가용성·Kafka·대형 AWS·SSE·무제한 큐를 붙이지 않는다. 위 단계가 실제로 통과하기 전에는 새 기능이나 트래픽 보호를 구현한 성과로 기록하지 않는다.

## 15. Redis 대기·입장 원자 코어 (#230)

### 구현한 경계

[RedisAdmissionStore](../../onticket/src/main/java/com/onticket/admission/RedisAdmissionStore.java)는 명시적인 `AdmissionPolicy`와 Redis 연결을 받는다. 자동 Spring bean·bootstrap·worker·Controller는 없다. Redis starter는 추가했지만 [앱 진입점](../../onticket/src/main/java/com/onticket/OnticketApplication.java)에서 Redis 자동 연결/repository 설정을 제외해 기존 health가 Redis에 의존하지 않게 했다. API 단계에서 opt-in 연결을 별도로 구성해야 한다. 인증·공연/회차 소속·서버 정책 원본·generation 전환 권한은 아직 이 코어가 대신 검증하지 않는다.

`bootstrap`은 운영자/테스트 준비용이며 일반 join이 호출하지 않는다. 최초 empty generation을 준비하고 기존 정책 fingerprint·상태 개수가 일치할 때만 재사용한다. marker 유실·부분 상태 유실·다른 정책·자료형/인덱스 모순은 `AdmissionUnavailableException`으로 닫는다. Redis 전체 자료 유실은 첫 empty generation과 자체적으로 구별할 수 없으므로, bootstrap은 신뢰된 제어 단계에서 **새 generation으로만** 재개해야 한다. 지금 public HTTP bootstrap이나 자동 fallback은 없다.

### 순서·입장·시간

[Lua 코어](../../onticket/src/main/resources/admission/admission.lua)는 회차/generation hash tag 아래 metadata·사용자 상태 hash·waiting sequence·waiting expiry·active expiry·terminal expiry의 6개 key를 관리한다. 사용자 member는 username의 SHA-256이며 인증을 대신하지 않는다. join마다 queueEntryId·admissionId·비밀 token 후보를 서버에서 생성하되, 기존 유효 상태를 재사용하면 ID·순서·만료를 바꾸지 않는다. WAITING/EXPIRED/LEFT snapshot에는 구매 token을 노출하지 않는다.

등록 sequence는 `HINCRBY`로 부여하고 score의 정확한 정수 범위를 검사한다. JSON에는 sequence를 문자열로 저장해 cjson 숫자 직렬화의 반올림을 피한다. tick은 오픈 시각 전 배정하지 않고, 수락된 순서의 앞에서 설정 batch만 검사해 유효 active 한도 안에서 배정한다. expired head가 batch를 채우면 그 tick은 배정 0건일 수 있으며 다음 tick이 진행한다. 뒤 사용자를 앞서 배정해 이 제한을 우회하지 않는다.

운영 생성자는 Lua의 Redis `TIME`을 쓰고, package-local 테스트 생성자만 제어 시간 seam을 제공한다. 정책 시각·TTL은 millisecond 정밀도, TTL 1ms~24h, active 1~1,000·waiting 1~10,000·batch 1~1,000 범위를 검증한다. 이 범위는 입력/작업 제한이며 권장 처리 용량이 아니다. 만료 정각부터 권한이 무효이며 GET/VERIFY는 기한이나 Redis 상태를 갱신하지 않는다. 만료 상태는 조회 시 EXPIRED로 투영하고 실제 정리는 bounded mutation에서 수행한다.

### 이탈·메모리·실패

leave는 username의 현재 queueEntryId와 요청 ID가 같을 때만 유효 상태를 LEFT로 전이한다. 오래된 이탈은 새 등록·입장을 지우지 않는다. 종료 상태 보존은 waiting TTL+admission TTL 이내의 정리 기한과 `maxWaiting+maxActive` cardinality 한도를 함께 둔다. 오래된 terminal은 eviction/정리 후 조회에서 사라질 수 있다. generation metadata는 남고 generation 자체 삭제/관리는 별도 제어 작업이다.

script는 관련 key type·marker·상태 개수/인덱스와 해당 batch의 JSON을 첫 mutation 전에 검증한다. 전체 key loss에서 marker만 남은 경우도 저장한 stateCount로 감지한다. 의미 오류로 인한 테스트는 변경 전 상태를 유지하지만, OOM·Redis crash 등에 대한 rollback을 보장하지 않는다. 오류 후 인덱스/개수 모순은 후속 접근을 닫으며 운영 복구는 별도다. API의 429/503 변환·metrics·자동 tick·기존 결제 연속성 gate는 다음 단계다.

### 검증과 로컬 실행

[RedisAdmissionStoreTest](../../onticket/src/test/java/com/onticket/admission/RedisAdmissionStoreTest.java)는 별도의 Redis 7.4.7-alpine Testcontainers(128MiB 제한)에서 작은 정책으로 검증한다. 8개의 동시 join, 독립 Lettuce 연결 두 개의 동시 tick, FIFO/한도·오픈 전·중복 재조회·정각·expired head·재등록 후 오래된 이탈·자료 유실·잘못된 자료형/JSON·terminal 한도·sequence 정밀도·서버 시간·null 결과 fail-closed를 확인한다. [활성화 경계 테스트](../../onticket/src/test/java/com/onticket/admission/AdmissionActivationBoundaryTest.java)는 자동 연결 제외와 비등록 코어를 확인하며 실제 서버 health E2E를 대신하지 않는다. CI가 기존 Backend 회귀를 추가 수행한다.

실행은 `onticket`에서 `gradlew.bat test --tests "com.onticket.admission.*" --no-daemon --max-workers=1`이며 실제 PG/KOPIS·MariaDB 재고를 호출하지 않는다. 별도 로컬 Redis는 저장소 루트에서 `docker compose -f compose.admission.yml --profile admission up -d admission-redis`, PING 확인은 같은 옵션의 `exec -T admission-redis redis-cli ping`, 종료는 `stop admission-redis`다. 기본 loopback 16379·128MiB container/64MiB Redis noeviction·AOF 설정으로 opt-in이다. 이 구성의 실제 기동/PONG/종료는 확인했지만 AOF crash durability·failover는 검증하지 않았다. volume은 보존했다.

첫 Redis 코어 테스트에서 queue-full 오류가 일반 unavailable로 변환되는 실패를 발견해 Redis exception의 가장 구체적인 원인 code로 분류하도록 수정했다. 이것은 신규 코어의 개발 회귀 수정이지 기존 예매 성능 개선이 아니다. 코어의 단위/통합 검증과 실제 사용자 입장 제어 완성·다중 앱 운영·대규모 성능 증명은 구분한다.

Reviewer 후속 지적으로 `AdmissionSnapshot.toString()`은 token·queueEntryId·admissionId를 제외하도록 보강하고 비노출 테스트를 추가했다. 이는 객체의 문자열 표현만 보호하며, 향후 HTTP DTO의 JSON 응답이나 요청 로그까지 자동으로 보호하는 것은 아니다. 실제 HTTP 연동에서 본인에게만 구매 token을 반환하고 로그 redaction을 별도로 검증해야 한다.

## 16. 인증 대기 API와 신규 구매 gate (#232)

### 요청부터 구매까지

이 단계는 15절 코어를 인증/구매 경계에 연결한다. 기본 `onticket.admission.enabled=false`이며 Redis bean/접속이 생기지 않는다. 활성화에는 `local,loadtest` 두 프로필과 `concert-id=LOAD-TEST-{runId}`, `concert-time-id`, `generation`, `opens-at` 명시 설정이 필요하다. 잘못된 프로필/정책은 startup 실패로 처리한다. active/waiting/batch 기본값은 2/20/2, 대기 TTL 5분·입장 TTL 1분·loopback Redis port 16379다. 작은 검증용 기본값이지 처리 용량이 아니다.

| 경로 | 입력/응답 | 경계 |
| --- | --- | --- |
| `GET /main/detail/{concertId}/times/{concertTimeId}/admission-policy` | enabled, 활성인 경우 generation/opensAt/pollIntervalMillis | 실제 공연-회차 소속 DB 조회, 비활성 회차는 Redis 미사용 |
| 같은 prefix의 `POST /admission` | accessToken cookie → generation + admission + serverNow | JWT username만 사용, 중복 등록은 기존 항목 재사용 |
| `GET /admission` | 본인 상태, 대기 순번/만료시각, ADMITTED만 token/admissionId | 없으면 404, GET은 입장 배정/TTL 갱신하지 않음 |
| `DELETE /admission?generation=...&queueEntryId=...` | 204 | 본인·현재 ID 일치만 이탈, 오래된 generation/ID는 새 항목을 지우지 않음 |
| 기존 `POST /seat-holds`, 신규 `POST /checkouts` | `X-Admission-Token` header | 활성 회차만 본인/현재 generation/입장 ID/token/만료 검증 후 구매 진행 |

API의 `serverNow`는 Redis Lua의 같은 조회에서 얻은 TIME이다. application clock으로 대기 기한을 연장하지 않는다. 모든 대기 응답은 `Cache-Control: no-store`, 429에는 `Retry-After: 5`, 상태 유실/정책 불일치/Redis 오류에는 안전한 code의 503을 사용한다. WAITING·EXPIRED·LEFT에는 구매 token을 반환하지 않는다. 문자열 표현도 token·식별자를 제외한다. 기존 FE와의 연결은 Vite `/api` same-origin proxy를 전제로 하며 직접 cross-origin CORS 정책 추가는 이번 범위가 아니다.

### 기존 결제를 막지 않는 이유

[AdmissionAccess](../../onticket/src/main/java/com/onticket/admission/AdmissionAccess.java)는 신규 구매의 입장 여부만 확인한다. [CheckoutService](../../onticket/src/main/java/com/onticket/concert/service/CheckoutService.java)의 gate callback은 기존 멱등 결과 조회 다음, 신규 생성 transaction 앞에 실행한다. 이미 존재하는 같은 Checkout 결과를 다시 읽는 데 Redis lease를 요구하지 않는다. Checkout 기반 PG 검증·취소·Hold DELETE에도 새로운 gate를 적용하지 않아 입장 만료/Redis 장애가 정리나 결제 후속 처리를 막지 않는다. 이 경로의 기존 소유권·fingerprint·상태 검증은 그대로 유지한다.

활성 회차의 Checkout 없는 `verified-reservation`은 PG 조회 전에 403 `CHECKOUT_REQUIRED`로 차단한다. 인증 후 410을 반환하던 legacy `reservation`은 그대로다. 활성 fixture의 `/loadtest/runs`, `/seat-holds/reset`, `/tokens`는 403으로 동결한다. 따라서 fixture와 테스트 사용자/token은 활성화 전 별도 준비해야 한다. 다른 fixture의 기존 테스트 API는 유지한다. JPA repository를 자동 HTTP CRUD로 노출하던 미사용 data-rest starter는 제거하며 custom Controller/JPA는 유지한다.

### 검증과 남은 단계

[AdmissionApiGateTest](../../onticket/src/test/java/com/onticket/admission/AdmissionApiGateTest.java)는 실제 disposable Redis 7.4.7-alpine Testcontainers(128MiB) + MockMvc에서 JWT 주체·다른 사용자·회차 경계·대기 포화·TTL 미연장·오래된 이탈·입장 만료·marker 유실·신규 gate·우회 차단·기존 결제/정리 연속성을 확인한다. 구매 서비스는 mock이므로 실제 MariaDB 쓰기/PG I/O까지의 E2E 성과는 아니다. [CheckoutAdmissionBoundaryTest](../../onticket/src/test/java/com/onticket/concert/service/CheckoutAdmissionBoundaryTest.java)는 실제 CheckoutService와 mock repository/transaction을 사용해 신규 gate 순서와 기존 결과 재사용을 검증한다. [설정 테스트](../../onticket/src/test/java/com/onticket/admission/AdmissionConfigurationTest.java)는 기본 비연결·잘못된 설정 fail-fast·자동 repository REST exporter 부재를 확인한다. 기존 Redis 코어/Controller 회귀도 함께 실행한다.

API에는 bootstrap/tick 호출 경로가 없다. #232 시점에는 테스트만 trusted fixture에서 직접 bootstrap/tick했으며 운영자 초기화·worker는 이후 17절에서 구현했다. FE는 아직 미연동이다. Redis 완전 유실 후 같은 generation 자동 재초기화는 금지한다. 입장 token 검증과 MariaDB transaction은 서로 다른 저장소이며 검증 직후 만료/이탈된 in-flight 요청을 취소하지 않는다. maxActive는 유효 입장권 한도이지 DB 동시 transaction 절대 상한·좌석 보장·TPS 성과가 아니다. 전역 Security 및 다른 관리자 경로의 기존 문제까지 해결한 것은 아니다.

로컬 대상 테스트는 76개 통과했다(API/gate 13·설정 5·Checkout 경계 3·Redis 코어 19·활성화/문자열 2·기존 Controller 회귀 34). 실행은 `gradlew.bat test --tests "*Admission*Test" --tests "*RedisAdmissionStoreTest" --tests "*ReservationControllerTest" --tests "*LegacyReservationDisabledTest" --tests "*LoadTestControllerTest" --no-daemon --max-workers=1`. full Backend CI와 독립 검토 결과는 Issue #232의 연결 PR에서 확인한다.

## 17. 운영자 초기화와 자동 입장 승격 (#234)

### 왜 polling과 승격을 분리하는가

본인의 GET은 조회만 수행한다. 오픈 시각 이후 대기열의 선두를 입장시키는 일은 [AdmissionWorker](../../onticket/src/main/java/com/onticket/admission/AdmissionWorker.java)가 별도 처리한다. `onticket.admission.enabled=true`와 기존 local+loadtest/fixture 정책 검증을 모두 통과할 때만 worker를 등록한다. 기본 fixed-delay/initial-delay는 1초이며 `onticket.admission.worker.delay-ms`는 250~60,000ms 범위, `worker.enabled=false`로 중단할 수 있다. 전용 scheduler는 한 thread이고 실행 종료 후 다음 지연을 세므로 같은 앱에서 tick을 중첩하지 않는다.

각 tick은 정책의 `batchSize`까지만 승격/만료를 처리한다. 오픈 전에는 승격하지 않고, 유효 입장권 수가 `maxActive`를 넘지 않게 Lua가 검사한다. 다른 앱 worker와 실행이 겹쳐도 Redis가 Lua를 직렬 실행하므로 별도 leader election/분산 락이 필요하지 않다. 실제 Redis TIME이 기준이며 JVM/클라이언트 시각으로 기한을 연장하지 않는다. 이탈 또는 만료로 빈 자리가 생기면 다음 tick에서 다음 대기자가 입장한다. 즉시 push가 아니므로 TTL/이탈 이후 입장까지 지연이 존재한다.

Redis 오류/marker 유실은 성공으로 취급하지 않는다. worker는 unavailable counter를 늘리고 다음 tick을 시도하지만 bootstrap/fallback을 호출하지 않는다. 장애 진입/회복 때만 안전한 로그를 기록해 매 tick의 stack trace/token 노출·반복 로그를 피한다. `onticket.admission.worker.ticks{result=success|unavailable}`, `onticket.admission.worker.promoted`만 사용하며 사용자/회차/generation label을 넣지 않는다. 성공 tick 수에는 승격 0건도 포함하고 promoted는 입장 배정 누적 건수이지 구매 성공/동시 입장 수가 아니다. 이 지표는 이번 단계에서 부하 비교/Prometheus 실측까지 한 것이 아니다.

### 초기화 권한과 실행 순서

[AdmissionBootstrapCommand](../../onticket/src/main/java/com/onticket/admission/AdmissionBootstrapCommand.java)는 웹 서버/DB/PG를 띄우지 않는 standalone CLI다. loopback Redis에만 연결하고 connect/command timeout은 각각 1초다. 정책 전용 properties 파일과 `--confirm-new-generation` 두 인자를 요구한다. 다른 설정 key·잘못된 정책·누락/중복 인자는 연결 전에 거절한다. 출력은 안전한 성공/실패 code뿐이며 원시 예외·정책 파일 내용·token은 출력하지 않는다. runtime 서버와 CLI는 같은 `AdmissionSettings.policy()`로 정책 fingerprint를 계산한다.

1. admission 비활성 서버에서 fixture와 테스트 사용자/token을 먼저 준비한다. 기존 `POST /loadtest/runs`, `GET /loadtest/fixture` 응답의 실제 concertId/concertTimeId를 사용한다.
2. [정책 예제](../../load-test/admission-policy.example.properties)를 추적하지 않는 로컬 파일로 복사해 실제 회차 ID, **새 generation**, UTC opensAt을 수정한다. 기본 2/20/2 한도는 작은 기능 검증용이며 2,000석 fixture와 별개다.
3. 저장소 루트에서 `docker compose -f compose.admission.yml --profile admission up -d admission-redis`로 명시적으로 Redis를 켠다. 다른 저장소/운영 데이터는 사용하지 않는다.
4. `onticket`에서 `gradlew.bat bootstrapAdmission --args="--policy-file=../load-test/admission-policy.example.properties --confirm-new-generation=local-demo-g1" --no-daemon --max-workers=1` 형식으로 초기화한다. **예제 경로/ID/generation은 그대로 실행할 값이 아니며 수정한 로컬 파일을 사용한다.** 기본 Redis port는 16379다.
5. 준비에 사용한 서버를 종료하고 `local,loadtest` 및 동일한 정책 파일로 재시작한다. 예: `gradlew.bat bootRun --args="--spring.profiles.active=local,loadtest --spring.config.additional-location=file:D:/project2/.work/admission-local.properties" --no-daemon --max-workers=1`. 기존 DB/JWT 로컬 환경 변수는 계속 필요하다. 활성 fixture 수정/token 발급이 동결되므로 1단계의 준비를 건너뛰지 않는다.
6. 현재 FE는 대기 API를 호출하거나 입장 token을 붙이지 않는다. 활성 fixture의 기존 화면에서 바로 좌석 선택을 누르면 403이 정상이며, FE 패널 연결은 별도 후속 작업이다. 이번 단계의 검증은 테스트에서 수행한다.

CLI는 `INIT_FRESH` Lua로 동일 namespace의 초기화를 원자 수행한다. 이미 초기화된 곳이나 일부 key만 남은 곳은 거절하고 아무것도 지우지 않는다. 동시 초기화는 하나만 성공한다. 설정이 잘못됐으면 exit 2, Redis 오류/이미 존재/부분 자료라면 exit 1, 생성이면 exit 0이다. 기존 healthy namespace의 서버 재시작에는 CLI를 다시 실행할 필요가 없다.

**완전 유실 후에는 과거 generation 사용 이력을 Redis만으로 알 수 없다.** 확인 인자는 운영자의 새 generation 선택을 요구하지만 이를 영구 ledger로 증명하지 않는다. 자료 유실/의심 상태에서 worker를 중단하고 새 generation 정책으로 CLI 초기화 및 서버 재시작한다. 이전 generation 자료를 이 도구가 삭제하지 않으며, old token은 새 namespace/주체 검증에서 사용할 수 없다. AOF crash durability·failover·자동 데이터 복원은 아직 검증하지 않았다.

### 검증의 의미와 다음 연결

[AdmissionWorkerIntegrationTest](../../onticket/src/test/java/com/onticket/admission/AdmissionWorkerIntegrationTest.java)는 disposable Redis 7.4.7-alpine Testcontainers(128MiB)에서 fresh 초기화 중복/부분 자료, 두 독립 Lettuce 연결 worker의 동시 tick·FIFO/한도·오픈 전·이탈/정각 만료, marker 유실·새 generation 복구를 검증한다. 실제 Spring scheduling이 수동 tick/polling 없이 승격하는지도 확인한다. 실제 CLI 연결의 정책과 server 생성자가 일치하는지, production Redis TIME 기반 조회/입장 TTL과 재초기화 거절을 확인한다. controlled-time 경계 검증과 실제 시간/scheduling 검증을 구분한다.

설정/CLI/worker unit 검증은 잘못된 설정 fail-fast, 기본 worker 비등록, 단일 scheduler, 장애 후 tick 재시도·낮은 cardinality 지표·로그 비노출을 확인한다. 기존 API/gate/코어/Controller 회귀도 유지한다. 이는 두 전체 BE 프로세스·MariaDB 구매·실 PG·고부하의 E2E 성과가 아니다. 다음은 FE 대기 패널, 제한된 공유/복구·실제 구매 서비스 연결, 이후 입장 제어 적용 전후의 p95/Hikari pending/오류/재고 불변식 비교다. worker 구현만으로 DB 부하 감소를 주장하지 않는다.

로컬 대상 91개 테스트(기존 76 + 초기화/worker 15)와 `bootJar` 빌드를 통과했다. JAR Start-Class가 기존 `OnticketApplication`임도 확인했다. Gradle `bootstrapAdmission`에 존재하지 않는 정책 파일을 전달한 부정 검증은 안전한 `INVALID_ADMISSION_BOOTSTRAP_REQUEST`·프로그램 exit 2(Gradle 실패)로 종료하며 Redis/DB/PG를 시작하지 않는다. 전체 CI·독립 리뷰는 Issue #234의 연결 PR에 기록한다. 반복 횟수나 처리량을 확대한 성능 결과는 아니다.

## 18. Redis 장애와 기존 구매 처리 분리 (#236)

### 왜 필요한가

입장 제어는 **새 구매의 접근 권한**이다. Redis가 잠시 응답하지 않을 때 새 Hold·Checkout을 허용하면 gate를 우회하지만, 이미 준비한 주문의 취소·좌석 해제·결제 검증까지 막으면 장애의 영향이 구매 후속 처리로 확산된다. 16절 API 테스트는 구매 서비스를 mock으로 확인했으므로 실제 DB 쓰기가 이 경계를 유지하는지는 별도로 검증해야 한다. FE [PR #56](https://github.com/SKUWooU/TicketOnBoarding_Fe/pull/56)은 정상 입장·대기·취소·다음 사용자 좌석 재사용을 실제 브라우저로 검증했고, 이번에는 BE 장애 경계를 보완한다.

### 코드 흐름

- 신규 Hold: Controller → `AdmissionAccess.requireAdmission` → 실제 Redis 권한 확인 → MariaDB `SeatHoldService`. Redis 오류는 `ADMISSION_UNAVAILABLE` 503으로 반환하고 구매 서비스에 진입하지 않는다.
- 신규 Checkout: `CheckoutService.prepare`의 기존 멱등 결과 조회 → 결과가 없을 때만 Redis gate → 생성 transaction. 기존 Key·동일 요청의 재조회는 Redis를 요구하지 않는다. 다른 Key의 신규 생성은 차단한다.
- 취소·Hold DELETE·기존 Checkout 결제 검증: 기존 MariaDB 소유권/상태/요청 검증을 사용하며 새로운 입장권을 요구하지 않는다. PG는 `PaymentVerificationPort`만 Mock이며 예약·결제 저장은 실제 transaction으로 처리한다.

### 검증 조건과 관측

[AdmissionPurchaseContinuityIntegrationTest](../../onticket/src/test/java/com/onticket/admission/AdmissionPurchaseContinuityIntegrationTest.java)는 JUnit 5·MariaDB 10.11.8(512MiB 제한)·Redis 7.4.7-alpine(128MiB container, maxmemory 64MiB/noeviction) Testcontainers를 사용한다. MariaDB는 Flyway 적용 후 Hibernate validate, HikariCP 최대 4개 연결이다. Spring 구매 서비스와 repository·transaction은 실제 구현이며 Controller는 standalone MockMvc, JWT 주체와 외부 PG 포트는 mock이다. 실제 HTTP 서버·FE E2E는 아니다.

| 시나리오 | 확인할 저장 결과 |
| --- | --- |
| 두 독립 Lettuce 연결 | 첫 연결에서 발급한 입장권을 두 번째 연결의 gate가 검증하고 실제 Hold 저장. 다른 사용자가 같은 token을 제출하면 403, DB 무변경. 조회 전후 만료 시각 동일. |
| Redis 일시 정지 중 신규 차단·정리 | `docker pause`로 해당 disposable Redis만 정지. 조회·신규 Hold·신규 Checkout은 503, 좌석·주문·귀속·예약·결제 snapshot 무변경. 기존 Checkout 재조회는 동일 결과, 취소/해제를 각각 두 번 수행해 CANCELED·점유 0·예약/결제 0 확인. |
| Redis 재개 | finally에서 unpause. 기존 entry ID·만료 시각·sequence 유지 및 기존 입장권으로 새 Hold 성공. 자동 초기화나 기한 연장으로 회복한 것이 아님. |
| 정지 중 기존 결제 후속 처리 | Redis 조회 503을 확인한 같은 정지 구간에서 준비된 Checkout의 Mock PG 검증·동일 Key 재시도 성공. PG 호출 1회, Booking·Reservation·Payment 각 1건, 잔여 수량 3→2, 예약 좌석 1개·남은 Hold 0. |

Redis 연결은 실제 서버와 같은 `AdmissionRedisConnections.loopback`의 connect/command timeout 1초 설정을 사용한다. 정지 중 테스트가 요청하는 Redis 명령은 조회뿐이며 timed-out JOIN 같은 쓰기의 사후 실행 안전성을 주장하지 않는다. 구매 시간은 고정 Clock, Redis 입장 기한은 실제 Redis TIME을 사용한다. pause는 **네트워크 차단·process crash·자료 유실·AOF 복원·failover가 아니다**. 두 연결은 두 전체 BE 프로세스의 운영 검증도 아니다.

실행: `onticket`에서 `gradlew.bat test --tests "*AdmissionPurchaseContinuityIntegrationTest" --no-daemon --max-workers=1`. 신규 3개와 기존 입장 제어·관련 Controller 회귀를 합친 대상 94개(실패/오류 0) 및 `bootJar`가 로컬에서 통과했다. 전체 Backend CI·독립 검토 결과는 Issue #236의 연결 PR에서 확인한다. 응답시간/처리량 비교나 장애 복구 SLA 수치는 측정하지 않았다. 테스트 전용 DB/Redis는 종료 시 정리하며 기존 로컬 volume을 사용하지 않는다.

### 다음 판단

이 경계를 확인한 뒤 같은 작은 fixture에서 입장 제어 적용 전후를 점진적으로 비교한다. 대기열 입장 지연과 구매 API 지연·DB pending·정상 좌석 충돌·예상 밖 오류·재고 불변식을 분리해야 한다. 유효 입장권 한도는 DB 동시 transaction의 절대 상한이나 성능 향상을 뜻하지 않는다.

## 19. 같은 구매 유입에서 입장 대기와 쓰기 비용 비교 (#238)

### 비교 단위와 흐름

HTTP RPS를 동일하게 맞추면 ON의 join/poll/leave 요청 때문에 실제 구매 세션 수가 달라진다. 따라서 **새 세션 유입**을 1/2/5 sessions/s로 고정한다. 각 세션은 새 사용자로 인기 1석의 Hold를 한 번 시도하고 성공 시 500ms 후 해제한다. ON만 join → 실제 정책의 5초 polling → 입장 확인 → Hold/release → leave를 거친다. Checkout·결제·브라우저는 이번 경로에 없다.

- 각 5초 도착 구간에 논리 세션 5/10/25개를 배정한다. 종료 경계에서 k6가 추가한 iteration 하나는 HTTP를 보내지 않고 별도 집계한다. 두 개 이상이면 실패다. dropped iteration을 이 항목으로 옮겨 성공 처리하지 않는다.
- warm-up은 모드마다 별도 사용자 2명·1 session/s·2초다. 지표에서는 제외하지만 이 정도로 JVM JIT·캐시가 완전히 안정됐다고 보장하지 않는다.
- OFF→ON(1), ON→OFF(2), OFF→ON(5) 순서, 조건마다 새 20석 fixture를 사용한다. 6개 fixture는 같은 disposable DB에 먼저 준비한다. 각 조건 1회인 **짧은 탐색**이지 반복 A/B 성능 증명은 아니다.
- 도착 종료 후 대기 중인 세션을 drain한다. 입장 대기 상한 75초·k6 gracefulStop 90초·전체 단계 120초를 넘으면 중단한다. 5초를 전체 완료 시간으로 잘못 나눠 TPS/RPS를 계산하지 않는다.
- ON 설정은 active 2·waiting 30·batch 2·waiting/admission TTL 각 2분·worker 1초다. 유효 입장권 수는 DB 동시 transaction 수의 절대 상한이 아니다.
- 한 번의 Hold 성공/충돌 뒤 좌석·입장권을 정리하는 synthetic 세션이다. 실제 FE에서 주문 취소 후에도 입장권을 유지하거나 사용자가 결제창에 오래 머무는 행동은 반영하지 않는다. 일반 사용자 체류가 길면 대기 비용이 더 클 수 있다.

### 도구와 안전 경계

[k6 시나리오](../../load-test/k6/admission-sessions.js)는 준비된 비공개 manifest만 읽고 fixture/token을 생성하지 않는다. loopback URL·runId/회차·실제 DB identity·정책·인증 주체·깨끗한 재고를 확인한다. warm-up과 측정의 사용자 범위는 겹치지 않는다. Hold의 정상 충돌은 해당 API의 HTTP 409 계약으로 분류하며, 다른 verified-reservation API의 헤더를 요구하지 않는다. JSON 응답·Cookie·입장 token을 출력하지 않는다.

[수집기](../../load-test/scripts/measure-admission-sessions.mjs)는 같은 주기의 Prometheus Hikari와 DB 전역 row-lock counter, Node의 Windows host CPU/가용 메모리를 수집한다. pause 설정 1초에 HTTP/Docker CLI 비용이 더해지므로 실제 표본 간격을 남긴다. pending peak 0은 표본상 0이지 순간 대기 부재의 증명이 아니다. DB counter는 첫~마지막 표본 창이며 drain·collector SQL을 포함한다. 쿼리별 병목 귀속이나 전체 실행 구간 counter로 해석하지 않는다. Hold HTTP 200/409의 서버 counter 차이도 k6 성공/충돌 건수와 교차 확인한다.

[실행기](../../load-test/scripts/run-admission-session-comparison.mjs)는 Windows·Java 21·Docker·k6에서 명시적인 `--confirm-disposable-local-resources`가 있어야 시작한다. 기존 서비스의 18080/18081/13308/17380 포트가 사용 중이면 연결하지 않고 거절한다. 전용 label과 새 ID의 `--rm` 컨테이너만 만들며 기존 named/bind volume을 사용하지 않는다. MariaDB 이미지의 자동 생성 anonymous volume은 이 전용 컨테이너와 함께 정리된다. fixture/token 준비 → 운영자 fresh bootstrap CLI → 동일 정책으로 앱 시작 순서다. 공개 API bootstrap/tick이나 자동 fallback은 추가하지 않는다.

실행은 저장소 `onticket`에서 `gradlew.bat bootJar --no-daemon --max-workers=1`로 현재 JAR를 빌드한 뒤, 저장소 루트에서 다음과 같이 수행한다. `JAVA_HOME`은 설치된 Java 21을 가리켜야 한다.

```powershell
node --test load-test/k6/test/admission-sessions.test.mjs
node load-test/scripts/run-admission-session-comparison.mjs --confirm-disposable-local-resources
```

시작·실행 중 host 가용 메모리 2GiB 미만, CPU 90% 이상 3연속 표본, 누락/예상 밖 오류/미해제 Hold/불변식 실패는 다음 단계 증량을 막는다. 표본상 pool pressure도 관측을 보존하고 후속 증량을 중단한다. 이는 탐색용 안전 정책이며 안정 처리 구간의 정의가 아니다. 실패 시 전용 자료·private console을 남기고 임의 reset/재실행하지 않는다. 자신의 앱은 실패 시에도 중단하고, 전체 성공 시에만 자신의 컨테이너·임시 인증 파일을 정리한다. private runtime 로그와 상태 경로는 콘솔에 표시하되 파일 내용은 공개하지 않는다.

### 결과 읽는 순서

1. 세션 수·Hold 성공/409·해제 수·입장/이탈 수·HTTP counter·최종 재고가 맞는지 먼저 확인한다.
2. 구매 Hold p95와 입장 대기 p95·세션 전체 p95를 구분한다. ON의 성공 수 증가는 더 긴 대기/drain으로 기회가 생긴 결과일 수 있으며 처리량 향상과 다르다.
3. 실제 DB 대기가 관측됐는지 본다. 입장 API도 공연/회차 소속 검증에서 MariaDB를 조회하므로 Redis 도입이 모든 DB 접근을 없애는 것은 아니다.
4. 조건별 표본 수·실제 수집 간격·runtime 로그·반복 부재를 함께 읽는다. 초기 도구 중단도 보존하되 최종 비교에 섞지 않는다. 실측과 한계는 [실험 10절](EXPERIMENTS.md#10-입장-제어의-구매-세션-탐색-238)에서 확인한다.

### 한 변수만 바꾸는 후속 비교 (#240)

학습 질문은 **입장권 한도 → polling까지 입장권 점유 → 인기 좌석 충돌·대기 비용**의 관계다. 요청 유입률을 높이거나 worker batch까지 함께 바꾸면 원인을 분리할 수 없다. 따라서 한도 2/4/8만 바꾸고 각 조건을 실행 위치별로 배치해 3회씩 비교한다. 실제 Redis 정책 fingerprint도 시작과 종료 시 확인한다.

기존 OFF/ON 실행 명령은 유지하며 새 비교는 다음 명시적 옵션을 추가한다. 현재 Java 21 JAR, Docker 엔진과 동일한 자원 중단 기준이 필요하다. 실행 시 전용 임시 컨테이너를 만들며 실제 PG/KOPIS는 호출하지 않는다.

```powershell
node --test load-test/k6/test/*.test.mjs
node load-test/scripts/run-admission-session-comparison.mjs --confirm-disposable-local-resources --compare-active-limits
```

각 한도의 **입장 대기·세션 전체·Hold 요청** p95를 구분하고 성공/정상 충돌·polling·표본 DB 대기를 함께 읽는다. 각 실행 p95의 중앙값을 전체 요청 p95로 쓰지 않는다. 짧은 3회 비교가 운영 처리 한계·대규모 트래픽 성능을 증명하지는 않는다. 계획과 실측은 [실험 11절](EXPERIMENTS.md#11-입장-한도별-대기경합-비용-비교-240)에 모은다.

실측에서 한도 2→4→8의 실행별 입장 대기 p95 중앙값은 59.24→25.17→10.11초, polling 합계는 480→238→118로 감소했다. 반면 정상 좌석 충돌은 각 75세션 중 39→41→48건이었다. **대기 감소와 예약 성공 증가는 다르다.** 표본 DB/pool 포화가 없는 이 작은 실험은 입장 정책의 교환관계를 보여주며 DB 처리량 개선이나 최적 한도를 증명하지 않는다. 다음 질문은 polling 주기·입장권을 받은 뒤 읽기까지의 지연을 한도와 분리할지이며, 다른 변수를 동시에 변경하지 않는다.

### 같은 한도에서 polling만 바꾸기 (#242)

입장 한도는 구매 과정에 들어갈 수 있는 사용자 수, polling은 사용자가 그 입장 여부를 확인하는 간격이다. 입장권을 이미 받았지만 사용자가 다음 조회까지 모르는 시간이 생길 수 있다. 한도를 4로 고정한 채 **실험 클라이언트만** 5/2/1초로 조회했다. 서버 권고값·실제 FE는 5초 그대로이며 앱을 개선한 결과가 아니라 적용 후보의 비용 비교다.

```powershell
node --test load-test/k6/test/*.test.mjs
node load-test/scripts/run-admission-session-comparison.mjs --confirm-disposable-local-resources --compare-client-polling
```

- `admission-poll-plan.mjs`: 주기 외 조건과 위치 균형 9단계 계약. 알 수 없는 profile·단계 뒤섞임은 거절한다.
- k6는 서버 권고 5초를 확인한 뒤 실험 주기로 조회한다. 최초 NOT_JOINED 사전 확인은 부하 polling에 포함하지 않으며, GET 200 서버 증분과 k6 poll 수를 대조한다.
- 실제 입장 대기 p95 실행별 중앙은 25.14/10.13/8.16초, 총조회는 각 75세션에서 226/228/345건이었다. **총조회 수가 비슷해도 짧은 구간에 집중되면 초당 조회 부하는 커질 수 있다.** 표본 구간 최대는 5.72/10.44/16.91 RPS였으며 순간 최대·서버 처리 한계와 다르다.
- 2초는 비용과 추가 대기 감소를 보고 고른 다음 검증 후보이지 운영 최적값이 아니다. worker 주기와 다른 사용자 해제 대기도 있어 polling만 줄여 대기를 없앨 수는 없다. [실험 12절](EXPERIMENTS.md#12-고정-한도의-클라이언트-polling-비용-비교-242)에서 환경·범위·한계를 확인한다.
