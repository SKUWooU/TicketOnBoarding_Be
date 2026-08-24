package com.onticket.concert.repository;

import com.onticket.concert.domain.Payment;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    boolean existsByProviderPaymentId(String providerPaymentId);

    long countByUsernameStartingWith(String usernamePrefix);
}
