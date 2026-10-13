# 작업 진행 기록

이 파일은 **현재 작업과 다음 한 걸음**만 기록한다. 완료된 Issue별 경과는 [근거 지도](docs/project-improvement/EVIDENCE_MAP.md)와 GitHub Issue/PR에서 찾는다.

| 구분 | 상태 |
| --- | --- |
| 최근 수정·검증 | [BE #218](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/218): 기존 미검증 예약 API 폐쇄. 수정 전 정상 본문 인증 요청 200 재현, 폐쇄·예약 Controller·최근 권한 수정 58개 로컬 테스트 통과. |
| 현재 | Checkout FE·PG 검증 경로와 내부 예약 서비스 유지. 리뷰·CI·병합 결과는 BE #218의 연결 PR에서 확인. 비밀번호 찾기는 계속 일시 중단. |
| 다음 후보 | 다좌석 요청 크기·잠금 대기·TTL 경합 재현. 안전한 계정 복구는 별도 BE/FE 계약. |

실측 수치와 제한은 [실험 요약](docs/project-improvement/EXPERIMENTS.md), 공부 순서는 [학습 가이드](docs/project-improvement/STUDY_GUIDE.md), 보류할 기술은 [BACKLOG](BACKLOG.md)에 둔다. 로컬 fixture 결과를 운영 성능으로 일반화하지 않는다.
