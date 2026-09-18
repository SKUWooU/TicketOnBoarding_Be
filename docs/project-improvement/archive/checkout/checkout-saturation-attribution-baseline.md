# Checkout 고경합 포화 원인 분리 관측 기준선

## 질문

Issue #132의 Checkout p95·Hikari pending·dropped iteration이 k6 VU 상한, 애플리케이션 connection pool, MariaDB lock 경합 중 어디에서 비롯되는지 단일 수치로 단정하지 않고 분리 관찰한다.

## 관측 계약

- 환경: 로컬 단일 Spring Boot 인스턴스, Docker Compose MariaDB 10.11.8, mock PG, 가상 좌석 2,000개
- 부하: `distributed`, 10초, `preAllocatedVUs=100`, `maxVUs=200`
- 단계: 50·75·100 RPS마다 warm-up 1회 후 본 측정 3회. warm-up은 집계에서 제외한다.
- run 결합: k6 iteration/dropped/VU gauge, Checkout 최종 snapshot·commit transition, Hikari, JVM, MariaDB row-lock/thread status
- raw 결과: `load-test/results/i134-attribution-02/` (Git ignore)

`vu-cap-reached-attribution-limited`는 k6가 설정한 VU 상한까지 확장했고 dropped iteration이 있었다는 뜻이다. 이는 서버가 느려 VU가 늘어난 결과일 수 있으므로, **부하 생성기 자체가 원인이라는 결론이 아니다.** 반대로 `pool-and-db-lock-signals`도 두 신호가 같이 있었다는 분류이지 단일 root cause 판정이 아니다.

## 본 측정 결과

| 단계 | 발행 도달률 중앙값 | p95 중앙값 | VU/설정 상한 | Hikari pending 중앙값 | DB lock wait/time 중앙값 | deadlock | 관측 분류 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| 50 RPS | 100.0% | 2,017.0ms | 90 / 200 | 65 | 487 / 130,656ms | 0 | pool-and-db-lock-signals (3/3) |
| 75 RPS | 67.1% | 6,338.5ms | 200 / 200 | 173 | 499 / 155,024ms | 0 | vu-cap-reached-attribution-limited (3/3) |
| 100 RPS | 51.6% | 6,571.6ms | 200 / 200 | 175 | 516 / 174,586ms | 0 | vu-cap-reached-attribution-limited (3/3) |

모든 본 측정에서 예상 밖 비성공은 0이고, `checkoutConfirmed`와 reserved seat·Reservation·Booking·Payment·commit transition의 최종 invariant가 충족됐다.

## 해석

1. 50 RPS에서 VU 상한에 닿지 않았는데도 Hikari pending과 DB lock wait/time이 같이 관찰됐다. 따라서 75·100 RPS의 dropped iteration을 k6 generator 한계만으로 설명할 수 없다.
2. 75·100 RPS는 VU 200 상한까지 도달했으므로, 현재 10초 local run만으로 pool 대기와 DB lock의 기여도를 분리하거나 서버 최대 처리량을 주장할 수 없다.
3. 모든 단계의 deadlock delta는 0이다. 이는 #128 변경 뒤 deadlock 회귀 관찰과 일치하지만, p95 개선 또는 운영 안전성 보장은 아니다.

## 다음 판단 경계

다음 후보는 VU cap을 충분히 높인 비교가 아니라, 먼저 동일 조건에서 pool 대기 시간과 DB query/lock 대기 시간을 더 직접적으로 상관시킬 수 있는 최소 진단이다. 단일 인스턴스·공유 host의 collector 지연과 MariaDB CLI 관측자 효과가 있으므로, pool size 변경·retry·대기열·Kafka·Grafana·JVM tuning은 이 결과만으로 도입하지 않는다. 실제 PG·KOPIS·운영 좌석·운영 SLA는 범위 밖이다.
