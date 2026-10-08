# 20석 fixture Hold→Release 로컬 smoke

## 질문과 범위

Issue #182는 #180에서 **생성만 확인한** 20석 가상 좌석이 실제 HTTP 점유·해제 왕복에서도 재고 불변식을 유지하는지 확인한다. 지금 5 RPS·10초는 실행 경로를 검증하는 작은 smoke이지, 고경합 한계·운영 TPS·공연장 성능의 측정이 아니다. 기존 k6는 `fixture.totalSeats === 2000`을 강제했으므로 `EXPECTED_TOTAL_SEATS=20`을 명시적으로 허용하고 기본값 2,000을 보존했다. 허용 값 외에는 시작을 거부한다.

## 실행 경계

- Windows 로컬, Java 21, Spring Boot `local,loadtest`, MariaDB 10.11.8, k6. 새 Compose 프로젝트 `ticketon-controlled172-r7`의 빈 볼륨, DB 127.0.0.1:3309, Backend 127.0.0.1:18080/18081을 사용했다.
- `ONTICKET_LOADTEST_ROWS=2`, `ONTICKET_LOADTEST_SEATS_PER_ROW=10`, 배치 자동 실행 비활성. 실 KOPIS·PG·SMS 자격 정보와 호출은 사용하지 않았다.
- `Run-ControlledSmallSeatHoldSmoke.ps1 -RunId smallsmoke182c -ComposeProject ticketon-controlled172-r7`은 #176의 전용 DB·빈 테이블·Backend fingerprint·loopback·새 결과 경로 사전 점검을 재사용한다. #180 runner로 fixture를 만든 뒤 k6 `weighted-hotspot-churn`을 고정 5 RPS×10초·인기 5/20석·선택 비율 70%·Hold 100ms·20개 가상 사용자 토큰으로 실행한다. 각 성공 Hold는 동일 사용자가 Release한다.
- 성공은 k6 완료 수·dropped·예상 밖 상태·Hold=Release, teardown/API의 최종 Hold=0·잔여 20·예약/결제 0·재고 불변식, MariaDB deadlock 전후 차이 0을 모두 만족할 때만 기록한다. 결과 JSON은 git 제외된 `load-test/results/smallsmoke182c/smoke-summary.json`에 생성한다. 실패 시 DB와 결과 경로를 삭제·재사용하지 않는다.

## 관측 결과와 실패 이력

| 전용 DB | 결과 | 의미 |
| --- | --- | --- |
| r5 | k6 이후 PowerShell이 정상적인 stderr 정보 로그를 예외로 처리해 결과 파싱 실패 | 최종 좌석 20·잔여 20·Hold 0·deadlock 0은 읽기 전용으로 확인. 측정 성공으로 간주하지 않음 |
| r6 | stderr 수집 분리 후 k6 콘솔 JSON 이스케이프를 파서가 처리하지 못함 | 최종 좌석 20·잔여 20·Hold 0·deadlock 0 확인. 측정 성공으로 간주하지 않음 |
| r7 | 실행기·파서 전체 통과 | 완료 51, Hold 51=Release 51, 예상 409 **0**, dropped 0, 예상 밖 실패 0, 최종 활성 Hold/hold row 0, DB deadlock 증가 0, 불변식 충족 |

동일 r7 RunId를 다시 실행하면 **빈 DB 사전 점검에서 POST 전 거부**되고 기존 PASS 결과 파일·재고 상태가 유지된다. r5/r6/r7 컨테이너는 중지했지만 각 볼륨은 보존했다. 세 DB 모두 사용된 상태이므로 후속 실험에 재사용하지 않는다.

회귀 검증은 Node k6 계약 11건, PowerShell 작은 fixture 10 assertions·좌석 점유 73 assertions, `gradlew --offline test` 전체 통과로 확인했다. JSON 입력 크기는 `20` 또는 `2000`만 허용하고, 기존 2,000석 기본값을 단위 테스트로 고정했다.

## 해석과 다음 단계

20석에서 5 RPS의 짧은 smoke는 좌석 충돌을 만들지 않았다. 따라서 인기 좌석의 지속 쓰기 경합이나 처리 한계를 증명하지 않는다. 다음 단계는 새로운 전용 빈 DB에서 먼저 목표 RPS·점유 시간·인기 좌석 수를 한 변수씩 올리고, 정상 409·성공/충돌 p95·dropped·Hikari/DB wait·최종 불변식을 분리 기록하는 것이다. 작은 표본의 p95나 단일 로컬 인스턴스 결과를 운영 성능 또는 확장성 결론으로 표현하지 않는다.
