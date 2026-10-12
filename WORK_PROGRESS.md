# 작업 진행 기록

이 파일은 **현재 작업과 다음 한 걸음**만 기록한다. 완료된 Issue별 경과는 [근거 지도](docs/project-improvement/EVIDENCE_MAP.md)와 GitHub Issue/PR에서 찾는다.

| 구분 | 상태 |
| --- | --- |
| 최근 구현·검증 | [BE #208](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/208): MCP·Codex 실사례 기반 SQL 진단. 두 실측은 첫 warm-up에서 집계 횟수 불일치로 중단·보존. MCP 71 tests·k6 16 tests·SQL 계약 12 assertions 통과. |
| 현재 | 추가 실측 중단. 코드 검토·CI·병합 결과는 [PR #209](https://github.com/SKUWooU/TicketOnBoarding_Be/pull/209)에서 확인. 원인 미확정·증량 보류. |
| 다음 후보 | 고정 SQL 실행 수의 직렬/병렬 대조로 계측 누락 원인 분리. 부하 증량·AI 효용 주장은 보류. |

실측 수치와 제한은 [실험 요약](docs/project-improvement/EXPERIMENTS.md), 공부 순서는 [학습 가이드](docs/project-improvement/STUDY_GUIDE.md), 보류할 기술은 [BACKLOG](BACKLOG.md)에 둔다. 로컬 fixture 결과를 운영 성능으로 일반화하지 않는다.
