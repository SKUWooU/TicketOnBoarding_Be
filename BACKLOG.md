# 개선 BACKLOG

후보는 확정 구현 목록이 아니다. **재현할 문제와 중단 기준이 먼저**이며, RPS 한 단계마다 Issue를 나누지 않는다. 완료된 긴 Phase 목록은 [정리 전 스냅샷](https://github.com/SKUWooU/TicketOnBoarding_Be/blob/53e9b7a7425a7ee961f332ef716e8b05e08484e5/BACKLOG.md)에서 찾는다.

| 우선 | 후보 | 진행 조건 / 제외 범위 |
| --- | --- | --- |
| 마무리 | MCP 분석·실험 연결 단계 | #210에서 제한된 SQL 대조와 기존 예매 경합 1회 복귀·실제 MCP 조회 완료. 직렬/병렬 집계 차이는 실제 DB 반영과 구분. 추가 계측·AI 비교·증량은 필수 후속 아님. |
| 다음 | 예매 서비스 흐름 학습·기존 근거 정리 | STUDY_GUIDE의 좌석 잠금→Hold→Checkout→PG 검증→예약 흐름으로 복귀. 새 구현은 재현 가능한 서비스 문제와 범위가 정해졌을 때 선택. |
| 보류 | 실제 AI 제안과 사람 기준 방식 비교 | #206 계약과 #208 단일 비맹검 사례 확보. 모델 품질·시간 절감 비교는 미실시. 모델·호출 예산·자료 범위 합의 필요. RAG·상위 부하 자동 실행 제외. |
| 선택 | 호스트 CPU·SQL 계측 심화 | #198 CPU 귀속·#208 JDBC 집계 원인은 미확정. #210 CLI 병렬 집계 부족 재현으로 현 단계 종료. 정확한 내부 원인·시스템 프로파일러·드라이버 비교는 필요성이 생길 때만 별도 검토. |
| 3 | 다른 로컬 장비의 별도 처리 구간 | 현재 노트북은 16GB RAM(Windows 인식 15.81GiB). 다른 PC에서 재실행하면 CPU·Docker 할당·fixture를 명시하고 새 기준선으로 기록. 서로 다른 호스트의 수치를 개선 전후로 비교하지 않음. |
| 4 | FE/BE 흐름 검증 | 가상 좌석·Hold·Checkout·결제 검증을 실제 로컬 UI/API로 점검. FE는 별도 저장소 Issue/PR. 실제 KOPIS·PG 호출 없이 fixture 사용. |
| 보류 | 대기열·Kafka·outbox·Redis 분산 락·운영 LB | 로컬 Hold 경합만으로 대기열·메시지 브로커를 도입하지 않기로 [ADR-0004](docs/project-improvement/adr/0004-defer-hold-admission-infrastructure.md)에 기록. 유실·독립 재시도·다중 인스턴스 문제를 재현한 뒤 재검토. |
| 보류 | 실제 PG 정산·환불, 운영 데이터 migration | 외부 연동 권한·운영 데이터·복구 계획이 필요한 별도 작업. 로컬 Mock PG와 Flyway fresh DB 검증을 운영 검증으로 확대 해석하지 않음. |
| 후속 후보 | AI의 근거 기반 병목 가설·실험 제안 | [ADR-0005](docs/project-improvement/adr/0005-evidence-grounded-experiment-assistance.md): 검증된 MCP 지표 + 관련 문서 근거로 가설·검증 계획 제안. 반복 분석의 실제 부담과 평가 사례 확보 후 검토. RAG는 기존 검색의 한계가 있을 때만 검토. AI의 자동 부하·DB·결제 변경 제외. |

이미 검증한 문제·수치는 [실험 요약](docs/project-improvement/EXPERIMENTS.md), 기술 판단은 [ADR](docs/project-improvement/adr/README.md)에 둔다.
