# 프로젝트 개선 문서

이 디렉터리는 TicketOnBoarding Backend의 개선 근거를 **전체 흐름 → 문제 → 실험** 순서로 읽는 진입점이다. 서비스 실행 방법은 저장소 루트 [README](../../README.md)를 기준으로 한다.

## 읽는 순서

1. [탑다운 학습 가이드](STUDY_GUIDE.md): KOPIS → 공연·회차·가상 좌석 → Hold → Checkout → 결제 검증·예약, 핵심 불변식과 코드 진입점
2. [근거 지도](EVIDENCE_MAP.md): 문제 → 개선 → 검증 → 대표 결과
3. [실험 요약](EXPERIMENTS.md): fixture·RPS·측정 수치·한계와 다음 실험의 중단 기준
4. [ADR 인덱스](adr/README.md): 기술 도입·보류 판단

## 필요할 때만 보는 원자료

| 묶음 | 내용 |
| --- | --- |
| [foundations](archive/foundations/) | 예약 transaction, 재고 원자성, 잠금 순서, 멱등성, 상태 전이 |
| [load-testing](archive/load-testing/) | 가상 fixture, k6 부하 시나리오, DB 병목과 HTTP 계약 |
| [seat-hold](archive/seat-hold/) | `AVAILABLE·HELD·RESERVED`, TTL 만료, 좌석 레이아웃, hold 계측 |
| [checkout](archive/checkout/) | 서버 소유 Checkout, 결제 검증 claim, UNKNOWN 복구, 취소 경합 |

## 기록 원칙

- 새로운 Issue마다 상세 Markdown 파일을 만들지 않는다. 새로운 결론만 기존 주제별 요약에 반영하고, 긴 수치·원자료는 `load-test/results/`의 추적된 JSON과 GitHub Issue/PR에 둔다.
- 기존 긴 문서는 [아카이브](archive/) 또는 [정리 전 Git 스냅샷](https://github.com/SKUWooU/TicketOnBoarding_Be/tree/53e9b7a7425a7ee961f332ef716e8b05e08484e5/docs/project-improvement)에서 찾을 수 있다. 루트 README는 진입점으로 유지한다.
- 가상 좌석 fixture와 로컬 측정 결과는 실제 예매처 또는 운영 환경 성능으로 일반화하지 않습니다.
- 대기열·outbox·브로커·외부 결제 연동은 재현한 문제와 도입 조건이 확인된 뒤 별도 ADR로 판단합니다.
