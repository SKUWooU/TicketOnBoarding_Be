package com.onticket.concert.service;

import com.onticket.concert.domain.ConcertTime;
import com.onticket.concert.domain.Seat;
import com.onticket.concert.domain.SeatAvailability;
import com.onticket.concert.dto.SeatLayoutSummaryDto;
import com.onticket.concert.dto.SeatPositionDto;
import com.onticket.concert.dto.SeatSectionDetailDto;
import com.onticket.concert.dto.SeatSectionSummaryDto;
import com.onticket.concert.repository.ConcertTimeRepository;
import com.onticket.concert.repository.SeatRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class SeatLayoutQueryService {

    private final ConcertTimeRepository concertTimeRepository;
    private final SeatRepository seatRepository;
    private final Clock clock;

    @Transactional(readOnly = true)
    public SeatLayoutSummaryDto getSectionSummary(String concertId, Long concertTimeId) {
        ConcertTime concertTime = requireOwnedLayout(concertId, concertTimeId);
        List<SeatRepository.SeatSectionInventoryProjection> inventory =
                seatRepository.summarizeLayoutSections(concertTimeId, LocalDateTime.now(clock));

        long structuredSeats = inventory.stream()
                .mapToLong(SeatRepository.SeatSectionInventoryProjection::getTotalSeats)
                .sum();
        if (inventory.isEmpty() || structuredSeats != seatRepository.countByConcertTimeId(concertTimeId)) {
            throw unavailable(concertTimeId);
        }

        List<SeatSectionSummaryDto> sections = inventory.stream()
                .map(section -> {
                    requireSectionMetadata(section, concertTimeId);
                    long available = section.getTotalSeats() - section.getHeldSeats() - section.getReservedSeats();
                    return new SeatSectionSummaryDto(
                            section.getSectionCode(),
                            section.getSectionName(),
                            section.getSectionOrder(),
                            section.getTotalSeats(),
                            available,
                            section.getHeldSeats(),
                            section.getReservedSeats()
                    );
                })
                .toList();
        return new SeatLayoutSummaryDto(concertTimeId, concertTime.getSeatLayoutVersion(), sections);
    }

    @Transactional(readOnly = true)
    public SeatSectionDetailDto getSection(String concertId, Long concertTimeId, String sectionCode) {
        ConcertTime concertTime = requireOwnedLayout(concertId, concertTimeId);
        List<Seat> seats = seatRepository
                .findByConcertTimeIdAndLayoutSectionCodeOrderByLayoutRowOrderAscLayoutSeatIndexAscIdAsc(
                        concertTimeId,
                        sectionCode
                );
        if (seats.isEmpty()) {
            throw new SeatLayoutNotFoundException("요청한 좌석 구역을 찾을 수 없습니다.");
        }
        if (seats.stream().anyMatch(this::hasIncompleteLayout)) {
            throw unavailable(concertTimeId);
        }

        Seat first = seats.get(0);
        LocalDateTime now = LocalDateTime.now(clock);
        List<SeatPositionDto> positions = seats.stream()
                .map(seat -> toPosition(seat, now))
                .toList();
        return new SeatSectionDetailDto(
                concertTimeId,
                concertTime.getSeatLayoutVersion(),
                first.getLayoutSectionCode(),
                first.getLayoutSectionName(),
                first.getLayoutSectionOrder(),
                positions
        );
    }

    private ConcertTime requireOwnedLayout(String concertId, Long concertTimeId) {
        ConcertTime concertTime = concertTimeRepository
                .findByIdAndConcert_ConcertId(concertTimeId, concertId)
                .orElseThrow(() -> new SeatLayoutNotFoundException("공연에 속한 회차를 찾을 수 없습니다."));
        if (concertTime.getSeatLayoutVersion() == null || concertTime.getSeatLayoutVersion().isBlank()) {
            throw unavailable(concertTimeId);
        }
        return concertTime;
    }

    private void requireSectionMetadata(
            SeatRepository.SeatSectionInventoryProjection section,
            Long concertTimeId
    ) {
        if (section.getSectionCode() == null || section.getSectionName() == null
                || section.getSectionOrder() == null) {
            throw unavailable(concertTimeId);
        }
    }

    private boolean hasIncompleteLayout(Seat seat) {
        return seat.getLayoutSectionName() == null
                || seat.getLayoutSectionOrder() == null
                || seat.getLayoutRowLabel() == null
                || seat.getLayoutRowOrder() == null
                || seat.getLayoutSeatIndex() == null;
    }

    private SeatPositionDto toPosition(Seat seat, LocalDateTime now) {
        SeatAvailability availability = seat.availabilityAt(now);
        return new SeatPositionDto(
                seat.getId(),
                seat.getSeatNumber(),
                seat.getLayoutRowLabel(),
                seat.getLayoutRowOrder(),
                seat.getLayoutSeatIndex(),
                availability,
                availability == SeatAvailability.HELD ? seat.getHeldUntil() : null
        );
    }

    private SeatLayoutUnavailableException unavailable(Long concertTimeId) {
        return new SeatLayoutUnavailableException(
                "회차 " + concertTimeId + "에는 구역 좌석 레이아웃이 구성되지 않았습니다. 기존 좌석 API를 사용해주세요."
        );
    }
}
