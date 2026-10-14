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
