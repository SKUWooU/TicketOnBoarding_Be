# Checkout HTTP 요청·iteration 단위 분리

## 계약

- Spring Boot Actuator의 표준 `http_server_requests_seconds_count`를 재사용한다.
- tag는 URI template·`POST` method·HTTP status만 사용하며 사용자·좌석·merchantUid를 넣지 않는다.
- Checkout k6 iteration은 `seat-holds → checkouts → verified-reservation`의 최대 세 HTTP 요청을 포함한다. 따라서 iteration과 HTTP 요청 수를 같은 단위로 취급하지 않는다.
- 성공 Checkout iteration은 세 endpoint의 HTTP 200 delta가 각각 `checkoutConfirmed`와 같은지 검증한다.

## 로컬 fixture 결과

| 조건 | 완료 iteration | dropped iteration | hold/prepare/verify HTTP 200 delta |
| --- | ---: | ---: | --- |
| distributed 50 RPS·10초 | 500 | 0 | 500 / 500 / 500 |
| distributed 100 RPS·10초 | 598 | 403 | 598 / 598 / 598 |

Docker MariaDB·mock PG·가상 좌석 2,000개에서 실행했으며, 두 run 모두 unexpected failure·deadlock 0과 최종 재고 invariant를 충족했다. 100 RPS의 dropped는 VU 200 cap과 함께 관찰된 local fixture 결과다.

## 한계

이 metric은 endpoint별 HTTP 완료 수이며 transaction commit, DB statement 또는 실제 운영 처리량과 동의어가 아니다. 성공 iteration의 세 200 응답만 교차 검증하며, 중간 409·기타 실패 endpoint의 흐름 귀속은 별도 시나리오가 필요하다.
