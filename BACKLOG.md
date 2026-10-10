# 개선 BACKLOG

후보는 확정 구현 목록이 아니다. **재현할 문제와 중단 기준이 먼저**이며, RPS 한 단계마다 Issue를 나누지 않는다. 완료된 긴 Phase 목록은 [정리 전 스냅샷](https://github.com/SKUWooU/TicketOnBoarding_Be/blob/53e9b7a7425a7ee961f332ef716e8b05e08484e5/BACKLOG.md)에서 찾는다.

| 우선 | 후보 | 진행 조건 / 제외 범위 |
| --- | --- | --- |
| 1 | 호스트 CPU 미설명분과 계측 부담 확인 | #198에서 전체 호스트 peak와 JVM·MariaDB·k6·Docker/WSL·기타 읽기 가능 프로세스를 교차 관측. 서로 다른 표본 창·읽지 못한 프로세스/커널 CPU 때문에 정확한 합산은 불가. 필요 시 별도 시스템 프로파일러와 계측 전용 기준선으로 재검토. |
| 2 | 다른 로컬 장비의 별도 처리 구간 | 현재 노트북은 16GB RAM(Windows 인식 15.81GiB). 다른 PC에서 재실행하면 CPU·Docker 할당·fixture를 명시하고 새 기준선으로 기록. 서로 다른 호스트의 수치를 개선 전후로 비교하지 않음. |
| 3 | FE/BE 흐름 검증 | 가상 좌석·Hold·Checkout·결제 검증을 실제 로컬 UI/API로 점검. FE는 별도 저장소 Issue/PR. 실제 KOPIS·PG 호출 없이 fixture 사용. |
| 보류 | 대기열·Kafka·outbox·Redis 분산 락·운영 LB | 로컬 Hold 경합만으로 대기열·메시지 브로커를 도입하지 않기로 [ADR-0004](docs/project-improvement/adr/0004-defer-hold-admission-infrastructure.md)에 기록. 유실·독립 재시도·다중 인스턴스 문제를 재현한 뒤 재검토. |
| 보류 | 실제 PG 정산·환불, 운영 데이터 migration | 외부 연동 권한·운영 데이터·복구 계획이 필요한 별도 작업. 로컬 Mock PG와 Flyway fresh DB 검증을 운영 검증으로 확대 해석하지 않음. |

이미 검증한 문제·수치는 [실험 요약](docs/project-improvement/EXPERIMENTS.md), 기술 판단은 [ADR](docs/project-improvement/adr/README.md)에 둔다.
