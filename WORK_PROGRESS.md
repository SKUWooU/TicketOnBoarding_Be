# 작업 진행 기록

이 파일은 **현재 작업과 다음 한 걸음**만 기록한다. 완료된 Issue별 경과는 [근거 지도](docs/project-improvement/EVIDENCE_MAP.md)와 GitHub Issue/PR에서 찾는다.

| 구분 | 상태 |
| --- | --- |
| 최근 구현·검증 | [BE #210](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/210): SQL 600 transactions의 DB 반영·집계 대조, 예매 경합 1,804 iterations 복귀·실제 MCP 조회. MCP 73 tests·k6 16 tests·probe 11 assertions 통과. |
| 현재 | MCP 분석·실험 연결 단계 종료. 추가 실측·구현 없음. 리뷰·CI·병합 결과는 BE #210의 연결 PR에서 확인. |
| 다음 후보 | 예매 서비스 흐름 학습과 기존 문제 해결 근거 정리. 계측 심화·AI 비교·부하 증량은 선택 과제로 보류. |

실측 수치와 제한은 [실험 요약](docs/project-improvement/EXPERIMENTS.md), 공부 순서는 [학습 가이드](docs/project-improvement/STUDY_GUIDE.md), 보류할 기술은 [BACKLOG](BACKLOG.md)에 둔다. 로컬 fixture 결과를 운영 성능으로 일반화하지 않는다.
