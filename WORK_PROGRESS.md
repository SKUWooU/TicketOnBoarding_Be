# 작업 진행 기록

이 파일은 **현재 작업과 다음 한 걸음**만 기록한다. 완료된 Issue별 경과는 [근거 지도](docs/project-improvement/EVIDENCE_MAP.md)와 GitHub Issue/PR에서 찾는다.

| 구분 | 상태 |
| --- | --- |
| 최근 수정·검증 | [BE #222](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/222): 추가 좌석/귀속 잠금 후 결제·해제 기한 판정. 수정 전 9개 중 8개 실패 재현, 수정 후 결제/Checkout/Hold/API 155개 로컬 회귀 통과. |
| 현재 | claim/finalize·취소·Hold 해제 수정, 명확한 거절 해제는 기존 로직 유지. 리뷰·CI·병합 결과는 BE #222의 연결 PR에서 확인. |
| 다음 후보 | 큰/작은 다좌석 요청 혼합의 지연·lock/connection 대기 측정 후 요청 상한 판단. 안전한 계정 복구는 별도 BE/FE 계약. |

실측 수치와 제한은 [실험 요약](docs/project-improvement/EXPERIMENTS.md), 공부 순서는 [학습 가이드](docs/project-improvement/STUDY_GUIDE.md), 보류할 기술은 [BACKLOG](BACKLOG.md)에 둔다. 로컬 fixture 결과를 운영 성능으로 일반화하지 않는다.
