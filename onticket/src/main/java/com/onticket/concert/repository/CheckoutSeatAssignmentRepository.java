package com.onticket.concert.repository;

import com.onticket.concert.domain.CheckoutSeatAssignment;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface CheckoutSeatAssignmentRepository
        extends JpaRepository<CheckoutSeatAssignment, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT assignment
            FROM CheckoutSeatAssignment assignment
            WHERE assignment.seat.id IN :seatIds
              AND assignment.activeUntil > :now
            ORDER BY assignment.seat.id
            """)
    List<CheckoutSeatAssignment> findActiveBySeatIdsWithLock(
            @Param("seatIds") List<Long> seatIds,
            @Param("now") LocalDateTime now
    );

    @Query("""
            SELECT CASE WHEN COUNT(assignment) > 0 THEN true ELSE false END
            FROM CheckoutSeatAssignment assignment
            WHERE assignment.seat.id IN :seatIds
              AND assignment.activeUntil > :now
            """)
    boolean existsActiveBySeatIds(
            @Param("seatIds") List<Long> seatIds,
            @Param("now") LocalDateTime now
    );

    List<CheckoutSeatAssignment> findByCheckoutId(Long checkoutId);
}
