package com.onticket.concert.dto;

import com.onticket.concert.domain.CheckoutStatus;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@AllArgsConstructor
public class CheckoutResponse {

    private final String merchantUid;
    private final long amount;
    private final LocalDateTime expiresAt;
    private final CheckoutStatus status;
}
