# 작업 진행 기록

이 파일은 **현재 작업과 다음 한 걸음**만 기록한다. 완료된 Issue별 경과는 [근거 지도](docs/project-improvement/EVIDENCE_MAP.md)와 GitHub Issue/PR에서 찾는다.

| 구분 | 상태 |
| --- | --- |
| 최근 정리 | #224·#226 혼합 실험은 도구·원자료 보존, 성능 비교 미완료·상한 미결정으로 보류 종료. host-only 진단을 EXPERIMENTS 9절에 연결, 필수 재측정 해제. |
| 현재 | BE #228: 공연 오픈 대기실·입장권의 신규 도메인 계약/ADR 초안. 앱·Redis·FE 코드는 아직 변경하지 않음. 리뷰·CI·병합은 연결 PR에서 확인. |
| 다음 후보 | Redis 도입/실행 경계 확인 후 BE 원자 코어→BE gate→별도 FE 대기 패널→작은 공유/복구 검증. 큰 부하·Kafka·AWS 제외. |

실측 수치와 제한은 [실험 요약](docs/project-improvement/EXPERIMENTS.md), 공부 순서는 [학습 가이드](docs/project-improvement/STUDY_GUIDE.md), 보류할 기술은 [BACKLOG](BACKLOG.md)에 둔다. 로컬 fixture 결과를 운영 성능으로 일반화하지 않는다.
