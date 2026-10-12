# 가상 좌석 경합 실험 요약

이 문서는 **결론을 재현 조건과 함께** 읽기 위한 요약이다. 기존 표의 RPS는 k6 목표 도착률(iterations/s)이며 TPS나 실제 예매처 처리량이 아니다. Hold 성공 iteration에는 POST와 DELETE가 포함되고 충돌 iteration에는 POST만 있으므로 전체 HTTP RPS와도 다르다. 각 run의 원 수치는 추적된 `load-test/results/` JSON에, 정리 전 긴 상세 기록은 [이전 문서 스냅샷](https://github.com/SKUWooU/TicketOnBoarding_Be/tree/53e9b7a7425a7ee961f332ef716e8b05e08484e5/docs/project-improvement)에 남아 있다.

## 1. 문제를 재현하고 고친 순서

| 문제 | 핵심 변경 | 검증에서 말할 수 있는 것 |
| --- | --- | --- |
| 다좌석 예매의 부분 commit·잔여 수량 갱신 유실 | 예매 변경을 한 트랜잭션으로 묶고 `seat_amount >= 요청 수량` 조건부 DB UPDATE | 실패 시 전체 rollback, 동시 재고 차감 불변식 유지. [#3](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/3) → [#5](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/5) |
| 역순 좌석 잠금의 deadlock | 좌석 번호 정렬 후 동일 순서로 `PESSIMISTIC_WRITE` 획득 | 겹치는 다좌석 요청의 잠금 순서 통일; 별도 반복 fixture에서 SQL deadlock 0. [#9](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/9) → [#11](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/11) |
| 좌석 잠금 조회의 넓은 탐색·connection 대기 | `UNIQUE(concert_time_id, seat_number)`로 조회와 식별 불변식 결합 | 동일한 가상 2,000석·100 RPS A/B에서 p95 **3,064.39→143.41ms**, Hikari pending peak **189→0**. 해당 로컬 fixture의 결과. [#57](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/57) → [#59](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/59) |
| 재시도 중복 Booking·결제 검증 경합 | 사용자별 key·요청 fingerprint·DB unique, Checkout 검증 선점과 PG I/O 트랜잭션 분리 | 같은 요청 결과 재사용, 다른 payload 충돌; Mock PG 지연 중 외부 검증 1회·최종 확정 1건. 실제 PG 호출 아님. [#35](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/35), [#82](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/82) |

복합 인덱스는 좌석 조회 비용을 줄였지만 경합 자체를 없애지는 않는다. 병목이 공통 회차 재고 갱신 등 다른 지점으로 옮겨갈 수 있어, p95와 재고 불변식을 함께 확인해야 한다. 여기서 0건은 해당 fixture에서 **관측하지 않았다**는 뜻이지 모든 입력·운영 환경에서 deadlock이 불가능하다는 증명이 아니다. 자세한 실패 재현·코드 전후는 [기존 foundations 아카이브](archive/foundations/)와 [checkout 아카이브](archive/checkout/)를 참고한다.

## 2. Hold 경합: 20석과 2,000석을 분리해 읽기

| 시나리오 | 고정 조건 | 결과·해석 | 원자료 |
| --- | --- | --- | --- |
| 20석 시작점 | 인기 1석/70%, Hold 500ms, 5 RPS×10초 | 완료·Hold=Release 51, 정상 409 0. 경합 강도 근거로 사용하지 않음. [#182](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/182) | [smoke 요약](evidence/smallsmoke182c-summary.json) |
| 20석 단계 | 같은 fixture, 5→10→20 RPS×각 10초 | 성공/정상 409가 29/21→47/54→74/126. 한 번씩 실행한 탐색. [#184](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/184) | [단계 요약](evidence/smallramp184-summary.json) |
| 20석 반복 | 같은 조건, 5/10/20 RPS 교차 순서 각 3회 | 9회 완료 1,058=Hold·해제 452+정상 409 606. dropped·예상 밖 오류·pending·timeout·deadlock·종료 Hold 0. 20 RPS 전역 DB row-lock wait 1/4/2건. [#186](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/186) | [9-run manifest](../../load-test/results/smallrepeat186d/small-seat-hold-ramp.json) |
| 20→30 RPS 탐색 | 같은 가상 20석, 각 10초 단일 순차 실행 | 완료 200/301, Hold=Release 74/97, 정상 409 126/204. 두 단계 모두 pending·dropped·deadlock·최종 Hold 0. 30 RPS 안정 구간 또는 p95 개선 주장은 불가. [#190](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/190) | [2-run manifest](../../load-test/results/probe190a/small-seat-hold-ramp.json) |
| 20/30 RPS 교차 반복 | 같은 가상 20석, 20·30→30·20→20·30 RPS, 각 10초·각 속도 3회 | 6단계 완료 1,506=Hold·해제 516+정상 409 990. dropped·예상 밖 오류·pending·timeout·deadlock·종료 Hold 0. 한 20 RPS 단계의 호스트 CPU peak 91.5%로 **추가 증량 전 원인 확인 필요**. [#196](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/196) | [완료 manifest](../../load-test/results/repeat196b/small-seat-hold-ramp.json), [미완료 manifest](../../load-test/results/repeat196a/small-seat-hold-ramp.json) |
| 20/30 RPS CPU 귀속 진단 | 동일 계획·fixture로 별도 빈 DB에서 6단계씩 2회. 두 번째에 Windows 프로세스군 표본 추가 | `attr198a` 1,501건·host peak 87.2%; `attr198b` 1,503건·host peak 91.2%. 두 실행 모두 예상 밖 오류·dropped·pending·timeout·deadlock·최종 Hold 0, 독립 SQL `20,0,0,20,0`. 높은 host peak를 JVM/DB 단독 병목으로 귀속할 수 없음. [#198](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/198) | [첫 실행](../../load-test/results/attr198a/small-seat-cpu-attribution.json), [프로세스군 포함](../../load-test/results/attr198b/small-seat-cpu-attribution.json), [부하 manifest](../../load-test/results/attr198b/small-seat-hold-ramp.json) |
| 동일 유입량·인기 집합 대비 | 가상 20석, 20 iterations/s·10초·Hold 500ms·인기 요청 가중치 70%·seed 17. 인기 1석/10석 각각 warm-up 1회 제외 후 교차 3회 | 측정 1,206건=Hold·해제 617+정상 409 589. 인기 1석 성공/409 225/378, 10석 392/211. dropped·예상 밖 오류·pending·timeout·deadlock·최종 Hold 0. 자원 재검토는 여전히 필요. [#200](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/200) | [완료 manifest](../../load-test/results/contrast200b/small-seat-hold-contrast.json), [미완료 첫 시도](../../load-test/results/contrast200a/small-seat-hold-contrast.json) |
| 2,000석 지속 쓰기 경합 | 인기 20/40/200석·70%, Hold 100ms, 50/100 RPS×10초, 각 속도 6-run | Hold=Release·최종 HELD 0, dropped·예상 밖 오류·deadlock 0. 인기 집합별 성공/409 비중이 달라 혼합 p95 직접 비교 금지. [#172](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/172) | [50 RPS manifest](../../load-test/results/churn172a/controlled-seat-hold-churn-manifest.json), [100 RPS manifest](../../load-test/results/churn172b/controlled-seat-hold-churn-manifest.json) |

좌석 수, 인기 분포, Hold 지속 시간이 다르므로 **20석과 2,000석 p95를 전후 개선처럼 비교하지 않는다.** DB row-lock wait는 DB 전역 counter라 특정 SQL의 잠금 원인을 단정할 수 없다. 짧은 표본에서 Hikari pending 0도 순간 최대 대기를 전부 포착했다는 뜻은 아니다.

#196은 로컬 8 logical CPU·15.81GiB RAM 호스트에서 계측했다. 첫 시도 `repeat196a`는 `Complete:false`, 기록된 단계 0건이므로 측정 결과로 사용하지 않는다. 이 manifest에는 실패 원인과 당시 표본 간격이 저장되지 않아 원인을 단정하지 않는다. 별도 빈 DB의 `repeat196b`는 약 1초 간격 Windows 전체 CPU·가용 메모리 카운터로 재실행했다. 단계별 최대 표본 간격 1,201~1,379ms, 최소 가용 메모리 3.32GiB, 20 RPS Hold 성공 p95 21.95~33.61ms, 30 RPS 22.05~34.01ms였다. 호스트 CPU의 단일 peak는 Backend/DB 병목의 원인 증명이 아니며, 서로 다른 호스트·fixture 또는 단일 탐색과의 성능 개선 수치로 비교하지 않는다.

#198의 두 실행도 같은 8 logical CPU·Windows 물리 RAM 15.81GiB 노트북이다. `attr198b`에서 host peak 91.2%인 표본 구간의 Windows 프로세스 CPU 증가량은 Backend JVM 0.7%, k6 0%, Docker/WSL 호스트군 7.3%, 기타 읽기 가능 프로세스군 35.1%로 환산됐다(각각 전체 8 logical CPU 용량 대비). 같은 실행의 JVM Prometheus peak는 14.1%(시점이 다름), MariaDB PID 1 CPU는 단계 평균 4.6~6.2% **한 코어 기준**, k6는 4.2~8.3% **한 코어 기준**이다. 이 수치는 서로 다른 수집 시각·창·분모를 갖고, 프로세스 시작/종료·접근 제한·Windows 커널 사용량도 빠질 수 있어 **서로 합하거나 host peak에서 빼서 잔여 CPU 원인을 특정하지 않는다.** 다만 Backend 또는 MariaDB 단독 포화라는 근거는 없고, 부하 도중 기타 로컬 프로세스 사용량도 유의미했다. 프로세스군 집계에는 개인 앱 이름을 저장하지 않았다. 추가 계측 시 최대 표본 간격은 `attr198a` 1,694~2,038ms, `attr198b` 1,810~2,134ms로 기존 #196의 1,201~1,379ms보다 길었다. 따라서 p95를 #196과 전후 성능 개선으로 비교하지 않는다.

## 3. 결과를 믿기 위한 관측 경계

- **부하 결과:** 목표 RPS×시간과 완료·dropped를 분리한다. 좌석 선점의 정상 409는 서버 오류와 다른 범주이며 성공/409 p95를 따로 본다.
- **도메인 결과:** Hold=Release, 종료 HELD·예약·재고를 API snapshot과 독립 SQL로 교차한다. 20석 반복/탐색의 최종 SQL 배열 `20,0,0,20,0`은 총 좌석·Hold 행·예약 좌석·잔여 수량·Reservation 수다.
- **자원 결과:** Hikari pending/active/acquire/timeout, DB deadlock·전역 row-lock wait, 표본 간격을 같이 본다. #190은 실행 중 CPU·메모리 시계열이 없어 30 RPS 안정 구간을 선언하지 않는다.
- **MCP:** [read-only 근거 서버](../../tools/ticketon-evidence-mcp/README.md)는 허용된 summary/manifest와 불변식만 조회한다. `assess_controlled_hold_evidence`는 20석 9-run과 2,000석 6-run을 **각각** 판정한다. `assess_small_seat_probe`는 schema v3 단일 탐색에 `REPEAT_REQUIRED`, `assess_small_seat_repeat_30`은 schema v4 반복에서 호스트 CPU peak 90% 이상이면 `RESOURCE_REVIEW_REQUIRED`를 반환한다. 두 도구 모두 실패·근거 누락 시 관측값을 숨기고, 더 높은 부하를 직접 실행하지 않는다. [#188](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/188), [#194](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/194), [#196](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/196)

## 4. 다음 실험의 중단 기준

호스트 CPU의 미설명분이 있고 추가 계측으로 표본 간격도 늘어났으므로 40 RPS 등으로 자동 증량하지 않는다. 상위 부하 전에는 OS/가상화 CPU를 같은 시계열에서 분리하고 계측 오버헤드를 낮춘 별도 기준선이 필요하다. dropped, 예상 밖 오류, timeout, deadlock, 재고 불일치, 샘플 단절 또는 메모리 부족이 나오면 중단한다. 별도 장비에서 재실행하면 **새 기준선**으로 기록하고 이 호스트의 수치를 직접 전후 비교하지 않는다.

## 5. 동일 유입량에서 정상 충돌과 시스템 대기 구분

#200의 `contrast200b`는 같은 Windows 8 logical CPU·15.81GiB RAM 호스트, MariaDB 10.11.8 전용 빈 DB(`ticketon-controlled172-i200b`, loopback 3309), Java 21 Backend(`local,loadtest`, `-Xms256m -Xmx768m`, Hikari max 24)에서 실행했다. fixture는 2행×10석, VU 예산은 20/20으로 고정했다. 기존 runner의 `-ContrastHotspots` 모드가 두 조건의 warm-up 각 10초를 별도 기록하고, 인기 집합 `1,10 → 10,1 → 1,10`을 각 10초씩 측정한다. DB 변경은 해당 가상 fixture에 한정했고 실제 KOPIS·PG·LLM은 호출하지 않았다.

| 관측 | 인기 1석 (3회) | 인기 10석 (3회) |
| --- | --- | --- |
| 완료 / 성공·해제 / 정상 409 | 603 / 225 / 378 | 603 / 392 / 211 |
| Hold 성공 p95 범위 | 43.06~125.51ms | 38.39~72.81ms |
| 정상 좌석 충돌 p95 범위 | 32.00~148.36ms | 34.35~75.29ms |
| DB 전역 row-lock wait 증분 / 대기 ms | 7/119, 10/397, 2/0 | 2/4, 3/6, 2/24 |

모든 측정 단계에서 Hikari pending·timeout, SQL deadlock, dropped와 예상 밖 오류는 0이었다. API snapshot과 단계별 SQL은 `20,0,0,20,0`이고 실행 후 Booking·Payment도 0이었다. 인기 집합을 넓히자 정상 충돌이 줄고 Hold 성공이 늘었지만 **서버 코드의 개선 전후가 아니다**. 성공할 때 DELETE 호출도 늘기 때문에 동일 iterations/s가 동일 HTTP 요청량을 뜻하지 않는다. DB 전역 wait 증분도 특정 좌석 SQL의 원인 증명이 아니다.

측정 단계마다 201 iterations가 완료됐다(목표 20/s×10초=200, 경계 스케줄링 포함). 각 단계의 인기/비인기 선택은 132/69로 동일했다. 70%는 설정 가중치이며 작은 표본의 정확한 할당 비율을 뜻하지 않는다. 전체 호스트 CPU peak는 측정 단계에서 95.18%, warm-up에서 100%였고 `ResourceReviewRequired:true`로 기록됐다. 최소 가용 메모리는 측정 단계에서 약 4.16GiB, 최대 표본 간격은 1,754ms였다. 처리 포화·안정 용량·p95 개선은 선언하지 않고 상위 부하는 보류한다.

첫 시도 `contrast200a`는 warm-up에서 표본 간격 4,592ms 오류로 중단됐으며 `Complete:false`, 완료된 측정 0건이다. 초기 manifest에는 실패 단계만 있고 간격 수치는 실행 오류에서 확인했다. 실패 이후 runner는 이후 실패의 표본 수·최대 간격도 보존하도록 보완했다. 실패 DB와 JSON은 삭제하지 않았고 재시도는 별도 빈 DB를 사용했다.

다음 판단의 입력은 **관측 사실 → 병목 가설 → 가설을 구분할 실험**이다. 예를 들어 정상 409 비중만으로 서버 증설을 제안하지 않고, 늘어난 DB wait가 반복되는지 및 호스트/계측 간섭을 별도로 확인한다. 이 흐름을 향후 AI가 보조할 조건은 [ADR-0005](adr/0005-evidence-grounded-experiment-assistance.md)에 기록했다. 이번 결과는 AI 분석 품질이나 RAG 도입 효과를 검증한 것이 아니며, 현재 MCP도 새 manifest를 자동으로 조회하지 않는다.

## 6. 같은 경합에서 계측 빈도 교차 검증

#202는 #200과 같은 Windows 8 logical CPU·15.81GiB RAM, Java 21 `-Xms256m -Xmx768m`·Hikari max 24, MariaDB 10.11.8 전용 빈 DB에서 실행했다. `-CompareSampling`은 가상 20석·인기 1석·가중치 70%·seed 17·Hold 500ms·20/20 VU·20 iterations/s를 고정한다. 각 단계는 15초다. 1초/2초 수집 warm-up을 각 1회 제외하고 `1초,2초 → 2초,1초 → 1초,2초`를 측정한다. 실제 KOPIS·PG·LLM 호출이나 부하 자동 증량은 없다.

| 측정 3회 합계 / 범위 | 1초 수집 | 2초 수집 |
| --- | --- | --- |
| 완료 / Hold·해제 / 정상 409 | 902 / 324 / 578 | 902 / 324 / 578 |
| 수집 횟수 (단계별) | 17회 | 9회 |
| 수집 함수 누적 경과 시간 (단계별) | 5,139.54~6,324.74ms | 2,814.30~3,925.08ms |
| Hold 성공 p95 범위 | 32.80~80.39ms | 34.21~93.43ms |
| 정상 409 p95 범위 | 28.64~72.74ms | 27.29~72.51ms |
| DB 전역 row-lock wait 증분 / 대기 ms (순서별) | 3/29, 5/25, 0/0 | 2/4, 4/63, 0/0 |
| 최대 표본 간격 (단계별 범위) | 1,298~1,613ms | 2,298~2,848ms |

[완료 manifest sampling202c](../../load-test/results/sampling202c/small-seat-sampling-contrast.json)의 측정 1,804건은 Hold·해제 648건과 정상 409 1,156건이다. dropped·예상 밖 오류·SQL deadlock·Hikari timeout·관측 pending·종료 Hold는 0이다. 단계별 독립 SQL은 `20,0,0,20,0`, API snapshot의 Booking·Payment도 0이다. 각 단계의 300/301 iterations 차이는 스케줄 경계에 있으며 선택 횟수도 인기 206/207, 비인기 94로 기록했다.

**말할 수 있는 결론:** 수집 빈도를 낮추면 이 실행의 수집 호출 수와 누적 경과 시간이 줄었다. 여기서 시간은 Prometheus HTTP·mysql 프로세스·DB 상태 조회·호스트 counter를 순차 수집하는 함수의 wall-clock이며, CPU 사용 시간이나 요청 경로 지연이 아니다. 두 조건의 p95 범위가 겹치므로 서버 성능 개선 또는 계측이 응답 지연의 원인이라는 결론은 내리지 않는다. DB wait는 앞선 두 쌍에 존재했으나 마지막 쌍에서는 모두 0으로, 지속적인 DB 포화가 반복됐다는 근거는 없다. DB 전역 counter만으로 특정 SQL에 귀속하지 않는다.

측정 host CPU peak는 앞선 단계에서 95~98%, 마지막 쌍에서는 73.01%/66.59%였고 최소 가용 메모리는 약 2.98GiB였다. 다른 표본 간격의 CPU·pending peak는 직접 비교할 수 없고, 관측 pending 0도 순간 대기를 모두 포착한 뜻은 아니다. `ResourceReviewRequired:true`를 유지하며 높은 CPU를 Backend/DB 단독 병목으로 단정하거나 상위 부하를 제안하지 않는다. 같은 호스트의 배경 작업과 시간에 따른 변화는 통제되지 않았다.

실패도 보존했다. [sampling202a](../../load-test/results/sampling202a/small-seat-sampling-contrast.json)는 새 15초 계획에 기존 10초 검증 조건이 적용돼 첫 warm-up 뒤 중단된 개발 과정의 실패다. 조건을 모드별로 분리하고 계약 테스트를 추가했다. [sampling202b](../../load-test/results/sampling202b/small-seat-sampling-contrast.json)는 warm-up 2회·측정 2회 후 sequence 5에서 표본 간격 3,420ms로 중단됐다. 모두 `Complete:false`이며 완료 배치의 비교에 포함하지 않는다. 2초 모드도 기존 3초 간격 상한을 완화하지 않았다. 재시도는 별도 빈 DB를 사용했으며 성공한 배치만 남기지 않았다.

실행은 전용 DB와 `local,loadtest` Backend를 준비한 뒤 `powershell -NoProfile -ExecutionPolicy Bypass -File load-test/scripts/Run-ControlledSmallSeatHoldRamp.ps1 -RunId <새 ID> -ComposeProject <전용 project> -CompareSampling`으로 재현한다. DB 포트 3309·2행×10석 설정 및 fresh DB 검증은 기존 fixture runner를 따른다. #204의 read-only MCP `assess_sampling_contrast`는 이 고정 schema 6만 별도로 검증한다. 완료 `sampling202c`는 warm-up 제외 관측과 자원 재검토 판정을 제공하고, 미완료 `sampling202a/b`는 부분 관측을 숨긴다. 순서·조건·재고·계측 평균/합계 모순도 차단한다. 이 판정은 결정적 계약 검증이며 AI의 병목 추론이나 RAG 성능을 평가한 결과가 아니다([ADR-0005](adr/0005-evidence-grounded-experiment-assistance.md)).
