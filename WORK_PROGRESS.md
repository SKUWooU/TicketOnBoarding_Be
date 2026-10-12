# 작업 진행 기록

이 파일은 **현재 작업과 다음 한 걸음**만 기록한다. 완료된 Issue별 경과는 [근거 지도](docs/project-improvement/EVIDENCE_MAP.md)와 GitHub Issue/PR에서 찾는다.

| 구분 | 상태 |
| --- | --- |
| 최근 구현·검증 | [BE #200](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/200) / [PR #201](https://github.com/SKUWooU/TicketOnBoarding_Be/pull/201): 인기 1석/10석 대비 1,206건·예상 밖 오류 0, 자원 재검토 신호 유지. ADR-0005와 실패·완료 근거 보존. |
| 현재 | 후속 구현은 미착수. #200의 최신 HEAD·CI·Reviewer·병합 상태는 연결된 PR #201에서 확인. |
| 다음 후보 | DB wait의 반복성과 호스트·계측 간섭을 구분하는 후속 검증. 상위 부하 증량과 AI/RAG 구현은 별도 조건 충족 후 검토. |

실측 수치와 제한은 [실험 요약](docs/project-improvement/EXPERIMENTS.md), 공부 순서는 [학습 가이드](docs/project-improvement/STUDY_GUIDE.md), 보류할 기술은 [BACKLOG](BACKLOG.md)에 둔다. 로컬 fixture 결과를 운영 성능으로 일반화하지 않는다.
