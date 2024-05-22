package com.onticket.concert.domain;


import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
public class ConcertDetail {

    @Id
    private String concertId;

    private String concertName;


}
