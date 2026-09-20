# Checkout digest 요청 정규화·관측 간섭 분리

## 측정 계약

- 로컬 단일 Spring Boot, Docker MariaDB 10.11.8의 진단 전용 `performance_schema=ON` overlay, mock PG, 가상 좌석 2,000개
- `distributed` 50·75·100 RPS, 10초, rate별 warm-up 1회 제외 본 측정 3회
- MariaDB digest delta는 `onticket_local` 업무 schema로 제한하고, `performance_schema`를 참조하는 관측 statement는 업무 집계에서 제외한다.
- statement count·실행 시간·lock 시간은 k6의 완료 Checkout iteration으로 나눈다. dropped iteration은 완료 흐름이 아니므로 분모에 넣지 않되, scheduled·completed·dropped iteration 수를 함께 기록한다. 한 iteration은 seat hold·Checkout 생성·결제 검증의 복수 HTTP 호출을 포함할 수 있으므로 HTTP 요청당 수치가 아니다.
- raw: `load-test/results/i138-iteration-03/` (Git ignore). runner는 성공·실패 모두 기본 Compose MariaDB와 backend health를 복구한다.

## 결과

| RPS | 완료 iteration 중앙값 | dropped iteration 중앙값 | statement 실행 시간 / 완료 iteration 중앙값 | lock 시간 / 완료 iteration 중앙값 | statement / 완료 iteration 중앙값 |
| --- | ---: | ---: | ---: | ---: | ---: |
| 50 | 483 | 18 | 314.48ms | 4.54ms | 47.64 |
| 75 | 550 | 200 | 322.96ms | 4.57ms | 47.70 |
| 100 | 513 | 487 | 347.47ms | 4.51ms | 47.63 |

모든 본 측정은 예상 밖 실패·deadlock 0이며 Checkout 최종 재고·상태 전이 invariant를 충족했다. 측정 종료 뒤 `performance_schema=OFF`와 backend health `UP`도 확인했다.

## 해석과 한계

완료 Checkout iteration당 statement 수는 약 47.6건으로 안정적이며, 75·100 RPS에서 dropped가 크게 늘어도 완료 iteration당 실행·lock 시간은 비슷한 범위였다. 따라서 기존의 statement 누적 시간 증가를 iteration 수 증가나 VU cap 영향 없이 특정 SQL·DB 단독 병목으로 해석할 수 없다는 한계를 더 명확히 했다.

이 수치는 동시 SQL의 누적 시간이며 HTTP 요청 wall-clock 지연이나 운영 처리량이 아니다. 관측 SQL 제외는 digest text의 `performance_schema` 참조를 대상으로 한 최소 분리이며, 실제 운영 observer 전체를 포괄한다고 주장하지 않는다. pool size 조정·재시도·queue/broker·JVM tuning·운영 SLA와 실제 PG·KOPIS 호출은 범위 밖이다.
