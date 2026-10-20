# 작업 진행 기록

이 파일은 **현재 작업과 다음 한 걸음**만 기록한다. 완료된 Issue별 경과는 [근거 지도](docs/project-improvement/EVIDENCE_MAP.md)와 GitHub Issue/PR에서 찾는다.

| 구분 | 상태 |
| --- | --- |
| 최근 정리 | #224·#226 혼합 실험은 도구·원자료 보존, 성능 비교 미완료·상한 미결정으로 보류 종료. host-only 진단을 EXPERIMENTS 9절에 연결, 필수 재측정 해제. |
| 현재 | BE #242: 클라이언트 polling 실측 9단계·225세션 및 전용 자원 정리 완료. 서버/FE 권고 5초 유지. CI·독립 리뷰·병합 상태는 Issue의 연결 PR 참조. |
| 다음 후보 | 기본값을 보존한 서버 polling 안내 설정화·실제 FE 경로 검증 여부 판단. 큰 부하·Kafka·AWS 및 운영 성능 주장 제외. |

실측 수치와 제한은 [실험 요약](docs/project-improvement/EXPERIMENTS.md), 공부 순서는 [학습 가이드](docs/project-improvement/STUDY_GUIDE.md), 보류할 기술은 [BACKLOG](BACKLOG.md)에 둔다. 로컬 fixture 결과를 운영 성능으로 일반화하지 않는다.
