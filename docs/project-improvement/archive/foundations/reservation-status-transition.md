# 예약 상태의 타입 안전 전이 정책

## 문제

기존 `Reservation.status`는 자유 문자열이었다. 예약 생성은 `결제완료`, 사용자 신청은 `취소신청`, 관리자 처리는 `취소완료`를 Service에서 직접 비교하고 대입했다. 따라서 컴파일 단계에서 오타나 알 수 없는 상태를 막을 수 없고, 상태 변경 규칙이 Entity가 아니라 여러 Service 분기에 흩어져 있었다.

Issue #31과 #35에서 transaction·예약 row lock으로 취소 중복과 신청·승인 경합을 해결했지만, 허용 전이 자체는 문자열 조건이었다. 후속 Payment 상태를 분리하고 고경합 부하에서 상태별 결과를 집계하려면 먼저 예약 상태를 제한된 타입과 실행 가능한 전이 정책으로 고정할 필요가 있다.

## 상태와 전이

`ReservationStatus`는 현재 외부 계약에 존재하는 세 상태만 표현한다.

```text
PAYMENT_COMPLETED("결제완료")
        ↓ requestCancellation
CANCELLATION_REQUESTED("취소신청")
        ↓ completeCancellation
CANCELLATION_COMPLETED("취소완료")
```

- `결제완료 → 취소신청`만 상태를 변경한다.
- `취소신청` 또는 `취소완료` 상태의 사용자 취소 재요청은 현재 멱등 정책에 따라 무변경 성공한다.
- `취소신청 → 취소완료`만 좌석 해제와 재고 복구를 수행한다.
- 이미 `취소완료`인 관리자 재요청은 무변경 성공한다.
- `결제완료 → 취소완료` 직접 전이는 거부한다.
- 최초 상태 초기화는 한 번만 허용한다.

`Reservation`의 status setter는 제거하고 `markPaymentCompleted`, `requestCancellation`, `completeCancellation`만 상태를 변경한다. Service는 사용자 소유권, 예약 row lock, 좌석과 회차 재고 처리를 조정한다. 관리자 완료에서 Entity 상태가 먼저 변경된 뒤 좌석 또는 재고 처리에 실패해도 Service transaction 전체가 rollback되므로 기존 상태와 재고가 함께 복구된다.

## DB와 API 호환

Java enum 이름을 그대로 DB에 저장하면 기존 한글 데이터와 호환되지 않고, JSON enum 이름을 반환하면 현재 Frontend의 상태 필터가 깨진다. `ReservationStatusConverter`는 기존 DB 값인 `결제완료`, `취소신청`, `취소완료`를 유지하고 `@JsonValue`도 같은 한글 값을 반환한다.

MariaDB fixture에서 `status='취소신청'` 원문을 직접 조회했고, `findByStatus(ReservationStatus.CANCELLATION_REQUESTED)`가 같은 예약을 반환함을 검증했다. 알 수 없는 DB 문자열은 enum으로 조용히 수용하지 않고 명시적으로 거부한다.

## 검증 결과

- 상태·converter·JSON·허용/거부/멱등 전이 단위 테스트 6개 통과
- 예약·취소 MariaDB 통합 테스트 52개 통과
  - 예약·멱등성·좌석 경합 29개
  - 취소 중복·신청/승인 경합·rollback 23개
- Controller와 application context를 포함한 전체 Backend 65개 invocation 통과
- 기존 `결제완료`, `취소신청`, `취소완료` DB·JSON 값 유지
- 기존 취소 lock 순서, 좌석 점유와 `remaining + reserved = 24` 불변식 유지

이 결과는 MariaDB 10.11.8 Testcontainers와 가상 좌석 24개의 상태 정합성 검증이다. TPS·p95·lock wait나 운영 처리량 측정 결과가 아니다.

## 한계와 후속 순서

`PAYMENT_COMPLETED`라는 현재 상태는 여전히 Backend가 PG 승인 정보를 검증하지 않고 생성한다. 이번 변경은 기존 잘못된 전제를 해결한 것이 아니라, 자유 문자열과 전이 분산을 제거해 다음 Payment 분리 작업의 안전한 기준을 만든 것이다.

후속 순서는 다음과 같다.

1. 서버 소유 가상 좌석 가격과 주문 금액 계산 기준 확정
2. 별도 Payment 상태와 mock 결제 검증 포트 구성
3. 검증 성공·실패·중복 승인과 예약 실패의 상태 수렴 테스트
4. 필요한 경우 좌석 hold·만료·보상 취소 분리
5. 상태 불변식이 안정된 뒤 k6로 로컬 8GB 환경의 TPS·p95·오류율·lock wait·connection 대기를 측정

실제 PG, 운영 데이터, Frontend와 외부 API는 이번 Issue에서 호출하거나 변경하지 않았다.

## 관련 Issue

- [Backend Issue #31](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/31)
- [Backend Issue #35](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/35)
- [Backend Issue #37](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/37)
- [Backend Issue #41](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/41)
