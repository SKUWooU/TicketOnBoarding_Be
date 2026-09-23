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
| 5 | BE/FE | 예약과 결제 완료가 결합되고 상태 전이가 불명확하다 | 현재 흐름 재현, mock 결제, 허용·거부 전이 테스트 | 실제 결제 실행 | 서버 미검증 기준선 ([#37](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/37)), 예약 상태 enum·전이 ([#41](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/41)), 서버 가상 가격·Payment/mock 검증 경계 ([#43](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/43)), 서버 소유 Checkout·예약 검증 결합 ([#67](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/67)), 동일 활성 hold Checkout 단일화 ([#70](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/70)), 부분 중첩 기준선·차단 ([#73](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/73), [#76](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/76)), 결제 검증 중 만료 경합 기준선·bounded claim ([#79](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/79), [#82](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/82)), UNKNOWN 재조회·복구 부재 기준선·수동 단건 수렴 경계 ([#94](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/94), [#97](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/97)), hold 해제·검증 진입 경합 기준선 ([#100](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/100), [PR #101](https://github.com/SKUWooU/TicketOnBoarding_Be/pull/101)), READY Checkout 취소·멱등·검증 진입 경합 ([#103](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/103), [PR #104](https://github.com/SKUWooU/TicketOnBoarding_Be/pull/104)) 완료; 검증 중·UNKNOWN 취소 보상, 실제 provider 조회·환불 adapter, PaymentAttempt 원장, 관리자 권한·진입점, Frontend 전환·legacy 제거 후속 |
| 5-HOLD | BE/FE | 좌석 선택과 검증된 예약 사이에 서버 임시 점유·소유자·만료가 없어 같은 가용 좌석을 여러 사용자가 선택할 수 있다 | 반복 snapshot, 독립 사용자 동시 경쟁, 점유 소유권·만료·회수 fixture | Redis·분산 lock·scheduler 선제 도입 | 부재 기준선·Backend DB 점유 구현 완료 ([#61](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/61), [#63](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/63)); 점유 고경합 150·200 RPS 검증 범위에서 pending·dropped·예상 밖 실패·deadlock 0 ([#65](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/65)); Frontend 연동 후속 |
| 6 | BE/FE | 예약·결제·취소 중복 요청의 결과가 안정적이지 않다 | 중복 key, 응답 유실, payload 충돌 fixture | 브로커 선제 도입 | 취소 개선·예약 중복 기준선 완료, Backend 예약 멱등 결과 구현·로컬 검증 완료 ([#21](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/21), [#31](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/31), [#33](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/33), [#35](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/35), [#37](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/37), [#39](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/39)); Frontend key 전달·운영 migration 후속 |
| 7 | BE | 고경합 병목 위치가 측정되지 않았다 | 2,000석 fixture·k6·Actuator 실행 기반 구성 후 단계별 TPS·p95·오류율, lock wait, Hikari 시계열 측정 | 운영 SLA 주장 | 측정·경합 HTTP 계약·관측 경계 완료 ([#47](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/47), [#49](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/49), [#51](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/51)); 50→100 RPS 변곡·SQL 병목 격리·복합 unique index A/B에서 p95 3,064.39→143.41ms·pending 189→0 확인 ([#53](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/53), [#55](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/55), [#57](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/57)); Entity 신규 schema 구현·로컬 검증 완료 ([#59](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/59)); 서버 hold 판정·commit transition과 k6 결과 교차 계측 완료 ([#91](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/91)); hold/release churn으로 정상 409와 Hikari pending 포화 신호 분리 ([#110](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/110)); pool 10/16/24 단일 matrix에서 24의 방향성은 확인했으나 반복 전 local 기본 10 유지 ([#112](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/112)) |
| 8 | BE | 비동기·분산 구조의 필요성이 확인되지 않았다 | burst 수용 한계, 이벤트 유실, 독립 재시도 요구 | 기술 시연 목적의 도입 | 보류 |

Phase 7의 Hikari pool은 local 2,000석 distributed churn 200 RPS 반복 matrix에서 10·16·24를 warm-up 분리 후 각 3회 비교했다. 24는 10 대비 완료율 중앙값 89.71%→99.15%, hold/cycle p95 1,109.21/2,048.2ms→431.60/886ms, pending 188→114를 보였다. 이 근거로 local profile 기본값만 24로 조정했으며, 운영 pool·SLA·다중 인스턴스 최적화는 별도 재현 전 보류한다 ([#114](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/114)).

pool 24의 system CPU 포화는 JVM·MariaDB 어느 한쪽으로 단정하지 않는다. opt-in Docker stats 진단에서 process/system CPU 49.27/99.76%, MariaDB CPU 77.27%, GC 0.084초/16회를 확인했지만, collector의 평균 표본 간격이 5,083ms로 늘어났다. 따라서 container CPU는 보조 진단값으로만 유지하고, 독립 host telemetry 또는 반복 관측 전 Grafana·GC tuning·분산 인프라는 보류한다 ([#116](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/116)).

loadtest 보조 API의 `runId` 입력 오류는 400 JSON으로 고정하고, 실패가 fixture 데이터를 만들지 않는 Testcontainers 회귀를 추가했다. 운영 API 오류 포맷 통일이나 외부 연동 정책으로 확장하지 않는다 ([#118](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/118)).

Checkout 취소·결제 검증은 commit된 상태 전이만 집계하는 Micrometer 계약을 추가했다. 이는 대시보드·운영 성능 측정이 아니라 다음 고경합 fixture에서 HTTP 결과와 도메인 수렴을 교차할 최소 근거다 ([#120](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/120)).

Checkout distributed 100 RPS에서 발생한 deadlock은 최신 InnoDB 진단으로 `reservation_checkout_seat_assignment`의 `(seat_id, active_until)` unique index 끝 gap insert-intention 순환 후보까지 좁혔다. 다음 변경은 해당 제약과 active-assignment 조회의 필요 범위를 분리해 같은 local fixture·rollback 불변식으로 비교한 뒤 결정하며, pool·재시도·일반 좌석 lock 순서는 이 근거만으로 조정하지 않는다 ([#126](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/126)).

active assignment는 history seat FK와 nullable active seat unique key를 분리하고, 이미 Seat lock을 보유한 Checkout 준비에는 non-locking 조회를 적용했다. Testcontainers barrier와 local mock fixture 모두에서 deadlock은 관찰되지 않았고 도메인 수렴을 유지했다. 같은 2,000석·100 RPS 조건의 warm-up 제외 3회 repeat에서도 deadlock·예상 밖 실패 0과 invariant 수렴을 확인했다. 다만 p95 5,165.8–5,573.0ms·dropped 374–410·pending 173–175의 단일 host 포화 신호는 남아 있다. 이를 pool·재시도·대기열 도입 근거로 확대하지 않으며, 다음 후보는 부하 생성 한계와 서버·DB 자원 포화를 구분하는 관측 계약이다 ([#128](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/128), [#132](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/132)).

Checkout 50·75·100 RPS attribution baseline에서 50 RPS는 VU 상한 미도달에도 pool pending·DB lock 신호를 함께 보였고, 75·100 RPS는 VU 200 상한 도달로 원인 분리가 제한됐다. 따라서 dropped iteration을 k6 단독 한계로 단정할 수 없지만, pool·DB 중 하나의 단독 root cause나 서버 최대 처리량도 주장하지 않는다. 다음 진단은 pool 대기 시간과 DB query/lock 대기를 더 직접적으로 상관시키는 최소 계약이며, pool size·retry·queue/broker·JVM tuning은 보류한다 ([#134](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/134)).

진단 전용 Performance Schema와 Hikari acquire timer를 같은 Checkout run에 결합해 acquire 평균 대기 증가와 statement lock 누적을 확인했다. 다만 concurrent statement time은 wall-clock 시간이 아니고 VU cap 이후 request 도달량도 달라 단독 원인을 확정하지 않는다. pool·retry·queue/broker·JVM tuning은 계속 보류하며, digest를 request 도달량으로 정규화할 수 있는 경우에만 후속 진단을 검토한다 ([#136](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/136)).

Checkout digest는 `performance_schema` 관측 statement를 제외하고 완료 Checkout iteration당으로 정규화했다. 50/75/100 RPS의 statement 수는 완료 iteration당 약 47.6건으로 안정적이었고, 75·100 RPS의 dropped 증가를 local VU cap과 함께 기록했다. 한 iteration은 복수 HTTP 호출을 포함할 수 있으므로 누적 SQL 시간과 HTTP request wall-clock을 같게 해석하지 않으며, 이 근거만으로 pool·retry·queue/broker·JVM tuning을 도입하지 않는다 ([#138](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/138)).

## Phase 1–2 첫 기술 Issue 후보

`[TEST] 가상 좌석 fixture로 예매 트랜잭션과 경합 정합성 기준선 검증`

- 공연 1개, 회차 1개와 명시적 좌석 fixture
- 동일 좌석 동시 요청
- 서로 다른 좌석 동시 요청
- 성공 요청, 예약 row, 좌석 상태와 잔여 좌석 수 교차 검증
- 외부 KOPIS·SMS·OAuth·결제 호출 비활성화
- 개선 구현은 재현 결과가 나온 다음 Issue로 분리

## Phase 9 - 서버 소유 가상 좌석 레이아웃

- Backend Issue #85: 24석·2,000석 fixture의 구역·행·순서 계약과 구역 요약/상세 API
- Backend Issue #88: loadtest fixture의 공연 상세·장소 계약과 lazy review 직렬화 복구 완료 ([#88](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/88), [PR #89](https://github.com/SKUWooU/TicketOnBoarding_Be/pull/89))
- Backend Issue #144: loadtest 토큰과 `SiteUser` fixture를 결합해 실제 `/auth/valid` 계약을 검증 완료 ([#144](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/144), [PR #145](https://github.com/SKUWooU/TicketOnBoarding_Be/pull/145)). Frontend browser E2E의 인증 route mock 제거는 별도 Issue로 검토
- 후속: Frontend가 상세 envelope mock 없이 local E2E를 재검증
- 보류: 실제 공연장 좌석도, 운영 migration, WebSocket·대기열·브로커, 근거 없는 virtualization

## 기술 도입 조건

- 대기열: 순간 요청량을 DB가 직접 수용하지 못하거나 공정한 진입 순서가 필요하다는 근거
- outbox: DB commit과 이벤트 발행 사이의 유실 재현 및 독립 후속 처리 요구
- 메시지 브로커: 독립 consumer, 재시도, replay와 장애 격리가 실제로 필요
- 분산 락: 다중 인스턴스에서 DB 제약·트랜잭션만으로 지킬 수 없는 공유 자원 경합이 확인됨
