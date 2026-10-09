# 가상 좌석 경합 실험 요약

이 문서는 **결론을 재현 조건과 함께** 읽기 위한 요약이다. 아래 RPS는 k6 목표 도착률이며 TPS나 실제 예매처 처리량이 아니다. 각 run의 원 수치는 추적된 `load-test/results/` JSON에, 정리 전 긴 상세 기록은 [이전 문서 스냅샷](https://github.com/SKUWooU/TicketOnBoarding_Be/tree/53e9b7a7425a7ee961f332ef716e8b05e08484e5/docs/project-improvement)에 남아 있다.

## 1. 문제를 재현하고 고친 순서

| 문제 | 핵심 변경 | 검증에서 말할 수 있는 것 |
| --- | --- | --- |
| 다좌석 예매의 부분 commit·잔여 수량 갱신 유실 | 예매 변경을 한 트랜잭션으로 묶고 `seat_amount >= 요청 수량` 조건부 DB UPDATE | 실패 시 전체 rollback, 동시 재고 차감 불변식 유지. [#3](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/3) → [#5](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/5) |
| 역순 좌석 잠금의 deadlock | 좌석 번호 정렬 후 동일 순서로 `PESSIMISTIC_WRITE` 획득 | 겹치는 다좌석 요청의 잠금 순서 통일; 별도 반복 fixture에서 SQL deadlock 0. [#9](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/9) → [#11](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/11) |
| 좌석 잠금 조회의 넓은 탐색·connection 대기 | `UNIQUE(concert_time_id, seat_number)`로 조회와 식별 불변식 결합 | 동일한 가상 2,000석·100 RPS A/B에서 p95 **3,064.39→143.41ms**, Hikari pending peak **189→0**. 해당 로컬 fixture의 결과. [#57](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/57) → [#59](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/59) |
| 재시도 중복 Booking·결제 검증 경합 | 사용자별 key·요청 fingerprint·DB unique, Checkout 검증 선점과 PG I/O 트랜잭션 분리 | 같은 요청 결과 재사용, 다른 payload 충돌; Mock PG 지연 중 외부 검증 1회·최종 확정 1건. 실제 PG 호출 아님. [#35](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/35), [#82](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/82) |

복합 인덱스는 좌석 조회 비용을 줄였지만 경합 자체를 없애지는 않는다. 병목이 공통 회차 재고 갱신 등 다른 지점으로 옮겨갈 수 있어, p95와 재고 불변식을 함께 확인해야 한다. 여기서 0건은 해당 fixture에서 **관측하지 않았다**는 뜻이지 모든 입력·운영 환경에서 deadlock이 불가능하다는 증명이 아니다. 자세한 실패 재현·코드 전후는 [기존 foundations 아카이브](archive/foundations/)와 [checkout 아카이브](archive/checkout/)를 참고한다.

## 2. Hold 경합: 20석과 2,000석을 분리해 읽기

| 시나리오 | 고정 조건 | 결과·해석 | 원자료 |
| --- | --- | --- | --- |
| 20석 시작점 | 인기 1석/70%, Hold 500ms, 5 RPS×10초 | 완료·Hold=Release 51, 정상 409 0. 경합 강도 근거로 사용하지 않음. [#182](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/182) | [smoke 요약](evidence/smallsmoke182c-summary.json) |
| 20석 단계 | 같은 fixture, 5→10→20 RPS×각 10초 | 성공/정상 409가 29/21→47/54→74/126. 한 번씩 실행한 탐색. [#184](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/184) | [단계 요약](evidence/smallramp184-summary.json) |
| 20석 반복 | 같은 조건, 5/10/20 RPS 교차 순서 각 3회 | 9회 완료 1,058=Hold·해제 452+정상 409 606. dropped·예상 밖 오류·pending·timeout·deadlock·종료 Hold 0. 20 RPS 전역 DB row-lock wait 1/4/2건. [#186](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/186) | [9-run manifest](../../load-test/results/smallrepeat186d/small-seat-hold-ramp.json) |
| 20→30 RPS 탐색 | 같은 가상 20석, 각 10초 단일 순차 실행 | 완료 200/301, Hold=Release 74/97, 정상 409 126/204. 두 단계 모두 pending·dropped·deadlock·최종 Hold 0. 30 RPS 안정 구간 또는 p95 개선 주장은 불가. [#190](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/190) | [2-run manifest](../../load-test/results/probe190a/small-seat-hold-ramp.json) |
| 2,000석 지속 쓰기 경합 | 인기 20/40/200석·70%, Hold 100ms, 50/100 RPS×10초, 각 속도 6-run | Hold=Release·최종 HELD 0, dropped·예상 밖 오류·deadlock 0. 인기 집합별 성공/409 비중이 달라 혼합 p95 직접 비교 금지. [#172](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/172) | [50 RPS manifest](../../load-test/results/churn172a/controlled-seat-hold-churn-manifest.json), [100 RPS manifest](../../load-test/results/churn172b/controlled-seat-hold-churn-manifest.json) |

좌석 수, 인기 분포, Hold 지속 시간이 다르므로 **20석과 2,000석 p95를 전후 개선처럼 비교하지 않는다.** DB row-lock wait는 DB 전역 counter라 특정 SQL의 잠금 원인을 단정할 수 없다. 짧은 표본에서 Hikari pending 0도 순간 최대 대기를 전부 포착했다는 뜻은 아니다.

## 3. 결과를 믿기 위한 관측 경계

- **부하 결과:** 목표 RPS×시간과 완료·dropped를 분리한다. 좌석 선점의 정상 409는 서버 오류와 다른 범주이며 성공/409 p95를 따로 본다.
- **도메인 결과:** Hold=Release, 종료 HELD·예약·재고를 API snapshot과 독립 SQL로 교차한다. 20석 반복/탐색의 최종 SQL 배열 `20,0,0,20,0`은 총 좌석·Hold 행·예약 좌석·잔여 수량·Reservation 수다.
- **자원 결과:** Hikari pending/active/acquire/timeout, DB deadlock·전역 row-lock wait, 표본 간격을 같이 본다. #190은 실행 중 CPU·메모리 시계열이 없어 30 RPS 안정 구간을 선언하지 않는다.
- **MCP:** [read-only 근거 서버](../../tools/ticketon-evidence-mcp/README.md)는 허용된 summary/manifest와 불변식만 조회한다. `assess_controlled_hold_evidence`는 20석 9-run과 2,000석 6-run을 **각각** 판정한다. 별도 `assess_small_seat_probe`는 schema v3의 20→30 단일 탐색을 안전 게이트에 통과시켜도 `REPEAT_REQUIRED`로 표시하며, 실패·근거 누락 시 비교 수치를 숨긴다. 부하·PG·운영 DB 실행 권한은 없다. [#188](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/188), [#194](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/194)

## 4. 다음 실험의 중단 기준

같은 가상 20석 조건에서 30 RPS를 교차 순서로 반복하고, 완료율·정상 충돌/오류·Hikari 대기·DB wait·CPU/메모리·종료 재고를 한 run으로 묶는다. dropped, 예상 밖 오류, timeout, deadlock, 재고 불일치, 샘플 단절 또는 로컬 자원 부족이 나오면 다음 강도로 자동 증량하지 않는다. 제한된 노트북에서 측정이 어려우면 16GB PC에서 동일 조건으로 **새 기준선**을 잡는다. 호스트가 다른 수치를 직접 개선 전후로 취급하지 않는다.
