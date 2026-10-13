# 개선 근거 지도

**문제 → 변경 → 검증 → 결과**만 빠르게 찾는 색인이다. 실행 조건·한계는 [실험 요약](EXPERIMENTS.md), 코드의 전체 흐름은 [학습 가이드](STUDY_GUIDE.md)를 먼저 확인한다.

5개 주제의 전후·코드·검증·한계는 [학습 가이드 7절](STUDY_GUIDE.md#7-다섯-개선-주제의-전후-흐름)에 통합했다 ([#212](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/212)). 성능 A/B, 정합성 반복 검증, Mock PG 경계, MCP 판정을 같은 종류의 성과로 섞지 않는다.

| 문제 / 개념 | 변경과 검증 | 대표 근거 |
| --- | --- | --- |
| 다좌석 예매 부분 commit·잔여 재고 갱신 유실 | 예매 transaction rollback, `seat_amount` 조건부 DB UPDATE, MariaDB Testcontainers 불변식 검증 | [#5](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/5), [초기 재고 기록](archive/foundations/) |
| 복수 좌석 잠금 순서·deadlock | 좌석 문자열 canonical ordering, 겹치는 좌석 묶음 반복 경합 | [#11](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/11), [indexed 재현](archive/foundations/seat-composite-index-deadlock-comparison.md), [50회·400회 시도](archive/foundations/overlapping-multi-seat-write-contention.md) |
| 잠금 조회의 넓은 탐색·pool 대기 | `(concert_time_id, seat_number)` unique index, 동일 fixture A/B·k6·Performance Schema | p95 **3,064.39→143.41ms**, Hikari pending peak **189→0** ([상세 A/B](archive/load-testing/seat-composite-index-high-contention-ab.md), [#59](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/59)) |
| 예약 재시도 중복 | Idempotency-Key·fingerprint·DB unique, 동일 요청 결과 재사용 | [#35](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/35), [400회 시도·50건 생성·350회 재사용](archive/foundations/repeated-idempotency-contention.md) |
| 결제 검증 중복·지연 | Checkout claim·PG I/O transaction 분리, Mock PG 지연 검증 | [#82](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/82), [외부 검증 1회·확정 1건](archive/foundations/payment-verification-io-boundary.md), [checkout 기록](archive/checkout/) |
| 인기 좌석 Hold의 정상 충돌과 시스템 포화 구분 | 20석 5/10/20 RPS 9-run, 20/30 RPS 6단계 교차 반복·호스트 자원, 2,000석 별도 50/100 RPS 6-run | [#186](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/186), [#196](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/196), [실험 요약](EXPERIMENTS.md) |
| 호스트 CPU peak의 원인 단정 방지 | 같은 가상 20석 20/30 RPS 반복에서 JVM·MariaDB PID 1·k6 CPU와 Windows 프로세스군을 별도로 계측. 표본 창 차이와 미수집 CPU를 명시 | [#198](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/198), [실험 요약](EXPERIMENTS.md), [ADR-0004](adr/0004-defer-hold-admission-infrastructure.md) |
| 좌석 충돌과 서버 병목의 구분 | 같은 20 iterations/s에서 인기 1석/10석 교차 반복. 1,206건 중 정상 409 589, 예상 밖 오류·pending 0; 호스트 자원 재검토 필요 | [#200](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/200), [실험 요약](EXPERIMENTS.md#5-동일-유입량에서-정상-충돌과-시스템-대기-구분), [ADR-0005](adr/0005-evidence-grounded-experiment-assistance.md) |
| AI 분석의 근거 범위 | read-only MCP로 허용된 요약·불변식·동일 조건 판정만 제공 | [#188](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/188), [MCP 사용법](../../tools/ticketon-evidence-mcp/README.md) |
| 계측 교차 결과의 재사용 계약 | schema 6 전용 MCP 판정: 완료·순서·재고·수집 비용 검증 후 측정 6단계만 반환. 미완료 2배치와 변조는 수치 차단 | [#204](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/204), [MCP 사용법](../../tools/ticketon-evidence-mcp/README.md) |
| 근거→가설→실험 제안의 평가 경계 | 실제 MCP 보고서와 수작업 후보를 대조하는 offline 계약. 오인용·확정 단정·조건 변경·자동 실행은 거절. LLM 품질/효용 검증 아님 | [#206](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/206), [ADR-0005](adr/0005-evidence-grounded-experiment-assistance.md) |
| MCP 실사례→SQL 진단 실험 | 실제 MCP 조회·Codex 제안·계약 검증 후 전용 DB 실측. 기대/집계 횟수 불일치로 두 시도 중단, 원인 확정·증량 제외 | [#208](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/208), [실측·한계](EXPERIMENTS.md#7-mcp-근거에서-선택한-sql-진단-208) |
| MCP 단계 종료·업무 결과와 집계 구분 | 독립 SQL 600 transactions의 DB 반영 일치, 병렬 digest 집계 부족 재현. 예매 경합 1,804건 복귀 후 실제 MCP 자원 재검토 판정 | [#210](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/210), [대조·복귀·한계](EXPERIMENTS.md#8-제한된-sql-대조와-예매-경합-복귀-210) |
| DB 대기 반복성과 계측 부담 구분 | 같은 인기 1석·20 iterations/s에서 1초/2초 수집 교차 3회. 1,804건·예상 밖 오류 0; 실패 배치도 보존, p95 개선·CPU 귀속 단정 제외 | [#202](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/202), [계측 비교](EXPERIMENTS.md#6-같은-경합에서-계측-빈도-교차-검증) |
| 다중 인스턴스·DB schema 변경 경계 | 로컬 2-instance 상태 수렴 smoke; Flyway fresh/baseline 검증 | [#150](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/150), [#152](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/152). 운영 확장성·기존 DB 무중단 migration 증명 아님. |

위 수치는 **가상 좌석 fixture와 제한된 로컬 환경**의 결과다. 0건은 해당 반복에서 관측하지 않았다는 뜻이며, 실제 예매처의 운영 처리량이나 모든 경합 조건의 안전성을 뜻하지 않는다. 과거 Issue별 긴 기록은 [아카이브](archive/)와 [정리 전 스냅샷](https://github.com/SKUWooU/TicketOnBoarding_Be/tree/53e9b7a7425a7ee961f332ef716e8b05e08484e5/docs/project-improvement)에 보존된다.
