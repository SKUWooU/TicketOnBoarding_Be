# 작업 진행 기록

이 파일은 **현재 작업과 다음 한 걸음**만 기록한다. 완료된 Issue별 경과는 [근거 지도](docs/project-improvement/EVIDENCE_MAP.md)와 GitHub Issue/PR에서 찾는다.

| 구분 | 상태 |
| --- | --- |
| 최근 수정·검증 | [BE #226](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/226): 메모리 기준 통과 후 재개, 작은 요청 warm-up 50번 성공·해제/오류 0. CPU 90.75% gate 중단, 큰 요청·측정 단계 0. |
| 현재 | 재시도 원 JSON·회귀 테스트·실험 결론 보존. 서비스 변경·200석 증량·gate 완화 없음. 최신 리뷰·CI·병합 상태는 #226 연결 PR에서 확인. |
| 다음 후보 | 무부하 자원 기준선과 환경 안정화 확인 후 20석 혼합 비교 재개. 요청 상한은 근거 확보 전 보류. 안전한 계정 복구는 별도 BE/FE 계약. |

실측 수치와 제한은 [실험 요약](docs/project-improvement/EXPERIMENTS.md), 공부 순서는 [학습 가이드](docs/project-improvement/STUDY_GUIDE.md), 보류할 기술은 [BACKLOG](BACKLOG.md)에 둔다. 로컬 fixture 결과를 운영 성능으로 일반화하지 않는다.
