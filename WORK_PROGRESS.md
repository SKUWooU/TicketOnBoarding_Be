# 작업 진행 기록

이 파일은 **현재 작업과 다음 한 걸음**만 기록한다. 완료된 Issue별 경과는 [근거 지도](docs/project-improvement/EVIDENCE_MAP.md)와 GitHub Issue/PR에서 찾는다.

| 구분 | 상태 |
| --- | --- |
| 최근 수정·검증 | [BE #214](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/214): 미검증 복구 경로 2개 일시 차단·전화번호 변경 서비스 제거. 수정 전 200 재현, 차단 10개·계정 관련 7개 로컬 테스트 통과. |
| 현재 | 비밀번호 찾기는 일시 사용 불가. 실제 SMS·FE·전역 Security 변경 없음. 리뷰·CI·병합 결과는 BE #214의 연결 PR에서 확인. |
| 다음 후보 | 안전한 복구 계약은 별도 BE/FE 작업으로 설계. 리뷰 소유권·예약 우회 경로와 다좌석 요청/TTL 경합 후보를 순차 검토. |

실측 수치와 제한은 [실험 요약](docs/project-improvement/EXPERIMENTS.md), 공부 순서는 [학습 가이드](docs/project-improvement/STUDY_GUIDE.md), 보류할 기술은 [BACKLOG](BACKLOG.md)에 둔다. 로컬 fixture 결과를 운영 성능으로 일반화하지 않는다.
