package com.onticket.concert.domain;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(
        name = "seat",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_seat_concert_time_number",
                columnNames = {"concert_time_id", "seat_number"}
        )
)
public class Seat {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 좌석 번호
    @Column(name = "seat_number")
    private String seatNumber;

    // 예약 상태
    private boolean reserved;

    @ManyToOne
    @JoinColumn(name = "concert_time_id")
    private ConcertTime concertTime;
}
