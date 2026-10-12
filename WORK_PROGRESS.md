# 작업 진행 기록

이 파일은 **현재 작업과 다음 한 걸음**만 기록한다. 완료된 Issue별 경과는 [근거 지도](docs/project-improvement/EVIDENCE_MAP.md)와 GitHub Issue/PR에서 찾는다.

| 구분 | 상태 |
| --- | --- |
| 최근 구현·검증 | [BE #206](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/206): offline 실험 제안 계약과 수작업 평가 fixture. 오인용·원인 단정·조건 변경·자동 실행 거절 사례 포함. MCP 69 tests·k6 계약 16 tests 통과. |
| 현재 | #206 구현·평가 사례 검증 완료. 최신 HEAD의 CI·Reviewer·병합 상태는 Issue에 연결된 PR에서 확인. |
| 다음 후보 | 실제 AI 제안과 사람 기준 방식의 비교 여부 결정. 모델·호출 예산·자연어 평가·사람 검토 필요. 현 단계는 출력 계약 검증이며 AI 효용 입증 아님. |

실측 수치와 제한은 [실험 요약](docs/project-improvement/EXPERIMENTS.md), 공부 순서는 [학습 가이드](docs/project-improvement/STUDY_GUIDE.md), 보류할 기술은 [BACKLOG](BACKLOG.md)에 둔다. 로컬 fixture 결과를 운영 성능으로 일반화하지 않는다.
