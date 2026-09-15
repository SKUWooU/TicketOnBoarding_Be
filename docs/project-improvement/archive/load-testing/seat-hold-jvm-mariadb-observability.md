# 좌석 hold JVM·MariaDB 자원 병목 분리 관측

## 목적

#114의 Hikari pool 반복 matrix는 24에서 지연과 pending이 낮아졌지만, 모든 변형에서 host system CPU 중앙값이 100%였다. 이 문서는 Spring JVM·MariaDB container 지표를 같은 churn run에 연결해, pool 조정 결과를 단일 자원 개선으로 과장하지 않기 위한 로컬 진단 기준선이다.

## 변경

- Prometheus에서 JVM live thread, GC pause seconds/count를 파싱하고 run summary의 peak/delta로 남긴다.
- Docker Compose `stats --format json`의 MariaDB CPU·memory를 엄격히 파싱한다.
- container stats는 `-CollectMariaDbContainerStats` opt-in으로만 수집한다. 기본 runner에는 관측 부하를 추가하지 않는다.
- 일부 표본만 container stats가 있는 경우는 summary 생성을 실패시켜 누락값을 0으로 오인하지 않는다.

## 실측 조건과 결과

- Windows local 단일 Spring Boot, MariaDB 10.11.8 Compose, Hikari 24, `local,loadtest`
- 가상 2,000석 `distributed-churn`, 200 RPS·10초, loopback HTTP
- `-CollectMariaDbContainerStats`, performance threshold 비활성 diagnostic 1회

| 구분 | 관측값 |
| --- | ---: |
| Hikari active/pending peak | 24 / 86 |
| Spring process CPU peak | 49.27% |
| host system CPU peak | 99.76% |
| JVM live thread peak | 229 |
| JVM GC pause delta / count | 0.084초 / 16 |
| MariaDB container CPU peak | 77.27% |
| MariaDB container memory peak | 265.6MiB |
| DB row lock wait / deadlock delta | 0 / 0 |
| 표본 간격 최소/평균/최대 | 2,331 / 5,083 / 8,513ms |

final snapshot은 `activeHeldSeats=0`, `holdRows=0` 및 예약·결제·재고 불변식 정상으로 종료했고, 예상 밖 hold/release 실패는 없었다.

## 해석과 한계

- host CPU 포화는 Spring process CPU만으로 설명되지 않으며, 같은 run에서 MariaDB container CPU도 높게 관찰됐다. 따라서 Hikari 24의 개선을 JVM 또는 DB 한쪽만의 개선으로 단정하지 않는다.
- Docker stats를 같은 PowerShell collector에서 호출하면 표본 간격이 목표 1초보다 크게 늘어난다. 이 결과는 pool 10·16·24의 #114 성능 비교와 섞지 않으며, container peak는 보조 진단값이다.
- 단일 Windows/Docker Desktop 인스턴스와 k6 process의 CPU를 완전히 분리하지 못한다. production CPU 비율, DB CPU 원인, GC tuning의 근거가 아니다.
- 반복 container 관측 또는 독립 host telemetry가 필요해질 때만 별도 Issue로 진행한다. Grafana·Redis·대기열·Kafka는 이 결과만으로 도입하지 않는다.

## 재현

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File load-test/scripts/Measure-SeatHoldContention.ps1 `
  -Scenario distributed-churn -Rate 200 -DurationSeconds 10 `
  -RunId example-observe -FixtureRunId example-observe `
  -ExpectedHikariMax 24 -CollectMariaDbContainerStats -DisablePerformanceThresholds
```

실제 KOPIS·PG·SMS·OAuth 호출은 포함하지 않는다.
