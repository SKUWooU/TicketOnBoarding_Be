package com.onticket.concert.domain;

import com.fasterxml.jackson.annotation.JsonBackReference;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

@Getter
@Setter
@Entity
public class Reservation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String concertId;

    private String concertName;

    private String posterUrl;
    // 예약자 ID
    private String username;

    // 예약 시간
    private LocalDateTime createdAt;

    //공연일자
    private LocalDate concertDate;

    // 공연 시간
    private LocalTime concertTime;

    private Long concertTimeId;

//    @ManyToOne
//    @JoinColumn(name = "concertTimeId")
//    private ConcertTime concertTime;

    // 좌석
    @ManyToOne
    @JoinColumn(name = "seatId")
    @JsonBackReference
    private Seat seat;

    private String seatNumber;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "booking_id")
    @JsonIgnore
    private Booking booking;

    // 예약 상태
    @Column
    @Setter(AccessLevel.NONE)
    private ReservationStatus status;

    public void markPaymentCompleted() {
        if (status != null) {
            throw new IllegalStateException("예약 상태가 이미 초기화되었습니다.");
        }
        status = ReservationStatus.PAYMENT_COMPLETED;
    }

    public void requestCancellation() {
        if (status == ReservationStatus.PAYMENT_COMPLETED) {
            status = ReservationStatus.CANCELLATION_REQUESTED;
            return;
        }
        if (status == ReservationStatus.CANCELLATION_REQUESTED
                || status == ReservationStatus.CANCELLATION_COMPLETED) {
            return;
        }
        throw new IllegalStateException("취소 신청할 수 없는 예약 상태입니다.");
    }

    public boolean completeCancellation() {
        if (status == ReservationStatus.CANCELLATION_COMPLETED) {
            return false;
        }
        if (status != ReservationStatus.CANCELLATION_REQUESTED) {
            throw new IllegalStateException("취소 신청 상태의 예약만 취소할 수 있습니다.");
        }
        status = ReservationStatus.CANCELLATION_COMPLETED;
        return true;
    }
}
