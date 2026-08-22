# 복수 좌석 canonical 잠금 순서와 요청 검증

## 문제

Issue #11의 MariaDB 10.11.8·가상 좌석 24개 fixture에서 `(concert_time_id, seat_number)` 복합 unique index를 적용하자 `[A1,A2]`와 `[A2,A1]` 요청이 서로 다른 첫 좌석을 잠갔다. 두 번째 좌석 잠금에서 순환 대기가 만들어져 3회 모두 MariaDB error `1213`, SQL state `40001`이 발생했다.

기존 `SeatReservationService.reserveSeat()`는 client가 전달한 좌석 목록을 그대로 순회했다. 따라서 같은 좌석 집합도 payload 순서에 따라 다른 잠금 순서를 사용했다. 좌석 목록이 null·빈 목록이거나 중복을 포함해도 잠금 query 전 검증이 없었다.

## 개선

Service transaction은 DB 조회 전에 다음 순서로 좌석 목록을 처리한다.

1. 예약 요청과 좌석 목록의 존재 여부를 확인한다.
2. null·blank 좌석 번호와 payload 내부 중복을 거부한다.
3. 요청 목록을 직접 변경하지 않고 새 목록으로 복사한다.
4. 좌석 번호의 natural order로 정렬한 목록만 잠금 query에 사용한다.

`[A1,A2]`와 `[A2,A1]`은 모두 `[A1,A2]`로 정규화된다. 두 transaction이 같은 좌석 집합을 예약하면 동일한 첫 좌석부터 경쟁하므로 반대 잠금 순서에 필요한 순환 대기 조건을 제거한다.

## 요청 검증 정책

| 입력 | Service 예외 | 좌석 잠금 query |
| --- | --- | --- |
| 예약 요청 null | `예약 요청이 필요합니다.` | 0회 |
| 좌석 목록 null·빈 목록 | `좌석을 한 개 이상 선택해야 합니다.` | 0회 |
| null·blank 좌석 번호 | `좌석 번호는 비어 있을 수 없습니다.` | 0회 |
| payload 내부 중복 | `중복된 좌석을 선택할 수 없습니다.` | 0회 |

현재 프로젝트에는 공통 API 예외 응답 모델이 없다. 이번 Issue는 Service 경계에서 DB 변경 전 실패시키는 범위이며 HTTP status·error body 표준화는 별도 API 계약 작업으로 남긴다.

## 검증 조건

- Java 21, Spring Boot 3.2.5
- MariaDB Testcontainers `mariadb:10.11.8`
- 공연 1개, 회차 1개, 가상 좌석 `A1`~`C8` 24개
- test schema에만 `(concert_time_id, seat_number)` 복합 unique index 적용
- 요청 1: `[A1,A2]`
- 요청 2: `[A2,A1]`
- repository proxy에서 thread별 잠금 query 좌석 번호 기록
- 동일 시나리오 3회 반복
- 외부 KOPIS·SMS·OAuth·결제·운영 DB 호출 없음

## 결과

| 항목 | 개선 전 | 개선 후 |
| --- | --- | --- |
| 요청별 첫 잠금 좌석 | `A1`, `A2` | 모두 `A1` |
| MariaDB deadlock | 3회 모두 `1213/40001` | 3회 모두 0건 |
| 요청 결과 | 성공 1·deadlock 1 | 성공 1·예약 충돌 1 |
| 실패 사용자 예약 row | 0 | 0 |
| 최종 좌석·예약·잔여 | 2·2·22 | 2·2·22 |

개선 후 실제 기록된 query 순서는 성공 transaction `[A1,A2]`, 실패 transaction `[A1]`이었다. 실패 transaction은 성공 transaction이 `A1`을 commit한 뒤 같은 좌석의 예약 상태를 확인하고 `이미 예약된 좌석입니다.`로 종료됐다. SQL state와 DB error code는 발생하지 않았다.

요청 목록 `[A2,A1]`은 호출 이후에도 원래 순서를 유지했고, repository query만 `[A1,A2]` 순서로 실행됐다. validation fixture는 모든 잘못된 입력에서 좌석 잠금 query 0회와 좌석·예약 0·잔여 24를 확인했다.

전체 회귀 테스트는 동시성 test invocation 17개와 application context 1개, 총 18개가 통과했다. 동일 좌석 8개 동시 요청은 계속 1개만 성공했고, 서로 다른 8좌석 요청은 좌석·예약 8·잔여 16을 유지했다.

## 해석과 한계

이번 결과는 반대 순서라는 확인된 deadlock 원인을 같은 fixture에서 제거했다는 근거다. 모든 종류의 DB deadlock이 사라졌거나 운영 처리량이 향상됐다는 뜻은 아니다. 좌석 번호 natural order는 물리적 좌석 배치가 아니라 모든 transaction이 공유하는 결정적 잠금 순서를 만들기 위한 기준이다.

운영 schema에는 아직 복합 unique index가 없다. 현재 저장소에는 Flyway와 기존 schema migration 기준도 없으므로 운영 index를 이번 Service 변경에 섞지 않았다. 다음 schema Issue에서는 기존 중복 데이터 검사, migration 실패 정책, 복합 unique index 생성과 실제 실행 계획을 별도로 검증해야 한다.

deadlock 무제한 retry, Redis 분산 lock과 대기열은 도입하지 않는다. 예약·결제 사이의 임시 좌석 점유와 만료도 별도 상태 전이 문제다.

## 연결

- [Issue #15](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/15)
- [좌석 복합 인덱스와 deadlock 비교 기준선](seat-composite-index-deadlock-comparison.md)
- [복수 좌석 잠금 순서와 deadlock 기준선](multi-seat-lock-order-deadlock-baseline.md)
- [개선 근거 연결표](EVIDENCE_MAP.md)
