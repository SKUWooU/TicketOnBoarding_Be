# ADR-0006: 공연 오픈 대기실과 구매 입장 제어

## 상태

신규 도메인 확장 방향·로컬 Redis 추가/실행 승인. #230 비노출 원자 코어·#232 인증 API/gate에 이어 #234에서 운영자 fresh bootstrap CLI·자동 승격 worker 구현. FE·구매 서비스 연동·입장 제어 전후 부하는 후속 단계.

## 맥락과 재현 근거

사용자는 공연 예매라는 기존 서비스에 오픈 시점 대기·순차 구매 입장을 추가하기로 했다. 현재 FE는 `/concertReservation/:concertID`에서 회차를 고르고 Hold를 요청한다. 기존 `SeatHoldService`는 좌석 재고를 보호하지만 구매 접근 순서는 관리하지 않았다. #228 조사 시 Redis 의존성은 없었으며 #230에서 추가했다. #232는 local+loadtest의 명시적 fixture 회차 한 개에만 API/gate를 연결한다.

#224·#226의 혼합 부하 비교는 warm-up 자원 gate에서 끝났으며 성능 개선·처리 한계 근거가 아니다. 이번 선택은 그 병목을 해결한다는 주장이 아니라 **새로운 입장 제어 요구**에 따른 확장이다. ADR-0004의 재검토 조건 중 명시적 입장 제어 요구가 생긴 것으로 해석한다. Kafka·outbox·운영 LB와 측정 peak만으로 인프라를 추가하지 않는 결정은 유지한다.

## 결정

