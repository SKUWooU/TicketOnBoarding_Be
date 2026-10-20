# 작업 진행 기록

이 파일은 **현재 작업과 다음 한 걸음**만 기록한다. 완료된 Issue별 경과는 [근거 지도](docs/project-improvement/EVIDENCE_MAP.md)와 GitHub Issue/PR에서 찾는다.

| 구분 | 상태 |
| --- | --- |
| 최근 정리 | #224·#226 혼합 실험은 도구·원자료 보존, 성능 비교 미완료·상한 미결정으로 보류 종료. host-only 진단을 EXPERIMENTS 9절에 연결, 필수 재측정 해제. |
| 현재 | BE #238: OFF/ON 6단계·80세션 탐색 완료, 오류/dropped/종료 Hold 0. 5 sessions/s ON 대기 p95 59.43초, 성능 향상 주장은 제외. 근거·학습 문서 정리 후 CI/별도 리뷰 진행. |
| 다음 후보 | 같은 작은 유입에서 입장 한도 또는 polling 주기 한 변수의 비용 비교. 큰 부하·Kafka·AWS 및 운영 성능 주장 제외. |

실측 수치와 제한은 [실험 요약](docs/project-improvement/EXPERIMENTS.md), 공부 순서는 [학습 가이드](docs/project-improvement/STUDY_GUIDE.md), 보류할 기술은 [BACKLOG](BACKLOG.md)에 둔다. 로컬 fixture 결과를 운영 성능으로 일반화하지 않는다.
