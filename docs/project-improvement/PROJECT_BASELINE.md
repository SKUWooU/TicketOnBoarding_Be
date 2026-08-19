# 프로젝트 개선 기준선

## 조사 범위

- 조사일: 2026-08-19 (Asia/Seoul)
- Backend: `main` / `bcfa7e30c947975e1f7f89d5eef0e8bffe36b9a1`
- Frontend: `main` / `1f9678be7a3a66ec610c6ef4ea335e9d6f5cbafd`
- 조사 순서: 원격 최신화, Issue·PR·Branch, README, 빌드 설정, 도메인·DB·인증·외부 API·배치·결제·FE 연동, 테스트·CI, 실행 가능 여부
- 실제 KOPIS·CoolSMS·OAuth·결제·운영 DB 호출은 수행하지 않음

## 저장소 상태

조사 시점에 두 저장소의 로컬 `main`과 `origin/main`은 일치했고 추적 파일 변경은 없었습니다. 공개 조회 기준 열린 Issue·PR은 없었고 원격 Branch는 각 저장소에서 `main`만 확인했습니다.

두 저장소 모두 Issue·PR template과 CI workflow가 없었습니다. 작업 이력은 저장소별 Issue와 PR로 분리하고 관련 Backend/Frontend 작업을 상호 링크합니다.

## Backend

### 확인된 구성

- Java source compatibility 21
- Spring Boot 3.2.5, Gradle wrapper 8.7
- Spring MVC, Spring Data JPA, Spring Security, Spring Batch
- MariaDB
- JWT access/refresh token과 Naver·Google 로그인
- KOPIS 공연 수집 batch
- CoolSMS 문자 인증
- 공연, 공연 상세, 장소, 회차, 좌석, 예약, 리뷰와 사용자 도메인

의존성에는 JDBC·JPA·Data REST·WebFlux 등이 함께 포함되고 MariaDB driver 버전이 중복 선언되어 있습니다. 이것이 실제 문제인지는 별도 빌드·실행 Issue에서 검증해야 합니다.

### 실행·테스트 기준선

- 시스템 `java -version`: Java 21.0.1
- 실행 당시 `JAVA_HOME`: JDK 17
- `compileJava`: `invalid source release: 21`로 실패
- Test: `@SpringBootTest`의 `contextLoads()` 1개
- CI: 없음
- DB migration: 확인되지 않음
- Docker/Testcontainers 기반 재현 환경: 없음

Gradle wrapper 배포 파일과 의존성 다운로드에는 네트워크가 필요합니다. 로컬의 ignored `application.yml`은 DB와 외부 연동 비밀값을 포함할 수 있으므로 내용과 값은 공개 문서에 기록하지 않습니다.

위 항목은 최초 조사 시점의 실행 상태입니다. Issue #3에서 Temurin JDK 21.0.12와 Testcontainers MariaDB 10.11.8 기반 테스트 환경을 추가한 뒤 `compileJava`와 전체 테스트 6개가 통과했습니다. 애플리케이션의 운영 설정과 실제 외부 연동을 포함한 실행 가능 여부는 아직 검증하지 않았습니다.

### 예약 흐름에서 확인된 사실

- 특정 회차·좌석 번호 조회에 `PESSIMISTIC_WRITE` 사용
- `reserveSeat()`는 `@Transactional`
- 좌석을 입력 목록 순서대로 하나씩 잠그고 `reserved=true`로 변경
- 예약 row를 좌석별로 생성
- 공통 `ConcertTime.seatAmount`를 읽은 값에서 요청 좌석 수만큼 감소
- 예약 생성 시 상태 문자열을 바로 결제 완료로 설정
- 취소 처리는 좌석을 해제하고 잔여 수량을 증가시키며 취소 완료 문자열로 변경

### 아직 검증하지 않은 가설

- 반대 순서 복수 좌석 요청이 deadlock을 만드는지
- 동일 예약·취소 요청의 반복 결과가 정합한지

### Issue #3에서 검증한 기준선

Java 21과 Testcontainers MariaDB 10.11.8 환경을 구성해 가상 좌석 24개로 검증했습니다. 동일 좌석 8개 동시 요청은 한 건만 성공했고, 대기 요청은 commit된 예약 상태를 확인했습니다. 반면 서로 다른 8좌석의 예약은 모두 성공했지만 잔여 수량 감소 7회가 유실됐습니다. 복수 좌석 중 checked exception이 발생하면 앞선 좌석과 예약 row가 부분 commit되는 것도 확인했습니다.

상세 fixture, 반복 횟수, 최종 DB 상태와 한계는 [예매 트랜잭션·경합 기준선](reservation-transaction-concurrency-baseline.md)에 기록합니다. 아직 검증하지 않은 항목은 동시성 fixture로 확인하기 전까지 개선 성과나 결함 확정으로 표현하지 않습니다.

## Frontend

### 확인된 구성

- React 18, Vite 5, JavaScript
- Axios, React Router, MUI, SCSS
- Backend base URL은 Vite 환경변수 사용
- Kakao Map, Naver·Google OAuth
- PortOne browser SDK를 통한 KakaoPay·이니시스 결제 화면
- 별도 frontend test script와 테스트 파일은 확인되지 않음
- CI 없음

### 실행 기준선

- README 요구 버전: Node 20.13.0, npm 10.5.2
- 조사 환경: Node 25.1.0, npm 11.6.2
- `npm ci`: Windows `ENOTEMPTY`로 실패
- 의존성이 완성되지 않아 lint와 production build를 검증하지 못함
- 실패 중 생성된 불완전한 ignored `node_modules`는 제거함

### 결제 경계에서 확인된 사실

브라우저 결제 성공 callback 이후 Backend 예약 API를 호출합니다. 조사한 Backend에는 결제 엔티티, 서버 측 승인·금액 검증 또는 결제 이벤트 중복 방지 흐름이 확인되지 않았습니다.

결제 위·변조, 결제 성공 후 예약 실패, callback 중복과 취소·환불 상태 전이는 실제 결제 호출이 아닌 mock과 명시적 fixture로 먼저 검증해야 합니다.

## 측정 표현 규칙

- 실제 좌석 데이터가 아니라 명시적 가상 좌석 fixture를 사용합니다.
- 로컬 단일 인스턴스, DB, fixture 수, 요청 방식과 시간을 함께 기록합니다.
- TPS·p95·오류율만으로 원인을 단정하지 않고 lock wait, DB connection, rollback과 최종 데이터 상태를 함께 확인합니다.
- fixture 결과를 실제 예매처 성능, 운영 SLA 또는 최대 처리량으로 표현하지 않습니다.
