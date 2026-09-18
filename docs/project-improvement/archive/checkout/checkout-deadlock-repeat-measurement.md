# Checkout deadlock 해소 반복 측정

## 질문

Issue #128의 local 단일 실행에서 deadlock이 0이었던 결과가 우연한 한 번의 관찰인지, 같은 가상 좌석 재고 경합 조건에서 반복해도 유지되는지 확인한다.

## 측정 계약

- 실행 환경: 로컬 단일 Spring Boot 인스턴스, Docker Compose MariaDB 10.11.8, mock PG
- fixture: 실행마다 독립 생성하는 가상 좌석 2,000개
- 부하: `distributed`, 100 RPS, 10초, `preAllocatedVUs=100`, `maxVUs=200`
- 절차: warm-up 1회는 집계에서 제외하고 본 측정 3회 수행
- 수집: k6 결과, 최종 재고 snapshot, commit transition delta, Hikari peak, MariaDB deadlock delta
- raw 결과: `load-test/results/i132-repeat-01/` (Git ignore). 실제 공연장·운영 인프라 성능이 아니다.

## 결과

| 항목 | 본 측정 3회 범위 | 해석 |
| --- | ---: | --- |
| MariaDB deadlock delta | 0–0 | #126에서 재현한 active-assignment insert deadlock은 관찰되지 않았다. |
| 예상 밖 비성공 | 0–0 | 정상 좌석 선점 충돌과 구분되는 실패는 없었다. |
| 재고 invariant | 3/3 충족 | `checkoutConfirmed`와 예약 좌석·Reservation·Booking·Payment·commit transition이 각 실행에서 수렴했다. |
| 확정 Checkout | 591–627 | 단일 로컬 host의 부하 도달량 관찰값이다. |
| Checkout p95 | 5,165.8–5,573.0 ms | deadlock 여부와 별개로 host/connection-pool 포화가 남아 있음을 보여 주며 성능 개선 수치로 사용하지 않는다. |
| dropped iteration | 374–410 | 목표 arrival rate를 모두 발행하지 못한 부하 생성/처리 포화 신호다. |
| Hikari pending peak | 173–175 | pool 대기 신호다. pool 크기 조정이나 재시도 도입의 근거로는 아직 부족하다. |

## 결론과 경계

활성 assignment unique key를 history FK와 분리하고, Seat lock을 이미 보유한 Checkout 준비 경로의 불필요한 locking range query를 제거한 뒤 같은 계약의 3회 본 측정에서 deadlock은 재발하지 않았다. 이는 **deadlock 회귀 관찰 결과**이며, p95·dropped iteration을 이전 단일 실행과 비교해 성능이 좋아졌다고 주장하지 않는다.

남은 pending·dropped 신호는 후속 진단 후보다. 다음 단계는 pool/retry/queue를 바로 바꾸는 것이 아니라, 부하 생성 한계와 서버·DB 자원 포화를 분리하는 관측 계약을 먼저 정한다. 실제 PG, 실제 KOPIS, 실제 좌석 데이터, 운영 SLA는 이 측정 범위에 포함하지 않는다.
