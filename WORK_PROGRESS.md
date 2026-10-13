# 작업 진행 기록

이 파일은 **현재 작업과 다음 한 걸음**만 기록한다. 완료된 Issue별 경과는 [근거 지도](docs/project-improvement/EVIDENCE_MAP.md)와 GitHub Issue/PR에서 찾는다.

| 구분 | 상태 |
| --- | --- |
| 최근 수정·검증 | [BE #220](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/220): 다좌석 잠금 후 Hold 만료 판정. 실제 DB lock·제어 시계 재현 후 Checkout/Hold/API 67개 로컬 회귀 통과. |
| 현재 | Hold 생성·Checkout 준비만 수정. 리뷰·CI·병합 결과는 BE #220의 연결 PR에서 확인. 요청 상한·k6 증량·결제 claim/finalize는 제외. |
| 다음 후보 | 결제 claim/finalize·해제의 잠금 후 시간 경계 조사. 큰/작은 요청 혼합 측정·상한은 그 뒤 판단. 안전한 계정 복구는 별도 BE/FE 계약. |

실측 수치와 제한은 [실험 요약](docs/project-improvement/EXPERIMENTS.md), 공부 순서는 [학습 가이드](docs/project-improvement/STUDY_GUIDE.md), 보류할 기술은 [BACKLOG](BACKLOG.md)에 둔다. 로컬 fixture 결과를 운영 성능으로 일반화하지 않는다.
