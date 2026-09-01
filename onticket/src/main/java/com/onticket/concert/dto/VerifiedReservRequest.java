package com.onticket.concert.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class VerifiedReservRequest extends ReservRequest {

    @JsonProperty("paymentId")
    private String paymentId;

    @JsonProperty("merchantUid")
    private String merchantUid;
}
