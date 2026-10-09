# 작업 진행 기록

이 파일은 **현재 작업과 다음 한 걸음**만 기록한다. 완료된 Issue별 경과는 [근거 지도](docs/project-improvement/EVIDENCE_MAP.md)와 GitHub Issue/PR에서 찾는다.

| 구분 | 상태 |
| --- | --- |
| 마지막 병합 | [BE #192](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/192) / [PR #193](https://github.com/SKUWooU/TicketOnBoarding_Be/pull/193), `463d953`: 개선 문서를 탑다운 학습 흐름으로 통합. |
| 현재 | [BE #194](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/194): 가상 20석 20→30 RPS 단일 탐색의 MCP 재측정·중단 판정. Docker 엔진 비활성으로 새 실측은 범위 밖. |
| 다음 후보 | 동일 가상 20석 조건에서 30 RPS 반복·교차 측정과 CPU/메모리·DB 대기 관측. 자원 한계가 먼저 나오면 16GB PC에서 새 기준선 수립. |

실측 수치와 제한은 [실험 요약](docs/project-improvement/EXPERIMENTS.md), 공부 순서는 [학습 가이드](docs/project-improvement/STUDY_GUIDE.md), 보류할 기술은 [BACKLOG](BACKLOG.md)에 둔다. 로컬 fixture 결과를 운영 성능으로 일반화하지 않는다.
