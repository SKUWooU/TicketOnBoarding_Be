package com.onticket.concert.controller;

import com.onticket.concert.dto.SeatLayoutSummaryDto;
import com.onticket.concert.dto.SeatSectionSummaryDto;
import com.onticket.concert.service.ConcertService;
import com.onticket.concert.service.SeatLayoutNotFoundException;
import com.onticket.concert.service.SeatLayoutQueryService;
import com.onticket.concert.service.SeatLayoutUnavailableException;
import com.onticket.concert.service.SeatReservationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.MediaType;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class ConcertControllerSeatLayoutTest {

    private SeatLayoutQueryService seatLayoutQueryService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        seatLayoutQueryService = mock(SeatLayoutQueryService.class);
        ConcertController controller = new ConcertController(
                mock(ConcertService.class),
                mock(SeatReservationService.class),
                seatLayoutQueryService
        );
        mockMvc = standaloneSetup(controller).build();
    }

    @Test
    void exposesSectionSummaryContract() throws Exception {
        when(seatLayoutQueryService.getSectionSummary("CONCERT-1", 10L))
                .thenReturn(new SeatLayoutSummaryDto(
                        10L,
                        "virtual-24-v1",
                        List.of(new SeatSectionSummaryDto("GENERAL", "일반석", 1, 24, 22, 1, 1))
                ));

        mockMvc.perform(get("/main/detail/CONCERT-1/calendar/10/seat-sections")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.layoutVersion").value("virtual-24-v1"))
                .andExpect(jsonPath("$.sections[0].availableSeats").value(22));
    }

    @Test
    void mapsAnOwnershipMismatchToNotFound() throws Exception {
        when(seatLayoutQueryService.getSectionSummary("CONCERT-1", 10L))
                .thenThrow(new SeatLayoutNotFoundException("not found"));

        mockMvc.perform(get("/main/detail/CONCERT-1/calendar/10/seat-sections"))
                .andExpect(status().isNotFound());
    }

    @Test
    void mapsLegacyLayoutToConflict() throws Exception {
        when(seatLayoutQueryService.getSectionSummary("CONCERT-1", 10L))
                .thenThrow(new SeatLayoutUnavailableException("unavailable"));

        mockMvc.perform(get("/main/detail/CONCERT-1/calendar/10/seat-sections"))
                .andExpect(status().isConflict());
    }
}
