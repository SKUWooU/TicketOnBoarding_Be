# 개선 BACKLOG

후보는 확정 구현 목록이 아니다. **재현할 문제와 중단 기준이 먼저**이며, RPS 한 단계마다 Issue를 나누지 않는다. 완료된 긴 Phase 목록은 [정리 전 스냅샷](https://github.com/SKUWooU/TicketOnBoarding_Be/blob/53e9b7a7425a7ee961f332ef716e8b05e08484e5/BACKLOG.md)에서 찾는다.

| 우선 | 후보 | 진행 조건 / 제외 범위 |
| --- | --- | --- |
| 완료 | 공연 오픈 대기실·입장권 설계 (#228) | ADR-0006·STUDY_GUIDE 14절은 API 계약 초안. 구매 접근 권한이며 좌석/결제 보증 아님. |
| 완료 | BE 대기·입장 원자 코어 (#230) | 비노출 코어·동시 join/tick·FIFO/한도·만료/이탈·자료 유실 검증. PR #231 병합. |
| 완료 | BE 인증 API·신규 구매 gate (#232) | 본인 API·fixture opt-in·신규 구매 gate·우회 차단·기존 결제 연속성, PR #233 병합. |
| 완료 | 운영자 초기화·bounded 승격 worker (#234) | PR #235 병합. loopback fresh CLI·single scheduler·중복 worker/FIFO/한도·Redis 유실 fail-closed 검증. |
| 완료 | FE 대기 패널·실제 구매 연동 | FE #53/PR #54, #55/PR #56에서 실제 Redis·MariaDB·두 브라우저 사용자 입장·취소·다음 사용자 좌석 재사용 검증. 성능 비교 아님. |
| 검증 완료 | Redis 장애 중 구매 경계·독립 연결 공유 (#236) | 신규 3개 포함 대상 94개·bootJar 로컬 통과, CI/리뷰/병합 결과는 연결 PR 참조. 전용 Redis pause/resume만 사용, crash/failover·전체 두 앱 E2E 제외. |
| 검증 완료 | 입장 제어 전후 구매 세션 탐색 (#238) | 1/2/5 sessions/s OFF/ON 6단계·80세션, pending 표본 0·5 sessions/s ON 대기 p95 59.43초. 성능 향상/처리 한계 증명 아님. CI/리뷰/병합은 연결 PR 참조. |
| 진행 | 입장 한도의 한 변수 통제 비교 (#240) | 5 sessions/s 고정, active 2/4/8 각 3회 균형 교차. polling 5초·worker 1초·batch 2 유지. 도구 테스트 통과, Docker 엔진 확인 대기이며 실측 미수행. 큰 부하·SSE·Kafka·AWS 제외. |
| 완료 | 미검증 비밀번호 복구 차단 (#214) | `/users/issmscode`·`/users/changepwd`를 일시 중단. 새 인증을 구현한 것으로 해석하지 않음. 로그인 후 변경·회원가입은 이번 범위 밖. |
| 후속 | 안전한 비밀번호 복구 BE/FE 계약 | 번호별 challenge·만료·시도 제한·일회용 권한·소비와 비밀번호 저장의 원자성, SMS 포트 분리·본문/로그 코드 미노출 검증 후 재개. 현재 공유 SMS 코드/응답 노출은 미해결이며 기존 코드를 복구 증명으로 재사용하지 않음. 실제 SMS 호출은 별도 승인. |
| 완료 | 리뷰 소유권 경계 (#216) | DB 작성자·공연 소속·관리자 삭제 정책을 서비스에서 검증. 평점 동시성·입력 평점 범위·전역 Security는 제외. |
| 완료 | 미검증 예약 API 폐쇄 (#218) | 기존 `/reservation` 인증 후 410. Checkout FE·PG 검증 경로와 내부 예약·멱등성 서비스는 유지. 전역 Security 개편은 제외. |
| 완료 | 다좌석 잠금 대기·Hold 만료 경계 (#220) | Hold 생성·Checkout 준비의 전체 잠금 후 시각 판정. 실제 DB lock·제어 시계 경계 테스트. 요청 상한은 제외. |
| 완료 | 결제 검증 claim/finalize·해제 시간 경계 (#222) | 추가 좌석/귀속 잠금 후 시각 판정. UNKNOWN은 업무 데이터 쓰기 전 결정, 기존 PG I/O 분리·거절 해제 정책 유지. 실제 PG 호출 없음. |
| 선택·보류 종료 | 큰/작은 다좌석 요청 혼합·요청 상한 (#224, #226, #228) | 도구·중단 근거 보존, 반복 비교 미완료·상한 미결정. host-only 진단도 연결하고 필수 재측정 해제. 자원 안정 환경에서의 재실행은 선택, 임의 상한·증량·gate 완화 제외. |
| 마무리 | MCP 분석·실험 연결 단계 | #210에서 제한된 SQL 대조와 기존 예매 경합 1회 복귀·실제 MCP 조회 완료. 직렬/병렬 집계 차이는 실제 DB 반영과 구분. 추가 계측·AI 비교·증량은 필수 후속 아님. |
| 학습 | 예매 서비스 흐름·기존 근거 복습 | #212에서 STUDY_GUIDE 7절에 5개 주제의 전후·코드·검증·한계 통합. 좌석 잠금→Hold→Checkout→PG 검증→예약 흐름으로 복귀. 새 구현은 재현 가능한 서비스 문제와 범위가 정해졌을 때 선택. |
| 보류 | 실제 AI 제안과 사람 기준 방식 비교 | #206 계약과 #208 단일 비맹검 사례 확보. 모델 품질·시간 절감 비교는 미실시. 모델·호출 예산·자료 범위 합의 필요. RAG·상위 부하 자동 실행 제외. |
| 선택 | 호스트 CPU·SQL 계측 심화 | #198 CPU 귀속·#208 JDBC 집계 원인은 미확정. #210 CLI 병렬 집계 부족 재현으로 현 단계 종료. 정확한 내부 원인·시스템 프로파일러·드라이버 비교는 필요성이 생길 때만 별도 검토. |
| 3 | 다른 로컬 장비의 별도 처리 구간 | 현재 노트북은 16GB RAM(Windows 인식 15.81GiB). 다른 PC에서 재실행하면 CPU·Docker 할당·fixture를 명시하고 새 기준선으로 기록. 서로 다른 호스트의 수치를 개선 전후로 비교하지 않음. |
| 4 | FE/BE 흐름 검증 | 가상 좌석·Hold·Checkout·결제 검증을 실제 로컬 UI/API로 점검. FE는 별도 저장소 Issue/PR. 실제 KOPIS·PG 호출 없이 fixture 사용. |
| 보류 | Kafka·outbox·Redis 좌석 분산 락·운영 LB | [ADR-0004](docs/project-improvement/adr/0004-defer-hold-admission-infrastructure.md)의 측정 기반 도입 보류 유지. 새 대기실 요구는 ADR-0006으로 분리. 메시지/배포 도입은 별도 근거·권한 필요. |
| 보류 | 실제 PG 정산·환불, 운영 데이터 migration | 외부 연동 권한·운영 데이터·복구 계획이 필요한 별도 작업. 로컬 Mock PG와 Flyway fresh DB 검증을 운영 검증으로 확대 해석하지 않음. |
| 후속 후보 | AI의 근거 기반 병목 가설·실험 제안 | [ADR-0005](docs/project-improvement/adr/0005-evidence-grounded-experiment-assistance.md): 검증된 MCP 지표 + 관련 문서 근거로 가설·검증 계획 제안. 반복 분석의 실제 부담과 평가 사례 확보 후 검토. RAG는 기존 검색의 한계가 있을 때만 검토. AI의 자동 부하·DB·결제 변경 제외. |

이미 검증한 문제·수치는 [실험 요약](docs/project-improvement/EXPERIMENTS.md), 기술 판단은 [ADR](docs/project-improvement/adr/README.md)에 둔다.
