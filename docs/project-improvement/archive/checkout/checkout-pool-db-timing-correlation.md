# Checkout pool·DB 지연 상관 진단 기준선

## 측정 계약

- 로컬 단일 Spring Boot, Docker MariaDB 10.11.8의 진단 전용 `performance_schema=ON` overlay, mock PG, 가상 좌석 2,000개
- `distributed` 50·75·100 RPS, 10초, rate별 warm-up 1회 제외 본 측정 3회
- Hikari acquire count/sum/timeout delta와 MariaDB statement digest count/execution/lock time delta를 같은 run에 결합
- raw: `load-test/results/i136-timing-04/` (Git ignore). Runner가 MariaDB diagnostic overlay 적용·`performance_schema=ON`·백엔드 health를 확인한 뒤 실행한다. Performance Schema는 진단 실행에서만 켰고 운영 환경 설정이 아니다.

## 결과

| RPS | Hikari 평균 acquire 대기 중앙값 | acquire timeout | statement 실행 시간 합계 | statement lock 시간 합계 |
| --- | ---: | ---: | ---: | ---: |
| 50 | 52.80ms | 0 | 142,258.75ms | 1,893.82ms |
| 75 | 377.81ms | 0 | 180,780.30ms | 2,019.50ms |
| 100 | 462.39ms | 0 | 185,105.18ms | 2,004.63ms |

모든 본 측정은 예상 밖 실패·deadlock 0이며 Checkout 최종 재고·상태 전이 invariant를 충족했다.

## 해석과 한계

RPS 증가와 함께 Hikari acquire 평균 대기는 증가했으며, #134의 pending 관측과 방향이 일치한다. 그러나 statement 시간 합계는 동시 SQL의 누적값이고 75·100 RPS는 VU cap/dropped 영향으로 완료 request 수도 다르다. 따라서 pool이 단독 병목이거나 특정 SQL이 root cause라는 결론은 낼 수 없다.

이 결과는 pool size 조정·재시도·queue/broker·JVM tuning의 근거가 아니다. 다음 진단은 checkout 경로의 digest별 실행량을 request 도달량으로 정규화하고, 관측자 쿼리를 분리할 수 있을 때만 검토한다. 실제 PG·KOPIS·운영 데이터·운영 SLA는 범위 밖이다.
