# 개선 근거 지도

**문제 → 변경 → 검증 → 결과**만 빠르게 찾는 색인이다. 실행 조건·한계는 [실험 요약](EXPERIMENTS.md), 코드의 전체 흐름은 [학습 가이드](STUDY_GUIDE.md)를 먼저 확인한다.

| 문제 / 개념 | 변경과 검증 | 대표 근거 |
| --- | --- | --- |
| 다좌석 예매 부분 commit·잔여 재고 갱신 유실 | 예매 transaction rollback, `seat_amount` 조건부 DB UPDATE, MariaDB Testcontainers 불변식 검증 | [#5](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/5), [초기 재고 기록](archive/foundations/) |
| 복수 좌석 잠금 순서·deadlock | 좌석 번호 canonical ordering, 겹치는 좌석 묶음 반복 경합 | [#11](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/11), [반복 검증 기록](archive/foundations/) |
| 잠금 조회의 넓은 탐색·pool 대기 | `(concert_time_id, seat_number)` unique index, 동일 fixture A/B·k6·Performance Schema | p95 **3,064.39→143.41ms**, Hikari pending peak **189→0** ([상세 A/B](archive/load-testing/seat-composite-index-high-contention-ab.md), [#59](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/59)) |
| 예약 재시도·결제 검증 중복 | Idempotency-Key·fingerprint·DB unique; Checkout claim·PG I/O transaction 분리, Mock PG 지연 검증 | [#35](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/35), [#82](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/82), [checkout 기록](archive/checkout/) |
| 인기 좌석 Hold의 정상 충돌과 시스템 포화 구분 | 20석 5/10/20 RPS 9-run, 20/30 RPS 6단계 교차 반복·호스트 자원, 2,000석 별도 50/100 RPS 6-run | [#186](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/186), [#196](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/196), [실험 요약](EXPERIMENTS.md) |
| AI 분석의 근거 범위 | read-only MCP로 허용된 요약·불변식·동일 조건 판정만 제공 | [#188](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/188), [MCP 사용법](../../tools/ticketon-evidence-mcp/README.md) |
| 다중 인스턴스·DB schema 변경 경계 | 로컬 2-instance 상태 수렴 smoke; Flyway fresh/baseline 검증 | [#150](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/150), [#152](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/152). 운영 확장성·기존 DB 무중단 migration 증명 아님. |

위 수치는 **가상 좌석 fixture와 제한된 로컬 환경**의 결과다. 0건은 해당 반복에서 관측하지 않았다는 뜻이며, 실제 예매처의 운영 처리량이나 모든 경합 조건의 안전성을 뜻하지 않는다. 과거 Issue별 긴 기록은 [아카이브](archive/)와 [정리 전 스냅샷](https://github.com/SKUWooU/TicketOnBoarding_Be/tree/53e9b7a7425a7ee961f332ef716e8b05e08484e5/docs/project-improvement)에 보존된다.
