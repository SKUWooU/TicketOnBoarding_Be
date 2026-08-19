# 개선 근거 연결표

문제, 재현, 변경, 검증 결과와 Issue·PR을 한 행에서 추적합니다. 측정 전에는 수치 칸을 추정값으로 채우지 않습니다.

| 문제 | 재현·기준선 | 개선 | 테스트·측정 | 결과 | Issue / PR |
| --- | --- | --- | --- | --- | --- |
| 협업·검증 기준 문서 부재 | 두 저장소에서 Template·workflow·진행 문서 부재 확인 | 문서와 Template 기준 구성 | 필수 항목, 링크, diff 검사 | Reviewer `MERGE_READY: YES`, 사용자 승인 후 병합 | [BE #1](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/1) / [PR #2](https://github.com/SKUWooU/TicketOnBoarding_Be/pull/2) |
| Backend 전체 구조와 후속 개선 경계의 학습 기준선 부재 | KOPIS batch, 도메인·DB·인증·예약·결제·취소와 FE 호출 경로 정적 분석 | 탑다운 아키텍처 학습 기준선과 차별화 Phase 문서화 | 코드 경로 대조, 상대 링크 검사, 전체 Gradle test 재실행 | 확인된 사실·관찰·미검증 가설과 가상 좌석 측정 한계를 분리 | [BE #7](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/7) / [PR #8](https://github.com/SKUWooU/TicketOnBoarding_Be/pull/8) |
| 동일 좌석 동시 예약 결과 미검증 | MariaDB 10.11.8, 가상 좌석 24개, `A1` 8개 동시 요청 | 운영 코드 변경 없음 | 성공 수, 예약 row, 좌석·집계 상태 | 1회 성공·7회 실패, 예약/좌석 각 1, 잔여 23, 불변식 충족 | [BE #3](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/3) |
| 서로 다른 좌석의 잔여 수량 갱신 유실 가능성 | 같은 회차 `A1`~`A8` 8개 동시 예약 | 운영 코드 변경 없음 | 집계 조회 후 잠금 호출 전 결정적 barrier, 한 실행에서 3회 반복 | 매회 8좌석·8예약 반영, 잔여 23으로 7회 감소 유실, 불변식 위반 | [BE #3](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/3) |
| checked exception에서 복수 좌석 부분 commit 가능성 | `[A1, NOT-EXISTING]` 요청 | 운영 코드 변경 없음 | 좌석·예약·잔여 수량, 동일 조건 4회 | 첫 좌석·예약 commit, 잔여 24, 불변식 위반 | [BE #3](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/3) |
| 서로 다른 좌석의 잔여 수량 갱신 유실 | Issue #3의 결정적 barrier, 매회 좌석·예약 8·잔여 23 | DB 조건부 원자 감소 | 같은 fixture에서 3회 반복 | 매회 좌석·예약 8, 잔여 16, 불변식 충족 | [BE #5](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/5) |
| checked exception의 복수 좌석 부분 commit | Issue #3에서 첫 좌석·예약 commit | `rollbackFor = Exception.class` | `[A1, NOT-EXISTING]` 최종 DB 상태 | 좌석·예약 0, 잔여 24, 전체 rollback | [BE #5](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/5) |
| 잔여 수량 음수 방지 | 잔여 1, 예약 가능한 좌석 `A1`, `A2`의 guard fixture | `seatAmount >= seatCount` 조건과 영향 row 확인 | 실패 후 좌석·예약·집계 확인 | update 0행, 좌석·예약 0, 잔여 1 | [BE #5](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/5) |
| 좌석 잠금 조회의 복합 인덱스 부재 | 생성 schema `SHOW INDEX`, 잠금 SQL `EXPLAIN` | 운영 schema 변경 없음 | index column, access type, selected key | `seat_number` 미포함, `type=ALL`, `key=null`, fixture 24행 | [BE #3](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/3) |
| 복수 좌석 잠금 순서의 deadlock 가능성 | `[A,B]`, `[B,A]` 동시 요청 | 미정 | deadlock, rollback, lock wait | 미측정 | 후속 Issue |
| 예약·결제·취소 중복 요청 | 중복 key와 응답 유실 fixture 필요 | 미정 | 결과 재사용, 중복 row, 상태·재고 | 미측정 | 후속 Issue |

## 기록 규칙

1. 문제는 확인된 사실과 가설을 구분합니다.
2. 재현 조건에는 DB, fixture, 동시 요청 모델을 포함합니다.
3. 개선 전후는 같은 조건에서만 직접 비교합니다.
4. 수치에는 원본 명령, 테스트 또는 문서 링크를 연결합니다.
5. 로컬 fixture 결과의 적용 한계를 함께 기록합니다.
