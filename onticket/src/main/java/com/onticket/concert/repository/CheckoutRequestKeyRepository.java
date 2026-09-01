package com.onticket.concert.repository;

import com.onticket.concert.domain.CheckoutRequestKey;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CheckoutRequestKeyRepository extends JpaRepository<CheckoutRequestKey, Long> {

    Optional<CheckoutRequestKey> findByUsernameAndIdempotencyKey(
            String username,
            String idempotencyKey
    );
}
