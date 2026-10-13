# 작업 진행 기록

이 파일은 **현재 작업과 다음 한 걸음**만 기록한다. 완료된 Issue별 경과는 [근거 지도](docs/project-improvement/EVIDENCE_MAP.md)와 GitHub Issue/PR에서 찾는다.

| 구분 | 상태 |
| --- | --- |
| 최근 수정·검증 | [BE #222](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/222): 추가 좌석/귀속 잠금 후 결제·해제 기한 판정. 수정 전 9개 중 8개 실패 재현, 수정 후 결제/Checkout/Hold/API 155개 로컬 회귀 통과. |
| 현재 | BE #224: 혼합 Hold 비교 도구·preflight 구성. 두 배치 모두 warm-up CPU gate에서 중단(측정 0단계), 410번 점유 시도·예상 밖 오류/종료 Hold 0. 상세는 EXPERIMENTS 9절. |
| 다음 후보 | 자원 여유 확보 후 기본 20석 혼합 비교 재개. 현재 약 1.22GiB 가용 메모리 확인으로 추가 실행·요청 상한 보류. 안전한 계정 복구는 별도 BE/FE 계약. |

실측 수치와 제한은 [실험 요약](docs/project-improvement/EXPERIMENTS.md), 공부 순서는 [학습 가이드](docs/project-improvement/STUDY_GUIDE.md), 보류할 기술은 [BACKLOG](BACKLOG.md)에 둔다. 로컬 fixture 결과를 운영 성능으로 일반화하지 않는다.
