package com.onticket.concert.domain;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@Entity
public class Review {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 작성자
    private String author;

    // 작성일
    private LocalDateTime date;

    // 내용
    @Column(columnDefinition = "TEXT")
    private String content;

    private float starCount;

    @ManyToOne
    @JoinColumn(name = "concertId")
    private ConcertDetail concertDetail;


}