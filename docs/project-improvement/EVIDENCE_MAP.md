# 개선 근거 연결표

문제, 재현, 변경, 검증 결과와 Issue·PR을 한 행에서 추적합니다. 측정 전에는 수치 칸을 추정값으로 채우지 않습니다.

| 문제 | 재현·기준선 | 개선 | 테스트·측정 | 결과 | Issue / PR |
| --- | --- | --- | --- | --- | --- |
| 협업·검증 기준 문서 부재 | 두 저장소에서 Template·workflow·진행 문서 부재 확인 | 문서와 Template 기준 구성 | 필수 항목, 링크, diff 검사 | Reviewer 대기 | [BE #1](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/1) / [PR #2](https://github.com/SKUWooU/TicketOnBoarding_Be/pull/2) |
| 동일 좌석 동시 예약 결과 미검증 | MySQL 동시성 fixture 필요 | 미정 | 성공 수, 예약 row, 좌석·집계 상태 | 미측정 | 후속 Issue |
| 서로 다른 좌석의 잔여 수량 갱신 유실 가능성 | 동일 회차의 서로 다른 좌석 동시 예약 | 미정 | 최종 `reserved`와 `seatAmount` 교차 검증 | 미측정 | 후속 Issue |
| 복수 좌석 잠금 순서의 deadlock 가능성 | `[A,B]`, `[B,A]` 동시 요청 | 미정 | deadlock, rollback, lock wait | 미측정 | 후속 Issue |
| 예약·결제·취소 중복 요청 | 중복 key와 응답 유실 fixture 필요 | 미정 | 결과 재사용, 중복 row, 상태·재고 | 미측정 | 후속 Issue |

## 기록 규칙

1. 문제는 확인된 사실과 가설을 구분합니다.
2. 재현 조건에는 DB, fixture, 동시 요청 모델을 포함합니다.
3. 개선 전후는 같은 조건에서만 직접 비교합니다.
4. 수치에는 원본 명령, 테스트 또는 문서 링크를 연결합니다.
5. 로컬 fixture 결과의 적용 한계를 함께 기록합니다.
