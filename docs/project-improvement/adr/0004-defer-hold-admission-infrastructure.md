# ADR-0004: Hold 경합의 대기열·메시지 인프라 도입 보류

## 상태

보류

## 맥락과 재현 근거

가상 20석 fixture의 인기 1석/70%, Hold 500ms, 20·30 RPS 교차 6단계 측정에서 전체 호스트 CPU 순간 최고치가 #196의 91.5%, #198 재측정의 87.2%·91.2%로 나타났다. 그러나 #198의 두 실행에서는 모든 단계에서 예상 밖 오류·dropped·Hikari pending·timeout·DB deadlock·최종 Hold가 0이었고, 독립 SQL 재고도 `20,0,0,20,0`이었다. 정상적인 좌석 충돌 HTTP 409는 실패나 서버 포화로 세지 않는다.

#198의 JVM·MariaDB PID 1·k6·Windows 프로세스군 계측은 호스트 peak를 Backend/DB 단독 병목으로 귀속할 근거를 주지 않았다. 같은 표본에서 기타 읽기 가능한 프로세스군도 CPU를 소비했다. 다만 프로세스 카운터와 전체 CPU의 표본 창·분모가 다르고, 커널 CPU 및 읽지 못한 프로세스가 있으므로 전체 peak의 원인을 완전히 분해하지 못했다. 추가 계측 때 표본 간격도 길어져 기존 p95와 전후 개선 비교가 불가능하다.

## 결정

현재 증거만으로 Hold 대기열, Kafka, Redis 분산 락 또는 자동 RPS 증량을 도입하지 않는다. 우선 기존 DB 상태 전이·잠금과 재고 불변식 검증을 유지한다. 이는 이 기술들이 불필요하다는 일반적 결론이 아니라, **이번 로컬 20석 fixture로 도입 필요성을 입증하지 못했다**는 결정이다.

## 검토한 대안

- 현재 동작을 유지하고 자원 계측을 보강한다 — 현재 선택.
- 호스트 CPU peak만 근거로 요청 대기열/메시지 브로커를 추가한다 — Backend 처리 포화나 대기 폭증이 확인되지 않아 보류.
- RPS만 계속 높여 용량을 찾는다 — 공유 노트북의 다른 프로세스와 관측 오버헤드가 섞여 결과 해석이 어려워 보류.

## 결과와 trade-off

새 인프라의 운영·일관성 복잡도를 피한다. 대신 운영 용량, 순간 CPU peak의 정확한 원인, 더 높은 부하에서의 안정성은 아직 알 수 없다. 현재 MCP의 `RESOURCE_REVIEW_REQUIRED`는 자동 증량 금지 신호이며, #198 CPU 부가 파일을 자동으로 판정하지는 않는다.

## 적용 범위와 한계

16GB RAM 노트북(Windows 인식 15.81GiB)의 loopback Backend·Docker MariaDB·k6와 가상 20석 전용 fixture에 한정한다. 실제 KOPIS 좌석·PG·운영 트래픽·다중 인스턴스 결과가 아니다. 이 결과를 기존 2,000석 fixture의 성능 전후 비교로 사용하지 않는다.

## 재검토 조건

- 같은 부하 조건에서 Backend/DB의 지속적인 CPU 포화 또는 lock/connection 대기와 지연·오류의 동반 증가가 반복 재현될 때
- 대기열 없이 처리할 수 없는 명시적 입장 제어 요구 또는 다중 인스턴스 간 점유 경합·유실이 재현될 때
- 계측 부하와 Windows/가상화·기타 프로세스의 CPU를 분리한 환경에서 새로운 기준선이 확보될 때

이 조건이 생기면 DB 잠금 유지, admission control, 메시지화의 비용·정합성·복구 경계를 다시 비교한다.

## 관련 Issue·PR·측정

- [#196](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/196), [#198](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/198)
- [실험 조건·결과](../EXPERIMENTS.md), [`attr198a` CPU](../../../load-test/results/attr198a/small-seat-cpu-attribution.json), [`attr198b` CPU](../../../load-test/results/attr198b/small-seat-cpu-attribution.json)
