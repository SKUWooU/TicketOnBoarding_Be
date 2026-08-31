# 작업 진행 기록

이 문서는 완료·진행 중인 Issue와 검증 상태를 기록하는 단일 진행 상태 원본입니다. 상세 문제와 결정은 연결된 Issue, PR, EVIDENCE_MAP과 ADR에서 확인합니다.

## 저장소 기준선

| 구분 | 저장소 | 기준 Branch | 조사 기준 commit |
| --- | --- | --- | --- |
| Backend | [TicketOnBoarding_Be](https://github.com/SKUWooU/TicketOnBoarding_Be) | `main` | `b04c39a1193825435ef1e151dfd39be6eaddf108` |
| Frontend | [TicketOnBoarding_Fe](https://github.com/SKUWooU/TicketOnBoarding_Fe) | `main` | `1f9678be7a3a66ec610c6ef4ea335e9d6f5cbafd` |

두 저장소는 독립된 Issue와 PR을 사용합니다. 교차 변경은 각 작업의 링크를 양쪽 Issue 또는 PR에 남깁니다.

## 진행 중

### Backend Issue #65 — 좌석 임시 점유 API의 고경합 부하 기준선

- Issue: [#65](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/65)
- Branch: `test/65-seat-hold-contention-baseline`
- PR: [#66](https://github.com/SKUWooU/TicketOnBoarding_Be/pull/66)
- 상태: Reviewer Blocking 수정·재검증 완료, 재검토 대기
- 계획 승인: 완료
- 확인 중인 근거: 기존 2,000석 fixture·JWT 발급·k6 constant-arrival-rate·Hikari/MariaDB 수집기는 예약 확정 API에 결합돼 있다. 점유 API는 Payment·Booking·회차 재고를 변경하지 않으며 TTL 동안 동일 좌석의 성공 응답 수와 실제 `HELD` row 수가 다를 수 있어 별도 결과 계약이 필요하다.
- 구현: `loadtest` profile의 동일 fixture hold reset·상태 snapshot, 점유 전용 k6·metric parser·단계별 반복 runner·중단 gate를 추가했다. hot 시나리오는 iteration 기준 500개 사용자를 순환해 같은 소유자 재요청과 타인 409가 구분되도록 했다.
- 측정: `issue65-base-01` warmup 1회 제외 27회 유효. distributed 50·100·150 RPS p95 중앙값 18.10·28.22·33.68ms, hot-section 100·150·200 RPS 20.48·25.44·38.07ms, hot-seat 100·150·200 RPS 24.10·32.03·29.37ms. 전 구간 목표 도달률 100%, dropped·예상 밖 실패·deadlock·Hikari pending 0, 상태 불변식 충족.
- 관측: hot-seat DB lock waits/time 중앙값은 100 RPS 6회·25ms, 150 RPS 69회·516ms, 200 RPS 142회·722ms로 증가했지만 p95·connection 포화 변곡으로 이어지지 않았다.
- 검증: Backend 전체 120 tests(실패·오류·skip 0), 기존 153개와 점유 전용 39개를 합한 PowerShell 192개 assertion, 예약·점유 k6 inspect, `git diff --check`, Compose config 통과. 최종 batch 28회 중 warmup 제외 27회 모두 유효하고 생략 0회다.
- 근거: [좌석 임시 점유 API의 고경합 부하 기준선](docs/project-improvement/seat-hold-contention-performance-baseline.md)
- 범위 후보: loadtest profile의 점유 fixture reset·snapshot, 점유 전용 k6와 단계별 runner, 정상 409/예상 밖 실패 분리, TPS·p95·Hikari·DB lock·상태 불변식 및 근거 문서
- 제외: 실제 KOPIS·PG·SMS·OAuth, Frontend·README, 운영 배포·SLA 주장, Redis·분산 lock·scheduler·대기열·브로커 구현

## 완료

### Backend Issue #63 — DB 기반 좌석 임시 점유·만료 상태 전이

- Issue: [#63](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/63)
- Branch: `feat/63-seat-hold-expiration`
- PR: [#64](https://github.com/SKUWooU/TicketOnBoarding_Be/pull/64)
- squash commit: `b04c39a1193825435ef1e151dfd39be6eaddf108`
- 상태: 완료
- 계획 승인: 완료
- 구현: `Seat` row에 nullable 점유자·만료 시각을 추가하고 `AVAILABLE/HELD/RESERVED` 파생 상태, 기본 5분 TTL과 `Clock`, 점유·해제 API, 좌석 조회 호환 필드, 자기 점유 예약·타인 점유 409를 연결했다. 동일 사용자 재요청은 TTL을 연장하지 않고 만료는 쓰기 시점에 lazy 회수한다.
- Reviewer: 최종 HEAD `57c60a42ee2ab75672f249cd3fafe755a1dd9130`, Blocking 없음, `MERGE_READY: YES`
- 검증: MariaDB Testcontainers에서 동일 좌석 2사용자 동시 점유 성공 1·conflict 1, 만료 직전/정확한 만료 경계, 다좌석 취득·해제 rollback, 자기/타인 점유 예약과 Payment·Booking rollback, 조회·HTTP 400/401/409 계약을 확인했다. Backend 전체 119 tests(실패·오류·skip 0), Backend CI, `git diff --check`, Compose config 통과.
- 근거: [DB 기반 좌석 임시 점유·만료 상태 전이](docs/project-improvement/seat-hold-expiration-state-transition.md)
- 범위: Backend `Seat`·점유 service/API·좌석 응답·예약 transaction, `Clock`·TTL, MariaDB 동시성·시간 경계·rollback fixture와 근거 문서
- 제외: 실제 KOPIS·PG·SMS, Frontend·README, Redis·분산 lock·scheduler·대기열·브로커, 운영 DB 자동 migration, 성능 수치 측정

### Backend Issue #61 — 결제 전 좌석 선택의 임시 점유·만료 기준선

- Issue: [#61](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/61)
- Branch: `test/61-seat-hold-expiration-baseline`
- PR: [#62](https://github.com/SKUWooU/TicketOnBoarding_Be/pull/62)
- squash commit: `825e06e1dc907547102b3162fedc4e9be61a38ff`
- 상태: 완료
- 계획 승인: 완료
- 확인된 근거: 좌석 조회는 `reserved` snapshot만 반환하고 상태를 바꾸지 않으며, Frontend 선택도 로컬 상태에만 남는다. 따라서 서로 다른 사용자가 같은 `A1`을 동시에 선택 가능하고 검증된 예약 transaction에 먼저 진입한 한 요청만 확정된다.
- 구현: 반복 좌석 조회가 점유를 만들지 않는 fixture와, 독립 사용자·결제 ID·멱등 key가 실제 예약 transaction의 Booking flush 직후 같은 좌석을 경쟁하는 mock 결제 fixture를 추가. 성공 결과의 사용자·결제 ID를 보존해 저장 소유권과 직접 대조
- Reviewer: 최종 HEAD `de6a19902398fb403794beda08070f4379ca96d8`, Blocking 없음, `MERGE_READY: YES`
- 검증: 대상 MariaDB Testcontainers 21개 invocation·Backend 전체 105개 test(실패·오류·skip 0), PowerShell 153개 assertion, k6 inspect, Compose config 통과. 경쟁 결과 성공 1·`SeatReservationConflictException` 1, Payment·Booking·Reservation·reserved seat 각 1, remaining 23
- 범위: 기존 24석 fixture 중 `A1` 한 좌석, 서버 snapshot·검증된 예약 경계, mock 결제, 정합성 기준선과 후속 설계 조건
- 제외: `HELD` 구현, 만료 scheduler, Redis·분산 lock·대기열, Frontend·README, 실제 KOPIS·PG·SMS, 성능 수치 주장

### Backend Issue #59 — 좌석 복합 unique 제약의 Entity schema 반영

- Issue: [#59](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/59)
- PR: [#60](https://github.com/SKUWooU/TicketOnBoarding_Be/pull/60)
- squash commit: `14ccb7780e6405e517e551679820615e7881aae3`
- 상태: 완료
- 계획 승인: 완료
- 구현: `Seat` 신규 schema에 `(concert_time_id, seat_number)` unique 제약을 선언하고 A/B runner 종료 시 fixture 정리 후 composite schema를 복원
- 검증: MariaDB 대상 30개·Backend 전체 103개 test, PowerShell 153개 assertion, Backend CI 통과
- Reviewer: 최종 HEAD `12e3e00970c9aed19b5e985c41029b669ae38124`, Blocking 없음, `MERGE_READY: YES`
- 한계: 기존 DB versioned migration과 전체 Flyway baseline은 별도 Issue로 보류

### Backend Issue #57 — 좌석 잠금 복합 unique index의 고경합 A/B 검증

- Issue: [#57](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/57)
- PR: [#58](https://github.com/SKUWooU/TicketOnBoarding_Be/pull/58)
- squash commit: `e4917b4ec9b547de264048f772756a72b04ea25f`
- 상태: 완료
- 계획 승인: 완료
- 측정: 최종 `i57-fixed-02` 본 측정 12/12회 정합성 충족·예상 밖 실패/deadlock 0; 100 RPS current→composite에서 완료/설정초 83.1→100.1, p95 3,064.39→143.41ms, dropped 169→0, Hikari pending 189→0, DB lock time 91,935→4,565ms, 좌석 잠금 평균 114.168→0.525ms
- 판정: 고정 2,000행 잠금 조회를 1행 식별로 전환해 병목 원인과 복합 unique index 효과 확인; 현재 100 RPS가 안정적이므로 분산 구조 도입 보류
- 검증: PowerShell 149개 assertion, k6 inspect, Compose config, Backend 전체 102개 test, Backend CI 통과
- Reviewer: 최신 HEAD `f0fd9b03af8dd00f6363058371472c5ab5e79db1`, Blocking 없음, `MERGE_READY: YES`
- 범위: 로컬 `loadtest` profile·MariaDB 10.11.8·2,000석 fixture와 반복 A/B
- 제외: 운영 DB DDL, 전체 Flyway baseline, 애플리케이션 Entity·Frontend·README, 실제 외부 연동과 분산 기술

### Backend Issue #55 — 회차 잔여 좌석 단일 행 갱신 병목 원인 격리

- Issue: [#55](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/55)
- Branch: `test/55-concert-time-row-bottleneck`
- PR: [#56](https://github.com/SKUWooU/TicketOnBoarding_Be/pull/56)
- squash commit: `294136aead691800578dfe21375a58f735c4b599`
- 상태: 완료
- 계획 승인: 완료
- 확인된 사실: 예약은 개별 좌석을 비관적 잠금한 뒤 모든 성공 transaction이 동일한 `concert_time.seat_amount` 행을 조건부 감소시키며, 해당 bulk update의 자동 flush로 Java 메서드 시간에는 선행 변경 flush가 섞임
- 조사: 기본 MariaDB는 Performance Schema가 꺼져 있고 일반 계정은 접근 불가; 시작 옵션으로 활성화한 전용 진단 container에서 정규화 SQL별 횟수·누적·평균·최대 statement 시간을 수집할 수 있음을 확인
- 계획: 기본 Compose·운영 API를 바꾸지 않는 선택적 Performance Schema override, 좌석 잠금 SELECT와 회차 UPDATE digest 수집, distributed 50·100 RPS 각 3회 동일 조건 비교, 근거·한계 문서화
- 구현: 진단 전용 Compose override, normalized SQL digest 분류·민감 SQL 원문 제외·성공 수 일치 gate, 7-run 전용 runner와 중앙값·범위·SQL 시간 비율 집계, PowerShell fixture CI
- 측정: 100 RPS에서 좌석 잠금 SELECT 평균 중앙값 146.44ms·누적 105,292ms, 회차 UPDATE 평균 0.354ms·누적 255ms로 410.63배 차이; Hikari pending 189·DB lock time +100,479ms·p95 4,096.96ms, 예상 밖 실패·deadlock 0·불변식 충족
- 판정: 회차 단일 행 UPDATE 주병목 가설은 반증; `(concert_time_id, seat_number)` 복합 index가 없어 잠금 조회가 회차당 약 2,000행을 검사하는 경로가 다음 고경합 A/B 후보
- 검증: 신규 SQL digest 16개·진단 runner 15개, 기존 수집기 42개·baseline runner 19개 PowerShell assertion, k6 inspect, Backend 전체 102개 test 통과(실패·오류·skip 0), `git diff --check` 통과
- 범위: Backend 로컬 `loadtest` profile·mock 결제·2,000석 fixture, SQL digest·Hikari·DB lock·k6 결과의 run별 연결
- 제외: 실제 KOPIS·PG·SMS·운영 DB, Frontend, 성능 개선 적용, P6Spy 전면 로깅, 회차 counter 생략 경로, Hikari 확대·Redis lock·대기열·outbox·브로커
- Reviewer: 구현 HEAD `6480a0eaee6a8a76e3544f24ed5084d6ea321daf`, Blocking 없음, `MERGE_READY: YES`; Backend CI 성공

### Backend Issue #53 — 가상 좌석 고경합의 단계별 성능 기준선 측정

- Issue: [#53](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/53)
- Branch: `test/53-staged-contention-baseline`
- PR: [#54](https://github.com/SKUWooU/TicketOnBoarding_Be/pull/54)
- squash commit: `6c798d8c0f421c0d9601e84d640c25ea3f1c3d5f`
- 상태: 완료
- 계획 승인: 완료
- 확인된 문제: k6 콘솔 처리율이 setup 시간을 포함하고 무거운 fixture 생성이 Hikari·DB 표본에 섞이며, 무경합 성공 처리량과 정상 409 경합 응답을 같은 TPS로 해석할 수 있음
- 계획: fixture 사전 준비, 민감 정보 없는 구조화 k6 결과, 시나리오별 단계·3회 반복 runner, 중앙값·범위와 중단 조건, 로컬 기준선 문서화
- 구현: fixture 준비 시간창 분리, `handleSummary()` 구조화 결과, k6/재고 parser, 28-run plan·반복 중단·중앙값/범위 runner, PowerShell fixture CI
- 측정: 최종 `preAllocatedVUs=maxVUs=200` batch에서 warmup 제외 유효 run 24회·distributed 150 RPS 3회 자동 생략; distributed 50 RPS p95 37.42ms·pending 0·도달률 100%에서 100 RPS p95 2,523.26ms·pending 189·도달률 94.91%·DB lock time +93,346ms로 변곡; 모든 유효 run 예상 밖 실패·deadlock 0·불변식 충족
- 검증: 수집기 42개·baseline runner 19개 PowerShell assertion, k6 inspect, Backend 전체 102개 test 통과(실패·오류·skip 0), `git diff --check` 통과
- 범위: Backend 로컬 `loadtest` profile·mock 결제·2,000석 fixture, distributed/hot-section/hot-seat 단계별 측정, ignored 원본 결과와 근거 문서
- 제외: 실제 KOPIS·PG·SMS·운영 DB, Frontend, 운영 SLA, Prometheus/Grafana 전체 stack, 성능 개선·분산 기술 선제 도입
- Reviewer: 최종 HEAD `c99fc3c3267a36f2847dfc6fe754d97c8414bbb8`, Blocking 없음, `MERGE_READY: YES`; Backend CI 성공 후 자동 squash merge

### Backend Issue #51 — 고경합 부하의 Hikari·MariaDB 지표 수집 경계 구성

- Issue: [#51](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/51)
- Branch: `test/51-lightweight-contention-observability`
- PR: [#52](https://github.com/SKUWooU/TicketOnBoarding_Be/pull/52)
- squash commit: `eba2e77ed209fb88128a8baaf4c951866bafc080`
- 상태: 완료
- 계획 승인: 완료
- 확인된 문제: 종료 후 단일 Hikari 값과 여러 실행이 누적된 MariaDB counter만으로는 부하 중 connection·lock peak와 해당 run의 증가분을 분리할 수 없음
- 조사: Hikari active/pending/idle/max Prometheus gauge 확인; MariaDB row lock wait/time/deadlock global counter와 current waits·threads gauge 확인; 반복 CLI 표본이 `Connections`와 thread 값에 관측자 영향을 주는 사실 확인
- 구현: 고유 run·결과 경로·필수 metric 검증, k6 비동기 process와 고정 cadence 표본, Hikari/DB peak·counter delta·실제 표본 간격·관측자 효과 summary, 실패 JSON·민감 정보 제외, PowerShell fixture CI
- 검증: PowerShell 23개 assertion·k6 inspect·전체 Backend 102개 강제 재실행 통과; distributed 5 RPS·5초 active peak 1·pending 0·lock delta 0; hot-seat 100 RPS·10초 1,001회 중 성공 1·예상 409 1,000·p95 39.68ms·active peak 2·pending 0·lock waits +77·time +142ms·deadlock 0·종료 불변식 충족
- 범위: 로컬 PowerShell 측정 wrapper, Hikari 시계열·MariaDB counter delta/현재 gauge, fixture 검사·CI, ignored 결과와 근거 문서
- 제외: 단계별 최대 처리량·성능 개선, Prometheus/Grafana 전체 stack, 운영 배포·장기 보관·알림, Frontend, 실제 외부 연동, 분산 기술
- Reviewer: 최종 HEAD `459719c48d389d49a569339c9586e6057e59c403`, 중간 DB counter reset Blocking 수정 후 `MERGE_READY: YES`; Backend CI 성공 후 자동 squash merge

### Backend Issue #49 — 좌석 경합 실패를 명시적 HTTP 409 계약으로 분류

- Issue: [#49](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/49)
- Branch: `fix/49-seat-contention-http-contract`
- PR: [#50](https://github.com/SKUWooU/TicketOnBoarding_Be/pull/50)
- squash commit: `13a18d3d36c2d2b4e9b6084beb30bf6c643fa969`
- 상태: 완료
- 계획 승인: 완료
- 확인된 문제: 동일 좌석 후발 요청과 잔여 재고 부족이 checked `Exception`으로 전파되어 예상된 재고 경합도 HTTP 500으로 응답하며, k6에서 실제 서버 장애와 구분되지 않음
- 구현: 좌석 선점·잔여 재고 실패 전용 HTTP 409 예외, 일반·검증 예약 Controller 계약, k6 hot 시나리오 예상 경합·예상 밖 오류 분리와 전체 시나리오 sanity threshold
- 검증: 전체 Backend 102개 test 통과(실패·오류·skip 0), k6 inspect 통과, hot-seat 20 RPS·10초에서 201회 중 성공 1·예상 409 경합 200·예상 밖 비-2xx/5xx 0·p95 36.69ms·종료 불변식 충족
- 범위: Backend 좌석 경합 예외·HTTP 409 계약, Controller·MariaDB 동시성 회귀, k6 예상 경합/예상 밖 오류 분리, 근거 문서
- 제외: 단계별 RPS 기준선·성능 개선, Prometheus/Grafana 전체 구성, Frontend, 실제 KOPIS·PG·SMS·운영 DB, 대기열·Redis 분산 lock·outbox·브로커
- Reviewer: 최종 HEAD `2d62796e73d3f127dfad91946dcfe3deda5971f9`, Blocking 없음, `MERGE_READY: YES`; Backend CI 성공 후 자동 squash merge

### Backend Issue #47 — 2,000석 가상 공연장 고경합 부하 측정 기반 구성

- Issue: [#47](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/47)
- Branch: `test/47-high-contention-loadtest-harness`
- PR: [#48](https://github.com/SKUWooU/TicketOnBoarding_Be/pull/48)
- squash commit: `174283b1b33936730a1e9dfbf5e0d0b521644fe1`
- 상태: 완료
- 계획 승인: 완료
- 구현: `loadtest` profile·2,000석 fixture·mock 결제 adapter·k6 4종 시나리오·Actuator 지표·상세 근거 문서 구성
- 검증: Blocking 수정 후 경계 테스트를 포함한 전체 Backend 100개 test 통과(실패·오류·skip 0), k6 inspect 통과, 독립 run 연속 distributed 5 RPS·10초에서 예약 50·51건 모두 종료 불변식 충족; 최종 distributed 예약 51건·p95 68.21ms; 32자 run ID idempotent-retry 51호출→20결과 재사용·p95 58.81ms·비-2xx 0·불변식 충족
- 범위: 로컬 가상 좌석 고경합 재현·측정 기반과 지표·정합성 결과 형식
- 제외: 실제 KOPIS·PG·SMS·운영 DB, Frontend, 운영 SLA·성능 개선 주장, 대기열·Redis 분산 lock·outbox·브로커, 전체 Prometheus/Grafana stack
- Reviewer: 최종 HEAD `1ab21f8d0c2fcd7fbd571fac3aa60d0dbc8b83d7`, Blocking 없음, `MERGE_READY: YES`; Backend CI 성공 후 자동 squash merge

### Backend Issue #43 — 서버 소유 가상 가격과 mock 결제 검증 경계 구성

- Issue: [#43](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/43)
- PR: [#44](https://github.com/SKUWooU/TicketOnBoarding_Be/pull/44)
- Branch: `feat/43-payment-verification-boundary`
- squash commit: `76315805130d57c6e764d840fb6cdf60395014ed`
- 상태: 완료
- 계획 승인: 완료
- 구현: 서버 30,000원 가상 단가, Payment 상태·mock 검증 경계, 검증된 결제 1회 소비와 예약 transaction 연계
- 검증: mock 승인/미승인·식별자/금액/사용자 불일치·결제 재사용·late-failure rollback·동시 멱등 재사용/충돌, Issue 대상 30개·전체 Backend 92개 invocation 통과
- Reviewer: 최신 HEAD `8147c0cf`, Blocking 없음, `MERGE_READY: YES`
- 제외: 실제 PG·KOPIS·운영 DB 호출, Frontend, 좌석 hold·만료, 취소·환불 보상, Flyway 운영 migration, k6·분산 기술

### Backend Issue #45 — Reviewer 자동 병합 알림과 줄바꿈 정규화

- Issue: [#45](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/45)
- PR: [#46](https://github.com/SKUWooU/TicketOnBoarding_Be/pull/46)
- Branch: `chore/45-reviewer-auto-merge-output`
- squash commit: `06244189a9335f1bd3dd2283e21bf6d87ae4dc8e`
- 상태: 완료
- 계획 승인: 완료
- 범위: Reviewer 댓글 CRLF 정규화, 성공 시 bot 댓글 제거, 실패 댓글 유지, PR #42 Markdown 댓글 교정
- 제외: Reviewer의 Blocking/Non-blocking 검토 댓글 제거, 애플리케이션 코드, Frontend
- 검증: Git Bash fixture에서 LF·CRLF 입력 통과 및 literal `\\n` 입력 거절, `git diff --check` 통과, 최신 HEAD Backend CI 성공

### Backend Issue #41 — 예약 상태 문자열을 명시적 전이 정책으로 전환

- Issue: [#41](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/41)
- PR: [#42](https://github.com/SKUWooU/TicketOnBoarding_Be/pull/42)
- Branch: `refactor/41-reservation-status-transition`
- squash commit: `83eed9db39597121ed0296df52164c6dd4049211`
- 상태: 완료
- 계획 승인: 완료
- 구현: 완료
- 검증: 상태·converter·JSON·허용/거부/멱등 전이 단위 6개, 예약·취소 MariaDB 통합 52개와 전체 Backend 65개 invocation 통과; Backend CI 1분 46초 성공
- Reviewer: 최신 HEAD `6c34d87cbbacd8b19689e582dbb0f215ecd4a330`, Blocking 없음, `MERGE_READY: YES`
- 범위: Backend ReservationStatus enum·JPA/JSON 호환·도메인 전이, 기존 예약·취소 동시성·rollback 회귀
- 제외: 실제 PG·Frontend, 별도 Payment/Order 전체 모델, 좌석 hold·만료·환불, 운영 migration, k6

### Backend Issue #39 — 예약 중복 요청의 결과 재사용과 payload 충돌 방지

- Issue: [#39](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/39)
- PR: [#40](https://github.com/SKUWooU/TicketOnBoarding_Be/pull/40)
- Branch: `fix/39-reservation-idempotency`
- squash commit: `38b54fe1b7916d426a7dcde294036b172d7bd9f0`
- 상태: 완료
- 계획 승인: 완료
- 구현: 완료
- 검증: 동일 payload 순차 재시도는 최초 생성 시각 재사용; 두 요청이 모두 기존 key 없음을 읽은 뒤의 동시 경쟁 3회 모두 Booking 1·예약/점유 1·잔여 23; payload 충돌·잘못된 key 무변경 거부, 실패 key rollback 후 재사용; Service 29개·Controller 3개·전체 Backend 59개 invocation 성공
- Reviewer: 최신 HEAD `9b737f8aedbeedeecbfef51f492064e2bf1892e9`, Blocking 없음, `MERGE_READY: YES`; Backend CI 1분 35초 성공
- 범위: Backend 선택적 `Idempotency-Key`, Booking과 안정적 성공 결과, 동일 payload 결과 재사용, payload 충돌 거부, 키 없는 현재 FE 호출 호환
- 제외: 실제 PG 호출·검증, Payment/Order 전체 상태 머신, Frontend 변경, 운영 Flyway 전환, Redis·대기열·outbox·메시지 브로커, k6

### Backend Issue #37 — 결제 승인과 예약 확정 경계·중복 요청 기준선

- Issue: [#37](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/37)
- PR: [#38](https://github.com/SKUWooU/TicketOnBoarding_Be/pull/38)
- Branch: `test/37-payment-reservation-boundary-baseline`
- squash commit: `4030c7c329db1123ee28e218f07dd3ca2a735926`
- 상태: 완료
- 계획 승인: 완료
- 구현: 완료
- 검증: 결제 ID·승인 token·금액이 없는 4필드 예약 DTO로 `결제완료` 예약 1건·점유 1·잔여 23 생성; 동일 요청 재시도는 `이미 예약된 좌석입니다.` 실패하고 최초 성공 이후의 잔여 수량·점유 수·예약 row 수 집계는 유지; 대상 20개·전체 Backend 47개 invocation, Backend CI 1분 46초 성공
- Reviewer: 최신 HEAD `3fa27f16ba48ba517b625ffa98fbd1f2dbe162a2`, Blocking 없음, `MERGE_READY: YES`
- 범위: Backend 예약 계약·중복 재시도 결과, FE 결제 성공 callback 이후 예약 호출의 정적 경계, MariaDB Testcontainers 기준선
- 제외: 실제 PG 호출, 운영 로직·Frontend 변경, Payment/Order·좌석 hold 상태 머신, k6·outbox·메시지 브로커

### Backend Issue #35 — 사용자 취소 신청의 잠금·상태 전이 원자성 보장

- Issue: [#35](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/35)
- PR: [#36](https://github.com/SKUWooU/TicketOnBoarding_Be/pull/36)
- Branch: `fix/35-cancellation-request-atomicity`
- squash commit: `60787de04d2f74fc2df7994bb5995438587aa2dc`
- 상태: 완료
- 계획 승인: 완료
- 구현: 완료
- 검증: 관리자 승인→사용자 신청과 사용자 신청→관리자 승인 양방향에서 두 번째 lock 조회의 200ms 미반환·해제 후 완료를 각 3회 확인, 최종 `취소완료`·미점유·잔여 24 유지; Service 통합 23개·Controller 3개·전체 Backend 45개 invocation, Backend CI 1분 54초 성공
- Reviewer: 최신 HEAD `72162f77aef4b08e34d9f504ee9acc05c6f91398`, Blocking 없음, `MERGE_READY: YES`
- 범위: 사용자 신청 transaction·예약 row lock·소유자 및 상태 정책, Controller Service 위임, Issue #33 회귀 테스트
- 제외: 실제 결제·환불·외부 API·Frontend, 전체 상태 enum 전환, k6·분산 lock·대기열·메시지 브로커

### Backend Issue #33 — 사용자 취소 신청과 관리자 승인 경합 상태 전이 기준선

- Issue: [#33](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/33)
- PR: [#34](https://github.com/SKUWooU/TicketOnBoarding_Be/pull/34)
- Branch: `test/33-cancellation-request-approval-race-baseline`
- squash commit: `87c35225877fc6f49ff597946322b462c856802c`
- 상태: 완료
- 계획 승인: 완료
- 구현: 완료
- 검증: 사용자 조회 완료 → 관리자 승인 commit → 사용자 지연 저장 순서를 latch로 제어해 3회 모두 `취소완료`가 `취소신청`으로 되돌아감을 재현; 좌석 미점유·잔여 24는 유지되고 재승인은 이미 해제된 좌석으로 거부됨; 정상 순차 제어군 포함 대상 15개·전체 Backend 34개 invocation, Backend CI 2분 5초 성공
- Reviewer: 최신 HEAD `c7ac32d2e88e1b95712a39e6ef0ee9240bdbd8ae`, Blocking 없음, `MERGE_READY: YES`
- 범위: 기존 사용자 Controller의 분리된 조회·저장 경계와 관리자 잠금 transaction 간 MariaDB Testcontainers 상태 전이 기준선
- 제외: 운영 로직 수정, 실제 결제·환불·외부 API·Frontend, 전체 상태 enum 전환, k6·분산 lock·대기열·메시지 브로커

### Backend Issue #31 — 취소 중복 요청의 재고 중복 복구 방지

- Issue: [#31](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/31)
- PR: [#32](https://github.com/SKUWooU/TicketOnBoarding_Be/pull/32)
- Branch: `fix/#31-cancellation-idempotency`
- squash commit: `b10c10727af06cc6fe3f9c8f6813fb3e651a6d7b`
- 상태: 완료
- 계획 승인: 완료
- 구현: 완료
- 검증: 대상 11개·전체 30개 invocation 성공, 첫 lock 보유 중 두 번째 lock 조회의 200ms 미반환과 해제 후 완료 확인, 회차 증가 0건 예외 후 예약·좌석 rollback, 없는 예약·이미 해제된 좌석 거부 검증
- Reviewer: Blocking 수정 후 최신 HEAD `0edd3d39eaa58a9fdca31a087cddbf2a7b79812b`, Blocking 없음, `MERGE_READY: YES`
- 범위: 관리자 취소 transaction, 예약 row lock, 취소 상태 정책, 원자적 재고 복구, MariaDB Testcontainers 회귀 테스트
- 제외: 실제 결제·환불·외부 API·Frontend, 전체 상태 enum 전환, k6·분산 lock·대기열·메시지 브로커

### Backend Issue #29 — auto-merge 연결 Issue 종료 E2E 검증

- Issue: [#29](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/29)
- PR: [#30](https://github.com/SKUWooU/TicketOnBoarding_Be/pull/30)
- Branch: `test/#29-auto-merge-issue-close-e2e`
- squash commit: `d514c791ebd81db6c0c3b8798afbfcfe85fdd51d`
- 상태: 완료
- 계획 승인: 완료
- 구현: 완료
- 검증: 최종 HEAD `85a4cd712a46187d787aa025e953fa8ced76799b` Backend CI 58초 성공, auto-merge Action `32654934930` 성공, Issue #27·#29 종료와 원격 branch 삭제·결과 comment 확인
- Reviewer: 두 차례 상태 기록 Blocking 수정 후 최종 HEAD Blocking 없음, PR 본문 CI 체크박스 Non-blocking, `MERGE_READY: YES`
- 범위: 문서 상태 diff와 실제 GitHub Actions 성공 경로
- 제외: 애플리케이션·Frontend·배포·외부 API·결제·운영 데이터 변경

### Backend Issue #27 — auto-merge 후 연결 Issue 명시적 종료

- Issue: [#27](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/27)
- PR: [#28](https://github.com/SKUWooU/TicketOnBoarding_Be/pull/28)
- Branch: `fix/#27-auto-merge-close-issues`
- squash commit: `b5d7347aadf7ce832e9c5df1a80f2765eb1700fe`
- 상태: 구현·Reviewer 검토·자동 squash merge 완료, default branch의 실제 종료 E2E는 Issue #29에서 검증
- 계획 승인: 완료
- 구현: 완료
- 검증: actionlint 1.7.7·`git diff --check` 통과, 최신 HEAD `50d369c4c36756f56dcd3b31a17ff76f4f6aa7cd`의 Backend CI 56초 성공, no-reference·already-closed·same-repository OPEN Issue 분기 정적 검토
- Reviewer: 최신 HEAD `50d369c4c36756f56dcd3b31a17ff76f4f6aa7cd`, Blocking 없음, CI 시간과 HEAD 연결 기록 Non-blocking 반영, `MERGE_READY: YES`
- 범위: auto-merge 후 같은 저장소의 열린 `closingIssuesReferences` 명시적 종료
- 제외: 다른 저장소 Issue, 애플리케이션·Frontend·배포·외부 연동 변경

### Backend Issue #25 — Reviewer auto-merge gate E2E 검증

- Issue: [#25](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/25)
- PR: [#26](https://github.com/SKUWooU/TicketOnBoarding_Be/pull/26)
- Branch: `test/#25-auto-merge-e2e`
- squash commit: `3496aa1c088fcace3e2fea6051ca0cd739144cb5`
- 상태: partial 완료, Issue 자동 종료 누락은 #27로 분리
- 계획 승인: 완료
- 구현: 완료
- 검증: stale HEAD Action 실패·PR OPEN·원인 comment, 최신 HEAD·Backend CI 기반 Action 성공·squash merge·원격 branch 삭제, Issue #25는 OPEN으로 남아 후속 연결 후 수동 종료
- Reviewer: 최신 HEAD `e9f95034b477039dc037c22a24601248954ae57f`, Blocking·Non-blocking 없음, `MERGE_READY: YES`
- auto-merge Action run `32651816465` 성공 후 2026-08-24 squash merge
- 범위: 문서 상태만 변경하는 synthetic PR과 실제 GitHub Actions gate
- 제외: 애플리케이션·Frontend·배포·외부 API·결제·운영 데이터 변경

### Backend Issue #23 — Reviewer 판정 기반 안전한 auto-merge 구성

- Issue: [#23](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/23)
- PR: [#24](https://github.com/SKUWooU/TicketOnBoarding_Be/pull/24)
- Branch: `chore/#23-reviewer-auto-merge`
- squash commit: `2b0a0c7b0455c7f5ad61dcfce5915ddf43c93512`
- 상태: 완료, 실제 gate E2E는 Issue #25에서 검증
- 계획 승인: 완료
- 구현: 완료
- 검증: actionlint 1.7.7 통과, 로컬 전체 Gradle test 24 invocation 기준 성공, 최신 PR Ubuntu `Backend test` 1분 5초 통과
- Reviewer: 최신 HEAD `1e0898b306539697c335dcd35146382341a0236d`, Blocking·Non-blocking 없음, `MERGE_READY: YES`
- 상시 승인 gate 충족 후 2026-08-24 squash merge
- 범위: Backend CI, Reviewer comment 기반 최신 HEAD squash merge, 협업 절차 갱신
- 제외: Frontend, 배포, 외부 API·결제·운영 데이터 자동 승인, 다음 Agent 자동 호출

### Backend Issue #21 — 취소 중복 요청과 상태·재고 정합성 기준선

- Issue: [#21](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/21)
- PR: [#22](https://github.com/SKUWooU/TicketOnBoarding_Be/pull/22)
- Branch: `test/#21-cancellation-idempotency-baseline`
- squash commit: `a17f2812a698dac6323513d14414681de603a525`
- 상태: 완료
- 계획 승인: 완료
- 구현: 완료
- 검증: 정상 취소 잔여 24·불변식 충족, 동일 예약 2회 취소 잔여 25·불변식 위반 3회 재현, `결제완료 → 취소완료` 직접 전이 허용, 대상 invocation 5개·전체 invocation 24개·`git diff --check` 통과
- Reviewer: 최신 HEAD `d82e3f3`, Blocking·Non-blocking 없음, `MERGE_READY: YES`
- 사용자 최종 승인 후 2026-08-24 squash merge
- 범위: MariaDB Testcontainers 가상 좌석 fixture와 현재 취소 동작 기준선
- 제외: 운영 로직 수정, 동시 취소 barrier, 실제 결제·외부 API·Frontend, k6·Actuator·Prometheus

### Backend Issue #19 — Docker Compose 기반 로컬 Backend 실행 기준선

- Issue: [#19](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/19)
- PR: [#20](https://github.com/SKUWooU/TicketOnBoarding_Be/pull/20)
- Branch: `chore/#19-local-backend-runtime`
- squash commit: `676325d97176ec7d38e3a4e75eb74910f1e607a6`
- 상태: 완료
- 계획 승인: 완료
- 구현: 완료
- 검증: Compose config 통과·MariaDB healthy, local `bootRun` port 18080 기동, `GET /main` 200 JSON, DB admin 1·concert 0 및 실제 SELECT 확인, 외부 호출 없음, 종료·volume 보존, 전체 Testcontainers test invocation 19개·`git diff --check` 통과
- Reviewer: 최신 HEAD `8f1c493`, Blocking·Non-blocking 없음, `MERGE_READY: YES`
- 사용자 최종 승인 후 2026-08-24 squash merge
- 범위: MariaDB Compose, 공개 가능한 local profile, `bootRun`·HTTP smoke, 실행 근거 문서
- 제외: Backend Dockerfile·Frontend, 실제 외부 API·결제, Flyway·운영 schema, k6·Actuator·Prometheus

### Backend Issue #17 — 좌석 복합 unique index migration 안전성 기준선

- Issue: [#17](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/17)
- PR: [#18](https://github.com/SKUWooU/TicketOnBoarding_Be/pull/18)
- Branch: `test/#17-seat-index-migration-baseline`
- squash commit: `c7ecbc27126ab0553ec39fea26567a2757b166b2`
- 상태: 완료
- 계획 승인: 완료
- 구현: 완료
- 검증: 중복 key count 2, DDL `23000/1062`, index 없음·row 2 유지; clean unique index 후 중복 insert `23000/1062`·row 1 유지; 전체 Testcontainers test invocation 19개 통과
- Reviewer: 최신 HEAD `fcdd5c3`, Blocking·Non-blocking 없음, `MERGE_READY: YES`
- 사용자 최종 승인 후 2026-08-23 squash merge
- 범위: 중복 사전 점검 SQL, 복합 unique index DDL 성공·실패와 실패 후 상태, schema ownership·Flyway 적용 조건
- 제외: 운영 DB·데이터 정리, Flyway 활성화·전체 schema baseline, 애플리케이션·Frontend 동작 변경

### Backend Issue #15 — 복수 좌석 잠금 순서 정규화와 예약 요청 검증

- Issue: [#15](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/15)
- PR: [#16](https://github.com/SKUWooU/TicketOnBoarding_Be/pull/16)
- Branch: `fix/#15-canonical-seat-lock-order`
- squash commit: `757ea05b35b91d87e0bb7a779c0cd1c14a368858`
- 상태: 완료
- 계획 승인: 완료
- 구현: 완료
- 검증: indexed 반대 순서 3회 모두 canonical `A1` 첫 query·deadlock 0·성공 1/예약 충돌 1, invalid input lock query 0, 전체 Testcontainers test invocation 18개 통과
- Reviewer: 최신 HEAD `6ed1403`, Blocking·Non-blocking 없음, `MERGE_READY: YES`
- 사용자 최종 승인 후 2026-08-23 squash merge
- 범위: canonical 좌석 잠금 순서, 요청 좌석 목록 검증, indexed 반대 순서 deadlock 회귀, 기존 예약 정합성 회귀
- 제외: 운영 복합 index·Flyway, retry·분산 lock·대기열, 임시 점유·결제·Frontend

### Backend Issue #13 — PR 전용 템플릿 분리

- Issue: [#13](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/13)
- PR: [#14](https://github.com/SKUWooU/TicketOnBoarding_Be/pull/14)
- Branch: `docs/#13-pr-template-separation`
- squash commit: `1c874e531861bb066cf562f6ea705552006e31fd`
- 상태: 완료
- 계획 승인: 완료
- 구현: 완료
- 검증: PR 전용 7개 섹션·체크박스 확인, Issue template 변경 없음, 애플리케이션·Frontend 변경 없음, `git diff --check` 통과
- Reviewer: 최신 HEAD `009200c`, Blocking 수정 후 Blocking·Non-blocking 없음, `MERGE_READY: YES`
- 사용자 최종 승인 후 2026-08-23 squash merge
- 범위: PR 전용 template, 당시 open 상태였던 PR #12 본문에 merge 전 새 형식 적용, Issue #11 완료 기록
- 제외: Issue template, 애플리케이션·Frontend 코드, 템플릿 적용 전에 이미 병합된 PR #2·#4·#6·#8·#10 본문 수정

### Backend Issue #11 — 좌석 복합 인덱스와 deadlock 비교 기준선

- Issue: [#11](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/11)
- PR: [#12](https://github.com/SKUWooU/TicketOnBoarding_Be/pull/12)
- Branch: `test/#11-seat-index-deadlock-comparison`
- squash commit: `cab2a1663867458065b459c69ca76d81f8f61295`
- 상태: 완료
- 계획 승인: 완료
- 구현: 완료
- 검증: indexed 반대 순서 시나리오 3회 모두 `1213/40001` deadlock·victim rollback 확인, 전체 Testcontainers test invocation 16개 통과, `git diff --check` 통과
- Reviewer: 최신 HEAD `85ff490`, Blocking·Non-blocking 없음, `MERGE_READY: YES`
- 사용자 최종 승인 후 2026-08-23 squash merge
- 범위: test schema 복합 unique index, 잠금 query plan, 첫 lock 동시 획득, 반대 순서 deadlock·rollback·최종 재고 비교
- 제외: 운영 Entity·schema·예약 로직, Flyway, retry·분산 lock·대기열, 임시 점유·결제·Frontend·부하 테스트

### Backend Issue #9 — 복수 좌석 잠금 순서와 deadlock 기준선

- Issue: [#9](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/9)
- PR: [#10](https://github.com/SKUWooU/TicketOnBoarding_Be/pull/10)
- Branch: `test/#9-multi-seat-deadlock-baseline`
- 상태: 완료
- 계획 승인: 완료
- 구현: 완료
- 검증: 반대 순서 기준선 3회와 전체 Testcontainers test invocation 12개 통과, `git diff --check` 통과
- Reviewer: 최종 검토 HEAD `97310c0`, Blocking 없음, Non-blocking 2건, `MERGE_READY: YES`
- 사용자 최종 승인 후 2026-08-20 squash merge
- 범위: 반대 순서 복수 좌석 transaction의 첫 lock 동기화, deadlock·예외·rollback·최종 재고 기준선
- 제외: 운영 로직·lock ordering·인덱스·retry·임시 점유·결제·Frontend·부하 테스트와 분산 기술

### Backend Issue #7 — 백엔드 아키텍처 학습 기준선

- Issue: [#7](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/7)
- PR: [#8](https://github.com/SKUWooU/TicketOnBoarding_Be/pull/8)
- Branch: `docs/#7-backend-architecture-baseline`
- 상태: 완료
- 계획 승인: 완료
- 구현: 완료
- 검증: 전체 Gradle test 실제 재실행 성공, 문서 상대 경로 확인, `git diff --check` 통과
- Reviewer: 최종 검토 HEAD `9088dbe`, Blocking·Non-blocking 없음, `MERGE_READY: YES`
- 사용자 최종 승인 후 2026-08-20 squash merge
- 범위: Backend 기술 스택, KOPIS 수집, 도메인·DB·인증·예약·결제·취소·FE 연동과 차별화 Phase를 단일 학습 문서로 정리
- 제외: README, 애플리케이션·Frontend 코드, 외부 API·결제·운영 데이터 실행

### Backend Issue #5 — 예약 원자성·잔여 좌석 갱신 정합성

- Issue: [#5](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/5)
- PR: [#6](https://github.com/SKUWooU/TicketOnBoarding_Be/pull/6)
- Branch: `fix/#5-reservation-atomicity-inventory`
- squash commit: `ab9a103cd5306ca6f25515f1292bf0e0318586c0`
- 상태: 완료
- 계획 승인: 완료
- 구현: 완료
- 검증: 예약 통합 테스트 invocation 8개 통과, 전체 테스트 invocation 9개 통과, `git diff --check` 통과
- Reviewer: 최종 HEAD Blocking 없음, `MERGE_READY: YES`
- 사용자 최종 승인 후 2026-08-20 squash merge
- 범위: checked exception 전체 rollback, 회차 잔여 수량 조건부 원자 감소, Issue #3 회귀 테스트 전환
- 제외: deadlock·인덱스·취소·결제·멱등성·인증·Frontend·부하 테스트와 분산 기술
- 결과: 상이 좌석 8개 반복 예약 시 잔여 16, checked exception과 잔여 부족 시 좌석·예약 전체 rollback

### Backend Issue #3 — 예매 트랜잭션·경합 기준선

- Issue: [#3](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/3)
- PR: [#4](https://github.com/SKUWooU/TicketOnBoarding_Be/pull/4)
- Branch: `test/#3-reservation-concurrency-baseline`
- squash commit: `a5db362373833c3847e28a47ad082a265bc1429d`
- 상태: 완료
- 계획 승인: 완료
- 구현: 완료
- 검증: 집계 조회 후 잠금 호출 전 결정적 barrier에서 상이 좌석 시나리오 3회 통과, 전체 `test` invocation 8개 통과, `git diff --check` 통과
- Reviewer: 최종 HEAD `ca069e1`, Blocking·Non-blocking 없음, `MERGE_READY: YES`
- 사용자 최종 승인 후 2026-08-19 squash merge
- 범위: MariaDB Testcontainers, 가상 좌석 fixture, 단건·동일 좌석·상이 좌석·복수 좌석 실패 기준선
- 제외: 운영 로직 수정, deadlock 해결, 취소·결제·인증·Frontend와 부하 테스트
- 관찰: 동일 좌석 8개 요청은 1개만 성공, 상이 좌석 8개는 모두 성공했으나 잔여 수량은 1만 감소, checked exception에서 첫 좌석·예약 부분 commit

### Backend Issue #1 — 문서·협업 절차 부트스트랩

- Issue: [#1](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/1)
- PR: [#2](https://github.com/SKUWooU/TicketOnBoarding_Be/pull/2)
- squash commit: `9b9b18405ce29a110cd67fcef6c923d5d9505d65`
- 상태: 완료
- Reviewer: 최신 검토 HEAD `485c071`, Blocking 없음, `MERGE_READY: YES`
- 사용자 최종 승인 후 2026-08-19 squash merge
- Frontend 변경과 외부 API·결제·운영 데이터 호출 없음

## 다음 후보

1. 복수 좌석 lock ordering과 deadlock 재현
2. `(concert_time_id, seat_number)` 복합·유일 인덱스 검증
3. Frontend: 별도 Issue로 Issue·PR template과 개선 문서 진입점 구성
4. 예약·결제·취소 멱등성과 상태 전이 Issue 분리
