# 개선 BACKLOG

후보는 확정 구현 목록이 아니다. **재현할 문제와 중단 기준이 먼저**이며, RPS 한 단계마다 Issue를 나누지 않는다. 완료된 긴 Phase 목록은 [정리 전 스냅샷](https://github.com/SKUWooU/TicketOnBoarding_Be/blob/53e9b7a7425a7ee961f332ef716e8b05e08484e5/BACKLOG.md)에서 찾는다.

| 우선 | 후보 | 진행 조건 / 제외 범위 |
| --- | --- | --- |
| 1 | 가상 20석 인기 좌석 Hold의 30 RPS 반복 검증 | 동일 fixture·반복/교차 순서, 완료·409·오류·Hikari/DB wait·CPU/메모리·종료 재고를 묶어 확인. 불변식 위반·dropped·자원 포화 시 증량 중단. 단일 탐색을 안정 구간으로 부르지 않음. |
| 2 | 다른 로컬 장비의 별도 처리 구간 | 8GB 노트북 한계가 확인되면 16GB PC에서 환경을 명시하고 새 기준선 측정. 서로 다른 호스트의 수치를 개선 전후로 비교하지 않음. |
| 3 | FE/BE 흐름 검증 | 가상 좌석·Hold·Checkout·결제 검증을 실제 로컬 UI/API로 점검. FE는 별도 저장소 Issue/PR. 실제 KOPIS·PG 호출 없이 fixture 사용. |
| 보류 | 대기열·Kafka·outbox·Redis 분산 락·운영 LB | 단순 DB 잠금과 현재 경합 실험의 한계, 유실·독립 재시도·다중 인스턴스 문제를 재현한 뒤 ADR로 도입 판단. |
| 보류 | 실제 PG 정산·환불, 운영 데이터 migration | 외부 연동 권한·운영 데이터·복구 계획이 필요한 별도 작업. 로컬 Mock PG와 Flyway fresh DB 검증을 운영 검증으로 확대 해석하지 않음. |

이미 검증한 문제·수치는 [실험 요약](docs/project-improvement/EXPERIMENTS.md), 기술 판단은 [ADR](docs/project-improvement/adr/README.md)에 둔다.
