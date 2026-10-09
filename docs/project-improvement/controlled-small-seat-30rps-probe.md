# 20석 Hold 경합 30 RPS 단일 탐색

## 질문과 범위

#186의 가상 20석·인기 1석/70%·Hold 500ms 실측은 20 RPS까지 3회 반복했다. 그 조건에서 Hikari pending은 0이었지만 전역 DB row-lock wait는 20 RPS에서 1/4/2건 증가했다. 한 단계 높은 강도에서 오류·대기·재고 상태가 어떻게 변하는지 확인하기 위해, 기존 runner에 **명시적 `-Probe30`** 20→30 RPS 두 단계만 추가했다. 기본 5/10/20 RPS와 3회 반복 계획은 그대로 유지한다.

## 실행 조건과 안전 경계

- 2026-10-09 Windows 로컬, Java 21·Spring Boot `local,loadtest`, MariaDB 10.11.8 전용 Compose project `ticketon-controlled172-r13`, Backend `127.0.0.1:18080`, Actuator `127.0.0.1:18081`, DB `127.0.0.1:3309`.
- Docker 할당 메모리 8,231,297,024 bytes, 실행 전 호스트 가용 물리 메모리 약 6.3GB·측정 후 약 5.0GB. 호스트 CPU/GC 시계열은 이 측정에 포함하지 않았다.
- 전용 새 빈 DB에 공연·회차 각 1개, 2행×10석을 한 번만 생성했다. 인기 1석/요청 70%, 고정 seed 17·토큰/VU 각 20, Hold 500ms 후 소유자가 해제, 20→30 RPS 각 10초. 실제 좌석·운영 트래픽이 아니다.
- `-CheckOnly`의 loopback·Compose label·빈 애플리케이션 테이블·Backend/DB fingerprint·미사용 결과 경로가 `READY`였다. `Run-ControlledSmallSeatHoldRamp.ps1 -RunId probe190a -ComposeProject ticketon-controlled172-r13 -Probe30`으로 실행했다.
- 20 RPS의 완료·Hold=Release·오류/누락 0·최종 독립 SQL 재고 `20,0,0,20,0`와 Hikari pending/timeout·DB deadlock 0을 먼저 통과해야 30 RPS로 넘어간다. 30 RPS 시작 전 호스트 가용 물리 메모리 2GiB 미만이면 중단한다. 샘플 5개 미만 또는 3초 초과 간격도 실패다. 40 RPS 이상 자동 증량은 없다.
- KOPIS·실제 PG·SMS는 호출하지 않았다. `loadtest` fixture만 전용 DB에 기록했다. 결과 JSON은 수치 필드만 담고 JWT·요청 본문은 없다.

## 측정 결과

| 목표 RPS | 완료 | Hold=Release | 예상 409 | 성공/409 p95 | Hikari pending/active peak | Hikari acquire 평균 대기 | DB 전역 row-lock wait/time | 표본/최대 간격 |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 20 | 200 | 74 | 126 | 47.27 / 30.15ms | 0 / 1 (max 24) | 0.027ms | 2 / 19ms | 12 / 2,489ms |
| 30 | 301 | 97 | 204 | 17.04 / 24.83ms | 0 / 1 (max 24) | 0.011ms | 0 / 0ms | 12 / 1,171ms |

두 단계 모두 dropped·예상 밖 오류·Hikari timeout·SQL deadlock·최종 Hold 0이며 최종 독립 SQL 재고는 `20,0,0,20,0`이다. 완료 501건 = Hold/Release 171건 + 예상 좌석 충돌 330건. [보관 manifest](../../load-test/results/probe190a/small-seat-hold-ramp.json)는 단계별 원 수치와 계측 필드를 포함한다.

## 해석과 후속 판단

이 **단일 순차 탐색**에서는 30 RPS에서도 pool 대기나 재고 불변식 위반이 관측되지 않았다. 20→30 RPS의 성공 p95 감소는 순서·캐시·호스트 상태 영향을 분리하지 못하므로 개선 효과나 30 RPS 안정 구간으로 주장하지 않는다. 특히 20 RPS 표본 최대 간격 2,489ms는 계약(3초 이내)은 통과했으나 세밀한 순간 대기 peak를 놓쳤을 수 있다. DB row-lock wait는 전역 counter라 특정 SQL 병목으로 단정하지 않는다.

다음 증량이 필요하면 별도 Issue에서 30 RPS를 교차 순서로 반복해 변동 폭과 호스트 CPU/메모리를 먼저 확인한다. 노트북 자원이 모자라면 16GB PC에서 동일 조건을 새로 측정하며, 서로 다른 호스트 수치를 직접 전후 성능 개선으로 취급하지 않는다.
