# 20석 인기 좌석 Hold 경합 반복 측정

## 질문과 범위

[Issue #184](controlled-small-seat-hold-ramp.md)의 단계별 수치는 각 조건 1회였다. 정상적인 좌석 선점 충돌(HTTP 409)이 증가하는 모습을 HikariCP 연결 대기나 MariaDB 잠금 병목으로 오인하지 않기 위해, 같은 fixture에서 순서를 교차하며 조건별 3회씩 반복했다. 이 결과는 **로컬 가상 좌석 재고**에만 적용하며 운영 예매처 성능·최대 처리량·인덱스 개선 효과를 뜻하지 않는다.

## 재현 조건과 안전 경계

- Windows 로컬 단일 Backend(Java 21, Spring Boot `local,loadtest`), MariaDB 10.11.8 전용 Compose project `ticketon-controlled172-r12`, k6. Backend `127.0.0.1:18080`, Actuator `127.0.0.1:18081`, DB `127.0.0.1:3309`.
- 새 빈 DB를 검증한 뒤 공연 1개·회차 1개·2행×10석 fixture를 한 번만 생성했다. 인기 1석에 요청의 70%를 배정하고, Hold 후 500ms 대기·해제했다. seed 17, 테스트 사용자 토큰 20개, k6 VU 20개를 고정했다.
- 5→10→20 / 20→10→5 / 5→10→20 RPS, 각 10초. 동일 DB 안에서 매 run 전후 Hold 0·재고 20을 확인했다. warm-up을 별도로 제외한 A/B 비교는 아니다.
- KOPIS·실제 PG·SMS·운영 데이터는 호출하지 않았다. 기존 재고를 청소하거나 무효 측정 DB를 재사용하지 않았다. 전용 r9는 Docker exec 계측 간격 초과, r10·r11은 Windows 절전으로 표본 단절·k6 중단되어 **모두 결과에서 제외**했다. r12에서는 측정 프로세스가 실행되는 동안에만 Windows 자동 절전을 억제했다. 시스템 전원 설정은 변경하지 않았다.
- `Run-ControlledSmallSeatHoldRamp.ps1 -RunId smallrepeat186d -ComposeProject ticketon-controlled172-r12 -Repeats 3`을 사용했다. 사전 검사에서 loopback·Compose label·빈 테이블·Backend/DB fingerprint·결과 경로 미사용을 확인했다. 같은 RunId/DB의 재실행은 fixture POST 이전에 거절한다.

## 계측 계약

각 run의 k6 완료 수를 성공 Hold와 예상 409로 분리하고, 성공 Hold=Release, dropped·예상 밖 HTTP/해제 오류 0을 요구했다. k6 teardown과 별도 API snapshot, MariaDB SQL을 교차 확인했다. 독립 SQL 배열 `20,0,0,20,0`은 좌석 총수·Hold 컬럼 존재 행·예약 완료 좌석·잔여 수량·Reservation 수를 뜻한다.

Actuator Prometheus에서 Hikari active/pending/max와 connection acquire count·누적 시간·timeout을 읽었다. 전용 DB의 loopback 포트에서 MariaDB `Innodb_row_lock_current_waits`, 누적 wait/time, deadlock을 읽었다. 샘플을 약 1초 간격으로 수집했으며, run당 12~13개·최대 간격 1,393ms였다. 표본 5개 미만, 간격 3초 초과, 누적 counter 감소, deadlock·Hikari timeout은 실패 처리했다. gauge peak는 **표본에서 관측한 최대값**일 뿐 순간 최대의 완전한 포착을 보장하지 않는다. MariaDB global counter는 DB 전체 수치로 특정 좌석 SQL의 원인을 단정할 수 없다.

## r12 결과

| 순서 (라운드) | 목표 RPS | 완료 | 성공 Hold=Release | 예상 409 | 성공 p95 / 409 p95 | Hikari pending/active peak | 획득 평균 대기 | DB row-lock waits / time |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 1 (1) | 5 | 51 | 29 | 22 | 52.91 / 65.25ms | 0 / 1 | 0.043ms | 0 / 0ms |
| 2 (1) | 10 | 101 | 47 | 54 | 49.24 / 39.85ms | 0 / 1 | 0.243ms | 0 / 0ms |
| 3 (1) | 20 | 201 | 75 | 126 | 41.18 / 32.66ms | 0 / 2 | 0.096ms | 1 / 7ms |
| 4 (2) | 20 | 201 | 75 | 126 | 81.27 / 69.97ms | 0 / 2 | 0.055ms | 4 / 21ms |
| 5 (2) | 10 | 101 | 47 | 54 | 44.85 / 34.18ms | 0 / 1 | 0.280ms | 0 / 0ms |
| 6 (2) | 5 | 51 | 29 | 22 | 47.51 / 34.41ms | 0 / 1 | 1.553ms | 0 / 0ms |
| 7 (3) | 5 | 51 | 29 | 22 | 45.88 / 45.19ms | 0 / 1 | 1.116ms | 0 / 0ms |
| 8 (3) | 10 | 101 | 47 | 54 | 46.27 / 41.61ms | 0 / 1 | 0.689ms | 0 / 0ms |
| 9 (3) | 20 | 200 | 74 | 126 | 44.34 / 42.20ms | 0 / 2 | 0.045ms | 2 / 12ms |

9회 합계 완료 1,058건 = 성공 Hold·Release 452건 + 예상 좌석 충돌 606건. 모든 run의 dropped·예상 밖 오류·Hikari timeout·SQL deadlock·최종 Hold가 0이었고 API·독립 SQL 재고 불변식이 성립했다. Hikari pool 최대 24개 중 active 표본 peak는 2, pending peak는 0이었다. row-lock wait는 20 RPS 세 run에서만 각각 1/4/2건, 누적 시간 7/21/12ms 증가했다. 20 RPS의 성공 p95는 41.18~81.27ms로 흔들려 단일 결과를 대표값으로 사용하지 않는다. [보관한 요약 데이터](evidence/smallrepeat186d-summary.json)는 위 9회 수치와 각 run의 샘플 수·간격·최종 재고를 포함한다.

## 해석과 다음 판단

이 고정 조건에서 409 증가의 주된 관측은 좌석 선점 경쟁이며, 같은 run에 Hikari pending/timeout 증가가 동반되지는 않았다. DB row-lock wait는 일부 20 RPS run에서 소량 보였으므로 `409 = DB 병목` 또는 `row-lock wait = 특정 쿼리 원인`이라고 말할 수 없다. 이 측정만으로 대기열·Kafka·분산 락을 도입하거나 운영 처리 한계를 선언하지 않는다. 더 높은 강도나 다른 사용자/좌석 분포를 시험하려면 메모리 여유가 있는 별도 환경에서 조건을 새로 고정하고, 표본 간격·connection·SQL 단위 진단을 함께 남겨야 한다.
