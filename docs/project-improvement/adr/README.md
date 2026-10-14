# Architecture Decision Records

ADR은 기술을 도입하거나 보류한 이유와 재검토 조건을 기록합니다. 구현한 기술을 나열하기 위한 문서가 아닙니다.

## 작성 조건

- 둘 이상의 유효한 대안이 있음
- transaction·일관성·운영 복잡도에 장기 영향을 줌
- 새로운 인프라 또는 외부 서비스를 도입하거나 명시적으로 보류함
- 이후 Issue에서 같은 결정을 반복 검토할 가능성이 있음

## 문서 형식

파일명은 `NNNN-short-title.md`를 사용합니다.

```markdown
# ADR-NNNN: 제목

## 상태
제안 / 승인 / 보류 / 대체

## 맥락과 재현 근거

## 결정

## 검토한 대안

## 결과와 trade-off

## 적용 범위와 한계

## 재검토 조건

## 관련 Issue·PR·측정
```

## 현재 상태

- [ADR-0001: 전체 schema baseline 전까지 Flyway 운영 활성화 보류](0001-schema-migration-ownership.md) — 보류
- [ADR-0002: bounded Checkout 결제 검증 claim](0002-bounded-checkout-payment-verification-claim.md) — 승인
- [ADR-0003: 서버 소유 가상 좌석 레이아웃](0003-server-owned-virtual-seat-layout.md) — 승인
- [ADR-0004: Hold 경합의 대기열·메시지 인프라 도입 보류](0004-defer-hold-admission-infrastructure.md) — 보류
- [ADR-0005: 측정 근거를 바탕으로 한 AI 가설·실험 제안](0005-evidence-grounded-experiment-assistance.md) — 방향 승인 / AI·RAG 구현 보류
- [ADR-0006: 공연 오픈 대기실과 구매 입장 제어](0006-performance-opening-admission.md) — 방향·로컬 Redis 승인 / 비노출 코어 검증, API/gate/FE 전

대기열·메시지 브로커를 #198의 로컬 측정만으로 도입하지 않는 ADR-0004는 유지합니다. ADR-0006은 명시적인 새 입장 제어 요구에 따른 별도 설계이며 성능 병목 해결 주장이나 Kafka 승인으로 해석하지 않습니다. outbox는 별도 실패·재시도 근거가 생기기 전까지 BACKLOG 후보입니다.
