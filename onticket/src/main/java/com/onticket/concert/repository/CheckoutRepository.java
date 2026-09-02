package com.onticket.concert.repository;

import com.onticket.concert.domain.Checkout;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

public interface CheckoutRepository extends JpaRepository<Checkout, Long> {

    Optional<Checkout> findByUsernameAndIdempotencyKey(String username, String idempotencyKey);

    Optional<Checkout> findByMerchantUid(String merchantUid);

    Optional<Checkout> findByUsernameAndRequestFingerprintAndExpiresAt(
            String username,
            String requestFingerprint,
            LocalDateTime expiresAt
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM Checkout c WHERE c.merchantUid = :merchantUid")
    Optional<Checkout> findByMerchantUidWithLock(@Param("merchantUid") String merchantUid);
}
