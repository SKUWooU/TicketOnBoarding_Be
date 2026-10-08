# 20석 인기 좌석 Hold 경합의 단계별 로컬 기준선

## 질문과 경계

Issue #182의 20석 smoke는 5 RPS·인기 5석·점유 100ms에서 409가 0건이었다. Issue #184는 **같은 가상 fixture 안에서 정상 좌석 점유 충돌이 관찰되는지** 확인한다. 별도 빈 DB에서 인기 좌석 1석, 선택 비율 70%, Hold 500ms, seed 17, 20개 가상 사용자 토큰을 고정하고 목표 RPS만 5→10→20으로 올렸다. 이전 smoke와는 인기 좌석 수·점유 시간이 달라졌으므로 #182의 숫자와 A/B 성능 비교하지 않는다.

로컬 Windows·Java 21·Spring Boot `local,loadtest`·MariaDB 10.11.8·k6, Compose 프로젝트 `ticketon-controlled172-r8`의 새 볼륨만 사용했다. Backend는 127.0.0.1:18080/18081, DB는 127.0.0.1:3309, fixture는 2행×10석이다. 배치 자동 실행과 실 KOPIS·PG·SMS 연동은 사용하지 않았다. 운영 트래픽이나 실제 공연장 좌석 성능이 아니다.

## 실행·검증 계약

`Run-ControlledSmallSeatHoldRamp.ps1 -RunId smallramp184 -ComposeProject ticketon-controlled172-r8`은 #176/#180의 빈 DB·Compose label·Backend/DB fingerprint·loopback·새 결과 경로 사전 점검 뒤 fixture POST를 한 번만 수행한다. 한 DB에서 3단계를 순서대로 실행하지만, 각 단계 시작·종료에 활성 Hold 0과 재고 수렴을 요구한다. 단계별 성공 Hold는 같은 사용자가 Release하며, `409`만 **예상된 좌석 충돌**로 집계한다. 미완료 iteration·예상 밖 HTTP 오류·Hold/Release 불일치·deadlock·최종 Hold·재고 불일치가 있으면 해당 단계에서 중단하고 완료로 표시하지 않는다.

종료 상태는 k6 teardown 및 별도 HTTP snapshot, 독립 MariaDB SQL의 `seat` 총수·Hold 컬럼 존재 행·예약 좌석·`concert_time.seat_amount`·`reservation` 건수, DB deadlock 전후 차이를 교차 확인한다. MariaDB와 결과 디렉터리는 실패 시에도 삭제하지 않는다. k6 콘솔 snapshot은 구조화된 `msg` 한 줄만 JSON으로 디코딩하여 다른 로그의 escaped quote를 변경하지 않는다. p95 임계값은 이번 실험의 합격 조건으로 쓰지 않고, 도메인·요청 완료 게이트와 분리했다.

## r8 결과

| 목표 RPS×10초 | 완료 | 성공 Hold=Release | 예상 409 | 성공 Hold p95 | 409 p95 | dropped / 예상 밖 오류 / deadlock 증가 | 종료 재고 |
| --- | ---: | ---: | ---: | ---: | ---: | --- | --- |
| 5 | 50 | 29 | 21 | 33.40ms | 28.50ms | 0 / 0 / 0 | 20석·Hold 0 |
| 10 | 101 | 47 | 54 | 26.45ms | 24.88ms | 0 / 0 / 0 | 20석·Hold 0 |
| 20 | 200 | 74 | 126 | 27.14ms | 20.34ms | 0 / 0 / 0 | 20석·Hold 0 |

세 단계 모두 API 불변식과 독립 SQL `20,0,0,20,0`(좌석·Hold 컬럼 행·예약 좌석·잔여 수량·예약)을 만족했다. [민감 정보 없는 결과 manifest](evidence/smallramp184-summary.json)를 저장소에 보관했다. 동일 DB·RunId 재실행은 빈 DB 사전 점검에서 fixture POST 전에 거부됐고 완료 manifest·재고가 유지됐다. r8 컨테이너는 중지했으며 볼륨은 보존했다. 재사용하지 않는다.

## 해석과 다음 조건

목표 RPS가 늘자 예상 409 건수가 21→54→126으로 증가했다. 이는 고정된 인기 좌석의 점유 시간이 후속 요청과 겹친 **정상 도메인 경합**을 보여준다. 409를 서버 실패율에 섞거나, 성공 Hold p95의 작은 차이를 성능 개선으로 해석하지 않는다. 단일 로컬 DB·짧은 10초·각 조건 1회로는 안정 처리 한계, 장기 지속 부하, Hikari/DB wait 원인을 결정할 수 없다. 다음 반복 실험은 독립 반복과 Hikari·DB lock wait를 함께 수집할 필요가 있으며, 그 근거 전에는 대기열·분산 락·브로커를 도입하지 않는다.
