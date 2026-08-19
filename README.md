# TicketOnBoarding Backend

KOPIS 공연 데이터를 바탕으로 공연·회차·가상 좌석을 구성하고 예약·결제·취소 흐름을 제공하는 TicketOnBoarding의 Backend입니다.

- Frontend: [SKUWooU/TicketOnBoarding_Fe](https://github.com/SKUWooU/TicketOnBoarding_Fe)
- Backend: Java 21, Spring Boot 3.2, Spring Data JPA, MariaDB
- 주요 도메인: 공연, 공연 회차, 좌석, 예약, 리뷰, 사용자
- 외부 연동: KOPIS, CoolSMS, Naver·Google OAuth

## 개선 원칙

현재 구현을 먼저 재현하고 측정한 뒤 개선합니다. 실제 좌석 데이터 대신 명시적인 fixture로 가상 좌석 재고를 구성하며, 로컬·fixture 측정 결과를 실제 예매처나 운영 환경의 성능으로 일반화하지 않습니다.

우선 검증 대상은 동일 좌석 동시 예매, 잔여 좌석 집계 정합성, 복수 좌석 잠금 순서, 예약·결제·취소 멱등성과 상태 전이입니다. 대기열, outbox, 메시지 브로커 등은 재현된 문제와 도입 조건이 확인된 이후에 판단합니다.

## 프로젝트 문서

- [현재 기준선](docs/project-improvement/PROJECT_BASELINE.md)
- [예매 트랜잭션·경합 기준선](docs/project-improvement/reservation-transaction-concurrency-baseline.md)
- [개선 BACKLOG](BACKLOG.md)
- [작업 절차](WORKFLOW.md)
- [작업 진행 기록](WORK_PROGRESS.md)
- [근거 연결표](docs/project-improvement/EVIDENCE_MAP.md)
- [학습 및 개선 기록](docs/project-improvement/LEARNING_JOURNEY.md)
- [ADR 인덱스](docs/project-improvement/adr/README.md)

예약 도메인의 MariaDB 통합 테스트 조건과 검증 명령은 [예매 트랜잭션·경합 기준선](docs/project-improvement/reservation-transaction-concurrency-baseline.md)에 기록합니다. 전체 애플리케이션 실행 제약과 외부 연동 주의사항은 [현재 기준선](docs/project-improvement/PROJECT_BASELINE.md)에서 확인합니다.
