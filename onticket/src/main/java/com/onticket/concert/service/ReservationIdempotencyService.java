package com.onticket.concert.service;

import com.onticket.concert.domain.Booking;
import com.onticket.concert.dto.ReservRequest;
import com.onticket.concert.repository.BookingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Optional;

@RequiredArgsConstructor
@Service
public class ReservationIdempotencyService {

    private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 100;

    private final BookingRepository bookingRepository;
    private final ReservationBookingTransactionService transactionService;
    private final SeatReservationService seatReservationService;

    public LocalDateTime reserve(
            String username,
            String concertId,
            ReservRequest reservRequest,
            String idempotencyKey
    ) throws Exception {
        if (idempotencyKey == null) {
            seatReservationService.reserveSeat(username, concertId, reservRequest);
            return LocalDateTime.now();
        }

        validateIdempotencyKey(idempotencyKey);
        String requestFingerprint = ReservationRequestCanonicalizer.fingerprint(concertId, reservRequest);

        Optional<Booking> existingBooking = bookingRepository
                .findByUsernameAndIdempotencyKey(username, idempotencyKey);
        if (existingBooking.isPresent()) {
            return resultForMatchingRequest(existingBooking.get(), requestFingerprint);
        }

        return reserveFirstRequest(
                username,
                concertId,
                reservRequest,
                idempotencyKey,
                requestFingerprint
        );
    }

    private LocalDateTime reserveFirstRequest(
            String username,
            String concertId,
            ReservRequest reservRequest,
            String idempotencyKey,
            String requestFingerprint
    ) throws Exception {
        try {
            return transactionService.reserve(
                    username,
                    concertId,
                    reservRequest,
                    idempotencyKey,
                    requestFingerprint
            );
        } catch (DataIntegrityViolationException exception) {
            Booking existingBooking = bookingRepository
                    .findByUsernameAndIdempotencyKey(username, idempotencyKey)
                    .orElseThrow(() -> exception);
            return resultForMatchingRequest(existingBooking, requestFingerprint);
        }
    }

    private LocalDateTime resultForMatchingRequest(Booking booking, String requestFingerprint) {
        if (!booking.getRequestFingerprint().equals(requestFingerprint)) {
            throw new IdempotencyKeyConflictException();
        }
        return booking.getCreatedAt();
    }

    private void validateIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey.isBlank()) {
            throw new InvalidIdempotencyKeyException("멱등 키는 비어 있을 수 없습니다.");
        }
        if (idempotencyKey.length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
            throw new InvalidIdempotencyKeyException("멱등 키는 100자를 초과할 수 없습니다.");
        }
    }
}
