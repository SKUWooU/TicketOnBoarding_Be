# 개선 및 학습 기록

이 문서는 작업 순서, 전후 코드와 관련 개념을 Issue 단위로 설명합니다. 진행 상태는 `WORK_PROGRESS.md`, 정량 근거는 `EVIDENCE_MAP.md`를 기준으로 합니다.

## 기록 형식

각 Issue가 병합된 뒤 다음 항목을 추가합니다.

1. 관찰한 기존 동작
2. 문제를 재현한 방법
3. 고려한 대안과 보류 이유
4. 변경 전후의 핵심 코드 경계
5. transaction, lock, rollback 등 관련 개념
6. 테스트와 측정 결과
7. 결과의 한계와 다음 질문
8. Issue, PR과 ADR 링크

## Issue #1 — 개선 기준선과 협업 절차

### 관찰

Backend와 Frontend가 별도 저장소지만 Issue·PR template, 공통 workflow와 개선 근거 문서가 없었습니다. 코드 변경보다 먼저 문제·범위·검증 조건을 일관된 형식으로 축적할 기준이 필요했습니다.

### 결정

Backend 저장소에 전체 개선의 기준 문서와 진행 상태를 두고, Frontend 변경은 독립 Issue·PR로 수행해 상호 링크합니다. 채팅 세션 전달만을 위한 별도 문서는 만들지 않고 `WORK_PROGRESS.md`를 진행 상태의 단일 원본으로 사용합니다.

GlobalTimes의 범용 작업 형식을 기준으로 Issue·PR template을 목적, 범위, 작은 작업 항목, 완료 기준, 테스트와 참고 링크 중심으로 유지합니다. 상세 기술 근거와 측정 한계는 `docs/project-improvement`에 분리하고 Reviewer 전달 문구는 PR 본문에 복제하지 않습니다. README는 Issue마다 갱신하지 않고 실제 코드와 설명이 달라지거나 Phase가 정리되는 시점에 갱신합니다.

참고 자료:

