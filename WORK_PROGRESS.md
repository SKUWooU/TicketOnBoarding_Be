# 작업 진행 기록

이 문서는 완료·진행 중인 Issue와 검증 상태를 기록하는 단일 진행 상태 원본입니다. 상세 문제와 결정은 연결된 Issue, PR, EVIDENCE_MAP과 ADR에서 확인합니다.

## 저장소 기준선

| 구분 | 저장소 | 기준 Branch | 조사 기준 commit |
| --- | --- | --- | --- |
| Backend | [TicketOnBoarding_Be](https://github.com/SKUWooU/TicketOnBoarding_Be) | `main` | `bcfa7e30c947975e1f7f89d5eef0e8bffe36b9a1` |
| Frontend | [TicketOnBoarding_Fe](https://github.com/SKUWooU/TicketOnBoarding_Fe) | `main` | `1f9678be7a3a66ec610c6ef4ea335e9d6f5cbafd` |

두 저장소는 독립된 Issue와 PR을 사용합니다. 교차 변경은 각 작업의 링크를 양쪽 Issue 또는 PR에 남깁니다.

## 진행 중

### Backend Issue #19 — Docker Compose 기반 로컬 Backend 실행 기준선

- Issue: [#19](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/19)
- PR: [#20](https://github.com/SKUWooU/TicketOnBoarding_Be/pull/20)
- Branch: `chore/#19-local-backend-runtime`
- 상태: 구성·실제 기동·HTTP smoke·근거 문서 및 전체 검증 완료, Reviewer 검토 대기
- 계획 승인: 완료
- 구현: 완료
- 검증: Compose config 통과·MariaDB healthy, local `bootRun` port 18080 기동, `GET /main` 200 JSON, DB admin 1·concert 0 및 실제 SELECT 확인, 외부 호출 없음, 종료·volume 보존, 전체 Testcontainers test invocation 19개·`git diff --check` 통과
- 범위: MariaDB Compose, 공개 가능한 local profile, `bootRun`·HTTP smoke, 실행 근거 문서
- 제외: Backend Dockerfile·Frontend, 실제 외부 API·결제, Flyway·운영 schema, k6·Actuator·Prometheus

## 완료

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
