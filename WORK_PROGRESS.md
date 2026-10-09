# 작업 진행 기록

이 파일은 **현재 작업과 다음 한 걸음**만 기록한다. 완료된 Issue별 경과는 [근거 지도](docs/project-improvement/EVIDENCE_MAP.md)와 GitHub Issue/PR에서 찾는다.

| 구분 | 상태 |
| --- | --- |
| 마지막 병합 | [BE #194](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/194) / [PR #195](https://github.com/SKUWooU/TicketOnBoarding_Be/pull/195), `e56b27c`: 단일 20→30 RPS 탐색의 MCP 재측정·중단 게이트. |
| 현재 | [BE #196](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/196): 가상 20석 20/30 RPS 6단계 교차 반복 완료, MCP 자원 판정·검증 진행 중. |
| 다음 후보 | 한 20 RPS 단계의 호스트 CPU peak 91.5% 원인 분리. 상위 RPS 증량·운영 용량 주장은 보류. |

실측 수치와 제한은 [실험 요약](docs/project-improvement/EXPERIMENTS.md), 공부 순서는 [학습 가이드](docs/project-improvement/STUDY_GUIDE.md), 보류할 기술은 [BACKLOG](BACKLOG.md)에 둔다. 로컬 fixture 결과를 운영 성능으로 일반화하지 않는다.
