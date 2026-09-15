# 프로젝트 개선 문서

이 디렉터리는 TicketOnBoarding Backend의 개선 과정에서 확인한 문제, 구현 결정, 검증 조건을 보관합니다. 서비스 개요와 실행 방법은 저장소 루트 [README](../../README.md)를 기준으로 합니다.

## 먼저 볼 문서

- [프로젝트 기준선](PROJECT_BASELINE.md): 실행 조건, 외부 연동, 현재 확인 범위
- [Backend 아키텍처 학습 기준선](backend-architecture-learning-baseline.md): 도메인 구조와 요청 흐름
- [근거 연결표](EVIDENCE_MAP.md): 문제·개선·검증·수치·Issue/PR의 연결
- [학습 및 개선 기록](LEARNING_JOURNEY.md): 작업 순서와 전후 코드 탐색 경로
- [ADR 인덱스](adr/README.md): 도입·보류한 기술과 적용 조건

## 완료 근거 아카이브

| 묶음 | 내용 |
| --- | --- |
| [foundations](archive/foundations/) | 예약 transaction, 재고 원자성, 잠금 순서, 멱등성, 상태 전이 |
| [load-testing](archive/load-testing/) | 가상 fixture, k6 부하 시나리오, DB 병목과 HTTP 계약 |
| [seat-hold](archive/seat-hold/) | `AVAILABLE·HELD·RESERVED`, TTL 만료, 좌석 레이아웃, hold 계측 |
| [checkout](archive/checkout/) | 서버 소유 Checkout, 결제 검증 claim, UNKNOWN 복구, 취소 경합 |

## 기록 원칙

- 상세 근거는 해당 아카이브 문서에 기록하고, 루트 README는 문서의 진입점으로만 유지합니다.
- 가상 좌석 fixture와 로컬 측정 결과는 실제 예매처 또는 운영 환경 성능으로 일반화하지 않습니다.
- 대기열·outbox·브로커·외부 결제 연동은 재현한 문제와 도입 조건이 확인된 뒤 별도 ADR로 판단합니다.
