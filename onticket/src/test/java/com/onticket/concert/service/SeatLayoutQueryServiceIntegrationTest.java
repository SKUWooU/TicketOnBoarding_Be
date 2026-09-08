package com.onticket.concert.service;

import com.onticket.concert.domain.Concert;
import com.onticket.concert.domain.ConcertTime;
import com.onticket.concert.domain.Seat;
import com.onticket.concert.domain.SeatAvailability;
import com.onticket.concert.dto.SeatLayoutSummaryDto;
import com.onticket.concert.dto.SeatSectionDetailDto;
import com.onticket.concert.repository.ConcertRepository;
import com.onticket.concert.repository.ConcertTimeRepository;
import com.onticket.concert.repository.SeatRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=create",
        "spring.jpa.show-sql=false"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
        SeatLayoutQueryService.class,
        VirtualSeatLayoutFactory.class,
        SeatLayoutQueryServiceIntegrationTest.ClockConfiguration.class
})
@Testcontainers
class SeatLayoutQueryServiceIntegrationTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2030, 1, 1, 12, 0);

    @Container
    static final MariaDBContainer<?> MARIA_DB = new MariaDBContainer<>("mariadb:10.11.8")
            .withDatabaseName("onticket_seat_layout")
            .withUsername("onticket")
            .withPassword("onticket");

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MARIA_DB::getJdbcUrl);
        registry.add("spring.datasource.username", MARIA_DB::getUsername);
        registry.add("spring.datasource.password", MARIA_DB::getPassword);
        registry.add("spring.datasource.driver-class-name", MARIA_DB::getDriverClassName);
    }

    @Autowired SeatLayoutQueryService queryService;
    @Autowired VirtualSeatLayoutFactory layoutFactory;
    @Autowired ConcertRepository concertRepository;
    @Autowired ConcertTimeRepository concertTimeRepository;
    @Autowired SeatRepository seatRepository;

    @Test
    void summarizesCurrentInventoryAndReturnsSeatsInLayoutOrder() {
        Fixture fixture = createStandardFixture("LAYOUT-CONCERT");
        List<Seat> seats = seatRepository
                .findByConcertTimeIdAndLayoutSectionCodeOrderByLayoutRowOrderAscLayoutSeatIndexAscIdAsc(
                        fixture.timeId(), "GENERAL"
                );
        seats.get(0).markReserved();
        seats.get(1).holdFor("holder", NOW, NOW.plusMinutes(5));
        seats.get(2).holdFor("expired", NOW.minusMinutes(10), NOW.minusMinutes(5));
        seatRepository.saveAllAndFlush(seats.subList(0, 3));

        SeatLayoutSummaryDto summary = queryService.getSectionSummary(fixture.concertId(), fixture.timeId());
        SeatSectionDetailDto detail = queryService.getSection(fixture.concertId(), fixture.timeId(), "GENERAL");

        assertThat(summary.layoutVersion()).isEqualTo(VirtualSeatLayoutFactory.STANDARD_VERSION);
        assertThat(summary.sections()).singleElement().satisfies(section -> {
            assertThat(section.totalSeats()).isEqualTo(24);
            assertThat(section.availableSeats()).isEqualTo(22);
            assertThat(section.heldSeats()).isEqualTo(1);
            assertThat(section.reservedSeats()).isEqualTo(1);
        });
        assertThat(detail.seats()).hasSize(24);
        assertThat(detail.seats().getFirst().seatNumber()).isEqualTo("A1");
        assertThat(detail.seats().getFirst().availability()).isEqualTo(SeatAvailability.RESERVED);
        assertThat(detail.seats().get(1).availability()).isEqualTo(SeatAvailability.HELD);
        assertThat(detail.seats().get(1).holdExpiresAt()).isEqualTo(NOW.plusMinutes(5));
        assertThat(detail.seats().get(2).availability()).isEqualTo(SeatAvailability.AVAILABLE);
        assertThat(detail.seats().getLast().seatNumber()).isEqualTo("C8");
    }

    @Test
    void rejectsConcertTimeOwnedByAnotherConcertAndUnknownSection() {
        Fixture fixture = createStandardFixture("OWNER-A");

        assertThatThrownBy(() -> queryService.getSectionSummary("OWNER-B", fixture.timeId()))
                .isInstanceOf(SeatLayoutNotFoundException.class);
        assertThatThrownBy(() -> queryService.getSection(fixture.concertId(), fixture.timeId(), "UNKNOWN"))
                .isInstanceOf(SeatLayoutNotFoundException.class);
    }

    @Test
    void explicitlyRejectsLegacySeatsWithoutLayoutMetadata() {
        Concert concert = saveConcert("LEGACY-CONCERT");
        ConcertTime concertTime = new ConcertTime();
        concertTime.setConcert(concert);
        concertTime.setSeatAmount(1);
        concertTime = concertTimeRepository.saveAndFlush(concertTime);
        Seat seat = new Seat();
        seat.setConcertTime(concertTime);
        seat.setSeatNumber("A1");
        seatRepository.saveAndFlush(seat);

        Long timeId = concertTime.getId();
        assertThatThrownBy(() -> queryService.getSectionSummary(concert.getConcertId(), timeId))
                .isInstanceOf(SeatLayoutUnavailableException.class);
    }

    private Fixture createStandardFixture(String concertId) {
        Concert concert = saveConcert(concertId);
        VirtualSeatLayoutFactory.LayoutDefinition layout = layoutFactory.standardLayout();
        ConcertTime concertTime = new ConcertTime();
        concertTime.setConcert(concert);
        concertTime.setSeatAmount(layout.positions().size());
        concertTime.setSeatLayoutVersion(layout.version());
        concertTime = concertTimeRepository.saveAndFlush(concertTime);

        ConcertTime savedTime = concertTime;
        List<Seat> seats = layout.positions().stream().map(position -> {
            Seat seat = new Seat();
            seat.setConcertTime(savedTime);
            seat.setSeatNumber(position.seatNumber());
            seat.assignLayout(
                    position.sectionCode(), position.sectionName(), position.sectionOrder(),
                    position.rowLabel(), position.rowOrder(), position.seatIndex()
            );
            return seat;
        }).toList();
        seatRepository.saveAllAndFlush(seats);
        return new Fixture(concertId, concertTime.getId());
    }

    private Concert saveConcert(String concertId) {
        Concert concert = new Concert();
        concert.setConcertId(concertId);
        concert.setConcertName(concertId);
        return concertRepository.saveAndFlush(concert);
    }

    private record Fixture(String concertId, Long timeId) {
    }

    @TestConfiguration
    static class ClockConfiguration {
        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2030-01-01T12:00:00Z"), ZoneOffset.UTC);
        }
    }
}
