# 좌석 hold 도메인 메트릭 시나리오 검증 gate

## 문제와 목표

Issue #91의 단일 hot-seat smoke는 HTTP 응답 수와 commit 이후 hold metric의 일치만 확인했다. `distributed`, `hot-section`, `hot-seat`는 서로 다른 정상 상태를 만든다. 성공 응답 수만 비교하면 한 좌석의 중복 획득이나 예상 밖의 재점유를 구분하기 어렵다.

이 문서는 가상 2,000석 fixture에서 k6 결과, 최종 DB snapshot, `/actuator/prometheus`의 commit 이후 metric delta를 함께 판단하는 기준을 정의한다. 실제 공연 예매처의 성능이나 운영 SLA를 측정하는 문서가 아니다.

## 시나리오 계약

| 시나리오 | k6 좌석 선택 | 기대 DB 상태 | commit 이후 transition 계약 |
| --- | --- | --- | --- |
| `distributed` | 2,000석을 순환 | 성공 요청 수만큼 서로 다른 `HELD` | `acquired = success`, `reused = reclaimed = 0` |
| `hot-section` | 첫 번째 구역 40석을 순환 | 최대 40개의 `HELD` | 각 좌석은 최초 한 번만 `acquired`, 같은 소유자 재요청만 `reused` 가능 |
| `hot-seat` | `R001-S001` 한 좌석 | 정확히 1개의 `HELD` | 최초 한 번만 `acquired`, 같은 소유자 재요청만 `reused`, `reclaimed = 0` |

공통 계약은 `success`/예상 `409 conflict`의 k6·서버 Timer 일치, `invalid`/`error` delta 0, `acquired + reused + reclaimed = success`다. 또한 예약·결제 row와 `partialHoldStates`는 0이고 물리 좌석·잔여 좌석 수는 변하지 않아야 한다.

## 만료·rollback 경계

5분 TTL을 부하 실행 중 대기시켜 재현하지 않는다. 실행 시간이 길고 스케줄링 편차가 커지기 때문이다. 대신 `SeatHoldIntegrationTest`의 고정 Clock·MariaDB Testcontainers fixture가 만료 경계의 단일 `reclaimed`, 복수 좌석 rollback, 바깥 transaction rollback 시 `error`만 기록되는지를 결정적으로 검증한다.

따라서 부하 gate는 활성 hold 경쟁을, 통합 테스트는 시간 경계와 rollback을 담당한다. 둘을 하나의 TPS 또는 운영 성능 주장으로 합치지 않는다.

## 실행 방법

Backend와 Docker MariaDB가 로컬에서 실행 중일 때 다음처럼 실행한다.

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\load-test\scripts\Measure-SeatHoldContention.ps1 `
  -Scenario hot-seat -Rate 100 -DurationSeconds 10 `
  -RunId issue106-hot-seat-100-a -FixtureRunId issue106-hot-seat-fixture
```

측정 스크립트는 fixture를 초기화하고 실행 전후 Prometheus metric·MariaDB 상태를 수집한다. 결과 JSON·CSV·k6 표준 출력은 `load-test/results` 아래 새 run ID로만 기록하며 기존 결과를 덮어쓰지 않는다.

## 로컬 실행 결과

2026-09-15 KST에 Java 21·Spring Boot 3.2.5 단일 Backend, Docker MariaDB 10.11.8, 가상 2,000석 fixture에서 각 100 RPS·10초를 실행했다. 외부 KOPIS·PG·SMS 호출은 없었다.

| 시나리오 | 완료/유실 | 성공·예상 409 | p95 | 최종 HELD | transition delta |
| --- | --- | --- | --- | --- | --- |
| `distributed` | 996 / 5 | 996 / 0 | 74.44ms | 996 | acquired 996, reused 0, reclaimed 0 |
| `hot-section` | 1,001 / 0 | 41 / 960 | 51.45ms | 40 | acquired 40, reused 1, reclaimed 0 |
| `hot-seat` | 1,001 / 0 | 3 / 998 | 26.60ms | 1 | acquired 1, reused 2, reclaimed 0 |

세 실행 모두 `invalid = error = 0`, 예약·결제 row 0, `partialHoldStates = 0`, 물리 좌석·잔여 좌석 2,000을 유지했다. 특히 hot 시나리오의 추가 성공은 동일 소유자의 재요청만 `reused`로 수렴했고, 다른 요청은 409로 분류됐다. 이 결과는 계약 검증이며 이전 index A/B 성능 개선 수치나 운영 처리량 주장이 아니다.

## 한계와 후속 판단

- 단일 로컬 Backend·MariaDB·가상 좌석 fixture 결과다. 다중 인스턴스, scrape 유실, 장기 추세, 실제 사용자 분포를 검증하지 않는다.
- Prometheus/Grafana 서버와 대시보드는 아직 도입하지 않는다. 먼저 반복 가능한 시나리오와 metric 계약이 있는지를 확인한다.
- Checkout 취소·결제 검증은 결정적 통합 테스트로 정합성을 검증하지만, Checkout 전용 metric은 아직 없다. 관측 질문이 생길 때 별도 Issue에서 계측 여부를 판단한다.
