package com.onticket.concert.service;

import com.onticket.concert.domain.Booking;
import com.onticket.concert.domain.Payment;
import com.onticket.concert.dto.VerifiedReservRequest;
import com.onticket.concert.repository.BookingRepository;
import com.onticket.concert.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

@RequiredArgsConstructor
@Service
public class VerifiedReservationTransactionService {

    private final BookingRepository bookingRepository;
    private final PaymentRepository paymentRepository;
    private final SeatReservationService seatReservationService;

    @Transactional(rollbackFor = Exception.class)
    public LocalDateTime reserve(
            String username,
            String concertId,
            VerifiedReservRequest request,
            String idempotencyKey,
            String requestFingerprint,
            PaymentApproval approval
    ) throws Exception {
        Booking booking = new Booking();
        booking.setUsername(username);
        booking.setIdempotencyKey(idempotencyKey);
        booking.setRequestFingerprint(requestFingerprint);
        booking.setCreatedAt(LocalDateTime.now().truncatedTo(ChronoUnit.MICROS));
        bookingRepository.saveAndFlush(booking);

        Payment payment = Payment.approved(
                approval.paymentId(),
                approval.username(),
                approval.approvedAmount(),
                approval.approvedAt(),
                booking
        );
        paymentRepository.saveAndFlush(payment);

        seatReservationService.reserveSeat(username, concertId, request, booking);
        payment.confirmReservation();
        return booking.getCreatedAt();
    }
}
