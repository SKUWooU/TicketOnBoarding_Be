package com.onticket.concert.domain;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.Objects;

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

    @Column(name = "held_by", length = 100)
    private String heldBy;

    @Column(name = "held_until")
    private LocalDateTime heldUntil;

    @ManyToOne
    @JoinColumn(name = "concert_time_id")
    private ConcertTime concertTime;

    public SeatAvailability availabilityAt(LocalDateTime now) {
        Objects.requireNonNull(now, "좌석 상태 확인 시각이 필요합니다.");
        if (reserved) {
            return SeatAvailability.RESERVED;
        }
        return isHeldAt(now) ? SeatAvailability.HELD : SeatAvailability.AVAILABLE;
    }

    public boolean isHeldAt(LocalDateTime now) {
        Objects.requireNonNull(now, "좌석 점유 확인 시각이 필요합니다.");
        return !reserved
                && heldBy != null
                && heldUntil != null
                && now.isBefore(heldUntil);
    }

    public boolean isHeldBy(String username, LocalDateTime now) {
        return isHeldAt(now) && Objects.equals(heldBy, username);
    }

    public void clearExpiredHold(LocalDateTime now) {
        if (!reserved && heldUntil != null && !now.isBefore(heldUntil)) {
            clearHold();
        }
    }

    public void holdFor(String username, LocalDateTime now, LocalDateTime expiresAt) {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("좌석 점유자가 필요합니다.");
        }
        Objects.requireNonNull(now, "좌석 점유 시작 시각이 필요합니다.");
        Objects.requireNonNull(expiresAt, "좌석 점유 만료 시각이 필요합니다.");
        if (!expiresAt.isAfter(now)) {
            throw new IllegalArgumentException("좌석 점유 만료 시각은 시작 시각 이후여야 합니다.");
        }
        if (reserved) {
            throw new IllegalStateException("예약된 좌석은 임시 점유할 수 없습니다.");
        }
        heldBy = username;
        heldUntil = expiresAt;
    }

    public void clearHold() {
        heldBy = null;
        heldUntil = null;
    }

    public void extendOwnedHoldUntil(
            String username,
            LocalDateTime now,
            LocalDateTime extendedUntil
    ) {
        if (!isHeldBy(username, now)) {
            throw new IllegalStateException("활성 좌석 점유의 소유자만 기한을 연장할 수 있습니다.");
        }
        Objects.requireNonNull(extendedUntil, "연장할 좌석 점유 기한이 필요합니다.");
        if (!extendedUntil.isAfter(heldUntil)) {
            throw new IllegalArgumentException("연장할 좌석 점유 기한은 현재 기한 이후여야 합니다.");
        }
        heldUntil = extendedUntil;
    }

    public boolean restoreOwnedHoldUntil(
            String username,
            LocalDateTime expectedCurrentExpiry,
            LocalDateTime restoredUntil
    ) {
        if (!Objects.equals(heldBy, username)
                || !Objects.equals(heldUntil, expectedCurrentExpiry)) {
            return false;
        }
        heldUntil = Objects.requireNonNull(restoredUntil, "복원할 좌석 점유 기한이 필요합니다.");
        return true;
    }

    public void markReserved() {
        reserved = true;
        clearHold();
    }

    public void markAvailable() {
        reserved = false;
        clearHold();
    }
}
