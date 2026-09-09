# loadtest fixture 공연 상세 계약 복구

## 문제

`local,loadtest`의 2,000석 fixture는 `ConcertDetail.place` 문자열만 만들고 `placeId`와 `Place` 행을 만들지 않았다. `GET /main/detail/{concertId}`는 `placeId`로 장소를 조회한 뒤 역참조하므로 loadtest 공연 상세가 500으로 실패했다.

장소를 보완한 뒤에는 기존에 가려져 있던 두 번째 경로가 드러났다. 상세 DTO가 lazy `reviews` 컬렉션 프록시를 그대로 반환해, HTTP JSON 직렬화 시 session이 닫힌 뒤 다시 500이 발생했다.

## 결정

1. runId마다 명시적인 가상 `Place`를 생성한다. 장소 ID·주소·좌표는 fixture 값이며 실제 공연장 데이터가 아니다.
2. 새 fixture는 `ConcertDetail.placeId`를 장소에 연결한다.
3. 기존 runId도 재초기화할 때 장소가 없거나 `placeId`가 비어 있으면 같은 가상 장소를 생성·연결한다.
4. 상세 서비스는 transaction 안에서 review 목록을 일반 리스트로 복사한다. 빈 관계는 빈 목록으로 반환한다.

## 검증

환경: Windows 로컬 단일 Backend, Docker MariaDB, `local,loadtest`, batch 비활성화. KOPIS·PG·SMS 호출 없음.

MariaDB Testcontainers `LoadTestFixtureIntegrationTest`는 다음을 검증한다.

- 2,000석·10구역·구역당 200석 생성과 같은 runId 재초기화
- 상세 DTO의 가상 장소명·주소·좌표·빈 review 목록
- 기존 fixture의 `placeId`와 Place 행을 의도적으로 제거한 뒤 재초기화로 복구
- 서비스 transaction 종료 뒤 `ObjectMapper` 직렬화로 lazy review proxy가 DTO에 남지 않음을 확인

실제 HTTP에서는 `runId=detail-fixture-88`로 다음을 확인했다.

| API | 결과 |
| --- | --- |
| `POST /loadtest/runs` | concert 1개·회차 1개 생성 또는 재사용 |
| `GET /main/detail/{concertId}` | 200, 가상 장소·주소, review 0개 |
| `GET /main/detail/{concertId}/calendar` | 회차 1개 |
| `GET .../seat-sections` | 구역 10개, 합계 2,000석 |

## 한계

- 가상 장소명·주소·좌표는 화면 및 계약 검증을 위한 fixture이며 실제 KOPIS 장소 정보가 아니다.
- 이 결과는 로컬 기능 계약 검증이지 운영 API 가용성·처리량·실제 예매처 성능이 아니다.
- 전체 Frontend 반응형과 browser E2E는 이 Backend 계약을 소비하는 별도 범위다.
