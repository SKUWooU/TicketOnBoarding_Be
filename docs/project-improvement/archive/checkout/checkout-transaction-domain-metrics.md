# Checkout transaction 완료 기준 도메인 메트릭

## 질문

좌석 hold는 요청 결과와 commit 이후 전이를 함께 관측하지만, Checkout 취소·결제 검증은 HTTP 응답이나 DB fixture만으로 확인했다. 고경합에서 `CANCELED`, `RESERVATION_CONFIRMED`, `PAYMENT_VERIFICATION_UNKNOWN` 중 어느 상태가 실제 commit됐는지를 부하 결과와 연결할 수 없었다.

## 계약

기존 Micrometer registry에 두 metric을 추가한다.

| Metric | 태그 | 의미 |
| --- | --- | --- |
| `onticket.checkout.transaction` | `operation`, `outcome` | transaction 완료 결과별 지연 시간 |
| `onticket.checkout.transitions` | `operation`, `transition` | commit된 Checkout 상태 전이 수 |

`operation`은 `cancel`, `verify_claim`, `verify_finalize`로 고정한다. `merchantUid`, 사용자, 좌석 번호, run ID처럼 metric cardinality를 늘리는 값은 태그로 사용하지 않는다.

일반 성공 전이는 commit일 때만 counter에 기록한다. `UNKNOWN`은 호출자에게 예외를 반환하지만 `noRollbackFor` 경로에서 상태가 commit될 수 있으므로, commit이 확인된 경우에만 `verification_unknown` 전이로 기록한다. rollback된 취소·검증 전이는 counter에 남지 않는다.

## 검증

- 단위 테스트는 rollback 뒤 transition counter가 없고, `UNKNOWN` commit 뒤에는 counter가 1인지 검증한다.
- 기존 MariaDB Testcontainers Checkout fixture는 성공 승인에서 `verification_claimed=1`, `reservation_confirmed=1`; 두 승인 불일치에서 `verification_unknown=2`를 검증한다.
- 취소 fixture는 최초 취소와 멱등 재취소를 각각 `canceled=1`, `cancel_reused=1`로 검증한다.

로컬 Docker daemon이 꺼져 있어 Testcontainers 통합 검증은 GitHub Actions에서 확인한다. 실제 PG·금전 이동·운영 트래픽은 호출하거나 주장하지 않는다.

## 제외

Prometheus server, Grafana dashboard, 알림 규칙, Kafka, 실제 PG 및 운영 SLA는 도입하지 않는다. 이 작업은 관측 계약이지 성능 개선 수치가 아니다.
