# 작업 진행 기록

이 파일은 **현재 작업과 다음 한 걸음**만 기록한다. 완료된 Issue별 경과는 [근거 지도](docs/project-improvement/EVIDENCE_MAP.md)와 GitHub Issue/PR에서 찾는다.

| 구분 | 상태 |
| --- | --- |
| 최근 정리 | #224·#226 혼합 실험은 도구·원자료 보존, 성능 비교 미완료·상한 미결정으로 보류 종료. host-only 진단을 EXPERIMENTS 9절에 연결, 필수 재측정 해제. |
| 현재 | BE #236: Redis pause 중 신규 구매 차단·기존 주문 처리 연속성, 독립 연결 공유 검증 완료. 신규 3개 포함 대상 94개·bootJar 로컬 통과. CI·독립 검토·병합 결과는 연결 PR에서 확인. |
| 다음 후보 | 같은 작은 fixture에서 입장 제어 전후 점진 측정. 입장 대기와 구매 API 지표 구분, 큰 부하·Kafka·AWS 제외. |

실측 수치와 제한은 [실험 요약](docs/project-improvement/EXPERIMENTS.md), 공부 순서는 [학습 가이드](docs/project-improvement/STUDY_GUIDE.md), 보류할 기술은 [BACKLOG](BACKLOG.md)에 둔다. 로컬 fixture 결과를 운영 성능으로 일반화하지 않는다.
