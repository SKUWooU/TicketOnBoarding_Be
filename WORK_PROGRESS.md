# 작업 진행 기록

이 파일은 **현재 작업과 다음 한 걸음**만 기록한다. 완료된 Issue별 경과는 [근거 지도](docs/project-improvement/EVIDENCE_MAP.md)와 GitHub Issue/PR에서 찾는다.

| 구분 | 상태 |
| --- | --- |
| 최근 구현·검증 | [BE #204](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/204): schema 6 전용 MCP 판정 연결. 완료 측정 6단계만 반환, 미완료·조건 모순·게이트 위반 수치 차단. MCP 47 tests·k6 계약 16 tests 통과. |
| 현재 | #204 구현·stdio 검증 완료. 최신 HEAD의 CI·Reviewer·병합 상태는 Issue에 연결된 PR에서 확인. |
| 다음 후보 | 검증된 MCP 근거로 병목 가설·다음 실험 제안의 실제 효용과 평가 기준 검토(ADR-0005). 상위 부하·외부 AI 호출은 별도 판단. |

실측 수치와 제한은 [실험 요약](docs/project-improvement/EXPERIMENTS.md), 공부 순서는 [학습 가이드](docs/project-improvement/STUDY_GUIDE.md), 보류할 기술은 [BACKLOG](BACKLOG.md)에 둔다. 로컬 fixture 결과를 운영 성능으로 일반화하지 않는다.
