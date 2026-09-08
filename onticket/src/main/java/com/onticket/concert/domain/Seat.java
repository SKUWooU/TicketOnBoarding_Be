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
        ),
        indexes = @Index(
                name = "idx_seat_layout_section_position",
                columnList = "concert_time_id, layout_section_code, layout_row_order, layout_seat_index"
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

    @Column(name = "layout_section_code", length = 30)
    private String layoutSectionCode;

    @Column(name = "layout_section_name", length = 50)
    private String layoutSectionName;

    @Column(name = "layout_section_order")
    private Integer layoutSectionOrder;

    @Column(name = "layout_row_label", length = 20)
    private String layoutRowLabel;

    @Column(name = "layout_row_order")
    private Integer layoutRowOrder;

    @Column(name = "layout_seat_index")
    private Integer layoutSeatIndex;

    @ManyToOne
    @JoinColumn(name = "concert_time_id")
    private ConcertTime concertTime;

    public void assignLayout(
            String sectionCode,
            String sectionName,
            int sectionOrder,
            String rowLabel,
            int rowOrder,
            int seatIndex
    ) {
        if (sectionCode == null || sectionCode.isBlank()
                || sectionName == null || sectionName.isBlank()
                || rowLabel == null || rowLabel.isBlank()) {
            throw new IllegalArgumentException("좌석 레이아웃 식별자는 비어 있을 수 없습니다.");
        }
        if (sectionOrder <= 0 || rowOrder <= 0 || seatIndex <= 0) {
            throw new IllegalArgumentException("좌석 레이아웃 순서는 1 이상이어야 합니다.");
        }
        this.layoutSectionCode = sectionCode;
        this.layoutSectionName = sectionName;
        this.layoutSectionOrder = sectionOrder;
        this.layoutRowLabel = rowLabel;
        this.layoutRowOrder = rowOrder;
        this.layoutSeatIndex = seatIndex;
    }

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
