# 작업 진행 기록

이 파일은 **현재 작업과 다음 한 걸음**만 기록한다. 완료된 Issue별 경과는 [근거 지도](docs/project-improvement/EVIDENCE_MAP.md)와 GitHub Issue/PR에서 찾는다.

| 구분 | 상태 |
| --- | --- |
| 최근 정리 | #224·#226 혼합 실험은 도구·원자료 보존, 성능 비교 미완료·상한 미결정으로 보류 종료. host-only 진단을 EXPERIMENTS 9절에 연결, 필수 재측정 해제. |
| 현재 | BE #234: fresh bootstrap CLI·자동 입장 worker 구현 완료, 대상 91개 로컬 테스트·bootJar 통과. 독립 리뷰·전체 CI·병합 결과는 연결 PR에서 확인. |
| 다음 후보 | worker 검증 후 별도 FE 대기 패널→작은 공유/복구·구매 서비스 연동 검증→입장 제어 전후 점진 측정. 큰 부하·Kafka·AWS 제외. |

실측 수치와 제한은 [실험 요약](docs/project-improvement/EXPERIMENTS.md), 공부 순서는 [학습 가이드](docs/project-improvement/STUDY_GUIDE.md), 보류할 기술은 [BACKLOG](BACKLOG.md)에 둔다. 로컬 fixture 결과를 운영 성능으로 일반화하지 않는다.
