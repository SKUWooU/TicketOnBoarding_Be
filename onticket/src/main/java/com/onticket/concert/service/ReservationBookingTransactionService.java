package com.onticket.concert.service;

import com.onticket.concert.domain.Booking;
import com.onticket.concert.dto.ReservRequest;
import com.onticket.concert.repository.BookingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

@RequiredArgsConstructor
@Service
public class ReservationBookingTransactionService {

    private final BookingRepository bookingRepository;
    private final SeatReservationService seatReservationService;

    @Transactional(rollbackFor = Exception.class)
    public LocalDateTime reserve(
            String username,
            String concertId,
            ReservRequest reservRequest,
            String idempotencyKey,
            String requestFingerprint
    ) throws Exception {
        Booking booking = new Booking();
        booking.setUsername(username);
        booking.setIdempotencyKey(idempotencyKey);
        booking.setRequestFingerprint(requestFingerprint);
        booking.setCreatedAt(LocalDateTime.now().truncatedTo(ChronoUnit.MICROS));
        bookingRepository.saveAndFlush(booking);

        seatReservationService.reserveSeat(username, concertId, reservRequest, booking);
        return booking.getCreatedAt();
    }
}
