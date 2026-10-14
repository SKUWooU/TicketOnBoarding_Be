# 작업 진행 기록

이 파일은 **현재 작업과 다음 한 걸음**만 기록한다. 완료된 Issue별 경과는 [근거 지도](docs/project-improvement/EVIDENCE_MAP.md)와 GitHub Issue/PR에서 찾는다.

| 구분 | 상태 |
| --- | --- |
| 최근 수정·검증 | [BE #224](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/224): 혼합 Hold 도구·중단 근거 보존. 두 배치 warm-up 410번·오류/종료 Hold 0, 반복 측정 0단계. |
| 현재 | [PR #225](https://github.com/SKUWooU/TicketOnBoarding_Be/pull/225): Reviewer Blocking 수정(준비된 fixture의 쓰기 전 GET·SQL 대조, 빈 묶음 거절·필수 조건 완료 검증). 최신 리뷰·CI·병합 상태는 PR에서 확인. |
| 다음 후보 | 자원 여유 확보 후 기본 20석 혼합 비교 재개. 현재 약 1.22GiB 가용 메모리 확인으로 추가 실행·요청 상한 보류. 안전한 계정 복구는 별도 BE/FE 계약. |

실측 수치와 제한은 [실험 요약](docs/project-improvement/EXPERIMENTS.md), 공부 순서는 [학습 가이드](docs/project-improvement/STUDY_GUIDE.md), 보류할 기술은 [BACKLOG](BACKLOG.md)에 둔다. 로컬 fixture 결과를 운영 성능으로 일반화하지 않는다.
