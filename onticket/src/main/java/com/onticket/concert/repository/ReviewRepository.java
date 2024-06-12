package com.onticket.concert.repository;

import com.onticket.concert.domain.ConcertDetail;
import com.onticket.concert.domain.Review;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ReviewRepository extends JpaRepository<Review, Long> {
    List<Review> findByConcertDetail(ConcertDetail concertDetail);
}