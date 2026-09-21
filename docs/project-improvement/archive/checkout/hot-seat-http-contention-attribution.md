# Hot-seat 409 HTTP 흐름 귀속 검증

## 문제

성공 Checkout iteration의 HTTP 200 수만 비교하면, 동일 좌석 경합에서 발생한 `409`가 seat hold 단계에서 정상 종료됐는지, 또는 준비·결제 검증 단계까지 잘못 전파됐는지 구분할 수 없다.

## 계약

- 표준 Actuator `http_server_requests_seconds_count`에서 URI template·`POST` method·HTTP status로 endpoint delta를 수집한다. 사용자·좌석·merchantUid tag는 추가하지 않는다.
- `hot-seat` run에서 `seat_hold|409` delta와 모든 seat-hold non-200 delta는 각각 k6 `expectedContention`과 같아야 한다.
- `seat-hold|200`, `checkouts|200`, `verified-reservation|200` delta는 각각 `checkoutConfirmed`와 같아야 한다.
- Checkout 준비·결제 검증 endpoint의 non-200 delta는 0이어야 한다. 즉 경합 요청은 hold 단계 이후로 진행하지 않는다.
- 위 HTTP 계약과 k6 완료 iteration 분류, 최종 좌석·예약·booking·payment snapshot, commit 상태 전이, deadlock delta를 함께 확인한다.

## 로컬 fixture 결과

Docker Compose MariaDB·local Mock PG·가상 좌석 2,000개에서 hot-seat 100 RPS·10초를 실행했다. 이 결과는 단일 로컬 fixture의 흐름 검증이며 실제 예매처 처리량이나 운영 오류율을 뜻하지 않는다.

| 항목 | 결과 |
| --- | ---: |
| 완료 / dropped iteration | 1,001 / 0 |
| `checkoutConfirmed` / `expectedContention` / 예상 밖 실패 | 1 / 1,000 / 0 |
| seat hold HTTP 200 / 409 / 전체 non-200 delta | 1 / 1,000 / 1,000 |
| checkout prepare HTTP 200 / non-200 delta | 1 / 0 |
| payment verify HTTP 200 / non-200 delta | 1 / 0 |
| 최종 reserved / reservation / booking / payment | 1 / 1 / 1 / 1 |
| DB deadlock / Hikari pending peak | 0 / 0 |

따라서 이 조건에서는 1,000개의 의도된 경합이 모두 hold의 `409`으로 귀속됐고, 확정된 한 흐름만 준비·Mock PG 검증 단계에 진입했다. 최종 재고 invariant도 충족했다.

## 검증

- `Test-ContentionMetrics.ps1`: 76 assertions
- Backend Gradle test: 성공
- 측정 결과: `load-test/results/i142-hot-seat-100-03-summary.json`

## 한계

HTTP endpoint counter는 transaction commit·DB statement·운영 SLA와 동의어가 아니다. 이 검증은 고정 hot-seat와 local 단일 인스턴스·Mock PG에 한정하며, retry, 대기열, broker, 실제 PG·KOPIS 호출, 다중 인스턴스 공정성은 도입·주장하지 않는다.
