# 작업 진행 기록

이 문서는 완료·진행 중인 Issue와 검증 상태를 기록하는 단일 진행 상태 원본입니다. 상세 문제와 결정은 연결된 Issue, PR, EVIDENCE_MAP과 ADR에서 확인합니다.

## 저장소 기준선

| 구분 | 저장소 | 기준 Branch | 조사 기준 commit |
| --- | --- | --- | --- |
| Backend | [TicketOnBoarding_Be](https://github.com/SKUWooU/TicketOnBoarding_Be) | `main` | `60787de04d2f74fc2df7994bb5995438587aa2dc` |
| Frontend | [TicketOnBoarding_Fe](https://github.com/SKUWooU/TicketOnBoarding_Fe) | `main` | `1f9678be7a3a66ec610c6ef4ea335e9d6f5cbafd` |

두 저장소는 독립된 Issue와 PR을 사용합니다. 교차 변경은 각 작업의 링크를 양쪽 Issue 또는 PR에 남깁니다.

## 진행 중

### Backend Issue #37 — 결제 승인과 예약 확정 경계·중복 요청 기준선

- Issue: [#37](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/37)
- PR: 생성 전
- Branch: `test/37-payment-reservation-boundary-baseline`
- 상태: 구현·로컬 검증 완료, PR 준비
- 계획 승인: 완료
- 구현: 완료
- 검증: 결제 ID·승인 token·금액이 없는 4필드 예약 DTO로 `결제완료` 예약 1건·점유 1·잔여 23 생성; 동일 요청 재시도는 `이미 예약된 좌석입니다.` 실패, 최종 DB snapshot은 첫 성공과 동일; 대상 20개·전체 Backend 47개 invocation 및 `git diff --check` 성공
- Reviewer: PR 생성 후 별도 Agent 검토 예정
- 범위: Backend 예약 계약·중복 재시도 결과, FE 결제 성공 callback 이후 예약 호출의 정적 경계, MariaDB Testcontainers 기준선
- 제외: 실제 PG 호출, 운영 로직·Frontend 변경, Payment/Order·좌석 hold 상태 머신, k6·outbox·메시지 브로커

## 완료

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
