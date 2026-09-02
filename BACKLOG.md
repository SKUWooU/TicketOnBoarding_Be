# 개선 BACKLOG

BACKLOG는 확정 구현 목록이 아니라 조사와 재현이 필요한 후보입니다. 각 Phase는 선행 Issue의 근거를 확인한 뒤 작은 Issue로 나눕니다.

| Phase | 대상 | 문제 가설 또는 목적 | 필요한 근거 | 지금 제외할 것 | 상태 |
| --- | --- | --- | --- | --- | --- |
| 0 | BE | 협업 절차와 근거 기록 기준이 없다 | Template·workflow·기준선 문서 검토 | 애플리케이션 코드 변경 | 완료 ([#1](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/1), [PR #2](https://github.com/SKUWooU/TicketOnBoarding_Be/pull/2)) |
| 0-AUTO | BE | Reviewer 결과 전달과 merge 승인이 Issue마다 반복된다 | 최신 review HEAD, Backend CI, squash merge gate | 배포·외부 연동 자동 승인 | 완료 ([#23](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/23), [#25](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/25), [#27](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/27), [#29](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/29)) |
| 0-RUN | BE | 실제 Backend·DB 로컬 실행 경로가 없다 | Compose health, `bootRun`, HTTP·SQL smoke | Backend image·운영 배포·부하 측정 | 완료 ([#19](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/19)) |
| 0-FE | FE | 독립 저장소의 작업 기록 기준이 없다 | BE 기준과 중복·차이를 조사한 FE Issue | BE Issue 한 개로 FE까지 수정 | 후보 |
| 1 | BE | 예약 경합을 반복 재현할 기반이 없다 | Java 21, MariaDB Testcontainers, 외부 연동 비활성화, 명시적 fixture | 동시성 로직 개선 | 완료 ([#3](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/3), [PR #4](https://github.com/SKUWooU/TicketOnBoarding_Be/pull/4)) |
| 2 | BE | 동일 좌석 잠금과 서로 다른 좌석 집계·복수 좌석 rollback이 검증되지 않았다 | 동시 요청 성공 수, 예약 row, 좌석 상태, 잔여 수량 | 잠금 방식 선제 교체 | 기준선 완료 ([#3](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/3), [PR #4](https://github.com/SKUWooU/TicketOnBoarding_Be/pull/4)) |
| 3 | BE | 공통 회차 잔여 좌석 갱신 유실과 checked exception 부분 commit | 결정적 경합·중간 실패 fixture의 개선 후 불변식 | Redis·분산 락 | 완료 ([#5](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/5), [PR #6](https://github.com/SKUWooU/TicketOnBoarding_Be/pull/6)) |
| 4 | BE | 복수 좌석의 입력 순서가 달라 deadlock이 발생할 수 있다 | 반대 순서 fixture, index 전후 query plan·첫 lock·DB 예외와 rollback | 무제한 재시도 | lock ordering·migration 중복 기준선 완료 ([#9](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/9), [#11](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/11), [#15](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/15), [#17](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/17)); Entity 신규 schema 제약 구현·로컬 검증 완료 ([#59](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/59)), 기존 DB migration은 전체 schema baseline 전 보류 |
| 5 | BE/FE | 예약과 결제 완료가 결합되고 상태 전이가 불명확하다 | 현재 흐름 재현, mock 결제, 허용·거부 전이 테스트 | 실제 결제 실행 | 서버 미검증 기준선 ([#37](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/37)), 예약 상태 enum·전이 ([#41](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/41)), 서버 가상 가격·Payment/mock 검증 경계 ([#43](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/43)), 서버 소유 Checkout·예약 검증 결합 ([#67](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/67)), 동일 활성 hold Checkout 단일화 ([#70](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/70)), 부분 중첩 기준선·차단 ([#73](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/73), [#76](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/76)), 결제 검증 중 만료 경합 기준선·bounded claim ([#79](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/79), [#82](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/82)) 완료; 실제 PG 재조회·PaymentAttempt 원장·UNKNOWN reconciliation, Checkout 취소, Frontend 전환·legacy 제거 후속 |
| 5-HOLD | BE/FE | 좌석 선택과 검증된 예약 사이에 서버 임시 점유·소유자·만료가 없어 같은 가용 좌석을 여러 사용자가 선택할 수 있다 | 반복 snapshot, 독립 사용자 동시 경쟁, 점유 소유권·만료·회수 fixture | Redis·분산 lock·scheduler 선제 도입 | 부재 기준선·Backend DB 점유 구현 완료 ([#61](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/61), [#63](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/63)); 점유 고경합 150·200 RPS 검증 범위에서 pending·dropped·예상 밖 실패·deadlock 0 ([#65](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/65)); Frontend 연동 후속 |
| 6 | BE/FE | 예약·결제·취소 중복 요청의 결과가 안정적이지 않다 | 중복 key, 응답 유실, payload 충돌 fixture | 브로커 선제 도입 | 취소 개선·예약 중복 기준선 완료, Backend 예약 멱등 결과 구현·로컬 검증 완료 ([#21](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/21), [#31](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/31), [#33](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/33), [#35](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/35), [#37](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/37), [#39](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/39)); Frontend key 전달·운영 migration 후속 |
| 7 | BE | 고경합 병목 위치가 측정되지 않았다 | 2,000석 fixture·k6·Actuator 실행 기반 구성 후 단계별 TPS·p95·오류율, lock wait, Hikari 시계열 측정 | 운영 SLA 주장 | 측정·경합 HTTP 계약·관측 경계 완료 ([#47](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/47), [#49](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/49), [#51](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/51)); 50→100 RPS 변곡·SQL 병목 격리·복합 unique index A/B에서 p95 3,064.39→143.41ms·pending 189→0 확인 ([#53](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/53), [#55](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/55), [#57](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/57)); Entity 신규 schema 구현·로컬 검증 완료 ([#59](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/59)) |
| 8 | BE | 비동기·분산 구조의 필요성이 확인되지 않았다 | burst 수용 한계, 이벤트 유실, 독립 재시도 요구 | 기술 시연 목적의 도입 | 보류 |

## Phase 1–2 첫 기술 Issue 후보

`[TEST] 가상 좌석 fixture로 예매 트랜잭션과 경합 정합성 기준선 검증`

- 공연 1개, 회차 1개와 명시적 좌석 fixture
- 동일 좌석 동시 요청
- 서로 다른 좌석 동시 요청
- 성공 요청, 예약 row, 좌석 상태와 잔여 좌석 수 교차 검증
- 외부 KOPIS·SMS·OAuth·결제 호출 비활성화
- 개선 구현은 재현 결과가 나온 다음 Issue로 분리

## 기술 도입 조건

- 대기열: 순간 요청량을 DB가 직접 수용하지 못하거나 공정한 진입 순서가 필요하다는 근거
- outbox: DB commit과 이벤트 발행 사이의 유실 재현 및 독립 후속 처리 요구
- 메시지 브로커: 독립 consumer, 재시도, replay와 장애 격리가 실제로 필요
- 분산 락: 다중 인스턴스에서 DB 제약·트랜잭션만으로 지킬 수 없는 공유 자원 경합이 확인됨