- 첫 범위는 로그인 사용자와 공연 회차 단위의 대기 순서·기한부 입장권이다. 입장권은 좌석이나 결제 성공을 보장하지 않는다.
- 기본 동작은 유지하고, 명시적으로 지정한 로컬 fixture 회차에만 입장 제어를 적용한다. 적용 대상 정책은 Redis 자료 유무와 독립된 서버 소유 구성으로 판정한다. Redis 오류/자료 유실을 비활성 회차로 해석해 우회하지 않는다.
- #230의 상태 공유·순서·원자적 입장 배정은 단일 Redis와 Sorted Set·bounded Lua script로 구현한다. 좌석·Hold·Checkout·결제의 원본은 MariaDB로 유지하며 Redis를 좌석 재고의 분산 락으로 쓰지 않는다.
- 첫 FE는 기존 예약 화면의 회차 선택 뒤 대기 패널을 보여준다. polling부터 시작하며 별도 디자인 전면 수정·SSE·WebSocket은 제외한다.
- #232의 신규 Hold/Checkout만 `X-Admission-Token`으로 본인 입장권을 검증한다. 기존 Checkout 동일 결과 재사용·검증·취소·Hold 해제는 새 입장권이나 Redis 연결 없이 기존 소유권/상태 검증으로 처리한다. 입장 확인과 MariaDB 변경은 하나의 원자 트랜잭션이 아니며, gate 통과 후 lease 만료/이탈은 이미 진행 중인 DB 요청을 중단시키지 않는다.
- 활성 회차의 Checkout 없는 직접 `verified-reservation`은 PG 조회 전 차단한다. 활성 fixture 생성/수정·Hold reset·테스트 사용자/token 발급도 중단한다. 기존에 사용하지 않던 data-rest starter를 제거해 repository 자동 CRUD로 구매 경계를 우회하지 못하게 한다. 커스텀 Controller API와 JPA repository는 유지한다. 전역 Security·다른 관리자 기능을 안전하게 만든 것은 아니다.
- API는 Redis namespace를 bootstrap하거나 tick하지 않는다. #234의 standalone CLI만 새 namespace를 명시적으로 초기화하고, 기존 namespace/부분 자료는 Lua에서 원자 거절한다. 완전 유실 이후 동일 generation의 과거 사용 여부는 Redis만으로 알 수 없으므로 새 generation을 고르는 운영자 책임·확인 인자가 필요하다. 강한 재사용 방지 ledger/failover를 구현한 것은 아니다.
- 활성 local+loadtest fixture의 worker는 전용 단일 scheduler에서 기본 1초 fixed-delay로 bounded tick한다. 여러 앱 worker의 직렬성/입장 한도는 Lua가 보장하며 leader election이나 분산 락을 추가하지 않는다. Redis 실패는 안전한 상태 전이 로그와 낮은 cardinality 지표만 기록하고, 자동 초기화/입장 fallback 없이 다음 tick을 시도한다.
- API 설계 계약은 [학습 가이드 14절](../STUDY_GUIDE.md#14-공연-오픈-대기실의-설계-계약-228), 구현/검증과 미구현 경계는 [15절](../STUDY_GUIDE.md#15-redis-대기입장-원자-코어-230)에 둔다. #230의 로컬 Redis 추가·컨테이너 실행은 사용자 승인 후 수행했다.

Redis의 Lua 실행은 다른 요청과 섞이지 않는 원자 실행을 제공하지만 실행 중 서버를 막으므로, 전체 대기열 순회가 아닌 bounded 작업으로 설계한다([공식 scripting 문서](https://redis.io/docs/latest/develop/programmability/eval-intro/)). script의 원자 실행은 MariaDB까지 포함하는 transaction·오류 시 전체 rollback·crash durability를 뜻하지 않는다. 입력·자료형을 변경 전에 검증하고 실패 후 부분 변경도 검사해야 한다.

## 검토한 대안

- 브라우저에서 순번/입장 여부만 관리: API 직접 호출과 다중 인스턴스의 우회를 막지 못해 제외.
- 프로세스 메모리 큐: 단위 테스트용 모델은 가능하나 재시작·서버 간 공유 요구의 최종 구현으로 선택하지 않음.
- MariaDB 대기열: 더 적은 인프라의 유효한 대안. 기존 재고 DB와 대기 polling·입장 배정을 분리하고, 공유 원자 배정 경계를 검증하기 위해 Redis 후보를 선택했다. Redis가 SQL보다 빠르다는 실측 주장은 하지 않음.
- Kafka로 대기 순서 관리: 소비 로그와 사용자별 입장권 조회/만료는 다른 문제이므로 첫 기능에는 제외.

## 결과와 trade-off

유효 입장권 수를 제한하고 구매 단계 접근의 순서·유효성을 서버에서 검증할 수 있도록 설계한다. 한 사용자도 여러 API를 동시에 요청할 수 있고 이미 시작한 결제 처리는 계속되므로 **입장권 한도는 실제 DB 동시 transaction 수의 상한이 아니다**. 별도 요청 rate/in-flight 제한·과부하 보호·공격 방어는 후속 요구다. 대기실 자체의 polling·등록 부하는 사라지지 않는다.

Redis 장애는 신규 대기/입장 확인/입장권이 필요한 신규 구매 쓰기를 503으로 차단한다. 기존 Hold 해제·Checkout 취소·이미 준비된 Checkout 결제 검증은 MariaDB 소유권·상태 검증으로 계속 처리해 cleanup/결제를 대기실 장애에 묶지 않는다. 이 연속 처리 경계는 테스트로 검증하기 전에는 구현 성과가 아니다.

## 적용 범위와 한계

- 작은 명시적 fixture로 순서·중복·만료·다중 서버 공유/우회·장애를 검증한 뒤 FE를 연동한다. 기존 모든 공연을 2,000석으로 바꾸지 않는다.
- 첫 단계는 Redis 단일 노드다. 앱 재시작과 Redis 재시작/자료 유실은 구분한다. AOF 등 persistence는 clean restart 검증의 후보이며 장애 시 완전한 무유실을 보장하지 않는다([공식 persistence 문서](https://redis.io/docs/latest/management/persistence/)). 자료 유실 시 신규 입장을 닫고 운영자가 새 회차 generation으로 재개하며 이전 순번 복원을 주장하지 않는다.
- 알려진 고부하 포화의 해결·대규모 운영·고가용성·처리량 개선으로 서술하지 않는다. 부하 수치는 안정된 별도 환경이 확보됐을 때만 추가한다.

## 재검토 조건

정책·기한·공정성 기준의 변경, polling 비용, Redis failover/유실과 복구 요구, 실제 신규 구매 경로의 누락, 종료 요청 처리의 실패가 확인될 때 계약을 갱신한다. 다중 인스턴스 검증은 앱 두 개의 공유 상태 검증이며 Redis 고가용성 검증과 다르다.

## 관련 Issue·PR·측정

- [#228](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/228)
- [#230](https://github.com/SKUWooU/TicketOnBoarding_Be/issues/230)
- [ADR-0004](0004-defer-hold-admission-infrastructure.md), [실험 9절](../EXPERIMENTS.md#9-큰작은-다좌석-hold-혼합-224)
