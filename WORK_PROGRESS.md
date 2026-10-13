# 작업 진행 기록

이 파일은 **현재 작업과 다음 한 걸음**만 기록한다. 완료된 Issue별 경과는 [근거 지도](docs/project-improvement/EVIDENCE_MAP.md)와 GitHub Issue/PR에서 찾는다.

| 구분 | 상태 |
| --- | --- |
| 최근 수정·검증 | [BE #216](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/216): DB 리뷰 작성자·공연 소속·관리자 삭제 정책 검증. 수정 전 위조 author 200 재현, 리뷰 17개·복구 차단 10개 로컬 테스트 통과. |
| 현재 | 리뷰 변경 경계 수정 완료. 비밀번호 찾기는 계속 일시 중단. 리뷰·CI·병합 결과는 BE #216의 연결 PR에서 확인. |
| 다음 후보 | 예약 우회 경로 조사 후 다좌석 요청/잠금 대기/TTL 경합 테스트. 안전한 계정 복구는 별도 BE/FE 계약. |

실측 수치와 제한은 [실험 요약](docs/project-improvement/EXPERIMENTS.md), 공부 순서는 [학습 가이드](docs/project-improvement/STUDY_GUIDE.md), 보류할 기술은 [BACKLOG](BACKLOG.md)에 둔다. 로컬 fixture 결과를 운영 성능으로 일반화하지 않는다.