- [GlobalTimes Issue template](https://github.com/SKU-GlobalTimes/GlobalTimes_BeSide/tree/develop/.github/ISSUE_TEMPLATE)
- [GlobalTimes PR template](https://github.com/SKU-GlobalTimes/GlobalTimes_BeSide/blob/develop/.github/PULL_REQUEST_TEMPLATE.md)

### 범위 제한

이번 Issue에서는 애플리케이션 코드, 동시성 로직, 결제 흐름과 Frontend 파일을 변경하지 않습니다. 확인된 사실과 미검증 가설을 분리하며 개선 효과나 성능 수치를 만들지 않습니다.

### 링크

- [Backend Issue #1](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/1)
- [Backend PR #2](https://github.com/SKUWooU/TicketOnBoarding_Be/pull/2)

## Issue #3 — 예매 트랜잭션·경합 기준선

### 관찰과 재현

MariaDB 10.11.8 Testcontainers에 공연 1개, 회차 1개와 가상 좌석 24개를 만들고 start latch로 8개 service 호출을 동시에 시작했습니다. 실제 외부 연동과 운영 DB는 사용하지 않았습니다.

좌석별 `PESSIMISTIC_WRITE`는 동일 좌석의 중복 성공을 막았지만 별도 `ConcertTime` row의 잔여 좌석 집계까지 보호하지 않았습니다. 서로 다른 8좌석이 모두 예약되어도 각 트랜잭션이 먼저 읽은 24에서 1을 뺀 23을 덮어써 불변식이 깨졌습니다.

또한 메서드가 checked `Exception`을 선언하고 있어 복수 좌석 중 두 번째 좌석에서 실패해도 첫 좌석과 예약 row가 commit됐습니다. 좌석 잠금 SQL은 `(concert_time_id, seat_number)` 복합 인덱스를 사용하지 못하고 fixture 24행 전체를 검사했습니다.

### 범위와 다음 단계

이번 Issue는 현재 결함을 재현하는 characterization test와 근거 문서만 추가하고 운영 로직은 바꾸지 않습니다. 다음 Issue에서 rollback 원자성과 회차 집계 갱신을 먼저 바로잡고, 복수 좌석 lock ordering·deadlock은 별도 시나리오로 분리합니다. 분산 락이나 대기열은 이 결과만으로 도입하지 않습니다.

상세 조건과 결과는 [예매 트랜잭션·경합 기준선](reservation-transaction-concurrency-baseline.md)에서 확인합니다.

### 링크

- [Backend Issue #3](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/3)

## Issue #5 — 예약 원자성·잔여 좌석 갱신 정합성

### 문제 경계

Issue #3에서 같은 예약 transaction 안의 두 결함을 분리해 확인했습니다. checked exception은 앞선 좌석과 예약을 부분 commit했고, 서로 다른 좌석 transaction은 각자 읽은 회차 집계값을 덮어썼습니다.

### 결정

기존 예외 signature를 바꾸지 않고 `rollbackFor = Exception.class`을 명시해 모든 예약 실패를 rollback 대상으로 만들었습니다. 회차 전체를 예약 시작부터 잠그는 대신 조건부 원자 감소 update를 사용해 서로 다른 좌석 작업을 선제적으로 직렬화하지 않았습니다. update 영향 row가 1이 아니면 예외를 발생시켜 앞서 flush된 좌석과 예약도 함께 rollback합니다.

### 전후 결과

같은 가상 좌석 24개와 결정적 barrier에서 서로 다른 8좌석 예약은 `잔여 23·불변식 위반`에서 `잔여 16·불변식 충족`으로 바뀌었습니다. 복수 좌석 checked exception은 첫 좌석·예약 부분 commit에서 전체 rollback으로 바뀌었습니다. 잔여 부족 guard fixture에서도 음수 집계와 부분 commit이 발생하지 않았습니다.

### 범위 제한

이번 변경은 처리량 최적화가 아니며 lock wait를 측정하지 않았습니다. 복수 좌석 lock ordering, 좌석 복합·유일 인덱스, 취소·결제·멱등성과 API 예외 모델은 후속 Issue로 남겼습니다.

상세 조건과 결과는 [예약 원자성·잔여 좌석 갱신 정합성 개선](reservation-atomicity-inventory-consistency.md)에서 확인합니다.

### 링크

- [Backend Issue #5](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/5)

## Issue #9 — 복수 좌석 잠금 순서와 deadlock 기준선

### 관찰

같은 회차의 `[A1,A2]`와 `[A2,A1]`을 동시에 예약하고, test 전용 Repository proxy에서 각 transaction의 첫 좌석 lock 반환 직후 barrier를 기다렸습니다. 세 번 모두 두 transaction이 서로 다른 첫 lock을 동시에 획득하지 못했고 SQL deadlock은 발생하지 않았습니다. 한 transaction이 두 좌석을 commit한 뒤 다른 transaction은 `이미 예약된 좌석입니다.`로 실패했습니다.

### 해석

이 결과는 입력 순서 잠금이 안전하다는 뜻이 아닙니다. 기존 `EXPLAIN`의 full scan과 복합 index 부재를 함께 보면 현재 잠금 조회가 첫 좌석부터 넓게 직렬화된 것으로 추론할 수 있습니다. 좌석 식별 index로 잠금 범위를 좁히면 반대 순서 cycle이 드러날 수 있으므로 index와 canonical lock ordering을 함께 비교해야 합니다.

### 최종 상태

매회 성공 1·business 실패 1, 예약 좌석 2, 예약 row 2, 잔여 22로 `24 = reserved + remaining`을 충족했습니다. 실패 transaction의 부분 commit과 SQL deadlock error는 없었습니다. 결과는 MariaDB 10.11.8 단일 컨테이너와 가상 좌석 24개 조건이며 운영 deadlock 발생률이나 처리량을 의미하지 않습니다.

상세 동기화 방식과 다음 검증 조건은 [복수 좌석 잠금 순서와 deadlock 기준선](multi-seat-lock-order-deadlock-baseline.md)에 기록합니다.

### 링크

- [Backend Issue #9](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/9)
- [Backend PR #10](https://github.com/SKUWooU/TicketOnBoarding_Be/pull/10)

## Issue #11 — 좌석 복합 인덱스와 deadlock 비교 기준선

### 비교

Issue #9와 같은 fixture에 test용 `(concert_time_id, seat_number)` 복합 unique index만 적용했습니다. 잠금 query plan은 `ALL/key=null`에서 `const/복합 key/rows=1`로 바뀌었고, 반대 순서 transaction은 서로 다른 첫 좌석 lock을 동시에 획득했습니다.

### 관찰

`[A1,A2]`와 `[A2,A1]` 시나리오를 3회 반복한 결과 매회 MariaDB error `1213`, SQL state `40001`이 발생했고 Spring은 `CannotAcquireLockException`으로 변환했습니다. 한 transaction만 성공했으며 deadlock victim 사용자의 예약 row는 0개였습니다. 최종 좌석 2·예약 2·잔여 22로 rollback 후 불변식은 유지됐습니다.

### 다음 결정

복합 index는 좌석 식별과 잠금 query plan을 개선하지만 입력 순서 잠금의 cycle을 실제로 드러냈습니다. 따라서 운영 변경에서는 index migration과 canonical lock ordering을 함께 검증해야 합니다. retry·분산 lock·대기열은 도입하지 않습니다.

상세 조건은 [좌석 복합 인덱스와 deadlock 비교 기준선](seat-composite-index-deadlock-comparison.md)에 기록합니다.

### 링크

- [Backend Issue #11](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/11)
- [Backend PR #12](https://github.com/SKUWooU/TicketOnBoarding_Be/pull/12)

## Issue #15 — 복수 좌석 canonical 잠금 순서와 요청 검증

### 변경

client가 전달한 좌석 목록을 직접 순회하던 흐름을 복사 후 natural order 정렬로 변경했다. 예약 요청·좌석 목록·좌석 번호의 null·blank와 payload 내부 중복은 첫 좌석 잠금 query 전에 거부한다. 요청 DTO의 원본 목록은 변경하지 않는다.

### 검증

Issue #11과 같은 test 복합 index 조건에서 `[A1,A2]`와 `[A2,A1]`을 3회 실행했다. 두 transaction의 repository query는 모두 `A1`부터 시작했고 SQL deadlock 없이 성공 1·예약 충돌 1로 끝났다. 최종 좌석 2·예약 2·잔여 22와 실패 사용자 예약 0건을 유지했다.

잘못된 좌석 목록은 잠금 query 0회였으며 전체 18개 test invocation이 통과했다. 결과는 로컬 단일 MariaDB·가상 좌석 fixture의 정확성 검증이며 운영 deadlock 발생률이나 처리량 수치가 아니다.

### 다음 결정

운영 복합 unique index는 기존 schema migration 기준과 중복 데이터 검사 없이 추가하지 않는다. 후속 schema Issue에서 migration과 query plan을 검증한 뒤 Phase 5의 예약·결제 상태 전이로 이동한다.

상세 내용은 [복수 좌석 canonical 잠금 순서와 요청 검증](canonical-seat-lock-order.md)에 기록한다.

### 링크

- [Backend Issue #15](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/15)
- [Backend PR #16](https://github.com/SKUWooU/TicketOnBoarding_Be/pull/16)

## Issue #17 — 좌석 복합 unique index migration 안전성 기준선

### 재현

현재 JPA 생성 schema에 같은 회차의 `A1`을 한 개 더 insert했다. 중복 사전 점검 query는 해당 key와 count 2를 반환했고 복합 unique index DDL은 SQL state `23000`, MariaDB error `1062`로 실패했다. 실패 후 index는 없었고 두 row는 그대로 유지됐다.

### clean schema 비교

중복이 없는 fixture에서는 `(concert_time_id, seat_number)` column 순서와 `Non_unique=0`을 확인했다. 이후 같은 `A1` insert는 동일한 `23000/1062`로 거부됐고 기존 row는 1개였다.

### 결정

좌석 ALTER migration만 추가하면 신규 DB에 전체 schema가 없어 실패한다. 10개 Entity의 baseline DDL과 기존 DB version을 확인하기 전까지 Flyway 운영 활성화를 보류한다. 실제 조건과 대안은 [ADR-0001](adr/0001-schema-migration-ownership.md)에 기록한다.

상세 내용은 [좌석 복합 unique index migration 안전성 기준선](seat-unique-index-migration-baseline.md)에서 확인한다.

### 링크

- [Backend Issue #17](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/17)
- [Backend PR #18](https://github.com/SKUWooU/TicketOnBoarding_Be/pull/18)

## Issue #19 — Docker Compose 기반 로컬 Backend 실행 기준선

### 이전 상태

테스트는 Testcontainers property로 실행됐지만 추적된 `application.yml`이 비어 있어 실제 `bootRun`에 필요한 DB와 외부 연동 placeholder가 없었다. HTTP server와 DB를 함께 재현하는 명령도 없었다.

### 구성

MariaDB 10.11.8만 Compose로 실행하고 Backend는 host JVM의 local profile로 실행했다. local profile은 port 18080, Compose DB, disposable Hibernate schema, 비활성 Batch와 실제 인증에 사용할 수 없는 외부 연동 placeholder를 사용한다. placeholder는 외부 요청 자체를 차단하지 않으므로 smoke에서는 관련 endpoint를 호출하지 않았고, 자동화 검증은 fixture·mock을 사용해야 한다.

### 실제 검증

Compose MariaDB가 healthy인 상태에서 `bootRun`이 18080 port로 시작했다. `GET /main`은 200과 두 개의 빈 공연 목록을 반환했고 Backend 로그의 실제 JPA SELECT와 DB의 concert 0건이 일치했다. 기존 DataInitializer의 admin 1건 생성도 확인했다.

서버와 container는 검증 후 종료했고 named volume은 삭제하지 않았다. KOPIS·CoolSMS·OAuth·결제·운영 DB 호출은 없었다.

상세 명령과 환경변수는 [Docker Compose 기반 로컬 Backend 실행 기준선](local-backend-runtime.md)에 기록한다.

### 링크

- [Backend Issue #19](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/19)
- [Backend PR #20](https://github.com/SKUWooU/TicketOnBoarding_Be/pull/20)

## Issue #21 — 취소 중복 요청과 상태·재고 정합성 기준선

취소 전 예약 1건, 점유 좌석 1개, 잔여 23인 MariaDB fixture에서 정상 취소는 예약을 `취소완료`, 좌석을 미점유, 잔여를 24로 만들었다. 같은 예약을 한 번 더 취소하면 상태와 좌석은 그대로지만 잔여가 25로 증가해 `24 = remaining + reserved` 불변식이 깨졌다. 동일 조건을 3회 반복해 같은 결과를 확인했다.

현재 service는 기존 예약 상태를 검사하지 않으므로 사용자의 `취소신청`을 거치지 않은 `결제완료` 예약도 바로 `취소완료`로 바뀌었다. 이 기준선은 순차 재시도만으로 멱등성 결함을 확정하므로 동시 barrier나 k6를 추가하지 않았다. 후속 개선에서는 허용 상태 전이, 중복 완료의 결과 정책, 좌석·예약·잔여 수량의 단일 transaction과 잠금 순서를 함께 검증한다.

### 링크

- [Backend Issue #21](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/21)
- [Backend PR #22](https://github.com/SKUWooU/TicketOnBoarding_Be/pull/22)

## Issue #23 — Reviewer 판정 기반 안전한 auto-merge

반복되는 최종 승인 전달을 줄이되 comment 문자열 하나만으로 병합하지 않는다. PR마다 Java 21·Testcontainers 전체 테스트를 실행하고, 허용 Reviewer가 기록한 40자리 `REVIEWED_HEAD`가 현재 같은 저장소의 `main` 대상 PR HEAD와 일치하는지 확인한다. 최신 `Backend test`가 성공한 뒤에도 HEAD를 다시 비교하고 `--match-head-commit`으로 squash merge한다.

workflow는 `actionlint 1.7.7`을 통과했고 PR의 첫 Ubuntu `Backend test`는 1분 37초에 성공했다. `issue_comment` workflow는 default branch의 정의만 사용하므로 이 PR 병합 이후 synthetic PR에서 stale HEAD·실패 gate·성공 merge를 검증해야 한다. 외부 API·결제·운영 데이터·배포와 사용자 선택이 필요한 설계는 상시 승인 범위에서 제외한다.

Issue #25의 synthetic PR에서 이전 HEAD `09ddb4578671b1591f3dc33d9867a57fd5531c8e`를 승인한 comment를 남기자 Action은 실패했고 PR은 OPEN을 유지했다. GitHub Actions는 `Reviewer가 검토한 HEAD와 현재 HEAD가 다릅니다`라는 원인을 comment로 남겼다. 최신 HEAD와 CI 성공을 사용하는 실제 Reviewer comment의 자동 squash merge는 같은 Issue의 성공 경로로 이어서 확인한다.

최신 HEAD `e9f95034b477039dc037c22a24601248954ae57f`의 실제 Review 이후 Action run `32651816465`는 squash merge와 원격 branch 삭제에 성공했다. 다만 PR이 closing Issue로 #25를 인식했음에도 Issue는 OPEN으로 남았다. #25는 결과와 후속 #27을 연결해 수동 종료했고, workflow가 같은 저장소의 열린 `closingIssuesReferences`를 merge 후 명시적으로 닫도록 보완한다.

Issue #27은 merge 후 같은 저장소의 closing Issue만 필터링하고 OPEN 상태만 명시적으로 닫는다. 이미 닫힌 Issue와 연결 Issue가 없는 PR은 건너뛰며, 종료 실패는 merge 이후 정리 실패로 Action과 PR comment에 드러낸다. [Backend Issue #27](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/27) / [PR #28](https://github.com/SKUWooU/TicketOnBoarding_Be/pull/28)

PR #28의 최신 HEAD `50d369c4c36756f56dcd3b31a17ff76f4f6aa7cd`는 Backend CI를 56초에 통과했고 Reviewer `MERGE_READY: YES` 후 `b5d7347aadf7ce832e9c5df1a80f2765eb1700fe`로 자동 squash merge됐다. 이 때는 병합 전 `main`의 workflow가 실행됐으므로 연결 Issue #27은 열린 상태를 유지한다. Issue #29의 문서 전용 synthetic PR에 #27과 #29를 함께 연결해 default branch에 반영된 종료 로직을 실제 경로로 검증한다. [Backend Issue #29](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/29)

### 링크

- [Backend Issue #23](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/23)
- [Backend PR #24](https://github.com/SKUWooU/TicketOnBoarding_Be/pull/24)
- [Backend Issue #25](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/25)
- [Backend PR #26](https://github.com/SKUWooU/TicketOnBoarding_Be/pull/26)

## Issue #31 — 취소 중복 요청의 재고 중복 복구 방지

### 문제와 결정

Issue #21에서 관리자 취소 Service가 상태 검사 없이 좌석 해제와 잔여 수량 증가를 반복해, 같은 예약의 두 번째 순차 호출만으로 잔여가 25가 되는 결함을 확인했다. 단순히 `취소완료`를 검사하는 것만으로는 두 transaction이 동시에 이전 상태를 읽는 경쟁 조건을 막지 못한다.

취소 처리를 단일 transaction으로 묶고 예약 row를 `PESSIMISTIC_WRITE`로 조회한다. 첫 요청만 `취소신청 → 취소완료` 전이와 좌석 해제·회차 잔여 원자 증가를 수행하며, lock을 이어받은 중복 요청은 `취소완료`를 확인하고 변경 없이 성공한다. `결제완료` 등 허용되지 않은 상태와 존재하지 않는 예약, 이미 해제된 좌석은 예외로 거부한다.

### 검증과 한계

MariaDB 10.11.8의 공연 1개·회차 1개·가상 좌석 24개 fixture에서 정상 취소와 순차 중복을 검증했다. 동시 중복은 test repository proxy가 첫 service transaction의 예약 lock 획득 직후 진행을 보류하고, 두 번째 service transaction이 같은 lock 조회에 진입한 뒤 200ms 동안 반환하지 못하는 것을 확인한 다음 첫 transaction을 commit하도록 제어했다. 이 조건을 3회 반복해 두 요청 모두 성공하고 최종 상태 `취소완료`·미점유·잔여 24와 `24 = remaining + reserved`를 충족했다.

회차 잔여 증가 query가 0건을 반환하도록 reservation의 회차 ID를 명시적으로 어긋나게 한 fixture에서는, 좌석·예약 변경이 flush된 뒤 발생한 예외가 전체 transaction을 rollback해 `취소신청`·점유 1·잔여 23을 유지했다. `결제완료` 직접 취소, 없는 예약과 이미 해제된 좌석도 변경 없이 거부했다. 대상 11개와 전체 Backend 30개 test invocation이 통과했다.

이는 단일 MariaDB와 가상 좌석의 정합성 결과이며 TPS·lock wait·운영 처리량을 측정한 결과가 아니다. 사용자 취소 신청과 관리자 승인 간 경쟁, 서로 다른 예약의 대량 동시 취소, 실제 결제 환불과 전체 상태 enum 전환은 후속 Issue로 남긴다.

### 링크

- [Backend Issue #31](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/31)

## Issue #33 — 사용자 취소 신청과 관리자 승인 경합 상태 전이 기준선

### 조사한 경계

사용자 취소 신청은 Controller가 `findById`로 예약을 읽고 소유자를 확인한 뒤 상태를 `취소신청`으로 바꾸어 별도로 `save`한다. 반면 관리자 승인은 Service transaction에서 같은 예약을 `PESSIMISTIC_WRITE`로 읽고 `취소신청 → 취소완료`, 좌석 해제와 잔여 수량 복구를 함께 commit한다. 사용자 경로의 조회와 저장 전체를 감싸는 transaction이나 예약 row lock은 없다.

### 결정적 재현

MariaDB 10.11.8의 공연 1개·회차 1개·가상 좌석 24개 fixture를 사용했다. 사용자 중복 신청 thread가 `취소신청` 예약을 읽은 직후 latch에서 대기하고, 관리자 실제 취소 Service가 먼저 `취소완료`·좌석 미점유·잔여 24를 commit한 것을 확인한 뒤 사용자 저장을 재개했다. 단순 동시 출발이 아니라 문제가 되는 commit 순서를 강제했으며 동일 조건을 3회 반복했다.

세 번 모두 사용자의 늦은 저장이 예약 상태만 `취소신청`으로 되돌렸다. 좌석은 미점유, 잔여는 24로 재고 수량 불변식 자체는 유지됐지만 관리 화면에는 다시 승인 대상처럼 나타날 수 있고, 같은 관리자 승인 재시도는 이미 해제된 좌석 오류로 거부됐다. 사용자 신청 후 관리자 승인을 순차 실행한 제어군은 최종 `취소완료`를 유지했다.

### 범위와 다음 판단

이는 단일 MariaDB와 가상 좌석에서 현재 repository 호출 순서를 재현한 상태 정합성 기준선이며 운영 환경의 발생 빈도나 처리량을 뜻하지 않는다. 이번 Issue에서는 애플리케이션을 수정하지 않는다. 사용자 취소 신청을 transaction Service로 이동할지, 어떤 상태에서 신청·재신청을 허용할지, 관리자 승인과 같은 예약 lock 순서를 공유할지는 별도 개선 Issue에서 결정한다.

### 링크

- [Backend Issue #33](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/33)

## Issue #35 — 사용자 취소 신청의 잠금·상태 전이 원자성 보장

### 상태 정책과 transaction 경계

사용자 취소 신청의 예약 조회·소유자 확인·상태 변경을 Controller에서 `@Transactional` Service로 이동하고 관리자 승인과 같은 `findByIdWithLock`을 사용한다. `결제완료`만 `취소신청`으로 변경하며 이미 `취소신청`이거나 `취소완료`이면 원하는 결과가 이미 진행 또는 달성된 것으로 보고 변경 없이 성공한다. 다른 사용자, 없는 예약과 알 수 없는 상태는 변경 없이 거부한다.

이 정책은 응답 유실 뒤 같은 요청이 다시 도착해도 상태를 역전시키지 않는다. Controller는 JWT에서 얻은 username과 reservationId를 Service에 위임하고 기존 성공·인증·검증 실패 HTTP 응답을 유지한다.

### 개선 후 경합 검증

Issue #33과 같은 MariaDB 10.11.8·가상 좌석 24개 fixture에서 관리자 승인 transaction이 예약 lock을 획득한 직후 진행을 보류했다. 사용자 신청이 같은 lock 조회에 진입했지만 200ms 동안 반환하지 못하는 것을 확인한 뒤 관리자 transaction을 commit했다. lock을 이어받은 사용자 신청은 최신 `취소완료`를 읽어 변경 없이 성공했다. 반대로 `결제완료` 예약의 사용자 신청이 먼저 lock을 보유한 경우에는 관리자 승인이 대기한 뒤 `취소신청` commit을 이어받아 취소를 완료했다. 두 순서를 각 3회 반복해 최종 `취소완료`·좌석 미점유·잔여 24를 유지했다.

정상 신청과 순차 중복, 취소 완료 후 재요청, 다른 사용자·없는 예약·미지원 상태도 예약·좌석·잔여 수량 snapshot으로 교차 검증했다. Controller 단위 테스트는 인증된 사용자 위임과 기존 200·400·401 응답을 확인했다. Service 통합 23개·Controller 3개와 전체 Backend 45개 test invocation이 통과했다.

### 범위와 한계

동일 예약의 사용자 신청과 관리자 승인 상태 전이만 단일 MariaDB fixture에서 검증했다. 실제 환불 API의 멱등성, 전체 상태 enum 전환, 운영 발생 빈도·처리량과 분산 환경의 lock은 이번 결과에 포함되지 않는다.

### 링크

- [Backend Issue #35](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/35)

## Issue #37 — 결제 승인과 예약 확정 경계·중복 요청 기준선

### 현재 계약에서 확인된 사실

Frontend의 KakaoPay·이니시스 화면은 브라우저 PortOne SDK의 성공 callback을 받은 뒤에야 Backend 예약 API를 호출한다. Backend `ReservRequest`에는 공연일·회차 ID·공연 시간·좌석 번호 목록 네 필드만 있고 payment ID, PG 승인 token과 결제 금액은 없다. Service는 이 요청만으로 좌석을 점유하고 예약 상태를 `결제완료`로 저장한다.

MariaDB 10.11.8의 공연 1개·회차 1개·가상 좌석 24개 fixture에서 현재 네 필드 DTO로 `A1`을 예약하자 `결제완료` 예약 1건·점유 좌석 1개·잔여 23이 생성되고 재고 불변식은 유지됐다. 이는 Backend 내부 재고 transaction은 일관되지만 서버가 PG 결제 사실을 독립적으로 확인했다는 의미는 아니다.

### 동일 요청 재시도

첫 예약 transaction이 성공한 뒤 응답 유실을 가정해 같은 사용자·회차·좌석 payload를 다시 전송했다. 두 번째 요청은 `이미 예약된 좌석입니다.`로 실패했고 예약 1건·점유 1개·잔여 23은 첫 성공 snapshot과 동일했다. 재고 중복 차감은 없지만 호출자는 첫 요청의 성공 결과를 재사용할 수 없으므로 API 수준의 멱등성은 제공되지 않는다. 대상 20개와 전체 Backend 47개 test invocation이 통과했다.

### 확인된 사실과 장애 가능성의 경계

코드에서 확인된 순서는 `브라우저 결제 성공 → Backend 예약`이며 예약 실패 catch는 오류 기록만 수행한다. 따라서 결제 성공 뒤 좌석 충돌·네트워크·Backend 실패가 발생하면 결제 승인과 예약 확정이 불일치할 수 있고 자동 취소·보상 경로는 확인되지 않는다. 이 문장은 현재 두 시스템의 호출 순서에서 도출한 실패 창이며, 실제 PG 장애를 실행하거나 발생률을 측정한 결과는 아니다.

후속 개선에는 서버가 소유하는 booking/payment 식별자, 결제 금액 검증, 동일 idempotency key의 결과 재사용과 payload 충돌 거부가 선행되어야 한다. 좌석 hold·만료, PG 승인과 보상 취소는 이 계약이 정해진 뒤 mock 결제 경계에서 별도로 검증한다. 이니시스 화면의 고정 결제 금액 `100`은 Frontend 별도 Issue로 분리한다.

### 링크

- [Backend Issue #37](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/37)

## Issue #39 — 예약 중복 요청의 최초 결과 재사용

### 왜 좌석 lock만으로 충분하지 않은가

좌석 `PESSIMISTIC_WRITE`는 첫 예약 이후 재고 중복 차감을 막지만, 응답을 받지 못한 호출자가 같은 요청을 다시 보냈다는 사실은 알 수 없다. 그래서 두 번째 요청은 이미 점유된 좌석이라는 현재 상태만 보고 실패한다. 멱등성은 “두 번 변경하지 않는다”뿐 아니라 동일 작업의 최초 결과를 다시 제공할 식별자와 결과 저장이 필요하다.

복수 좌석 예약은 현재 좌석마다 `Reservation` 한 행을 만들기 때문에 한 요청을 묶는 Booking을 추가했다. `(username, idempotency_key)` unique constraint가 동시 키 소유자를 하나로 정하고, Booking과 좌석·예약·잔여 수량 변경을 같은 transaction에 둬 중간 실패 시 키도 함께 rollback한다. 같은 키의 패자 transaction은 unique 위반 뒤 승자의 commit 결과를 다시 조회한다.

### payload 의미와 호환 경계

fingerprint에는 현재 Service가 실제 사용하는 공연 ID·회차 ID·정렬된 좌석 목록만 포함한다. 좌석 입력 순서가 달라도 같은 예약으로 보며, 사용하지 않는 client 공연일·공연 시간 문자열은 결과 의미에 영향을 주지 않는다. 다른 의미의 요청에 같은 키를 쓰면 HTTP 409, 공백이나 100자를 넘는 키는 HTTP 400으로 거부한다.

기존 Frontend가 아직 header를 보내지 않기 때문에 `Idempotency-Key`는 선택적으로 추가했다. 키 없는 호출의 기존 `LocalDateTime` 성공 응답과 예약 경로를 유지하고, 키가 있는 호출도 응답 구조를 바꾸지 않은 채 Booking의 최초 생성 시각을 반환한다.

### 검증과 한계

MariaDB 10.11.8·가상 좌석 24개 fixture에서 순차 재시도, 두 요청이 모두 기존 키 없음 결과를 얻은 뒤의 동시 경쟁을 3회, 다른 payload 충돌과 실패 후 키 재사용을 검증했다. 동시 경쟁은 매회 같은 생성 시각을 반환하고 Booking 1·Reservation 1·점유 1·잔여 23으로 수렴했다. Service 29개·Controller 3개와 전체 Backend 59개 test invocation이 통과했다.

이는 실제 PG 결제 멱등성이나 운영 처리량 결과가 아니다. Frontend 키 전달과 운영 schema migration이 완료되기 전에는 실제 화면 경로와 배포 DB에 적용됐다고 주장할 수 없다. 상세 계약과 fixture는 [예약 요청 멱등성과 최초 결과 재사용](reservation-idempotency.md)에 기록한다.

### 링크

- [Backend Issue #39](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/39)

## Issue #41 — 예약 상태 문자열을 명시적 전이 정책으로 전환

### 문자열 상태가 남긴 문제

취소 transaction과 예약 row lock을 보강한 뒤에도 상태 자체는 Service가 한글 문자열을 비교하고 직접 setter로 변경했다. 이 구조에서는 새로운 문자열을 아무 곳에서나 저장할 수 있고, 허용 전이와 멱등 전이를 Entity가 보장하지 못한다. 후속 Payment 상태 분리 전에 현재 예약 상태의 책임부터 제한했다.

### 타입과 외부 계약 분리

Java 내부에서는 `ReservationStatus`만 사용하고 상태 setter를 제거했다. `Reservation`은 결제 완료 초기화, 취소 신청, 취소 완료 메서드로만 상태를 바꾼다. 사용자 신청 재시도와 완료 후 재신청, 관리자 완료 재시도는 기존 멱등 정책을 유지하고 `결제완료 → 취소완료` 직접 전이는 거부한다.

DB와 Frontend는 기존 한글 값에 결합되어 있으므로 converter와 JSON value로 `결제완료`, `취소신청`, `취소완료`를 그대로 유지했다. MariaDB raw column과 typed repository 조회, Reservation JSON을 함께 검증해 enum 도입이 외부 계약을 바꾸지 않음을 확인했다.

### 검증과 다음 단계

상태 단위 6개, 예약·멱등성·좌석 경합 29개, 취소 경합·rollback 23개를 포함한 전체 Backend 65개 invocation이 통과했다. 상태가 먼저 `취소완료`로 바뀐 뒤 재고 복구 query가 실패하는 fixture도 transaction rollback 후 `취소신청`·점유·잔여 수량을 유지했다.

이번 변경은 서버 미검증 `결제완료` 생성을 해결하지 않는다. 다음에는 Frontend 고정 가격이 아니라 서버가 소유하는 가상 가격 기준과 별도 Payment 상태·mock 검증 경계를 설계한다. 그 상태 불변식이 안정된 후에야 k6의 성공·충돌·실패 응답을 도메인 결과로 해석할 수 있다. 상세 내용은 [예약 상태의 타입 안전 전이 정책](reservation-status-transition.md)에 기록한다.

### 링크

- [Backend Issue #41](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/41)

## Issue #43 — 서버 소유 가상 가격과 mock 결제 검증 경계

### client 성공 callback을 서버 승인으로 보지 않기

KOPIS의 가격은 자유 문자열이고 Frontend 금액도 서로 다른 고정값이다. 신규 Backend 경로는 client 금액을 받지 않고 서버 가상 단가 30,000원과 정규화된 좌석 수로 기대 금액을 계산한다. `PaymentVerificationPort`가 반환한 식별자·사용자·승인 상태·금액·시각이 전부 일치해야 예약 transaction에 진입한다.

### 외부 검증과 DB 소유권을 분리하기

결제 검증은 좌석 lock 전에 수행하고, 검증된 provider payment ID의 1회 소비는 DB unique 제약으로 보장한다. Payment unique insert부터 Booking·Reservation·Seat·ConcertTime 변경은 한 transaction이다. 따라서 외부 대기는 lock 보유 시간에 포함되지 않고, 예약 실패는 결제 소비 기록까지 rollback한다.

### 가상 결제 검증과 한계

MariaDB 10.11.8·가상 좌석 24개에서 2석 60,000원 정상 확정, 미승인·식별자·금액/사용자 불일치 무변경 거부, 성공 응답 재사용, 예약 실패 rollback을 확인했다. 동일 결제 ID를 다른 멱등 키로 동시 소비하는 시나리오는 3회 모두 1건만 확정됐다. Reviewer Blocking 후에는 실제 좌석·예약 변경 뒤 재고 감소가 실패하는 late-failure와, 동일 멱등 키의 동시 동일 payload 재사용·다른 payload 충돌을 각 3회 결정적 barrier로 추가했다. Issue 대상 30개와 기존 회귀를 포함한 전체 Backend 92개 invocation이 통과했다.

실제 PG, 운영 schema, Frontend 전환, 좌석 hold·만료·환불은 검증하지 않았다. 기존 `/reservation`은 호환을 위한 미검증 legacy 경로로 남아 있다. Backend 우선 작업 후 Frontend 계약을 전환하고 legacy를 제거해야 한다. 상세 근거는 [서버 가상 가격과 mock 결제 검증 경계](verified-payment-reservation-boundary.md)에 기록한다.

### 링크

- [Backend Issue #43](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/43)

## Issue #47 — 정확성 fixture에서 고경합 측정 기반으로 확장

### 좌석 수와 트래픽을 구분하기

기존 24석 Testcontainers fixture는 barrier와 lock 대기를 이용해 동시성 오류를 결정적으로 재현한다. 이 fixture를 단순히 2,000석으로 늘리는 것은 처리 능력의 증거가 아니며 테스트 실행 비용만 키울 수 있다. 그래서 정확성 회귀는 24석으로 유지하고, HTTP 요청률·p95·커넥션·DB lock을 관찰하는 실행 전용 fixture만 50행 × 40석으로 분리했다.

2,000석은 실제 KOPIS 공연장 좌석을 모사한 데이터가 아니라 hot seat·hot section·분산 좌석이라는 서로 다른 경합 분포를 만들기 위한 가상 재고 공간이다. 성능 수치는 좌석 수보다 요청 도착률, 경합 분포, DB connection 설정, 실행 장비와 함께 기록해야 비교할 수 있다.

### 측정 경로를 운영 연동과 분리하기

검증된 예약 경로에는 Payment 검증이 필요하므로 `loadtest` profile 전용 adapter가 로컬 형식의 결제 ID만 승인하도록 했다. fixture·JWT endpoint·adapter는 profile 밖에서 등록되지 않고 서버와 관리 포트도 loopback에 bind한다. 따라서 KOPIS·PG·SMS 없이 예약의 실제 transaction·lock·멱등성 코드는 그대로 통과한다.

첫 구현은 고정 공연·회차를 재사용해 두 번째 k6부터 이전 좌석과 멱등 결과가 섞였다. Reviewer Blocking을 통해 실행마다 run ID로 공연·회차·사용자·멱등 key·결제 ID를 분리했다. snapshot도 회차와 run별 식별자만 세며, 정합성 false를 단순 출력하지 않고 k6 실패로 연결한다. 재리뷰에서는 최대 run ID가 결제 ID에 두 번 들어가 100자 계약을 넘는 경계값을 찾아, run별 username만 소유권 경계로 남기고 nonce 중복을 제거했다. 반복 가능한 측정에서는 입력·집계 격리뿐 아니라 식별자 조합의 최대 길이도 실제 API 계약으로 검증해야 한다.

k6는 동일 한 좌석, 40석 구간, 2,000석 분산, 동일 멱등 요청의 네 모델을 제공한다. 처리량과 지연만 보지 않고 종료 snapshot에서 `전체 = 잔여 + 점유`, `점유 = 예약`, `Booking = Payment`도 검사한다. 성능과 정합성 중 하나라도 실패하면 개선으로 판단하지 않는다.

### smoke와 기준선의 차이

run 격리 보완 후 distributed 5 RPS·10초를 연속 실행했을 때 각 run은 예약 50건과 51건을 별도 재고에 저장하고 모두 불변식을 충족했다. run 경계·로그 조건까지 최종 보완한 실행은 예약 API 전용 p95 68.21ms·비-2xx 0건·예약 51건으로 종료됐다. 이는 스크립트·fixture·관측 경로와 재실행 격리가 함께 동작한다는 증거이지 안정 처리량이나 개선 전후 수치가 아니다. 로그 조건이 다른 앞선 실행과 직접 비교하지 않으며, 종료 뒤 Hikari와 MariaDB 상태도 peak가 아니므로 시계열 관측 없이는 병목 위치를 단정할 수 없다.

다음 학습 순서는 경합 실패를 409와 5xx로 구분하는 HTTP 계약, 부하 중 시계열 수집, 단계별 도착률 측정이다. 대기열·분산 lock·outbox·브로커는 이 측정에서 필요 조건이 확인된 뒤 선택한다. 상세 조건과 명령은 [가상 좌석 2,000석 고경합 부하 측정 기반](high-contention-load-test-harness.md)에 기록했다.

### 링크

- [Backend Issue #47](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/47)

## Issue #49 — 정상적인 좌석 경합과 서버 장애를 분리

### DB 정합성과 HTTP 의미는 별도 계약이다

동일 좌석 동시 예약은 비관적 잠금으로 한 요청만 성공했고 DB 불변식도 유지됐다. 그러나 잠금 이후 점유를 발견한 후발 요청은 일반 checked `Exception`으로 끝나 HTTP 500이 됐다. 데이터가 깨지지 않았다는 사실만으로 API 계약까지 올바른 것은 아니다. 예측 가능한 재고 경쟁을 서버 내부 장애와 분리해야 부하 결과의 오류율을 해석할 수 있다.

### 예상 경합을 모든 시나리오에서 성공으로 숨기지 않기

좌석 선점 실패와 잔여 수량 감소 실패만 전용 409 예외로 바꾸고 성공·인증·멱등성·결제 검증 계약은 그대로 유지했다. k6도 hot-seat·hot-section의 409만 예상 경합으로 집계한다. distributed·idempotent-retry의 409는 시나리오상 예상하지 않은 결과이므로 실패율에 남긴다. 같은 상태 코드라도 부하 모델의 의도에 따라 해석이 달라져야 한다.

### 기본 지표와 사용자 정의 지표의 의미 맞추기

첫 hot-seat smoke는 성공 1·409 경합 200·예상 밖 오류 0이었지만 k6 기본 `http_req_failed`는 409를 모두 실패로 세어 98.03%를 표시했다. hot 시나리오에서만 409를 expected status로 등록한 뒤 독립 run을 다시 실행해 기본 실패율과 커스텀 실패율을 모두 0%로 맞췄다. 지표 이름만 추가하는 것으로는 충분하지 않고 도구의 기본 성공 판정도 도메인 계약과 일치해야 한다.

최종 로컬 smoke는 20 RPS·10초의 201회 요청에서 성공 1·예상 409 경합 200·예상 밖 비-2xx/5xx 0, 예약 p95 36.69ms, 종료 불변식 충족으로 끝났다. 이는 오류 분류 계약 확인값이며 안정 처리량이나 성능 개선 수치가 아니다. 전체 Backend 102개 test도 실패·오류·skip 없이 통과했다. 상세 내용은 [좌석 경합 실패의 HTTP 409 계약](seat-contention-http-contract.md)에 기록한다.

### 링크

- [Backend Issue #49](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/49)

## Issue #51 — 부하 결과와 connection·lock 상태를 같은 시간축에 연결

### 종료 순간값과 global 누적값을 특정 run의 원인으로 보지 않기

Issue #47 smoke가 끝난 뒤 Hikari active 0과 MariaDB row lock wait 누적값은 확인할 수 있었지만, 부하 중 peak인지 해당 run에서 증가했는지는 알 수 없었다. Hikari gauge는 반복 표본의 peak로, MariaDB global counter는 첫 값과 마지막 값의 delta로 나눠야 의미가 생긴다. 실제 표본에서 DB CLI와 Compose healthcheck도 connection을 만들었으므로 `Connections` 증가는 application 병목 근거에서 제외했다.

### 관측 도구의 실패를 측정 성공과 분리하기

경량 PowerShell wrapper는 k6 child process와 Hikari·MariaDB 표본을 같은 run ID로 실행한다. 필수 metric 누락, counter 감소, 결과 파일 충돌, 수집 실패, k6 실패, 종료 재고 불변식 실패 중 하나라도 있으면 `ValidMeasurement=true` summary를 만들지 않는다. 개발 중 Docker config 접근 실패와 process exit/snapshot parser false negative도 성공으로 우회하지 않고 실패 결과로 남긴 뒤 새 run에서 수정 사항을 검증했다.

### 목표 sampling interval과 실제 interval을 함께 기록하기

DB CLI 표본 시간이 추가되므로 단순히 매 조회 후 1초를 기다리면 실제 간격이 1.5~2초로 늘어났다. 고정 cadence로 바꾸고 summary에 실제 min·avg·max를 추가했다. 최종 hot-seat smoke의 목표는 1,000ms였고 실제 평균은 1,009.36ms, 최대는 1,692ms였다. polling이 놓칠 수 있는 짧은 spike를 숨기지 않고 수치 해석의 한계로 남긴다.

최종 로컬 smoke에서 distributed 5 RPS·5초는 Hikari active peak 1·pending 0·lock wait delta 0이었다. hot-seat 100 RPS·10초는 1,001회 중 성공 1·예상 409 1,000·예상 밖 오류 0, p95 39.68ms, Hikari active peak 2·pending 0, row lock wait +77회·+142ms, deadlock 0과 종료 불변식을 기록했다. 이는 서로 다른 조건의 collector 기능 확인값이며 성능 전후 비교가 아니다. 상세 내용은 [고경합 부하의 경량 Hikari·MariaDB 관측 경계](lightweight-contention-observability.md)에 기록한다.

### 링크

- [Backend Issue #51](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/51)

## Issue #53 — 무경합 성공 처리량과 정상 경합 거부 비용의 변곡점 분리

### 콘솔 처리율 대신 부하 구간의 완료·dropped iteration 보기

기존 k6 콘솔 rate는 setup 시간이 포함돼 목표 100 RPS·10초에 1,001 iterations를 완료한 smoke도 약 77 RPS로 보였다. `handleSummary()`에서 예약 전용 p95와 iterations·dropped iterations를 구조화하고, 완료 iterations를 10초로 나눈 처리율과 요청 도달률을 계산했다. JWT·cookie·setup data는 결과에서 제외했다.

### fixture 생성과 측정 시간창 분리

run마다 2,000석을 삽입하는 약 2초 이상의 준비 transaction이 Hikari·DB 표본에 섞이면 실제 예약 부하의 peak로 오해할 수 있었다. fixture를 첫 표본 전에 준비하고 준비 시간과 제외 여부를 summary에 별도 기록했다. k6 setup의 조회와 teardown snapshot은 시간창에 남는다는 한계도 함께 기록했다.

### 반복 측정에서 처음 드러난 공유 행 직렬화 후보

VU 상한 또는 동적 할당 지연이 서버 병목과 섞인 탐색 batch 둘은 폐기하고, `preAllocatedVUs=maxVUs=200`인 최종 batch만 공개 근거로 사용했다. warmup 제외 단계별 3회에서 distributed 50 RPS는 완료/설정초 50.1·도달률 100%·p95 37.42ms·Hikari pending 0이었다. 100 RPS는 완료/설정초 95.0·도달률 94.91%·p95 2,523.26ms, Hikari active 10·pending 189, row lock wait 949회·93,346ms가 중앙값으로 반복됐다. 예상 밖 실패와 deadlock은 0이었다. 완료/설정초는 graceful stop을 포함한 wall-clock TPS가 아니다.

distributed는 서로 다른 좌석을 선택하지만 모든 예약 transaction이 동일 `concert_time.seat_amount` 행을 조건부 감소시킨다. 이 공유 행이 connection을 점유한 transaction을 직렬화한다는 강한 가설이 생겼다. 아직 원인 격리 전이므로 개선 결론으로 단정하지 않고 다음 Issue의 A/B 검증 대상으로 남긴다.

hot-section·hot-seat은 100 RPS에서 dropped 없이 예상 409를 처리했지만 150 RPS에서는 Hikari active 10·pending 최대 189와 row lock wait 중앙값 1,400회 이상이 반복됐다. 이 완료율은 성공 예약 TPS가 아니라 충돌 판정과 409 거부 처리율이므로 distributed와 분리해 해석한다.

상세 조건과 전체 중앙값·범위는 [가상 좌석 고경합의 단계별 성능 기준선](staged-contention-performance-baseline.md)에 기록한다.

### 링크

- [Backend Issue #53](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/53)

## Issue #55 — 공유 회차 행 가설을 SQL별 시간으로 반증

### 코드상 공유 자원과 실제 병목 statement는 다를 수 있다

Issue #53에서 서로 다른 좌석 예약도 100 RPS부터 Hikari pending과 DB lock time이 급증했다. 모든 transaction이 같은 `concert_time.seat_amount`를 갱신하므로 공유 회차 행을 우선 의심했지만, 코드 구조만으로 병목을 확정하지 않았다. 특히 회차 bulk update의 자동 flush 때문에 Java repository 메서드 시간에는 선행 변경 flush까지 섞일 수 있었다.

### normalized SQL digest로 측정 경계를 한 단계 내리기

기본 Compose는 유지하고 진단 override에서만 MariaDB Performance Schema를 켰다. fixture 생성 뒤 digest를 초기화하고 좌석 `SELECT ... FOR UPDATE`와 회차 `UPDATE`의 횟수·누적·평균·최대 시간을 별도로 수집했다. SQL 원문과 파라미터는 summary에 저장하지 않았고 두 statement 횟수가 예약 성공 수와 다르면 측정을 무효화했다.

### 가설을 고집하지 않고 다음 원인 후보로 이동하기

100 RPS 3회 중앙값에서 좌석 잠금 SELECT는 평균 146.44ms·누적 105,292ms였고 회차 UPDATE는 평균 0.354ms·누적 255ms였다. 좌석 statement 평균이 410.63배 길었으며 DB row lock time은 +100,479ms였다. 회차 단일 행이 주병목이라는 가설은 반증됐다.

실제 `seat` schema에는 `concert_time_id` 단일 FK index만 있고 `seat_number`를 포함한 복합 index가 없다. 실행계획은 한 좌석을 찾을 때 같은 회차 약 2,000행을 검사했다. 대기시간이 좌석 잠금 SELECT에 집중된 사실은 확인했지만 복합 index 적용 후 고경합 개선은 아직 측정하지 않았다. 다음 Issue에서 동일 진단 조건의 index 전후 A/B로 검증한다.

상세한 판단 과정과 전체 범위는 [회차 잔여 좌석 단일 행 병목 가설의 SQL별 진단](concert-time-row-bottleneck-diagnosis.md)에 기록한다.

### 링크

- [Backend Issue #55](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/55)

## Issue #57 — 넓은 좌석 잠금 조회를 복합 index A/B로 검증

### 실행계획 개선을 곧바로 처리량 개선으로 단정하지 않기

Issue #55에서 좌석 잠금 SELECT의 대기와 약 2,000행 탐색을 확인했지만 `EXPLAIN rows=1`만으로 HTTP p95와 connection 대기가 개선된다고 단정하지 않았다. 애플리케이션과 운영 schema는 유지하고 진단용 MariaDB에서 current/composite index를 run마다 전환해 같은 50·100 RPS 조건으로 비교했다.

### 순서 효과·정합성·관측 누락을 A/B gate에 포함하기

각 variant를 한 번씩 warmup하고 반복 1·3과 반복 2의 적용 순서를 뒤집었다. 최초 완료 batch는 run마다 새 2,000석을 누적해 물리 table 크기가 달라지는 교란이 있어 폐기했다. 최종 runner는 이전 load-test data를 삭제하고 물리 좌석 0→2,000행을 강제한 뒤 optimizer 통계를 갱신한다. 일반 좌석이 있으면 삭제하지 않고 중단한다. 종료 시 좌석·예약·Booking·Payment·잔여 수량과 예상 밖 응답·deadlock을 교차 검증한다. Performance Schema의 이번 관측 누락을 계기로 최소 coverage 95%와 digest lost·NULL digest health를 추가했으며 최종 batch 최소 coverage는 99%였다.

### 100 RPS 변곡의 원인을 수치로 확인하기

복합 index는 잠금 조회의 예상 행을 2,000에서 1로 바꿨다. 최종 `i57-fixed-02`의 100 RPS 3회 중앙값에서 완료/설정초는 83.1→100.1, p95는 3,064.39→143.41ms, dropped는 169→0, Hikari pending peak는 189→0, DB lock time은 91,935→4,565ms, 좌석 잠금 SELECT 평균은 114.168→0.525ms로 바뀌었다. 본 측정 12회 모두 예상 밖 실패·deadlock 0과 재고 불변식을 유지했다.

### 병목이 사라진 것이 아니라 이동할 수 있음을 보기

좌석 조회가 빨라지자 100 RPS 회차 감소 UPDATE 평균은 0.381→5.287ms로 늘었고 composite에서도 row lock wait 중앙값 412회가 남았다. 더 많은 transaction이 같은 회차 counter에 빨리 도달한 결과로 해석할 수 있지만 composite 조건은 여전히 목표 100 RPS·pending 0을 유지했다. 따라서 지금 counter 분리·대기열·Redis lock을 도입하지 않고, 복합 unique index를 Entity·기존 DB migration 경계에 영구 반영한 뒤 더 높은 부하나 burst에서 다음 변곡을 다시 측정한다.

상세 설계·전체 범위·폐기 batch와 한계는 [좌석 잠금 복합 unique index의 고경합 A/B 검증](seat-composite-index-high-contention-ab.md)에 기록한다.

### 링크

- [Backend Issue #57](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/57)

## Issue #59 — 측정된 index를 신규 Entity schema의 불변식으로 승격

### 성능 index와 도메인 unique 제약을 같은 구조로 표현하기

Issue #57은 `(concert_time_id, seat_number)` index가 좌석 잠금 조회를 2,000행 탐색에서 1행 식별로 바꾸고 100 RPS p95와 connection 대기를 낮춘다는 사실을 확인했다. 좌석 번호는 공연 전체에서 유일하지 않고 회차 안에서만 유일하므로 같은 column 조합은 조회 최적화뿐 아니라 좌석 식별 불변식이기도 하다.

`Seat` Entity의 `@Table`에 이름이 고정된 복합 unique 제약을 선언했다. 작은 fixture에서 같은 회차 `A1` 재삽입은 `23000/1062`로 거부되고 기존 row 1개가 유지됐으며, 다른 회차의 `A1`은 허용됐다. 기존 24석 fixture의 `SHOW INDEX`는 `concert_time_id, seat_number` 순서와 unique 속성을, 잠금 SQL `EXPLAIN`은 `const/rows=1`을 확인했다. canonical 복수 좌석 예약을 포함한 대상 30개·전체 Backend 103개 test도 통과했다. 2,000석 A/B는 이미 성능 효과를 측정했으므로 같은 조건을 불필요하게 반복하지 않았다.

첫 A/B 종료 구현은 composite 복원 뒤 fixture를 정리했다. Reviewer 검토에서 무인덱스 단계에 중복 fixture가 생기면 복원이 먼저 실패하고, 이후 cleanup이 성공해도 index 생성을 재시도하지 않는 경로가 확인됐다. 종료 절차를 load-test fixture cleanup 후 composite 복원 순서의 공용 함수로 옮겼고, 실제 fake 상태에서 복원 선행 실패→중복 제거→index 생성까지 검증했다. 수정 후 PowerShell 검증은 총 153개 assertion이다.

### Entity schema 적용과 기존 DB migration을 구분하기

Entity annotation은 Hibernate가 새로 만드는 local·test schema에는 제약을 생성하지만 이미 존재하는 DB의 versioned 변경 이력은 아니다. 따라서 이번 적용을 운영 migration 완료로 과장하지 않는다. 10개 Entity 전체 Flyway baseline, 실제 기존 schema diff와 중복 데이터 정리 정책은 ADR-0001의 재검토 조건이 충족될 때 별도 Issue로 다룬다.

### 링크

- [Backend Issue #59](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/59)
