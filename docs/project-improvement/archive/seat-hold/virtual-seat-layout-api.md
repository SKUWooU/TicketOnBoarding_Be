# 가상 공연장 layout·구역별 좌석 API 계약

## 문제와 범위

기존 API는 회차의 모든 좌석을 한 번에 반환하고 `A1`, `R001-S001` 같은 표시용 번호만
제공했다. 24석 화면은 프론트엔드 하드코딩으로 동작했지만 2,000석 fixture를 연결하면
구역·행을 알 수 없고, 서버 재고와 화면 규칙이 달라지며, 전체 좌석을 한 번에 조회·렌더링한다.

이번 변경은 실제 공연장 좌석도가 아니라 **KOPIS 공연·회차에 연결되는 가상 좌석 재고의
표시 계약**을 정의한다. 운영 공연장 좌표, 등급별 가격, 통로와 무대 형상은 범위 밖이다.

## 탑다운 구조

```text
Concert
└─ ConcertTime (seatLayoutVersion)
   ├─ Section summary API → 구역별 total / available / held / reserved
   └─ Section detail API  → Seat (section → row → seatIndex 순서)
```

`VirtualSeatLayoutFactory`가 좌석 번호와 표시 메타데이터를 함께 생성한다. KOPIS 기반 24석과
로컬 부하 fixture가 같은 서버 소유 규약을 사용하며 클라이언트는 좌석 번호를 파싱하지 않는다.

## 저장 모델

`ConcertTime.seatLayoutVersion`은 응답 계약의 버전을 표시한다. `Seat`에는 구역 코드·이름·순서,
행 이름·순서, 행 내부 좌석 순서를 추가했다. 필드를 nullable로 둔 이유는 기존 DB 좌석을 이
PR에서 임의 변환하지 않기 위해서다. 기존 좌석은 종전 API로 조회할 수 있고, 새 API는 불완전한
레이아웃을 추정하지 않고 HTTP 409로 알린다. 운영 migration은 별도 판단 대상이다.

조회 인덱스 `(concert_time_id, layout_section_code, layout_row_order, layout_seat_index)`는 선택한
회차·구역의 정렬 조회 경로를 명시한다. 기존 `(concert_time_id, seat_number)` unique는 유지한다.

## 가상 레이아웃 규칙

### 일반 24석

- version `virtual-24-v1`, section `GENERAL` / `일반석`
- A~C행, 각 8석, 기존 번호 `A1`~`C8` 유지

### 부하 fixture 2,000석

- version `loadtest-sectioned-v1`, 50행 × 40석
- 5행씩 `S01`~`S10`, 구역당 200석
- 기존 번호 `R001-S001`~`R050-S040` 유지

2,000은 실제 예매처 수용 규모나 성능을 뜻하지 않는다. 고경합 실험용 가상 재고 크기이며,
구역당 200석은 프론트엔드가 한 번에 다루는 데이터 양을 제한하는 계약상의 기준이다.

## API 계약

- `GET /main/detail/{concertId}/calendar/{timeId}/seat-sections`: 공연-회차 소유 관계를 확인하고
  레이아웃 버전과 구역별 재고를 반환한다. 만료 hold는 `AVAILABLE`로 집계한다.
- `GET /main/detail/{concertId}/calendar/{timeId}/seat-sections/{sectionCode}`: 선택 구역만
  `rowOrder → seatIndex → id` 순으로 반환하며 활성 hold의 만료 시각을 제공한다.
- 공연과 회차 불일치 또는 없는 구역은 404, 레이아웃 누락은 409다.
- 기존 `/main/detail/{concertId}/calendar/{timeId}`는 호환을 위해 유지한다.

## 검증과 결과

MariaDB 10.11.8 Testcontainers와 고정 `Clock`으로 다음을 확인했다.

- 24석 1개 구역과 A1~C8 정렬
- 예약 1·활성 hold 1·만료 hold 1일 때 total 24 / available 22 / held 1 / reserved 1
- 활성 hold만 `holdExpiresAt` 노출
- 다른 공연의 회차와 없는 구역은 404, legacy 회차는 409
- 2,000석은 10개 구역·구역당 200석, `S01` 상세는 200석
- Backend 전체 186 tests 통과(실패·오류·skip 0)

이는 기능·정합성 fixture 결과이며 TPS나 브라우저 렌더링 성능 측정이 아니다. 프론트 연동 후
원본 JSON을 보존하는 브라우저 viewport 측정으로 구역 분할 전후를 별도 비교한다.

## 제외 및 후속 조건

- 실제 KOPIS·PG·SMS 호출, 운영 DB migration 없음
- 실제 좌석도·가격·좌표, WebSocket, Redis, 대기열, 브로커 없음
- 프론트 반응형 CSS와 virtualization은 후속 Issue

후속 UI에서는 좌석 버튼의 최소 클릭 크기는 px/rem으로 유지하고, 구역 컨테이너에 max-width,
Grid, breakpoint 또는 제한적 가로 스크롤을 적용한다. 전체 좌석을 vw/vh로 단순 치환하지 않는다.

## 연결

- Backend Issue #85
- ADR-0003
